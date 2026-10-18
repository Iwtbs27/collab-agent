package ai.opencyvis.engine

import ai.opencyvis.action.Action
import ai.opencyvis.action.ActionExecutor
import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.fixtures.FakeLlmClient
import ai.opencyvis.fixtures.buildFinishResponse
import ai.opencyvis.fixtures.buildOpenAppResponse
import ai.opencyvis.fixtures.buildTapResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentEngineTest {

    @Before
    fun setUp() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns FAKE_SCREENSHOT
        every { ScreenCapture.captureBase64(any(), any()) } returns FAKE_SCREENSHOT
    }

    @After
    fun tearDown() {
        unmockkObject(ScreenCapture)
    }

    // --- AgentState data class tests ---

    @Test
    fun `initial state is Idle`() {
        val state: AgentState = AgentState.Idle()
        assertTrue(state is AgentState.Idle)
    }

    @Test
    fun `Running state holds step and thought`() {
        val state = AgentState.Running(step = 3, thought = "opening settings")
        assertEquals(3, state.step)
        assertEquals("opening settings", state.thought)
    }

    @Test
    fun `Error state holds message`() {
        val state = AgentState.Error(message = "API timeout")
        assertEquals("API timeout", state.message)
    }

    @Test
    fun `WaitingForUser state holds question and step`() {
        val state = AgentState.WaitingForUser(question = "Which contact?", step = 3)
        assertEquals("Which contact?", state.question)
        assertEquals(3, state.step)
    }

    @Test
    fun `WaitingForHandoff state holds reason and step`() {
        val state = AgentState.WaitingForHandoff(reason = "need login", step = 5)
        assertEquals("need login", state.reason)
        assertEquals(5, state.step)
    }

    @Test
    fun `Paused is a singleton object`() {
        assertSame(AgentState.Paused, AgentState.Paused)
    }

    // --- SIDE_EFFECT_ACTIONS tests ---

    @Test
    fun `side-effect actions include all mutating action types`() {
        val sideEffects = AgentEngine.SIDE_EFFECT_ACTIONS
        assertTrue("tap" in sideEffects)
        assertTrue("swipe" in sideEffects)
        assertTrue("type_text" in sideEffects)
        assertTrue("key_event" in sideEffects)
        assertTrue("open_app" in sideEffects)
        assertTrue("long_press" in sideEffects)
    }

    @Test
    fun `non-mutating actions are not side-effects`() {
        val sideEffects = AgentEngine.SIDE_EFFECT_ACTIONS
        assertFalse("wait" in sideEffects)
        assertFalse("note" in sideEffects)
        assertFalse("finish" in sideEffects)
        assertFalse("fail" in sideEffects)
        assertFalse("ask_user" in sideEffects)
        assertFalse("handoff_user" in sideEffects)
        assertFalse("remember" in sideEffects)
    }

    // --- StepResult tests ---

    @Test
    fun `StepResult holds all properties`() {
        val result = StepResult(
            step = 1, actionType = "tap", thought = "tapping button",
            success = true, detail = "tapped at (500, 300)", durationMs = 150L,
            completed = false
        )
        assertEquals(1, result.step)
        assertEquals("tap", result.actionType)
        assertTrue(result.success)
        assertFalse(result.completed)
    }

    @Test
    fun `StepResult completed flag for finish action`() {
        val result = StepResult(
            step = 5, actionType = "finish", thought = "task complete",
            success = true, detail = "completed", durationMs = 50L, completed = true
        )
        assertTrue(result.completed)
    }

    // --- System prompt tests ---

    @Test
    fun `system prompt contains coordinate system description`() {
        val prompt = AgentEngine.SYSTEM_PROMPT
        assertTrue(prompt.contains("0") && prompt.contains("1000"))
        assertTrue(prompt.contains("坐标") || prompt.contains("coordinate") || prompt.contains("Coordinate"))
    }

    @Test
    fun `system prompt contains all action types`() {
        val prompt = AgentEngine.SYSTEM_PROMPT
        for (actionType in listOf("tap", "swipe", "type_text", "key_event", "open_app", "wait", "finish", "fail", "ask_user")) {
            assertTrue("Should mention $actionType", prompt.contains(actionType))
        }
    }

    @Test
    fun `system prompt mentions ask_user with question parameter`() {
        assertTrue(AgentEngine.SYSTEM_PROMPT.contains("ask_user(question)"))
    }

    // --- Engine integration tests with FakeLlmClient + MockK ---

    @Test
    fun `engine starts in Idle state`() {
        val engine = createEngine()
        val state = engine.state.value
        assertTrue("Engine should start Idle", state is AgentState.Idle)
    }

    @Test
    fun `engine with finish-only response completes`() = runTest {
        val fakeLlm = FakeLlmClient()
        fakeLlm.enqueue(buildFinishResponse("done"))

        val actionExecutor = mockk<ActionExecutor>(relaxed = true)
        val engine = AgentEngine(
            llmClient = fakeLlm,
            actionExecutor = actionExecutor,
            maxSteps = 10
        )

        engine.start("say hello")

        // Wait for engine to finish
        eventually { assertTrue(engine.state.value is AgentState.Idle) }

        // finish action should NOT call actionExecutor.execute (intercepted by engine)
        coVerify(exactly = 0) { actionExecutor.execute(any(), any()) }
        assertEquals(1, fakeLlm.requestHistory.size)
    }

    @Test
    fun `engine respects maxSteps limit`() = runTest {
        val fakeLlm = FakeLlmClient()
        // Queue more tap responses than maxSteps allows
        repeat(10) { fakeLlm.enqueue(buildTapResponse(500, 500)) }

        val actionExecutor = mockk<ActionExecutor>(relaxed = true)
        coEvery { actionExecutor.execute(any(), any()) } returns StepResult(
            step = 1, actionType = "tap", thought = "tap", success = true,
            detail = "", durationMs = 10, completed = false
        )

        val engine = AgentEngine(
            llmClient = fakeLlm,
            actionExecutor = actionExecutor,
            maxSteps = 3
        )

        engine.start("infinite task")
        eventually { assertTrue(engine.state.value is AgentState.Idle) }

        assertTrue("Should not exceed maxSteps", fakeLlm.requestHistory.size <= 4)
    }

    // --- Helpers ---

    companion object {
        private const val FAKE_SCREENSHOT = "/9j/4AAQSkZJRg=="
    }

    private fun createEngine(
        fakeLlm: FakeLlmClient = FakeLlmClient(),
        maxSteps: Int = 100
    ): AgentEngine {
        val actionExecutor = mockk<ActionExecutor>(relaxed = true)
        return AgentEngine(
            llmClient = fakeLlm,
            actionExecutor = actionExecutor,
            maxSteps = maxSteps
        )
    }

    private suspend fun eventually(
        timeoutMs: Long = 10000,
        intervalMs: Long = 100,
        block: () -> Unit
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try { block(); return }
            catch (e: Throwable) {
                lastError = e
                kotlinx.coroutines.delay(intervalMs)
            }
        }
        throw AssertionError("Timed out after ${timeoutMs}ms", lastError)
    }
}
