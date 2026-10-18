package ai.opencyvis.remoteim.feishu

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Feishu App Registration API — creates a bot app via QR code scanning.
 *
 * Uses the undocumented accounts.feishu.cn/oauth/v1/app/registration endpoint
 * (same as OpenClaw). Three-step protocol: init → begin → poll.
 */
class FeishuRegistrationApi(
    private val baseUrl: String = REGISTRATION_URL
) {

    companion object {
        private const val TAG = "FeishuRegApi"
        private const val REGISTRATION_URL = "https://accounts.feishu.cn/oauth/v1/app/registration"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class InitResult {
        data class Success(val nonce: String) : InitResult()
        data class Error(val message: String) : InitResult()
    }

    sealed class BeginResult {
        data class Success(
            val deviceCode: String,
            val userCode: String,
            val verificationUriComplete: String,
            val expiresIn: Int
        ) : BeginResult()

        data class Error(val message: String) : BeginResult()
    }

    sealed class PollResult {
        data class Success(
            val clientId: String,
            val clientSecret: String,
            val openId: String
        ) : PollResult()

        object Pending : PollResult()
        object SlowDown : PollResult()
        data class Error(val message: String) : PollResult()
    }

    /**
     * Step 1: Initialize registration session.
     */
    suspend fun init(): InitResult = withContext(Dispatchers.IO) {
        try {
            val formBody = "action=init"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = Request.Builder()
                .url(baseUrl)
                .post(formBody)
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext InitResult.Error("Empty response")
            val json = JSONObject(body)

            if (json.has("error")) {
                return@withContext InitResult.Error("init failed: ${json.optString("error")}")
            }

            val nonce = json.optString("nonce", "")
            if (nonce.isEmpty()) {
                return@withContext InitResult.Error("No nonce in response: $body")
            }

            InitResult.Success(nonce)
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            InitResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Step 2: Begin registration — returns QR code payload.
     */
    suspend fun begin(nonce: String): BeginResult = withContext(Dispatchers.IO) {
        try {
            val formBody = (
                "action=begin" +
                "&archetype=PersonalAgent" +
                "&auth_method=client_secret" +
                "&request_user_info=open_id" +
                "&nonce=$nonce"
                ).toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = Request.Builder()
                .url(baseUrl)
                .post(formBody)
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext BeginResult.Error("Empty response")
            val json = JSONObject(body)

            if (json.has("error")) {
                return@withContext BeginResult.Error("begin failed: ${json.optString("error")}")
            }

            val deviceCode = json.optString("device_code", "")
            if (deviceCode.isEmpty()) {
                return@withContext BeginResult.Error("No device_code in response: $body")
            }

            BeginResult.Success(
                deviceCode = deviceCode,
                userCode = json.optString("user_code", ""),
                verificationUriComplete = json.optString("verification_uri_complete", ""),
                expiresIn = json.optInt("expires_in", 300)
            )
        } catch (e: Exception) {
            Log.e(TAG, "begin failed", e)
            BeginResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Step 3: Poll for user scan completion.
     */
    suspend fun poll(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        try {
            val formBody = "action=poll&device_code=$deviceCode"
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = Request.Builder()
                .url(baseUrl)
                .post(formBody)
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext PollResult.Error("Empty response")
            val json = JSONObject(body)

            val error = json.optString("error", "")
            if (error == "authorization_pending") {
                return@withContext PollResult.Pending
            }
            if (error == "slow_down" || error == "cool_down") {
                return@withContext PollResult.SlowDown
            }
            if (error.isNotEmpty()) {
                return@withContext PollResult.Error("poll failed: $error")
            }

            val clientId = json.optString("client_id", "")
            val clientSecret = json.optString("client_secret", "")
            val openId = json.optString("open_id", "")
            if (clientId.isNotEmpty() && clientSecret.isNotEmpty()) {
                PollResult.Success(clientId, clientSecret, openId)
            } else {
                PollResult.Error("Missing credentials in response: $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "poll failed", e)
            PollResult.Error(e.message ?: "Unknown error")
        }
    }
}
