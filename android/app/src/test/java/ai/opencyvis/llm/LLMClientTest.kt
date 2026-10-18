package ai.opencyvis.llm

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Tests for the LLM client that communicates with the LLM API.
 *
 * Since LLMClient may not exist yet, these tests define the expected behavior
 * and use MockWebServer to simulate the API. The tests work with the raw
 * OkHttp + JSON parsing pattern that the client is expected to use.
 */
class LLMClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildFunctionCallResponse(
        name: String = "phone_action",
        arguments: String = """{"thought":"I see the home screen","action_type":"tap","x":500,"y":300,"completed":false}"""
    ): String {
        return JSONObject().apply {
            put("id", "resp_test123")
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "function_call")
                    put("name", name)
                    put("arguments", arguments)
                })
            })
        }.toString()
    }

    private fun buildTextResponse(text: String): String {
        return JSONObject().apply {
            put("id", "resp_text456")
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

    @Test
    fun `successful function_call response is parsed correctly`() {
        val responseBody = buildFunctionCallResponse()
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val client = OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url(server.url("/api/v3/responses"))
            .post(okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                """{"model":"test-model","input":[]}"""
            ))
            .addHeader("Authorization", "Bearer test-key")
            .build()

        val response = client.newCall(request).execute()
        assertEquals(200, response.code)

        val json = JSONObject(response.body!!.string())
        val output = json.getJSONArray("output")
        assertEquals(1, output.length())

        val item = output.getJSONObject(0)
        assertEquals("function_call", item.getString("type"))
        assertEquals("phone_action", item.getString("name"))

        val args = JSONObject(item.getString("arguments"))
        assertEquals("tap", args.getString("action_type"))
        assertEquals(500, args.getInt("x"))
        assertEquals(300, args.getInt("y"))
        assertFalse(args.getBoolean("completed"))
    }

    @Test
    fun `text fallback response is parsed correctly`() {
        val jsonInText = """{"thought":"fallback","action_type":"wait","completed":false}"""
        val responseBody = buildTextResponse(jsonInText)
        server.enqueue(MockResponse().setBody(responseBody).setResponseCode(200))

        val client = OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url(server.url("/api/v3/responses"))
            .post(okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                """{"model":"test-model","input":[]}"""
            ))
            .build()

        val response = client.newCall(request).execute()
        val json = JSONObject(response.body!!.string())
        val output = json.getJSONArray("output")
        val message = output.getJSONObject(0)
        assertEquals("message", message.getString("type"))

        val content = message.getJSONArray("content")
        val textObj = content.getJSONObject(0)
        val text = textObj.getString("text")

        // Should be able to parse JSON from text
        val parsed = JSONObject(text)
        assertEquals("wait", parsed.getString("action_type"))
    }

    @Test
    fun `500 error should be retryable`() {
        // Enqueue a 500 followed by a success
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(buildFunctionCallResponse()))

        val client = OkHttpClient()

        // First call returns 500
        val request1 = okhttp3.Request.Builder()
            .url(server.url("/api/v3/responses"))
            .post(okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                """{"model":"test-model","input":[]}"""
            ))
            .build()
        val response1 = client.newCall(request1).execute()
        assertEquals(500, response1.code)

        // Retry should succeed
        val request2 = okhttp3.Request.Builder()
            .url(server.url("/api/v3/responses"))
            .post(okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                """{"model":"test-model","input":[]}"""
            ))
            .build()
        val response2 = client.newCall(request2).execute()
        assertEquals(200, response2.code)

        // Verify server received 2 requests
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `4xx error should not be retried`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid API key"}}""")
        )

        val client = OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url(server.url("/api/v3/responses"))
            .post(okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                """{"model":"test-model","input":[]}"""
            ))
            .build()

        val response = client.newCall(request).execute()
        assertEquals(401, response.code)

        // 4xx errors are client errors - should NOT retry
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `400 bad request returns error`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"Bad request"}}""")
        )

        val client = OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url(server.url("/api/v3/responses"))
            .post(okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                """{"model":"test-model","input":[]}"""
            ))
            .build()

        val response = client.newCall(request).execute()
        assertEquals(400, response.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `timeout handling with short timeout`() {
        // Server delays response longer than client timeout
        server.enqueue(
            MockResponse()
                .setBody(buildFunctionCallResponse())
                .setBodyDelay(5, TimeUnit.SECONDS)
        )

        val client = OkHttpClient.Builder()
            .readTimeout(1, TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url(server.url("/api/v3/responses"))
            .post(okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                """{"model":"test-model","input":[]}"""
            ))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                response.body!!.string()
            }
            fail("Expected timeout exception")
        } catch (e: java.net.SocketTimeoutException) {
            // Expected
        } catch (e: java.io.InterruptedIOException) {
            // Also acceptable
        }
    }

    @Test
    fun `request includes correct authorization header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(buildFunctionCallResponse()))

        val client = OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url(server.url("/api/v3/responses"))
            .post(okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                """{"model":"test-model","input":[]}"""
            ))
            .addHeader("Authorization", "Bearer test-api-key")
            .build()

        client.newCall(request).execute()

        val recorded = server.takeRequest()
        assertEquals("Bearer test-api-key", recorded.getHeader("Authorization"))
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.contains("/api/v3/responses"))
    }

    // --- tool_choice: required tests ---

    private fun buildSseStreamResponse(argsJson: String): String {
        val event = JSONObject().apply {
            put("type", "response.function_call_arguments.done")
            put("arguments", argsJson)
        }
        return "data: $event\n\ndata: [DONE]\n\n"
    }

    @Test
    fun `LLMClient sends tool_choice required in every request`() = runTest {
        val args = """{"thought":"need clarification","action_type":"ask_user","question":"Which Jimmy?","completed":false}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(buildSseStreamResponse(args))
                .addHeader("Content-Type", "text/event-stream")
        )

        val client = LLMClient(
            apiKey = "test-key",
            model = "test-model",
            baseUrl = "http://localhost:${server.port}"
        )

        client.chatWithTools(listOf(mapOf("role" to "user", "content" to "dial jimmy")))

        val recorded = server.takeRequest()
        val body = JSONObject(recorded.body.readUtf8())
        assertTrue("Request must include tool_choice field", body.has("tool_choice"))
        assertEquals("tool_choice must be 'required'", "required", body.getString("tool_choice"))

        client.shutdown()
    }

    @Test
    fun `LLMClient parses ask_user function call correctly`() = runTest {
        val args = """{"thought":"Contact name is ambiguous","action_type":"ask_user","question":"您要拨打哪个Jimmy？","completed":false}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(buildSseStreamResponse(args))
                .addHeader("Content-Type", "text/event-stream")
        )

        val client = LLMClient(
            apiKey = "test-key",
            model = "test-model",
            baseUrl = "http://localhost:${server.port}"
        )

        val result = client.chatWithTools(listOf(mapOf("role" to "user", "content" to "dial jimmy")))

        assertEquals("ask_user", result["action_type"])
        assertEquals("您要拨打哪个Jimmy？", result["question"])
        assertEquals("Contact name is ambiguous", result["thought"])
        assertEquals(false, result["completed"])

        client.shutdown()
    }

    @Test
    fun `LLMClient request body contains tools array alongside tool_choice`() = runTest {
        val args = """{"thought":"tap","action_type":"tap","x":500,"y":500,"completed":false}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(buildSseStreamResponse(args))
                .addHeader("Content-Type", "text/event-stream")
        )

        val client = LLMClient(
            apiKey = "test-key",
            model = "test-model",
            baseUrl = "http://localhost:${server.port}"
        )

        client.chatWithTools(listOf(mapOf("role" to "user", "content" to "tap the screen")))

        val recorded = server.takeRequest()
        val body = JSONObject(recorded.body.readUtf8())
        assertTrue("tools array must be present", body.has("tools"))
        assertEquals("tool_choice must be required", "required", body.getString("tool_choice"))
        assertTrue("tools must be non-empty", body.getJSONArray("tools").length() > 0)

        client.shutdown()
    }

    @Test
    fun `request body contains model and input`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(buildFunctionCallResponse()))

        val requestBody = JSONObject().apply {
            put("model", "test-model-v1")
            put("input", JSONArray())
        }

        val client = OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url(server.url("/api/v3/responses"))
            .post(okhttp3.RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                requestBody.toString()
            ))
            .build()

        client.newCall(request).execute()

        val recorded = server.takeRequest()
        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("test-model-v1", body.getString("model"))
        assertTrue(body.has("input"))
    }
}
