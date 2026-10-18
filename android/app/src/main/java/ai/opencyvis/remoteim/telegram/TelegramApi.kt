package ai.opencyvis.remoteim.telegram

import android.util.Log
import ai.opencyvis.config.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TelegramApi(
    private val token: String,
    private val config: ConfigRepository
) {
    companion object {
        private const val TAG = "TelegramApi"
        private const val BASE_URL = "https://api.telegram.org"
        private const val STALE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var offset: Long = config.telegramOffset
    private var lastUpdateTime: Long = System.currentTimeMillis()

    data class TgUpdate(
        val updateId: Long,
        val chatId: Long,
        val senderId: Long,
        val text: String,
        val chatType: String = "private"
    )

    suspend fun getUpdates(timeout: Int = 30): List<TgUpdate> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/bot$token/getUpdates?offset=$offset&timeout=$timeout"
        val req = Request.Builder().url(url).get().build()

        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)

                if (!json.optBoolean("ok")) {
                    Log.e(TAG, "getUpdates failed: $body")
                    return@withContext emptyList()
                }

                val result = json.optJSONArray("result") ?: return@withContext emptyList()
                val updates = mutableListOf<TgUpdate>()

                for (i in 0 until result.length()) {
                    val obj = result.getJSONObject(i)
                    val uid = obj.getLong("update_id")
                    if (uid >= offset) offset = uid + 1

                    val msg = obj.optJSONObject("message") ?: continue
                    val chat = msg.getJSONObject("chat")
                    val from = msg.optJSONObject("from")
                    val chatType = chat.optString("type", "private")
                    updates.add(
                        TgUpdate(
                            updateId = uid,
                            chatId = chat.getLong("id"),
                            senderId = from?.getLong("id") ?: chat.getLong("id"),
                            text = msg.optString("text", ""),
                            chatType = chatType
                        )
                    )
                }

                // Persist offset
                config.telegramOffset = offset
                lastUpdateTime = System.currentTimeMillis()

                if (updates.isNotEmpty()) {
                    Log.d(TAG, "getUpdates: ${updates.size} new, offset=$offset")
                }

                updates
            }
        } catch (e: Exception) {
            Log.e(TAG, "getUpdates error", e)
            emptyList()
        }
    }

    suspend fun sendMessage(chatId: Long, text: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/bot$token/sendMessage"
        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body).build()

        try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 429) {
                    val retryAfter = JSONObject(resp.body?.string() ?: "")
                        .optJSONObject("parameters")
                        ?.optInt("retry_after", 5) ?: 5
                    Log.w(TAG, "429 rate limited, retry after ${retryAfter}s")
                    delay(retryAfter * 1000L)
                    return@withContext false
                }
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage error", e)
            false
        }
    }

    suspend fun sendPhoto(chatId: Long, photoBytes: ByteArray, caption: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/bot$token/sendPhoto"
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .addFormDataPart("caption", caption)
                .addFormDataPart(
                    "photo", "screenshot.jpg",
                    photoBytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()
            val req = Request.Builder().url(url).post(body).build()

            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 429) {
                        val retryAfter = JSONObject(resp.body?.string() ?: "")
                            .optJSONObject("parameters")
                            ?.optInt("retry_after", 5) ?: 5
                        Log.w(TAG, "429 rate limited on photo, retry after ${retryAfter}s")
                        delay(retryAfter * 1000L)
                        return@withContext false
                    }
                    resp.isSuccessful
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendPhoto error", e)
                false
            }
        }

    suspend fun sendChatAction(chatId: Long, action: String = "typing"): Boolean =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/bot$token/sendChatAction"
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("action", action)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(body).build()

            try {
                client.newCall(req).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.w(TAG, "sendChatAction error", e)
                false
            }
        }

    fun checkStale(): Boolean {
        return System.currentTimeMillis() - lastUpdateTime > STALE_TIMEOUT_MS
    }

    fun resetStale() {
        lastUpdateTime = System.currentTimeMillis()
    }
}
