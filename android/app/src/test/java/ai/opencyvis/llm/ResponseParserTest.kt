package ai.opencyvis.llm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for parsing LLM API responses.
 *
 * The ResponseParser (or equivalent logic) is expected to:
 * 1. Extract function_call from the output array
 * 2. Fall back to parsing JSON from text output
 * 3. Handle malformed/empty responses gracefully
 */
class ResponseParserTest {

    /**
     * Helper: build a function_call response JSON string.
     */
    private fun buildFunctionCallResponse(
        name: String = "phone_action",
        arguments: String
    ): String {
        return JSONObject().apply {
            put("id", "resp_test")
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "function_call")
                    put("name", name)
                    put("arguments", arguments)
                })
            })
        }.toString()
    }

    /**
     * Helper: build a text message response.
     */
    private fun buildTextResponse(text: String): String {
        return JSONObject().apply {
            put("id", "resp_text")
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "message")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "output_text")
                            put("text", text)
                        })
                    })
                })
            })
        }.toString()
    }

    // --- function_call parsing tests ---

    @Test
    fun `parse function_call extracts arguments correctly`() {
        val args = """{"thought":"I see the home screen","action_type":"tap","x":500,"y":300,"completed":false}"""
        val response = buildFunctionCallResponse(arguments = args)
        val json = JSONObject(response)
        val output = json.getJSONArray("output")
        val item = output.getJSONObject(0)

        assertEquals("function_call", item.getString("type"))
        assertEquals("phone_action", item.getString("name"))

        val parsed = JSONObject(item.getString("arguments"))
        assertEquals("tap", parsed.getString("action_type"))
        assertEquals(500, parsed.getInt("x"))
        assertEquals(300, parsed.getInt("y"))
        assertEquals("I see the home screen", parsed.getString("thought"))
        assertFalse(parsed.getBoolean("completed"))
    }

    @Test
    fun `parse function_call with finish action`() {
        val args = """{"thought":"Task completed successfully","action_type":"finish","completed":true}"""
        val response = buildFunctionCallResponse(arguments = args)
        val json = JSONObject(response)
        val output = json.getJSONArray("output")
        val item = output.getJSONObject(0)
        val parsed = JSONObject(item.getString("arguments"))

        assertEquals("finish", parsed.getString("action_type"))
        assertTrue(parsed.getBoolean("completed"))
    }

    @Test
    fun `parse function_call with all action types`() {
        val actionTypes = listOf(
            "tap", "long_press", "open_app", "swipe",
            "key_event", "type_text", "wait", "finish", "fail"
        )
        for (actionType in actionTypes) {
            val args = """{"thought":"test","action_type":"$actionType","completed":false}"""
            val response = buildFunctionCallResponse(arguments = args)
            val json = JSONObject(response)
            val output = json.getJSONArray("output")
            val item = output.getJSONObject(0)
            val parsed = JSONObject(item.getString("arguments"))
            assertEquals(actionType, parsed.getString("action_type"))
        }
    }

    // --- Text fallback parsing tests ---

    @Test
    fun `parse text fallback extracts JSON from plain text`() {
        val jsonStr = """{"thought":"fallback","action_type":"wait","completed":false}"""
        val response = buildTextResponse(jsonStr)
        val json = JSONObject(response)
        val output = json.getJSONArray("output")
        val message = output.getJSONObject(0)
        val content = message.getJSONArray("content")
        val text = content.getJSONObject(0).getString("text")

        val parsed = JSONObject(text)
        assertEquals("wait", parsed.getString("action_type"))
    }

    @Test
    fun `parse text fallback extracts JSON from markdown code block`() {
        val text = """Here is the action:
```json
{"thought":"found it","action_type":"tap","x":100,"y":200,"completed":false}
```"""
        // Extract JSON from code block
        val jsonPattern = Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = jsonPattern.find(text)
        assertNotNull("Should find JSON in code block", match)
        val parsed = JSONObject(match!!.groupValues[1])
        assertEquals("tap", parsed.getString("action_type"))
        assertEquals(100, parsed.getInt("x"))
    }

    @Test
    fun `parse text fallback extracts JSON from mixed text`() {
        val text = """I'll tap the settings icon. {"thought":"opening settings","action_type":"tap","x":500,"y":300,"completed":false}"""
        // Extract first JSON object from text
        val startIdx = text.indexOf('{')
        assertTrue("Should find opening brace", startIdx >= 0)

        // Simple bracket-matching extraction
        var depth = 0
        var endIdx = -1
        for (i in startIdx until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        endIdx = i
                        break
                    }
                }
            }
        }
        assertTrue("Should find closing brace", endIdx > startIdx)

        val jsonStr = text.substring(startIdx, endIdx + 1)
        val parsed = JSONObject(jsonStr)
        assertEquals("tap", parsed.getString("action_type"))
    }

    // --- Malformed JSON handling ---

    @Test
    fun `handle malformed JSON gracefully`() {
        val malformedArgs = """{"thought":"broken","action_type":"tap","x":}"""
        try {
            JSONObject(malformedArgs)
            fail("Should throw on malformed JSON")
        } catch (e: org.json.JSONException) {
            // Expected - malformed JSON should be caught
            assertNotNull(e.message)
        }
    }

    @Test
    fun `handle truncated JSON`() {
        val truncated = """{"thought":"cut off","action_typ"""
        try {
            JSONObject(truncated)
            fail("Should throw on truncated JSON")
        } catch (e: org.json.JSONException) {
            assertNotNull(e.message)
        }
    }

    // --- Empty response handling ---

    @Test
    fun `handle empty output array`() {
        val response = JSONObject().apply {
            put("id", "resp_empty")
            put("output", JSONArray())
        }
        val output = response.getJSONArray("output")
        assertEquals(0, output.length())
    }

    @Test
    fun `handle response with no output field`() {
        val response = JSONObject().apply {
            put("id", "resp_no_output")
        }
        assertFalse(response.has("output"))
    }

    @Test
    fun `handle null arguments in function call`() {
        val response = JSONObject().apply {
            put("id", "resp_null")
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "function_call")
                    put("name", "phone_action")
                    put("arguments", JSONObject.NULL)
                })
            })
        }
        val item = response.getJSONArray("output").getJSONObject(0)
        assertTrue(item.isNull("arguments"))
    }

    // --- incomplete_details handling ---

    @Test
    fun `handle incomplete_details in response`() {
        val response = JSONObject().apply {
            put("id", "resp_incomplete")
            put("status", "incomplete")
            put("incomplete_details", JSONObject().apply {
                put("reason", "max_output_tokens")
            })
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "message")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "output_text")
                            put("text", """{"thought":"partial","action_type":"wait"}""")
                        })
                    })
                })
            })
        }

        assertEquals("incomplete", response.getString("status"))
        assertTrue(response.has("incomplete_details"))
        assertEquals("max_output_tokens",
            response.getJSONObject("incomplete_details").getString("reason"))

        // Should still be able to parse whatever output was provided
        val output = response.getJSONArray("output")
        assertTrue(output.length() > 0)
    }

    @Test
    fun `handle multiple output items picks first function_call`() {
        val response = JSONObject().apply {
            put("id", "resp_multi")
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "message")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "output_text")
                            put("text", "thinking...")
                        })
                    })
                })
                put(JSONObject().apply {
                    put("type", "function_call")
                    put("name", "phone_action")
                    put("arguments", """{"thought":"act","action_type":"tap","x":1,"y":2,"completed":false}""")
                })
            })
        }

        val output = response.getJSONArray("output")
        var functionCall: JSONObject? = null
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            if (item.getString("type") == "function_call") {
                functionCall = item
                break
            }
        }

        assertNotNull("Should find function_call in output", functionCall)
        assertEquals("phone_action", functionCall!!.getString("name"))
    }

    // ==========================================================================
    // Tests that call the REAL ResponseParser methods
    // ==========================================================================

    // --- extractJsonFromText ---

    @Test
    fun `extractJsonFromText parses markdown fenced JSON`() {
        val text = """Here is my action:
```json
{"thought":"opening settings","action_type":"tap","x":500,"y":300,"completed":false}
```
That should do it."""
        val result = ResponseParser.extractJsonFromText(text)
        assertNotNull("Should extract JSON from markdown fence", result)
        assertEquals("tap", result!!["action_type"])
        assertEquals(500, result["x"])
        assertEquals(300, result["y"])
        assertEquals("opening settings", result["thought"])
        assertEquals(false, result["completed"])
    }

    @Test
    fun `extractJsonFromText parses plain JSON in text`() {
        val text = """I will tap the button. {"thought":"tapping","action_type":"tap","x":100,"y":200,"completed":false} Done."""
        val result = ResponseParser.extractJsonFromText(text)
        assertNotNull("Should extract JSON from plain text", result)
        assertEquals("tap", result!!["action_type"])
        assertEquals(100, result["x"])
        assertEquals(200, result["y"])
    }

    @Test
    fun `extractJsonFromText parses standalone JSON string`() {
        val text = """{"thought":"done","action_type":"finish","completed":true}"""
        val result = ResponseParser.extractJsonFromText(text)
        assertNotNull(result)
        assertEquals("finish", result!!["action_type"])
        assertEquals(true, result["completed"])
    }

    // --- extractFieldsFromMalformedJson ---

    @Test
    fun `extractFieldsFromMalformedJson handles truncated JSON`() {
        val truncated = """{"thought":"starting the app","action_type":"open_app","app_name":"Settings","completed":false"""
        val result = ResponseParser.extractFieldsFromMalformedJson(truncated)
        assertNotNull("Should extract fields from truncated JSON", result)
        assertEquals("open_app", result!!["action_type"])
        assertEquals("Settings", result["app_name"])
        assertEquals(false, result["completed"])
    }

    @Test
    fun `extractFieldsFromMalformedJson handles extra commas`() {
        val malformed = """{"thought":"swiping up","action_type":"swipe",,"direction":"up",,"completed":false,}"""
        val result = ResponseParser.extractFieldsFromMalformedJson(malformed)
        assertNotNull("Should extract fields despite extra commas", result)
        assertEquals("swipe", result!!["action_type"])
        assertEquals("up", result["direction"])
        assertEquals(false, result["completed"])
    }

    @Test
    fun `extractFieldsFromMalformedJson returns null for no action_type`() {
        val noAction = """{"thought":"just thinking"}"""
        val result = ResponseParser.extractFieldsFromMalformedJson(noAction)
        assertNull("Should return null when action_type is absent", result)
    }

    // --- Unicode in action parameters ---

    @Test
    fun `extractJsonFromText handles unicode in parameters`() {
        val text = """{"thought":"输入中文文本","action_type":"type_text","text":"你好世界","completed":false}"""
        val result = ResponseParser.extractJsonFromText(text)
        assertNotNull("Should handle unicode text", result)
        assertEquals("type_text", result!!["action_type"])
        assertEquals("你好世界", result["text"])
        assertEquals("输入中文文本", result["thought"])
    }

    @Test
    fun `extractJsonFromText handles emoji in text field`() {
        val text = """{"thought":"typing emoji","action_type":"type_text","text":"Hello 🌍🎉","completed":false}"""
        val result = ResponseParser.extractJsonFromText(text)
        assertNotNull(result)
        assertEquals("type_text", result!!["action_type"])
        assertEquals("Hello 🌍🎉", result["text"])
    }

    // --- Empty / null fields ---

    @Test
    fun `extractJsonFromText handles empty string fields`() {
        val text = """{"thought":"","action_type":"wait","completed":false}"""
        val result = ResponseParser.extractJsonFromText(text)
        assertNotNull(result)
        assertEquals("wait", result!!["action_type"])
        assertEquals("", result["thought"])
    }

    @Test
    fun `extractJsonFromText handles null fields`() {
        val text = """{"thought":"testing null","action_type":"tap","x":100,"y":200,"app_name":null,"completed":false}"""
        val result = ResponseParser.extractJsonFromText(text)
        assertNotNull(result)
        assertEquals("tap", result!!["action_type"])
        assertNull("null JSON value should become null", result["app_name"])
    }

    @Test
    fun `jsonObjectToMap converts null values`() {
        val json = JSONObject()
        json.put("key1", "value1")
        json.put("key2", JSONObject.NULL)
        val map = ResponseParser.jsonObjectToMap(json)
        assertEquals("value1", map["key1"])
        assertNull(map["key2"])
    }

    // --- parse() with OpenAI tool_calls format ---

    @Test
    fun `parse handles OpenAI chat completions tool_calls format`() {
        val response = JSONObject().apply {
            put("id", "chatcmpl-test123")
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("index", 0)
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("tool_calls", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", "call_abc123")
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", "phone_action")
                                    put("arguments", """{"thought":"I see the home screen","action_type":"tap","x":540,"y":960,"completed":false}""")
                                })
                            })
                        })
                    })
                    put("finish_reason", "tool_calls")
                })
            })
        }

        val result = ResponseParser.parse(response)
        assertNotNull("Should parse OpenAI tool_calls format", result)
        assertEquals("tap", result!!["action_type"])
        assertEquals(540, result["x"])
        assertEquals(960, result["y"])
        assertEquals(false, result["completed"])
        assertEquals("I see the home screen", result["thought"])
    }

    // --- parse() with text-only response (no tool calls) ---

    @Test
    fun `parse handles text-only response via chat completions`() {
        val response = JSONObject().apply {
            put("id", "chatcmpl-text")
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("index", 0)
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", """{"thought":"no tool call","action_type":"wait","completed":false}""")
                    })
                    put("finish_reason", "stop")
                })
            })
        }

        val result = ResponseParser.parse(response)
        assertNotNull("Should fall back to parsing JSON from content", result)
        assertEquals("wait", result!!["action_type"])
        assertEquals(false, result["completed"])
    }

    @Test
    fun `parse handles Responses API text-only message`() {
        val response = JSONObject().apply {
            put("id", "resp_text_only")
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "message")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "output_text")
                            put("text", """{"thought":"waiting","action_type":"wait","completed":false}""")
                        })
                    })
                })
            })
        }

        val result = ResponseParser.parse(response)
        assertNotNull("Should extract JSON from text message in Responses API", result)
        assertEquals("wait", result!!["action_type"])
    }

    @Test
    fun `parse returns null for empty response`() {
        val response = JSONObject().apply {
            put("id", "resp_empty")
        }
        val result = ResponseParser.parse(response)
        assertNull("Should return null for response with no output or choices", result)
    }

    @Test
    fun `parse handles Responses API function_call`() {
        val response = JSONObject().apply {
            put("id", "resp_fc")
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "function_call")
                    put("name", "phone_action")
                    put("arguments", """{"thought":"opening app","action_type":"open_app","app_name":"Chrome","completed":false}""")
                })
            })
        }

        val result = ResponseParser.parse(response)
        assertNotNull(result)
        assertEquals("open_app", result!!["action_type"])
        assertEquals("Chrome", result["app_name"])
        assertEquals(false, result["completed"])
    }
}
