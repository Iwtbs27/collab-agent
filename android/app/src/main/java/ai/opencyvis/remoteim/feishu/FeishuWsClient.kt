package ai.opencyvis.remoteim.feishu

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.TimeUnit

class FeishuWsClient(
    private val appId: String,
    private val appSecret: String,
    private val onMessage: suspend (String, String, String, String) -> Unit
) {
    companion object {
        private const val TAG = "FeishuWsClient"
        private const val ENDPOINT_URL = "https://open.feishu.cn/callback/ws/endpoint"
        private const val RECONNECT_DELAY = 5000L
        private const val HEARTBEAT_INTERVAL = 30000L
        private const val DEDUP_CAPACITY = 64
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var serviceId: Int = 0
    private val recentMessageIds: MutableSet<String> = Collections.newSetFromMap(
        LinkedHashMap<String, Boolean>(DEDUP_CAPACITY, 0.75f, true)
    )

    fun connect() {
        scope.launch { doConnect() }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "disconnect")
        webSocket = null
    }

    private suspend fun doConnect() {
        val wsUrl = getWebSocketUrl()
        if (wsUrl == null) {
            Log.e(TAG, "Failed to get WebSocket URL")
            scheduleReconnect()
            return
        }

        Log.i(TAG, "Connecting to WebSocket")
        val req = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleTextMessage(text) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                scope.launch { handleBinaryMessage(bytes) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code")
                heartbeatJob?.cancel()
                if (code != 1000) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                heartbeatJob?.cancel()
                scheduleReconnect()
            }
        })
    }

    private fun handleTextMessage(raw: String) {
        try {
            parseAndDispatch(raw)
        } catch (e: Exception) {
            Log.e(TAG, "handleTextMessage error", e)
        }
    }

    private suspend fun handleBinaryMessage(data: ByteString) {
        try {
            val bytes = data.toByteArray()
            val frame = PbFrame.decode(bytes)

            val typeHeader = frame.headers.find { it.key == "type" }?.value ?: ""

            when {
                frame.method == 0 && typeHeader == "pong" -> {
                    Log.d(TAG, "Received pong")
                }
                frame.method == 1 -> {
                    val jsonStr = frame.payload.toString(Charsets.UTF_8)
                    parseAndDispatch(jsonStr)
                    sendAck(frame)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleBinaryMessage error", e)
        }
    }

    private fun sendAck(frame: PbFrame) {
        val respPayload = """{"code":200}""".toByteArray(Charsets.UTF_8)
        val ackFrame = PbFrame(
            seqId = frame.seqId,
            logId = frame.logId,
            service = frame.service,
            method = frame.method,
            headers = frame.headers,
            payload = respPayload,
            logIdNew = frame.logIdNew
        )
        val ackBytes = ackFrame.encode()
        webSocket?.send(ackBytes.toByteString())
    }

    private fun parseAndDispatch(raw: String) {
        val json = JSONObject(raw)
        val header = json.optJSONObject("header") ?: return
        val eventType = header.optString("event_type", "")
        if (eventType != "im.message.receive_v1") return

        val event = json.optJSONObject("event") ?: return
        val message = event.optJSONObject("message") ?: return
        val sender = event.optJSONObject("sender") ?: return

        val messageId = message.optString("message_id", "")
        if (messageId.isNotEmpty()) {
            synchronized(recentMessageIds) {
                if (!recentMessageIds.add(messageId)) {
                    Log.d(TAG, "Dedup: skipping duplicate message_id=$messageId")
                    return
                }
                if (recentMessageIds.size > DEDUP_CAPACITY) {
                    recentMessageIds.remove(recentMessageIds.first())
                }
            }
        }

        val chatId = message.optString("chat_id", "")
        val senderId = sender.optJSONObject("sender_id")?.optString("open_id", "") ?: ""
        val chatType = message.optString("chat_type", "p2p")

        val contentStr = message.optString("content", "{}")
        val content = JSONObject(contentStr)
        val text = content.optString("text", "")

        if (text.isNotEmpty() && chatId.isNotEmpty() && senderId.isNotEmpty()) {
            scope.launch { onMessage(chatId, senderId, text, chatType) }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                val pingFrame = PbFrame(
                    method = 0,
                    service = serviceId,
                    headers = listOf(PbHeader("type", "ping"))
                )
                webSocket?.send(pingFrame.encode().toByteString())
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            Log.i(TAG, "Reconnecting...")
            doConnect()
        }
    }

    private fun getWebSocketUrl(): String? {
        return try {
            val body = JSONObject().apply {
                put("AppID", appId)
                put("AppSecret", appSecret)
            }.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url(ENDPOINT_URL)
                .post(body)
                .build()

            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: return null
            val json = JSONObject(respBody)

            if (json.optInt("code", -1) != 0) {
                Log.e(TAG, "getWebSocketUrl error: $respBody")
                return null
            }

            val url = json.optJSONObject("data")?.optString("URL")
            if (url != null) {
                val uri = java.net.URI(url)
                val params = uri.query?.split("&")?.associate {
                    val (k, v) = it.split("=", limit = 2)
                    k to v
                } ?: emptyMap()
                serviceId = params["service_id"]?.toIntOrNull() ?: 0
            }
            url
        } catch (e: Exception) {
            Log.e(TAG, "getWebSocketUrl failed", e)
            null
        }
    }

    // --- Lightweight protobuf codec for Feishu WS Frame ---

    private data class PbHeader(val key: String, val value: String)

    private data class PbFrame(
        val seqId: Long = 0,
        val logId: Long = 0,
        val service: Int = 0,
        val method: Int = 0,
        val headers: List<PbHeader> = emptyList(),
        val payloadEncoding: String = "",
        val payloadType: String = "",
        val payload: ByteArray = ByteArray(0),
        val logIdNew: String = ""
    ) {
        companion object {
            fun decode(data: ByteArray): PbFrame {
                val buf = ByteBuffer.wrap(data)
                var seqId = 0L
                var logId = 0L
                var service = 0
                var method = 0
                val headers = mutableListOf<PbHeader>()
                var payloadEncoding = ""
                var payloadType = ""
                var payload = ByteArray(0)
                var logIdNew = ""

                while (buf.hasRemaining()) {
                    val tag = readVarint(buf)
                    val fieldNumber = (tag shr 3).toInt()
                    val wireType = (tag and 0x7).toInt()

                    when (fieldNumber) {
                        1 -> seqId = readVarint(buf)
                        2 -> logId = readVarint(buf)
                        3 -> service = readVarint(buf).toInt()
                        4 -> method = readVarint(buf).toInt()
                        5 -> {
                            val headerBytes = readBytes(buf)
                            headers.add(decodeHeader(headerBytes))
                        }
                        6 -> payloadEncoding = readString(buf)
                        7 -> payloadType = readString(buf)
                        8 -> payload = readBytes(buf)
                        9 -> logIdNew = readString(buf)
                        else -> skipField(buf, wireType)
                    }
                }

                return PbFrame(seqId, logId, service, method, headers,
                    payloadEncoding, payloadType, payload, logIdNew)
            }

            private fun decodeHeader(data: ByteArray): PbHeader {
                val buf = ByteBuffer.wrap(data)
                var key = ""
                var value = ""
                while (buf.hasRemaining()) {
                    val tag = readVarint(buf)
                    val fieldNumber = (tag shr 3).toInt()
                    val wireType = (tag and 0x7).toInt()
                    when (fieldNumber) {
                        1 -> key = readString(buf)
                        2 -> value = readString(buf)
                        else -> skipField(buf, wireType)
                    }
                }
                return PbHeader(key, value)
            }

            private fun readVarint(buf: ByteBuffer): Long {
                var result = 0L
                var shift = 0
                while (buf.hasRemaining()) {
                    val b = buf.get().toInt() and 0xFF
                    result = result or ((b.toLong() and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                return result
            }

            private fun readBytes(buf: ByteBuffer): ByteArray {
                val len = readVarint(buf).toInt()
                val bytes = ByteArray(len)
                buf.get(bytes)
                return bytes
            }

            private fun readString(buf: ByteBuffer): String {
                return readBytes(buf).toString(Charsets.UTF_8)
            }

            private fun skipField(buf: ByteBuffer, wireType: Int) {
                when (wireType) {
                    0 -> readVarint(buf)
                    2 -> {
                        val len = readVarint(buf).toInt()
                        buf.position(buf.position() + len)
                    }
                    1 -> buf.position(buf.position() + 8)
                    5 -> buf.position(buf.position() + 4)
                }
            }
        }

        fun encode(): ByteArray {
            val out = ByteArrayOutputStream()

            fun writeVarint(value: Long) {
                var v = value
                if (v == 0L) { out.write(0); return }
                while (v != 0L) {
                    val b = (v and 0x7F).toInt()
                    v = v ushr 7
                    out.write(if (v != 0L) b or 0x80 else b)
                }
            }

            fun writeTag(fieldNumber: Int, wireType: Int) {
                writeVarint(((fieldNumber shl 3) or wireType).toLong())
            }

            fun writeBytes(fieldNumber: Int, data: ByteArray) {
                writeTag(fieldNumber, 2)
                writeVarint(data.size.toLong())
                out.write(data)
            }

            fun writeString(fieldNumber: Int, value: String) {
                if (value.isEmpty()) return
                writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
            }

            if (seqId != 0L) { writeTag(1, 0); writeVarint(seqId) }
            if (logId != 0L) { writeTag(2, 0); writeVarint(logId) }
            if (service != 0) { writeTag(3, 0); writeVarint(service.toLong()) }
            writeTag(4, 0); writeVarint(method.toLong())

            for (h in headers) {
                val headerOut = ByteArrayOutputStream()
                // key (field 1)
                val keyBytes = h.key.toByteArray(Charsets.UTF_8)
                headerOut.write(((1 shl 3) or 2)) // tag
                var kLen = keyBytes.size.toLong()
                if (kLen == 0L) { headerOut.write(0) } else {
                    while (kLen != 0L) {
                        val b = (kLen and 0x7F).toInt()
                        kLen = kLen ushr 7
                        headerOut.write(if (kLen != 0L) b or 0x80 else b)
                    }
                }
                headerOut.write(keyBytes)
                // value (field 2)
                val valBytes = h.value.toByteArray(Charsets.UTF_8)
                headerOut.write(((2 shl 3) or 2))
                var vLen = valBytes.size.toLong()
                if (vLen == 0L) { headerOut.write(0) } else {
                    while (vLen != 0L) {
                        val b = (vLen and 0x7F).toInt()
                        vLen = vLen ushr 7
                        headerOut.write(if (vLen != 0L) b or 0x80 else b)
                    }
                }
                headerOut.write(valBytes)

                writeBytes(5, headerOut.toByteArray())
            }

            writeString(6, payloadEncoding)
            writeString(7, payloadType)
            if (payload.isNotEmpty()) writeBytes(8, payload)
            writeString(9, logIdNew)

            return out.toByteArray()
        }
    }
}
