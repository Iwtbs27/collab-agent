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
 * OkHttp-based client for OpenAI-compatible /chat/completions endpoint.
 * Uses connection pooling, HTTP/2, and streaming (SSE) for lower latency.
 */
class LLMClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    private val temperature: Double = DEFAULT_TEMPERATURE,
    private val disableThinking: Boolean = false
) : LLMClientInterface {

    companion object {
        private const val TAG = "LLMClient"
        private const val MAX_RETRIES = 6
        private const val MAX_OUTPUT_TOKENS = 1024
        private const val DEFAULT_TEMPERATURE = 0.1
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS = 90_000L  // longer for streaming
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)  // we handle retries ourselves
        .build()

    /**
     * Send messages to the OpenAI-compatible /chat/completions endpoint with tool calling.
     * Uses streaming (SSE) to parse the function_call as soon as it arrives.
     * Falls back to non-streaming on stream parse failure.
     *
     * @param messages List of message maps in OpenAI format
     * @return Map of action parameters from the tool call
     * @throws LLMException if all retries fail
     */
    override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            val messagesArray = convertMessagesToJson(messages)
            var maxTokens = MAX_OUTPUT_TOKENS

            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val payload = JSONObject().apply {
                        put("model", model)
                        put("messages", messagesArray)
                        put("max_tokens", maxTokens)
                        put("temperature", temperature)
                        put("tools", ToolSchema.toolsArray())
                        put("tool_choice", "required")
                        if (disableThinking) {
                            put("thinking", JSONObject().put("type", "disabled"))
                        } else if (model.contains("qwen3") || model.contains("qwq")) {
                            put("enable_thinking", false)
                        }
                        put("stream", true)
                    }

                    val payloadStr = payload.toString()
                    Log.d(TAG, "Request to $baseUrl/chat/completions (attempt ${attempt + 1}), payload ${payloadStr.length} chars, stream=true")

                    val request = Request.Builder()
                        .url("$baseUrl/chat/completions")
                        .post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
                        .addHeader("Authorization", "Bearer $apiKey")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val code = response.code

                    if (code !in 200..299) {
                        val errorBody = response.body?.string()?.take(500) ?: ""
                        response.close()

                        if (code in 400..499 && code != 429) {
                            throw LLMException("LLM API error ($code): $errorBody")
                        }
                        if (attempt == MAX_RETRIES - 1) {
                            throw LLMException("LLM API failed after $MAX_RETRIES attempts: HTTP $code")
                        }
                        Log.w(TAG, "HTTP $code on attempt ${attempt + 1}, retrying...")
                        delay((1L shl attempt) * 1000)
                        continue
                    }

                    // Parse SSE stream
                    val result = parseSSEStream(response)
                    if (result != null) return@withContext result

                    // If streaming parse returned null, check for incomplete
                    response.close()
                    if (attempt < MAX_RETRIES - 1) {
                        Log.w(TAG, "Stream parse returned null, retrying with more tokens")
                        maxTokens *= 2
                        continue
                    }

                    throw LLMException("Cannot parse LLM streaming response")

                } catch (e: LLMException) {
                    throw e
                } catch (e: IOException) {
                    if (attempt == MAX_RETRIES - 1) {
                        throw LLMException(
                            "LLM API failed after $MAX_RETRIES attempts: ${e.message}"
                        )
                    }
                    Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                    delay((1L shl attempt) * 1000)
                } catch (e: Exception) {
                    throw LLMException("LLM API call failed: ${e.message}")
                }
            }

            throw LLMException("LLM API call failed: exhausted retries")
        }

    /**
     * Parse SSE stream from the OpenAI-compatible /chat/completions API.
     *
     * Each chunk: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"..."}}]}, "finish_reason":null}]}
     * Final chunk: {"choices":[{"delta":{}, "finish_reason":"tool_calls"}]}
     *
     * Returns as soon as tool_call arguments are complete (finish_reason == "tool_calls").
     */
    private fun parseSSEStream(response: okhttp3.Response): Map<String, Any?>? {
        val body = response.body ?: return null
        val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))

        val argumentsBuilder = StringBuilder()
        val contentBuilder = StringBuilder()
        var finishReason: String? = null

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                if (!currentLine.startsWith("data:")) continue
                val data = currentLine.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue

                val event: JSONObject = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    Log.d(TAG, "Skipping non-JSON SSE line: ${data.take(100)}")
                    continue
                }

                val eventType = event.optString("type", "")
                if (eventType == "response.function_call_arguments.done") {
                    val args = event.optString("arguments", "")
                    if (args.isNotEmpty()) {
                        Log.d(TAG, "Got Responses-style function_call args (${args.length} chars)")
                        val result = parseFunctionCallArgs(args)
                        if (result != null) return result
                    }
                }

                if (eventType == "response.output_text.done") {
                    val text = event.optString("text", "")
                    if (text.isNotEmpty()) {
                        val result = ResponseParser.extractJsonFromText(text)
                        if (result != null) return result
                    }
                }

                val choices = event.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val choice = choices.getJSONObject(0)

                // Capture finish_reason when present
                val fr = choice.optString("finish_reason", "")
                if (fr.isNotEmpty() && fr != "null") {
                    finishReason = fr
                }

                val delta = choice.optJSONObject("delta") ?: continue

                // Accumulate tool_calls argument chunks
                val toolCalls = delta.optJSONArray("tool_calls")
                if (toolCalls != null && toolCalls.length() > 0) {
                    val function = toolCalls.getJSONObject(0).optJSONObject("function")
                    val argChunk = function?.optString("arguments", "") ?: ""
                    if (argChunk.isNotEmpty()) {
                        argumentsBuilder.append(argChunk)
                    }
                }

                // Accumulate text content (fallback if model ignores tool_choice)
                // Note: optString returns "null" for JSON null values on Android
                val content = delta.optString("content", "")
                if (content.isNotEmpty() && content != "null") {
                    contentBuilder.append(content)
                }

                // Return as soon as tool_call is complete
                if (finishReason == "tool_calls" && argumentsBuilder.isNotEmpty()) {
                    Log.d(TAG, "Got tool_call args (${argumentsBuilder.length} chars), returning early")
                    val result = parseFunctionCallArgs(argumentsBuilder.toString())
                    if (result != null) return result
                }
            }
        } finally {
            reader.close()
            body.close()
        }

        // Finish_reason arrived before we checked (args already accumulated)
        if (argumentsBuilder.isNotEmpty()) {
            Log.d(TAG, "Using accumulated tool_call args (${argumentsBuilder.length} chars)")
            val result = parseFunctionCallArgs(argumentsBuilder.toString())
            if (result != null) return result
        }

        // Fallback: model returned plain text instead of tool_call
        if (contentBuilder.isNotEmpty()) {
            Log.w(TAG, "No tool_call in stream, trying text fallback: ${contentBuilder.take(300)}")
            return ResponseParser.extractJsonFromText(contentBuilder.toString())
        }

        Log.w(TAG, "Stream ended without parseable result")
        return null
    }

    /**
     * Parse function_call arguments JSON string into a Map.
     * Falls back to regex-based field extraction when SSE streaming corrupts
     * escaped quotes (e.g. \"iphone16pro\" decoded to bare "iphone16pro").
     */
    private fun parseFunctionCallArgs(argsStr: String): Map<String, Any?>? {
        Log.d(TAG, "parseFunctionCallArgs raw: ${argsStr.take(500)}")
        // 1. Try direct JSON parse
        try {
            return ResponseParser.jsonObjectToMap(JSONObject(argsStr))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse function_call arguments: ${argsStr.take(200)}", e)
        }

        // 2. Try closing truncated JSON (missing trailing "}")
        if (!argsStr.trimEnd().endsWith("}")) {
            try {
                return ResponseParser.jsonObjectToMap(JSONObject("$argsStr}"))
            } catch (_: Exception) {}
        }

        // 3. Regex-based field extraction for malformed JSON
        //    (handles SSE decoding corrupting escaped quotes in string values)
        return ResponseParser.extractFieldsFromMalformedJson(argsStr)
    }

    /**
     * Convert messages list to JSONArray in OpenAI chat/completions format.
     * Handles nested Maps (e.g. image_url: {url: "..."}).
     */
    private fun convertMessagesToJson(messages: List<Map<String, Any>>): JSONArray {
        val array = JSONArray()
        for (msg in messages) {
            val jsonMsg = JSONObject()
            jsonMsg.put("role", msg["role"])

            when (val content = msg["content"]) {
                is String -> jsonMsg.put("content", content)
                is List<*> -> {
                    val contentArray = JSONArray()
                    for (item in content) {
                        if (item is Map<*, *>) {
                            contentArray.put(mapToJsonObject(item))
                        }
                    }
                    jsonMsg.put("content", contentArray)
                }
                else -> jsonMsg.put("content", content?.toString() ?: "")
            }

            array.put(jsonMsg)
        }
        return array
    }

    /** Recursively convert a Map to JSONObject, handling nested Maps. */
    private fun mapToJsonObject(map: Map<*, *>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in map) {
            when (v) {
                is Map<*, *> -> obj.put(k.toString(), mapToJsonObject(v))
                else -> obj.put(k.toString(), v)
            }
        }
        return obj
    }

    /**
     * Shut down the HTTP client and release connections.
     */
    override fun shutdown() {
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Exception) {
            // Ignore — may already be shut down
        }
    }
}

/**
 * Exception type for LLM client errors.
 */
class LLMException(message: String, cause: Throwable? = null) : Exception(message, cause)
