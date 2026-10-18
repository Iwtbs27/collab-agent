package ai.opencyvis.remoteim.feishu

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FeishuOpenApi(
    private val appId: String,
    private val appSecret: String
) {
    companion object {
        private const val TAG = "FeishuOpenApi"
        private const val BASE_URL = "https://open.feishu.cn"
        private const val TOKEN_REFRESH_MARGIN_MS = 10 * 60 * 1000L // 10 min before expiry
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var tenantToken: String? = null
    private var tokenExpiresAt: Long = 0

    suspend fun getTenantToken(): String = withContext(Dispatchers.IO) {
        // Return cached token if still valid
        if (tenantToken != null && System.currentTimeMillis() < tokenExpiresAt - TOKEN_REFRESH_MARGIN_MS) {
            return@withContext tenantToken!!
        }

        val url = "$BASE_URL/open-apis/auth/v3/tenant_access_token/internal"
        val json = JSONObject().apply {
            put("app_id", appId)
            put("app_secret", appSecret)
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body).build()

        val resp = client.newCall(req).execute()
        val respBody = resp.body?.string() ?: throw RuntimeException("Empty response")
        val respJson = JSONObject(respBody)

        if (respJson.optInt("code", -1) != 0) {
            throw RuntimeException("tenant_access_token failed: $respBody")
        }

        tenantToken = respJson.getString("tenant_access_token")
        tokenExpiresAt = System.currentTimeMillis() + respJson.optInt("expire", 7200) * 1000L
        tenantToken!!
    }

    suspend fun sendText(chatId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val token = getTenantToken()
        val url = "$BASE_URL/open-apis/im/v1/messages?receive_id_type=chat_id"
        val json = JSONObject().apply {
            put("receive_id", chatId)
            put("msg_type", "text")
            put("content", JSONObject().apply { put("text", text) }.toString())
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        try {
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string()
            val code = JSONObject(respBody ?: "{}").optInt("code", -1)
            code == 0
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed", e)
            false
        }
    }

    suspend fun uploadImage(imageBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val token = getTenantToken()
        val url = "$BASE_URL/open-apis/im/v1/images"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image_type", "message")
            .addFormDataPart(
                "image", "screenshot.jpg",
                imageBytes.toRequestBody("image/jpeg".toMediaType())
            )
            .build()
        val req = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        try {
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string()
            val json = JSONObject(respBody ?: "{}")
            if (json.optInt("code", -1) == 0) {
                json.optJSONObject("data")?.optString("image_key")
            } else {
                Log.e(TAG, "uploadImage failed: $respBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadImage error", e)
            null
        }
    }

    suspend fun sendImage(chatId: String, imageKey: String): Boolean = withContext(Dispatchers.IO) {
        val token = getTenantToken()
        val url = "$BASE_URL/open-apis/im/v1/messages?receive_id_type=chat_id"
        val json = JSONObject().apply {
            put("receive_id", chatId)
            put("msg_type", "image")
            put("content", JSONObject().apply { put("image_key", imageKey) }.toString())
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        try {
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string()
            JSONObject(respBody ?: "{}").optInt("code", -1) == 0
        } catch (e: Exception) {
            Log.e(TAG, "sendImage failed", e)
            false
        }
    }

    suspend fun sendPhoto(chatId: String, imageBytes: ByteArray, caption: String): Boolean {
        val imageKey = uploadImage(imageBytes) ?: return false
        return sendImage(chatId, imageKey)
    }

    suspend fun sendTypingStatus(chatId: String): Boolean = withContext(Dispatchers.IO) {
        val token = getTenantToken()
        val url = "$BASE_URL/open-apis/im/v1/chats/$chatId/typing_status"
        val json = JSONObject().apply {
            put("chat_id", chatId)
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .build()

        try {
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string()
            JSONObject(respBody ?: "{}").optInt("code", -1) == 0
        } catch (e: Exception) {
            Log.w(TAG, "sendTypingStatus error", e)
            false
        }
    }

    data class RecentMessage(
        val messageId: String,
        val senderId: String,
        val text: String,
        val createTime: Long,
        val chatId: String
    )

    suspend fun fetchRecentMessages(chatId: String, allowedSenderId: String): RecentMessage? = withContext(Dispatchers.IO) {
        try {
            val token = getTenantToken()
            val url = "$BASE_URL/open-apis/im/v1/messages?" +
                "container_id_type=chat&container_id=$chatId&page_size=5&sort_type=ByCreateTimeDesc"
            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: return@withContext null
            val respJson = JSONObject(respBody)

            if (respJson.optInt("code", -1) != 0) {
                Log.w(TAG, "fetchRecentMessages error: ${respJson.optString("msg")}")
                return@withContext null
            }

            val items = respJson.optJSONObject("data")?.optJSONArray("items") ?: return@withContext null

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val senderType = item.optString("sender_type", "")
                if (senderType != "user") continue

                val sender = item.optJSONObject("sender") ?: continue
                val senderOpenId = sender.optString("id", "")
                if (senderOpenId != allowedSenderId) continue

                val msgType = item.optString("msg_type", "")
                if (msgType != "text") continue

                val messageId = item.optString("message_id", "")
                val createTimeStr = item.optString("create_time", "0")
                val createTime = createTimeStr.toLongOrNull() ?: 0L

                val contentStr = item.optJSONObject("body")?.optString("content", "{}") ?: "{}"
                val content = JSONObject(contentStr)
                val text = content.optString("text", "")

                if (text.isNotEmpty()) {
                    return@withContext RecentMessage(
                        messageId = messageId,
                        senderId = senderOpenId,
                        text = text,
                        createTime = createTime,
                        chatId = chatId
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "fetchRecentMessages failed", e)
            null
        }
    }
}
