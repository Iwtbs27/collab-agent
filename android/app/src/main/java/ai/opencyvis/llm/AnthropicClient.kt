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
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * OkHttp-based client for Anthropic Messages API (/v1/messages).
 * Implements the same interface as LLMClient but with Anthropic protocol.
 */
class AnthropicClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String
) : LLMClientInterface {

    companion object {
        private const val TAG = "AnthropicClient"
        private const val MAX_RETRIES = 3
        private const val MAX_OUTPUT_TOKENS = 1024
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS = 90_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            var maxTokens = MAX_OUTPUT_TOKENS

            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val payload = buildRequestPayload(messages, maxTokens)
                    val payloadStr = payload.toString()
                    Log.d(TAG, "Request to $baseUrl/v1/messages (attempt ${attempt + 1}), payload ${payloadStr.length} chars")

                    val request = Request.Builder()
                        .url("$baseUrl/v1/messages")
                        .post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val code = response.code

                    if (code !in 200..299) {
                        val errorBody = response.body?.string()?.take(500) ?: ""
                        response.close()
                        if (code in 400..499 && code != 429) {
                            throw LLMException("Anthropic API error ($code): $errorBody")
                        }
                        if (attempt == MAX_RETRIES - 1) {
                            throw LLMException("Anthropic API failed after $MAX_RETRIES attempts: HTTP $code")
                        }
                        Log.w(TAG, "HTTP $code on attempt ${attempt + 1}, retrying...")
                        delay((1L shl attempt) * 1000)
                        continue
                    }

                    val result = parseSSEStream(response)
                    if (result != null) return@withContext result

                    response.close()
                    if (attempt < MAX_RETRIES - 1) {
                        Log.w(TAG, "Stream parse returned null, retrying with more tokens")
                        maxTokens *= 2
                        continue
                    }

                    throw LLMException("Cannot parse Anthropic streaming response")

                } catch (e: LLMException) {
                    throw e
                } catch (e: IOException) {
                    if (attempt == MAX_RETRIES - 1) {
                        throw LLMException("Anthropic API failed after $MAX_RETRIES attempts: ${e.message}")
                    }
                    Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                    delay((1L shl attempt) * 1000)
                } catch (e: Exception) {
                    throw LLMException("Anthropic API call failed: ${e.message}")
                }
            }
            throw LLMException("Anthropic API call failed: exhausted retries")
        }

    /**
     * Build the Anthropic /v1/messages request payload.
     *
     * Key differences from OpenAI:
     * - system prompt is a top-level "system" string, not a message
     * - tool definitions use input_schema (not parameters)
     * - tool_choice {"type":"any"} = must use a tool
     * - image content uses {type:"image", source:{type:"base64",...}}
     */
    private fun buildRequestPayload(messages: List<Map<String, Any>>, maxTokens: Int): JSONObject {
        var systemPrompt: String? = null
        val anthropicMessages = JSONArray()

        for (msg in messages) {
            val role = msg["role"] as? String ?: continue
            if (role == "system") {
                systemPrompt = msg["content"] as? String
                continue
            }

            val jsonMsg = JSONObject()
            jsonMsg.put("role", role)

            when (val content = msg["content"]) {
                is String -> jsonMsg.put("content", content)
                is List<*> -> {
                    val contentArray = JSONArray()
                    for (item in content) {
                        if (item is Map<*, *>) {
                            contentArray.put(convertContentBlock(item))
                        }
                    }
                    jsonMsg.put("content", contentArray)
                }
                else -> jsonMsg.put("content", content?.toString() ?: "")
            }
            anthropicMessages.put(jsonMsg)
        }

        return JSONObject().apply {
            put("model", model)
            if (systemPrompt != null) put("system", systemPrompt)
            put("messages", anthropicMessages)
            put("max_tokens", maxTokens)
            put("tools", ToolSchema.anthropicToolsArray())
            put("tool_choice", JSONObject().put("type", "any"))
            put("stream", true)
        }
    }

    /**
     * Convert a content block from OpenAI format to Anthropic format.
     * Main conversion: image_url -> image with base64 source.
     */
    private fun convertContentBlock(block: Map<*, *>): JSONObject {
        val type = block["type"] as? String
        if (type == "image_url") {
            // Convert from {type:"image_url", image_url:{url:"data:image/jpeg;base64,..."}}
            // to {type:"image", source:{type:"base64", media_type:"image/jpeg", data:"..."}}
            val imageUrl = block["image_url"]
            val url = when (imageUrl) {
                is Map<*, *> -> imageUrl["url"] as? String ?: ""
                is String -> imageUrl
                else -> ""
            }
            val obj = JSONObject()
            obj.put("type", "image")
            val source = JSONObject()
            if (url.startsWith("data:")) {
                // Parse data URI: data:image/jpeg;base64,XXXX
                val commaIdx = url.indexOf(',')
                val meta = url.substring(5, url.indexOf(';')) // "image/jpeg"
                val data = url.substring(commaIdx + 1)
                source.put("type", "base64")
                source.put("media_type", meta)
                source.put("data", data)
            } else {
                // Plain URL - use url source type
                source.put("type", "url")
                source.put("url", url)
            }
            obj.put("source", source)
            return obj
        }

        // text and other types pass through
        val obj = JSONObject()
        for ((k, v) in block) {
            when (v) {
                is Map<*, *> -> {
                    val inner = JSONObject()
                    for ((ik, iv) in v) inner.put(ik.toString(), iv)
                    obj.put(k.toString(), inner)
                }
                else -> obj.put(k.toString(), v)
            }
        }
        return obj
    }

    /**
     * Parse Anthropic SSE stream.
     *
     * Anthropic event types:
     * - message_start: {message: {id, model, ...}}
     * - content_block_start: {index, content_block: {type: "tool_use", id, name}}
     * - content_block_delta: {index, delta: {type: "input_json_delta", partial_json: "..."}}
     * - content_block_stop: {index}
     * - message_delta: {delta: {stop_reason: "tool_use"}, usage: {...}}
     * - message_stop
     *
     * We accumulate partial_json from input_json_delta events and parse when complete.
     */
    private fun parseSSEStream(response: okhttp3.Response): Map<String, Any?>? {
        val body = response.body ?: return null
        val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))

        val toolArgsBuilder = StringBuilder()
        val textBuilder = StringBuilder()
        var stopReason: String? = null
        var currentBlockType: String? = null

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                if (!currentLine.startsWith("data:")) continue
                val data = currentLine.removePrefix("data:").trim()
                if (data.isEmpty()) continue

                val event: JSONObject = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    Log.d(TAG, "Skipping non-JSON SSE line: ${data.take(100)}")
                    continue
                }

                val eventType = event.optString("type", "")

                when (eventType) {
                    "content_block_start" -> {
                        val block = event.optJSONObject("content_block")
                        currentBlockType = block?.optString("type", "")
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta") ?: continue
                        val deltaType = delta.optString("type", "")
                        when (deltaType) {
                            "input_json_delta" -> {
                                val chunk = delta.optString("partial_json", "")
                                if (chunk.isNotEmpty()) toolArgsBuilder.append(chunk)
                            }
                            "text_delta" -> {
                                val text = delta.optString("text", "")
                                if (text.isNotEmpty()) textBuilder.append(text)
                            }
                        }
                    }
                    "message_delta" -> {
                        val delta = event.optJSONObject("delta")
                        val sr = delta?.optString("stop_reason", "")
                        if (!sr.isNullOrEmpty()) stopReason = sr
                    }
                    "message_stop" -> {
                        // Stream complete
                        break
                    }
                }

                // If we got tool_use stop and have args, return early
                if (stopReason == "tool_use" && toolArgsBuilder.isNotEmpty()) {
                    Log.d(TAG, "Got tool args (${toolArgsBuilder.length} chars), returning")
                    val result = parseFunctionCallArgs(toolArgsBuilder.toString())
                    if (result != null) return result
                }
            }
        } finally {
            reader.close()
            body.close()
        }

        // Check accumulated args
        if (toolArgsBuilder.isNotEmpty()) {
            Log.d(TAG, "Using accumulated tool args (${toolArgsBuilder.length} chars)")
            val result = parseFunctionCallArgs(toolArgsBuilder.toString())
            if (result != null) return result
        }

        // Fallback: text content
        if (textBuilder.isNotEmpty()) {
            Log.w(TAG, "No tool_use in stream, trying text fallback: ${textBuilder.take(300)}")
            return ResponseParser.extractJsonFromText(textBuilder.toString())
        }

        Log.w(TAG, "Stream ended without parseable result")
        return null
    }

    private fun parseFunctionCallArgs(argsStr: String): Map<String, Any?>? {
        return try {
            ResponseParser.jsonObjectToMap(JSONObject(argsStr))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse tool args: ${argsStr.take(200)}", e)
            null
        }
    }

    override fun shutdown() {
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Exception) {}
    }
}
