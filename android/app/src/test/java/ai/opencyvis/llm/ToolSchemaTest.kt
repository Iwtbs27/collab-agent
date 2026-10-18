package ai.opencyvis.llm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the tool schema sent to the LLM API.
 *
 * The phone_action tool schema defines the actions the LLM can invoke.
 * This verifies the schema has all required fields and valid enum values.
 */
class ToolSchemaTest {

    private fun actualProperties(): JSONObject =
        ToolSchema.phoneActionTool()
            .getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")

    /**
     * Build the expected phone_action tool schema.
     * This should match what the LLM client sends in the tools parameter.
     */
    private fun buildPhoneActionSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", "phone_action")
            put("description", "Perform an action on the Android phone")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("thought", JSONObject().apply {
                        put("type", "string")
                        put("description", "Your reasoning about what you see and what to do")
                    })
                    put("action_type", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("tap")
                            put("long_press")
                            put("swipe")
                            put("type_text")
                            put("key_event")
                            put("open_app")
                            put("wait")
                            put("finish")
                            put("fail")
                        })
                        put("description", "The type of action to perform")
                    })
                    put("x", JSONObject().apply {
                        put("type", "integer")
                        put("description", "X coordinate (0-1000)")
                    })
                    put("y", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Y coordinate (0-1000)")
                    })
                    put("direction", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("up"); put("down"); put("left"); put("right")
                        })
                    })
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "Text to type")
                    })
                    put("key", JSONObject().apply {
                        put("type", "string")
                        put("description", "Key to press")
                    })
                    put("app_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "Name of app to open")
                    })
                    put("reason", JSONObject().apply {
                        put("type", "string")
                        put("description", "Reason for failure")
                    })
                    put("completed", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Whether the task is completed")
                    })
                })
                put("required", JSONArray().apply {
                    put("thought")
                    put("action_type")
                    put("completed")
                })
            })
        }
    }

    @Test
    fun `schema has required top-level fields`() {
        val schema = buildPhoneActionSchema()
        assertTrue(schema.has("type"))
        assertTrue(schema.has("name"))
        assertTrue(schema.has("description"))
        assertTrue(schema.has("parameters"))
        assertEquals("function", schema.getString("type"))
        assertEquals("phone_action", schema.getString("name"))
    }

    @Test
    fun `schema description is non-empty`() {
        val schema = buildPhoneActionSchema()
        val desc = schema.getString("description")
        assertTrue("Description should be non-empty", desc.isNotEmpty())
    }

    @Test
    fun `parameters has correct structure`() {
        val schema = buildPhoneActionSchema()
        val params = schema.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        assertTrue(params.has("properties"))
        assertTrue(params.has("required"))
    }

    @Test
    fun `action_type enum includes all action types`() {
        val schema = buildPhoneActionSchema()
        val params = schema.getJSONObject("parameters")
        val properties = params.getJSONObject("properties")
        val actionType = properties.getJSONObject("action_type")
        val enumValues = actionType.getJSONArray("enum")

        val expectedTypes = setOf(
            "tap", "long_press", "swipe", "type_text",
            "key_event", "open_app", "wait", "finish", "fail"
        )

        val actualTypes = mutableSetOf<String>()
        for (i in 0 until enumValues.length()) {
            actualTypes.add(enumValues.getString(i))
        }

        assertEquals("All action types should be in enum", expectedTypes, actualTypes)
    }

    @Test
    fun `required fields include thought, action_type, and completed`() {
        val schema = buildPhoneActionSchema()
        val params = schema.getJSONObject("parameters")
        val required = params.getJSONArray("required")

        val requiredSet = mutableSetOf<String>()
        for (i in 0 until required.length()) {
            requiredSet.add(required.getString(i))
        }

        assertTrue("thought should be required", requiredSet.contains("thought"))
        assertTrue("action_type should be required", requiredSet.contains("action_type"))
        assertTrue("completed should be required", requiredSet.contains("completed"))
    }

    @Test
    fun `properties include coordinate fields`() {
        val schema = buildPhoneActionSchema()
        val properties = schema.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(properties.has("x"))
        assertTrue(properties.has("y"))
        assertEquals("integer", properties.getJSONObject("x").getString("type"))
        assertEquals("integer", properties.getJSONObject("y").getString("type"))
    }

    @Test
    fun `properties include text and key fields`() {
        val schema = buildPhoneActionSchema()
        val properties = schema.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(properties.has("text"))
        assertTrue(properties.has("key"))
        assertTrue(properties.has("app_name"))
        assertTrue(properties.has("reason"))
    }

    @Test
    fun `direction enum includes all directions`() {
        val schema = buildPhoneActionSchema()
        val properties = schema.getJSONObject("parameters").getJSONObject("properties")
        val direction = properties.getJSONObject("direction")
        val enumValues = direction.getJSONArray("enum")

        val expected = setOf("up", "down", "left", "right")
        val actual = mutableSetOf<String>()
        for (i in 0 until enumValues.length()) {
            actual.add(enumValues.getString(i))
        }
        assertEquals(expected, actual)
    }

    @Test
    fun `completed field is boolean type`() {
        val schema = buildPhoneActionSchema()
        val properties = schema.getJSONObject("parameters").getJSONObject("properties")
        val completed = properties.getJSONObject("completed")
        assertEquals("boolean", completed.getString("type"))
    }

    @Test
    fun `schema is valid JSON when serialized`() {
        val schema = buildPhoneActionSchema()
        val serialized = schema.toString()
        // Should be parseable back
        val reparsed = JSONObject(serialized)
        assertEquals(schema.getString("name"), reparsed.getString("name"))
    }

    // --- ask_user tests ---

    @Test
    fun `ToolSchema action_type enum includes ask_user`() {
        val schema = ToolSchema.phoneActionTool()
        val enumValues = schema
            .getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("action_type")
            .getJSONArray("enum")

        val values = (0 until enumValues.length()).map { enumValues.getString(it) }.toSet()
        assertTrue("action_type enum should contain ask_user", values.contains("ask_user"))
    }

    @Test
    fun `ToolSchema properties include question field`() {
        val schema = ToolSchema.phoneActionTool()
        val properties = schema
            .getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")

        assertTrue("properties should have question field", properties.has("question"))
        val question = properties.getJSONObject("question")
        assertEquals("string", question.getString("type"))
        assertTrue("question description should be non-empty", question.getString("description").isNotEmpty())
    }

    @Test
    fun `ToolSchema question description mentions ask_user`() {
        val schema = ToolSchema.phoneActionTool()
        val description = schema
            .getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("question")
            .getString("description")

        assertTrue(
            "question description should reference ask_user",
            description.contains("ask_user")
        )
    }

    @Test
    fun `ToolSchema enum includes all original action types plus ask_user`() {
        val schema = ToolSchema.phoneActionTool()
        val enumValues = schema
            .getJSONObject("function")
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("action_type")
            .getJSONArray("enum")

        val values = (0 until enumValues.length()).map { enumValues.getString(it) }.toSet()
        val expected = setOf("tap", "open_app", "swipe", "key_event", "type_text", "wait", "finish", "fail", "ask_user", "handoff_user", "note", "remember")
        assertTrue("All action types including ask_user must be present", values.containsAll(expected))
    }

    @Test
    fun `ToolSchema action_type enum includes handoff_user`() {
        val enumValues = actualProperties()
            .getJSONObject("action_type")
            .getJSONArray("enum")

        val values = (0 until enumValues.length()).map { enumValues.getString(it) }.toSet()
        assertTrue("action_type enum should contain handoff_user", values.contains("handoff_user"))
    }

    @Test
    fun `ToolSchema properties include handoff_reason field`() {
        val properties = actualProperties()

        assertTrue("properties should have handoff_reason field", properties.has("handoff_reason"))
        val handoffReason = properties.getJSONObject("handoff_reason")
        assertEquals("string", handoffReason.getString("type"))
        assertTrue(handoffReason.getString("description").contains("handoff_user"))
    }

    @Test
    fun `ToolSchema action_type enum includes remember`() {
        val enumValues = actualProperties()
            .getJSONObject("action_type")
            .getJSONArray("enum")

        val values = (0 until enumValues.length()).map { enumValues.getString(it) }.toSet()
        assertTrue("action_type enum should contain remember", values.contains("remember"))
    }

    @Test
    fun `ToolSchema properties include memory fields`() {
        val properties = actualProperties()

        assertTrue(properties.has("memory_key"))
        assertTrue(properties.has("memory_value"))
        assertTrue(properties.has("memory_category"))
        assertEquals("string", properties.getJSONObject("memory_key").getString("type"))
        assertEquals("string", properties.getJSONObject("memory_value").getString("type"))
        assertEquals("string", properties.getJSONObject("memory_category").getString("type"))
    }

    // ==========================================================================
    // Golden tests against the real ToolSchema
    // ==========================================================================

    @Test
    fun `all action_type enum values are present in real schema`() {
        val expectedActions = setOf(
            "tap", "open_app", "swipe", "key_event", "type_text",
            "wait", "finish", "fail", "ask_user", "handoff_user", "note", "remember"
        )

        val enumArray = actualProperties()
            .getJSONObject("action_type")
            .getJSONArray("enum")

        val actualActions = (0 until enumArray.length()).map { enumArray.getString(it) }.toSet()

        assertEquals(
            "Schema action_type enum must exactly match expected set",
            expectedActions,
            actualActions
        )
    }

    @Test
    fun `tap requires x and y integer parameters`() {
        val props = actualProperties()
        assertTrue("Schema must have x property", props.has("x"))
        assertTrue("Schema must have y property", props.has("y"))
        assertEquals("integer", props.getJSONObject("x").getString("type"))
        assertEquals("integer", props.getJSONObject("y").getString("type"))
    }

    @Test
    fun `swipe requires direction parameter with correct enum`() {
        val props = actualProperties()
        assertTrue("Schema must have direction property", props.has("direction"))
        val dirEnum = props.getJSONObject("direction").getJSONArray("enum")
        val directions = (0 until dirEnum.length()).map { dirEnum.getString(it) }.toSet()
        assertEquals(setOf("up", "down", "left", "right"), directions)
    }

    @Test
    fun `type_text requires text string parameter`() {
        val props = actualProperties()
        assertTrue("Schema must have text property", props.has("text"))
        assertEquals("string", props.getJSONObject("text").getString("type"))
    }

    @Test
    fun `open_app requires app_name string parameter`() {
        val props = actualProperties()
        assertTrue("Schema must have app_name property", props.has("app_name"))
        assertEquals("string", props.getJSONObject("app_name").getString("type"))
    }

    @Test
    fun `real schema is valid JSON when serialized and reparsed`() {
        val schema = ToolSchema.phoneActionTool()
        val serialized = schema.toString()
        val reparsed = JSONObject(serialized)

        // Verify the round-tripped schema still has essential structure
        assertEquals("function", reparsed.getString("type"))
        val function = reparsed.getJSONObject("function")
        assertEquals("phone_action", function.getString("name"))
        assertTrue(function.has("description"))
        assertTrue(function.has("parameters"))
        val params = function.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        assertTrue(params.has("properties"))
        assertTrue(params.has("required"))
    }

    @Test
    fun `toolsArray returns non-empty JSONArray`() {
        val tools = ToolSchema.toolsArray()
        assertTrue("toolsArray() should return at least one tool", tools.length() > 0)

        // The first element should be our phone_action tool
        val firstTool = tools.getJSONObject(0)
        assertEquals("function", firstTool.getString("type"))
        assertEquals("phone_action", firstTool.getJSONObject("function").getString("name"))
    }

    @Test
    fun `toolsArray is parseable as JSON string`() {
        val tools = ToolSchema.toolsArray()
        val serialized = tools.toString()
        val reparsed = JSONArray(serialized)
        assertEquals(tools.length(), reparsed.length())
    }
}
