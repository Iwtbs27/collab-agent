package ai.opencyvis.llm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the LLM API JSON response.
 * Extracts function_call arguments or falls back to JSON extraction from text.
 */
object ResponseParser {

    private const val TAG = "ResponseParser"

    /**
     * Parse the response JSON and extract the action parameters map.
     *
     * Checks (in order):
     * 1. output[] items with type=function_call → parse arguments
     * 2. output[] items with type=message → extract JSON from text
     * 3. choices[] (chat/completions fallback) → tool_calls or content
     *
     * @return Map of action parameters, or null if unparseable
     */
    fun parse(responseJson: JSONObject): Map<String, Any?>? {
        // Check output array (Responses API format)
        val output = responseJson.optJSONArray("output")
        if (output != null) {
            for (i in 0 until output.length()) {
                val item = output.getJSONObject(i)
                val type = item.optString("type", "")

                // function_call type
                if (type == "function_call") {
                    val argsStr = item.optString("arguments", "{}")
                    return try {
                        jsonObjectToMap(JSONObject(argsStr))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse function_call arguments: $argsStr", e)
                        null
                    }
                }

                // message type → look for text content
                if (type == "message") {
                    val content = item.optJSONArray("content")
                    if (content != null) {
                        for (j in 0 until content.length()) {
                            val contentItem = content.getJSONObject(j)
                            val contentType = contentItem.optString("type", "")
                            if (contentType == "output_text" || contentType == "text") {
                                val text = contentItem.optString("text", "")
                                if (text.isNotEmpty()) {
                                    Log.w(TAG, "No tool_call, extracting JSON from text")
                                    return extractJsonFromText(text)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fallback: chat/completions style
        val choices = responseJson.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.getJSONObject(0)
            val message = choice.optJSONObject("message") ?: return null

            // Check tool_calls
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val function = toolCalls.getJSONObject(0).optJSONObject("function")
                if (function != null) {
                    val argsStr = function.optString("arguments", "{}")
                    return try {
                        jsonObjectToMap(JSONObject(argsStr))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse tool_calls arguments", e)
                        null
                    }
                }
            }

            // Check content
            val content = message.optString("content", "")
            if (content.isNotEmpty()) {
                return extractJsonFromText(content)
            }
        }

        // Check for incomplete response
        val incomplete = responseJson.optJSONObject("incomplete_details")
        if (incomplete != null) {
            Log.w(TAG, "Incomplete response: $incomplete")
        }

        return null
    }

    /**
     * Extract a JSON object from text that may contain markdown fences or other wrapping.
     */
    fun extractJsonFromText(text: String): Map<String, Any?>? {
        // Try direct parse first
        try {
            return jsonObjectToMap(JSONObject(text))
        } catch (_: Exception) {}

        // Try to find JSON in markdown code blocks (escape } for Android ICU regex)
        try {
            val codeBlockPattern = Regex("```(?:json)?\\s*\\n?(\\{.*?\\})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
            val codeBlockMatch = codeBlockPattern.find(text)
            if (codeBlockMatch != null) {
                try {
                    return jsonObjectToMap(JSONObject(codeBlockMatch.groupValues[1]))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Try to find any JSON object in the text
        try {
            val jsonPattern = Regex("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}", RegexOption.DOT_MATCHES_ALL)
            val jsonMatch = jsonPattern.find(text)
            if (jsonMatch != null) {
                try {
                    return jsonObjectToMap(JSONObject(jsonMatch.value))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Last resort: regex-based field extraction for malformed JSON
        // (e.g. unescaped quotes inside string values from some models)
        val fields = extractFieldsFromMalformedJson(text)
        if (fields != null) return fields

        Log.w(TAG, "Could not extract JSON from text: ${text.take(200)}")
        return null
    }

    /**
     * Extract action fields from malformed JSON using regex.
     * Handles cases where SSE streaming corrupts escaped quotes in string values,
     * making the JSON unparseable but individual fields still extractable.
     */
    fun extractFieldsFromMalformedJson(text: String): Map<String, Any?>? {
        val map = mutableMapOf<String, Any?>()

        // Extract action_type (simple string, no embedded quotes)
        Regex("\"action_type\"\\s*:\\s*\"([^\"]+)\"").find(text)?.let {
            map["action_type"] = it.groupValues[1]
        }

        // Extract numeric fields
        Regex("\"x\"\\s*:\\s*(\\d+)").find(text)?.let {
            map["x"] = it.groupValues[1].toIntOrNull() ?: return@let
        }
        Regex("\"y\"\\s*:\\s*(\\d+)").find(text)?.let {
            map["y"] = it.groupValues[1].toIntOrNull() ?: return@let
        }
        // Extract completed (boolean)
        Regex("\"completed\"\\s*:\\s*(true|false)").find(text)?.let {
            map["completed"] = it.groupValues[1].toBoolean()
        }

        // Extract simple string fields (no embedded quotes expected)
        for (field in listOf("app_name", "direction", "key", "text", "reason", "question", "note", "memory_key", "memory_value", "memory_category")) {
            Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"").find(text)?.let {
                map[field] = it.groupValues[1]
            }
        }

        // Extract thought: grab everything between "thought":"..." up to the next known field
        Regex("\"thought\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"(?:action_type|completed|x|y|app_name|direction|key|text|reason|question)\"",
            RegexOption.DOT_MATCHES_ALL).find(text)?.let {
            map["thought"] = it.groupValues[1]
        }

        if (map.containsKey("action_type")) {
            Log.d(TAG, "Extracted fields from malformed JSON: ${map.keys}")
            return map
        }

        return null
    }

    /**
     * Convert a JSONObject to a Map<String, Any?>.
     */
    fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)
            map[key] = when (value) {
                JSONObject.NULL -> null
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                else -> value
            }
        }
        return map
    }

    private fun jsonArrayToList(array: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until array.length()) {
            val value = array.get(i)
            list.add(
                when (value) {
                    JSONObject.NULL -> null
                    is JSONObject -> jsonObjectToMap(value)
                    is JSONArray -> jsonArrayToList(value)
                    else -> value
                }
            )
        }
        return list
    }
}
