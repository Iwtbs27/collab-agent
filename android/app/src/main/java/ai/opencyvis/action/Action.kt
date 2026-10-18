package ai.opencyvis.action

/**
 * Sealed class representing all possible phone actions.
 * Mirrors the Python ActionType enum from actions.py.
 */
sealed class Action(val typeName: String, open val thought: String) {

    data class Tap(
        val x: Int,
        val y: Int,
        override val thought: String = ""
    ) : Action("tap", thought)

    data class LongPress(
        val x: Int,
        val y: Int,
        override val thought: String = ""
    ) : Action("long_press", thought)

    data class OpenApp(
        val appName: String,
        override val thought: String = ""
    ) : Action("open_app", thought)

    data class Swipe(
        val direction: String,
        override val thought: String = ""
    ) : Action("swipe", thought)

    data class KeyEvent(
        val key: String,
        override val thought: String = ""
    ) : Action("key_event", thought)

    data class TypeText(
        val text: String,
        override val thought: String = ""
    ) : Action("type_text", thought)

    data class Wait(
        override val thought: String = ""
    ) : Action("wait", thought)

    data class Finish(
        override val thought: String = "",
        val suggestedRoutineName: String? = null,
        val suggestedRoutineIcon: String? = null
    ) : Action("finish", thought)

    data class Fail(
        val reason: String,
        override val thought: String = ""
    ) : Action("fail", thought)

    data class AskUser(
        val question: String,
        override val thought: String = ""
    ) : Action("ask_user", thought)

    data class HandoffUser(
        val reason: String,
        override val thought: String = ""
    ) : Action("handoff_user", thought)

    data class Note(
        val note: String,
        override val thought: String = ""
    ) : Action("note", thought)

    data class Remember(
        val key: String,
        val value: String,
        val category: String = "",
        override val thought: String = ""
    ) : Action("remember", thought)

    data class ListApps(
        val keyword: String = "",
        override val thought: String = ""
    ) : Action("list_apps", thought)

    data class SaveRoutine(
        val routineName: String,
        val routineIcon: String,
        val routineInstruction: String?,
        val scheduleType: String?,
        val scheduleTime: String?,
        val scheduleRepeat: String?,
        val scheduleInterval: Int?,
        val scheduleLocation: String?,
        val scheduleOnEnter: Boolean?,
        override val thought: String = ""
    ) : Action("save_routine", thought)

    companion object {
        /**
         * Parse an Action from the LLM tool call result map.
         */
        private fun extractInt(value: Any?): Int? = when (value) {
            is Number -> value.toInt()
            is List<*> -> (value.firstOrNull() as? Number)?.toInt()
            else -> null
        }

        private fun extractCoords(map: Map<String, Any?>): Pair<Int?, Int?> {
            val xVal = map["x"]
            val yVal = map["y"]
            if (xVal is List<*> && xVal.size >= 2 && yVal == null) {
                return Pair((xVal[0] as? Number)?.toInt(), (xVal[1] as? Number)?.toInt())
            }
            return Pair(extractInt(xVal), extractInt(yVal))
        }

        fun fromMap(map: Map<String, Any?>): Action {
            val thought = (map["thought"] as? String) ?: ""
            val actionType = ((map["action_type"] as? String) ?: "fail").trim()

            return when (actionType) {
                "tap" -> extractCoords(map).let { (cx, cy) ->
                    Tap(
                        x = cx ?: throw IllegalArgumentException("tap action missing required field 'x'"),
                        y = cy ?: throw IllegalArgumentException("tap action missing required field 'y'"),
                        thought = thought
                    )
                }
                "long_press" -> extractCoords(map).let { (cx, cy) ->
                    LongPress(
                        x = cx ?: throw IllegalArgumentException("long_press action missing required field 'x'"),
                        y = cy ?: throw IllegalArgumentException("long_press action missing required field 'y'"),
                        thought = thought
                    )
                }
                "open_app" -> OpenApp(
                    appName = (map["app_name"] as? String) ?: "",
                    thought = thought
                )
                "swipe" -> Swipe(
                    direction = (map["direction"] as? String) ?: "up",
                    thought = thought
                )
                "key_event" -> KeyEvent(
                    key = (map["key"] as? String) ?: "back",
                    thought = thought
                )
                "type_text" -> TypeText(
                    text = (map["text"] as? String) ?: "",
                    thought = thought
                )
                "wait" -> Wait(thought = thought)
                "finish" -> Finish(
                    thought = thought,
                    suggestedRoutineName = map["suggested_routine_name"] as? String,
                    suggestedRoutineIcon = map["suggested_routine_icon"] as? String
                )
                "fail" -> Fail(
                    reason = (map["reason"] as? String) ?: "unknown reason",
                    thought = thought
                )
                "ask_user" -> AskUser(
                    question = (map["question"] as? String) ?: thought,
                    thought = thought
                )
                "handoff_user" -> HandoffUser(
                    reason = (map["handoff_reason"] as? String) ?: thought,
                    thought = thought
                )
                "note" -> Note(
                    note = (map["note"] as? String) ?: thought,
                    thought = thought
                )
                "remember" -> Remember(
                    key = (map["memory_key"] as? String) ?: "",
                    value = (map["memory_value"] as? String) ?: "",
                    category = (map["memory_category"] as? String) ?: "",
                    thought = thought
                )
                "list_apps" -> ListApps(
                    keyword = (map["keyword"] as? String) ?: "",
                    thought = thought
                )
                "save_routine" -> SaveRoutine(
                    routineName = (map["routine_name"] as? String) ?: "",
                    routineIcon = (map["routine_icon"] as? String) ?: "⚡",
                    routineInstruction = map["routine_instruction"] as? String,
                    scheduleType = map["schedule_type"] as? String,
                    scheduleTime = map["schedule_time"] as? String,
                    scheduleRepeat = map["schedule_repeat"] as? String,
                    scheduleInterval = (map["schedule_interval"] as? Number)?.toInt(),
                    scheduleLocation = map["schedule_location"] as? String,
                    scheduleOnEnter = map["schedule_on_enter"] as? Boolean,
                    thought = thought
                )
                else -> Fail(reason = "Unknown action type: $actionType", thought = thought)
            }
        }
    }
}
