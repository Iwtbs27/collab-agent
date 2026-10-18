package ai.opencyvis.fixtures

import kotlinx.coroutines.delay

suspend fun eventually(
    timeoutMs: Long = 5000,
    intervalMs: Long = 200,
    block: suspend () -> Unit
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastError: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            block()
            return
        } catch (e: Throwable) {
            lastError = e
            delay(intervalMs)
        }
    }
    throw AssertionError("Timed out after ${timeoutMs}ms", lastError)
}
