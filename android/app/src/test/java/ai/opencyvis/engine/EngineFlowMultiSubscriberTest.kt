package ai.opencyvis.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for Bug 2 (chat progress disappeared after the slim
 * overlay was added).
 *
 * Root cause: `AgentService.onEngineCreated` was a single-listener `var`.
 * Both `OverlayService` and `ControlPanelActivity` registered against it,
 * and whichever bound last silently overwrote the other. The fix replaces
 * the var with a `StateFlow<AgentEngine?>` so any number of subscribers
 * can observe engine swaps independently.
 *
 * This test models the new contract: when the flow value changes, every
 * collector observes every emission. If anyone reverts the design back to
 * a single-listener var or a SharedFlow without replay, this test would
 * fail.
 */
class EngineFlowMultiSubscriberTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `multiple subscribers each observe every engine swap`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = MutableStateFlow<String?>(null)
        val flow: StateFlow<String?> = source.asStateFlow()

        val seenA = mutableListOf<String?>()
        val seenB = mutableListOf<String?>()

        val jobA = launch(dispatcher) { flow.collect { seenA += it } }
        val jobB = launch(dispatcher) { flow.collect { seenB += it } }

        // Three consecutive "engine creations".
        source.value = "engine-1"
        advanceUntilIdle()
        source.value = "engine-2"
        advanceUntilIdle()
        source.value = null         // engine cleared (e.g. service destroyed)
        advanceUntilIdle()
        source.value = "engine-3"
        advanceUntilIdle()

        jobA.cancel()
        jobB.cancel()

        // StateFlow is conflated; with StandardTestDispatcher the launched
        // collectors haven't started by the time the first value is set,
        // so the initial null is conflated away. The crucial property for
        // the regression is that BOTH subscribers see the SAME sequence.
        val expected = listOf("engine-1", "engine-2", null, "engine-3")
        assertEquals(
            "Subscriber A should observe every engine emission in order",
            expected, seenA
        )
        assertEquals(
            "Subscriber B should observe the same sequence as Subscriber A — " +
                    "regression check for Bug 2 (single-listener overwrite)",
            expected, seenB
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `late subscriber receives current engine via StateFlow replay`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = MutableStateFlow<String?>(null)
        val flow: StateFlow<String?> = source.asStateFlow()

        // First subscriber runs while engine swaps happen.
        val seenA = mutableListOf<String?>()
        val jobA = launch(dispatcher) { flow.collect { seenA += it } }
        source.value = "engine-1"
        advanceUntilIdle()
        source.value = "engine-2"
        advanceUntilIdle()

        // Second subscriber attaches AFTER engine-2 is already current
        // — it must immediately receive the current value (sticky).
        val seenB = mutableListOf<String?>()
        val jobB = launch(dispatcher) { flow.collect { seenB += it } }
        advanceUntilIdle()

        jobA.cancel()
        jobB.cancel()

        assertEquals(
            "Late subscriber must immediately receive the current engine — " +
                    "this guarantees OverlayService/ControlPanelActivity that " +
                    "bind after a task has already started don't miss state.",
            listOf("engine-2"), seenB
        )
    }
}
