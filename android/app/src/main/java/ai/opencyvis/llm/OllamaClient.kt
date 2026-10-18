package ai.opencyvis.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-based client for Ollama native /api/chat endpoint.
 * Uses non-streaming (stream:false) since local inference has no network latency benefit.
 *
 * Key protocol differences from OpenAI-compatible:
 * - Endpoint: /api/chat (not /chat/completions)
 * - Images: message.images = [base64] (not image_url content blocks)
 * - tool_calls[].function.arguments is an object (not a JSON string)
 * - No Authorization header needed
 * - System prompt: separate message with role=system (same as OpenAI)
 */
class OllamaClient(
    private val model: String,
    private val baseUrl: String
) : LLMClientInterface {

    companion object {
        private const val TAG = "OllamaClient"
        private const val MAX_RETRIES = 6
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 300_000L  // 5 min — local inference is slow
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES))
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val payload = buildPayload(messages)
                    val payloadStr = payload.toString()
                    Log.d(TAG, "Request to $baseUrl/api/chat (attempt ${attempt + 1}), payload ${payloadStr.length} chars")

                    val request = Request.Builder()
                        .url("$baseUrl/api/chat")
                        .post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val code = response.code

                    if (code !in 200..299) {
                        val errorBody = response.body?.string()?.take(500) ?: ""
                        response.close()

                        if (code in 400..499) {
                            throw LLMException("Ollama API error ($code): $errorBody")
                        }
                        if (attempt == MAX_RETRIES - 1) {
                            throw LLMException("Ollama API failed after $MAX_RETRIES attempts: HTTP $code")
                        }
                        Log.w(TAG, "HTTP $code on attempt ${attempt + 1}, retrying...")
                        delay((1L shl attempt) * 1000)
                        continue
                    }

                    val bodyStr = response.body?.string() ?: ""
                    response.close()

                    if (bodyStr.isEmpty()) {
                        if (attempt < MAX_RETRIES - 1) {
                            Log.w(TAG, "Empty response, retrying...")
                            continue
                        }
                        throw LLMException("Ollama returned empty response")
                    }

                    val result = parseResponse(bodyStr)
                    if (result != null) return@withContext result

                    if (attempt < MAX_RETRIES - 1) {
                        Log.w(TAG, "Failed to parse response, retrying (attempt ${attempt + 1})...")
                        delay((1L shl attempt) * 1000)
                        continue
                    }
                    throw LLMException("Cannot parse Ollama response after $MAX_RETRIES attempts")

                } catch (e: LLMException) {
                    throw e
                } catch (e: IOException) {
                    if (attempt == MAX_RETRIES - 1) {
                        val hint = if (e.message?.contains("Connect") == true || e.message?.contains("refused") == true) {
                            " — is Ollama running? Check with: ollama ps"
                        } else ""
                        throw LLMException("Ollama connection failed after $MAX_RETRIES attempts: ${e.message}$hint")
                    }
                    Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                    delay((1L shl attempt) * 1000)
                } catch (e: Exception) {
                    throw LLMException("Ollama API call failed: ${e.message}")
                }
            }
            throw LLMException("Ollama API call failed: exhausted retries")
        }

    /**
     * Build the Ollama /api/chat request payload.
     *
     * Converts from the app's OpenAI-format messages:
     * - system message: passed as-is (role=system)
     * - user message with [image_url, text] content: extract base64 into message.images
     * - assistant message: passed as-is (plain text content)
     */
    private fun buildPayload(messages: List<Map<String, Any>>): JSONObject {
        val ollamaMessages = JSONArray()

        for (msg in messages) {
            val role = msg["role"] as? String ?: continue
            val ollamaMsg = JSONObject().put("role", role)

            when (val content = msg["content"]) {
                is String -> ollamaMsg.put("content", content)
                is List<*> -> {
                    // Multimodal content: extract text and images separately
                    val textParts = mutableListOf<String>()
                    val images = JSONArray()

                    for (item in content) {
                        if (item !is Map<*, *>) continue
                        when (item["type"]) {
                            "text" -> {
                                val text = item["text"] as? String
                                if (!text.isNullOrEmpty()) textParts.add(text)
                            }
                            "image_url" -> {
                                // Extract base64 from data:image/jpeg;base64,XXXX
                                val imageUrl = item["image_url"]
                                val url = when (imageUrl) {
                                    is Map<*, *> -> imageUrl["url"] as? String ?: ""
                                    is String -> imageUrl
                                    else -> ""
                                }
                                if (url.startsWith("data:")) {
                                    val commaIdx = url.indexOf(',')
                                    if (commaIdx > 0) {
                                        images.put(url.substring(commaIdx + 1))
                                    }
                                }
                            }
                        }
                    }

                    ollamaMsg.put("content", textParts.joinToString("\n"))
                    if (images.length() > 0) {
                        ollamaMsg.put("images", images)
                    }
                }
                else -> ollamaMsg.put("content", content?.toString() ?: "")
            }

            ollamaMessages.put(ollamaMsg)
        }

        return JSONObject().apply {
            put("model", model)
            put("messages", ollamaMessages)
            put("tools", ToolSchema.toolsArray())
            put("stream", false)
            put("think", false)  // disable thinking/CoT to avoid wasting tokens
        }
    }

    /**
     * Parse the Ollama /api/chat response.
     *
     * Response format:
     * {
     *   "message": {
     *     "role": "assistant",
     *     "content": "...",
     *     "tool_calls": [{
     *       "function": { "name": "phone_action", "arguments": { ... } }
     *     }]
     *   }
     * }
     *
     * Note: arguments is already an object, not a JSON string.
     */
    private fun parseResponse(bodyStr: String): Map<String, Any?>? {
        val json = try {
            JSONObject(bodyStr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse response JSON: ${bodyStr.take(200)}", e)
            return null
        }

        val message = json.optJSONObject("message")
        if (message == null) {
            Log.w(TAG, "No 'message' in response")
            return null
        }

        // Try tool_calls first
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null && toolCalls.length() > 0) {
            val function = toolCalls.getJSONObject(0).optJSONObject("function")
            if (function != null) {
                // arguments is already an object in Ollama native API
                val args = function.optJSONObject("arguments")
                if (args != null) {
                    Log.d(TAG, "Parsed tool_call arguments (${args.length()} fields)")
                    return ResponseParser.jsonObjectToMap(args)
                }
                // Fallback: maybe it's a string (some models/versions)
                val argsStr = function.optString("arguments", "")
                if (argsStr.isNotEmpty()) {
                    return try {
                        ResponseParser.jsonObjectToMap(JSONObject(argsStr))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse arguments string: ${argsStr.take(200)}")
                        ResponseParser.extractFieldsFromMalformedJson(argsStr)
                    }
                }
            }
        }

        // Fallback: extract from text content
        val content = message.optString("content", "")
        if (content.isNotEmpty()) {
            Log.w(TAG, "No tool_calls, trying text fallback: ${content.take(300)}")
            return ResponseParser.extractJsonFromText(content)
        }

        Log.w(TAG, "Response has no tool_calls and no content")
        return null
    }

    override fun shutdown() {
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Exception) {}
    }
}
