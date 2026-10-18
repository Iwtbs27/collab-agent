package ai.opencyvis.llm

import ai.opencyvis.engine.LlmPrompts
import org.json.JSONArray
import org.json.JSONObject

object ToolSchema {

    fun phoneActionTool(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
            put("name", "phone_action")
            put("description", LlmPrompts.toolDescription())
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("thought", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("thought"))
                    })
                    put("action_type", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("tap")
                            put("open_app")
                            put("swipe")
                            put("key_event")
                            put("type_text")
                            put("wait")
                            put("finish")
                            put("fail")
                            put("ask_user")
                            put("handoff_user")
                            put("note")
                            put("remember")
                            put("save_routine")
                        })
                        put("description", LlmPrompts.paramDescription("action_type"))
                    })
                    put("x", JSONObject().apply {
                        put("type", "integer")
                        put("description", LlmPrompts.paramDescription("x"))
                    })
                    put("y", JSONObject().apply {
                        put("type", "integer")
                        put("description", LlmPrompts.paramDescription("y"))
                    })
                    put("app_name", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("app_name"))
                    })
                    put("direction", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("up")
                            put("down")
                            put("left")
                            put("right")
                        })
                        put("description", LlmPrompts.paramDescription("direction"))
                    })
                    put("key", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("back")
                            put("home")
                            put("enter")
                            put("recent")
                        })
                        put("description", LlmPrompts.paramDescription("key"))
                    })
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("text"))
                    })
                    put("reason", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("reason"))
                    })
                    put("question", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("question"))
                    })
                    put("handoff_reason", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("handoff_reason"))
                    })
                    put("note", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("note"))
                    })
                    put("memory_key", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("memory_key"))
                    })
                    put("memory_value", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("memory_value"))
                    })
                    put("memory_category", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("memory_category"))
                    })
                    put("routine_name", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("routine_name"))
                    })
                    put("routine_icon", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("routine_icon"))
                    })
                    put("routine_instruction", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("routine_instruction"))
                    })
                    put("schedule_type", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("time")
                            put("interval")
                            put("geofence")
                        })
                        put("description", LlmPrompts.paramDescription("schedule_type"))
                    })
                    put("schedule_time", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("schedule_time"))
                    })
                    put("schedule_repeat", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("schedule_repeat"))
                    })
                    put("schedule_interval", JSONObject().apply {
                        put("type", "integer")
                        put("description", LlmPrompts.paramDescription("schedule_interval"))
                    })
                    put("schedule_location", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("schedule_location"))
                    })
                    put("schedule_on_enter", JSONObject().apply {
                        put("type", "boolean")
                        put("description", LlmPrompts.paramDescription("schedule_on_enter"))
                    })
                    put("suggested_routine_name", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("suggested_routine_name"))
                    })
                    put("suggested_routine_icon", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("suggested_routine_icon"))
                    })
                })
                put("required", JSONArray().apply {
                    put("thought")
                    put("action_type")
                })
            })
            })
        }
    }

    fun toolsArray(): JSONArray {
        return JSONArray().put(phoneActionTool())
    }

    fun anthropicPhoneActionTool(): JSONObject {
        return JSONObject().apply {
            put("name", "phone_action")
            put("description", LlmPrompts.toolDescription())
            put("input_schema", phoneActionTool().getJSONObject("function").getJSONObject("parameters"))
        }
    }

    fun anthropicToolsArray(): JSONArray {
        return JSONArray().put(anthropicPhoneActionTool())
    }
}
