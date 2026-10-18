package ai.opencyvis.fixtures

import ai.opencyvis.engine.StepResult

fun buildFinishResponse(thought: String = "task done"): Map<String, Any?> = mapOf(
    "action_type" to "finish",
    "thought" to thought
)

fun buildTapResponse(x: Int, y: Int, thought: String = "tapping"): Map<String, Any?> = mapOf(
    "action_type" to "tap",
    "thought" to thought,
    "x" to x,
    "y" to y
)

fun buildLongPressResponse(x: Int, y: Int, thought: String = "long pressing"): Map<String, Any?> = mapOf(
    "action_type" to "long_press",
    "thought" to thought,
    "x" to x,
    "y" to y
)

fun buildOpenAppResponse(appName: String, thought: String = "opening app"): Map<String, Any?> = mapOf(
    "action_type" to "open_app",
    "thought" to thought,
    "app_name" to appName
)

fun buildSwipeResponse(direction: String, thought: String = "swiping"): Map<String, Any?> = mapOf(
    "action_type" to "swipe",
    "thought" to thought,
    "direction" to direction
)

fun buildTypeTextResponse(text: String, thought: String = "typing"): Map<String, Any?> = mapOf(
    "action_type" to "type_text",
    "thought" to thought,
    "text" to text
)

fun buildKeyEventResponse(key: String, thought: String = "pressing key"): Map<String, Any?> = mapOf(
    "action_type" to "key_event",
    "thought" to thought,
    "key" to key
)

fun buildWaitResponse(thought: String = "waiting"): Map<String, Any?> = mapOf(
    "action_type" to "wait",
    "thought" to thought
)

fun buildFailResponse(reason: String, thought: String = "failing"): Map<String, Any?> = mapOf(
    "action_type" to "fail",
    "thought" to thought,
    "reason" to reason
)

fun buildAskUserResponse(question: String, thought: String = "asking user"): Map<String, Any?> = mapOf(
    "action_type" to "ask_user",
    "thought" to thought,
    "question" to question
)

fun buildNoteResponse(note: String, thought: String = "noting"): Map<String, Any?> = mapOf(
    "action_type" to "note",
    "thought" to thought,
    "note" to note
)

fun buildHandoffResponse(reason: String, thought: String = "handing off"): Map<String, Any?> = mapOf(
    "action_type" to "handoff_user",
    "thought" to thought,
    "handoff_reason" to reason
)

fun buildRememberResponse(key: String, value: String, category: String = "", thought: String = "remembering"): Map<String, Any?> = mapOf(
    "action_type" to "remember",
    "thought" to thought,
    "memory_key" to key,
    "memory_value" to value,
    "memory_category" to category
)

fun stepResult(
    step: Int = 1,
    actionType: String = "tap",
    thought: String = "test",
    success: Boolean = true,
    detail: String = "",
    durationMs: Long = 100,
    completed: Boolean = false
) = StepResult(step, actionType, thought, success, detail, durationMs, completed)
