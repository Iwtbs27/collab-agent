package ai.opencyvis.engine

data class StepResult(
    val step: Int,
    val actionType: String,
    val thought: String,
    val success: Boolean,
    val detail: String,
    val durationMs: Long,
    val completed: Boolean,
    val debugInfo: String? = null,
    val suggestedRoutineName: String? = null,
    val suggestedRoutineIcon: String? = null
)
