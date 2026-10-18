package ai.opencyvis.engine

sealed class HandoffUiState {
    object Idle : HandoffUiState()

    data class Active(
        val reason: String,
        val elapsedMs: Long = 0L
    ) : HandoffUiState()

    data class PendingReturn(
        val reason: String,
        val countdownSeconds: Int,
        val source: String
    ) : HandoffUiState()
}
