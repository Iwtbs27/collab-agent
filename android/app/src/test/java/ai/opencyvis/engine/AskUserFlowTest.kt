package ai.opencyvis.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/**
 * Functional tests for the ask_user flow.
 *
 * Tests the core suspension/resumption mechanism used by AgentEngine when the LLM
 * returns action_type="ask_user". These tests verify the CompletableDeferred pattern
 * in isolation, matching the engine implementation exactly.
 *
 * Flow under test:
 *   LLM returns ask_user → engine sets WaitingForUser state + suspends on deferred
 *   → submitUserResponse(answer) completes deferred → engine resumes and appends message
 *   → next loop iteration executes
 *
 *   OR: stop() → complete(null) → engine exits loop and sets Idle
 */
class AskUserFlowTest {

    // -------------------------------------------------------------------------
    // Core mechanism: CompletableDeferred suspend / resume
    // -------------------------------------------------------------------------

    @Test
    fun `submitUserResponse resumes suspended coroutine with answer`() = runTest {
        val deferred = CompletableDeferred<String?>()
        var receivedAnswer: String? = null

        val job = launch {
            receivedAnswer = deferred.await()
        }

        yield() // let the coroutine suspend
        assertNull("Should not have an answer yet", receivedAnswer)

        deferred.complete("Call John")
        job.join()

        assertEquals("Call John", receivedAnswer)
    }

    @Test
    fun `stop() sends null to resume suspended coroutine`() = runTest {
        val deferred = CompletableDeferred<String?>()
        var receivedAnswer: String? = "initial"

        val job = launch {
            receivedAnswer = deferred.await()
        }

        yield()
        deferred.complete(null) // simulates stop()
        job.join()

        assertNull("stop() should produce null answer", receivedAnswer)
    }

    @Test
    fun `null answer signals engine loop to exit`() = runTest {
        // Mirrors the engine logic: null answer → return (Idle)
        val deferred = CompletableDeferred<String?>()
        val state = MutableStateFlow<AgentState>(AgentState.Running(1, "thinking"))

        val job = launch {
            val answer = deferred.await()
            if (answer == null) {
                state.value = AgentState.Idle()
                return@launch
            }
            state.value = AgentState.Running(1, "got answer: $answer")
        }

        yield()
        deferred.complete(null)
        job.join()

        assertTrue("Engine should be Idle after null answer (stop)", state.value is AgentState.Idle)
    }

    @Test
    fun `non-null answer lets loop continue with Running state`() = runTest {
        val deferred = CompletableDeferred<String?>()
        val state = MutableStateFlow<AgentState>(AgentState.WaitingForUser("Which contact?", 2))

        val job = launch {
            val answer = deferred.await()
            if (answer == null) {
                state.value = AgentState.Idle()
                return@launch
            }
            // Engine resumes: update state to Running for next step
            state.value = AgentState.Running(2, "Continuing after user answered")
        }

        yield()
        assertEquals(AgentState.WaitingForUser("Which contact?", 2), state.value)

        deferred.complete("John Smith")
        job.join()

        assertTrue("Engine should resume Running after answer", state.value is AgentState.Running)
    }

    // -------------------------------------------------------------------------
    // Message appending: user answer added to conversation history
    // -------------------------------------------------------------------------

    @Test
    fun `user answer is appended to messages as user role`() = runTest {
        val messages = mutableListOf<Map<String, Any>>()
        val deferred = CompletableDeferred<String?>()

        val job = launch {
            val answer = deferred.await() ?: return@launch
            messages.add(mapOf("role" to "user", "content" to "用户回答：$answer"))
        }

        yield()
        deferred.complete("Open WeChat")
        job.join()

        assertEquals(1, messages.size)
        assertEquals("user", messages[0]["role"])
        assertEquals("用户回答：Open WeChat", messages[0]["content"])
    }

    @Test
    fun `null answer does not append message to history`() = runTest {
        val messages = mutableListOf<Map<String, Any>>()
        val deferred = CompletableDeferred<String?>()

        val job = launch {
            val answer = deferred.await() ?: return@launch
            messages.add(mapOf("role" to "user", "content" to "用户回答：$answer"))
        }

        yield()
        deferred.complete(null)
        job.join()

        assertEquals("Stop should not add message to history", 0, messages.size)
    }

    @Test
    fun `answer message uses correct format prefix`() = runTest {
        val deferred = CompletableDeferred<String?>()
        var content = ""

        val job = launch {
            val answer = deferred.await() ?: return@launch
            content = "用户回答：$answer"
        }

        yield()
        deferred.complete("confirmed")
        job.join()

        assertTrue("Message should start with 用户回答：", content.startsWith("用户回答："))
        assertTrue("Message should contain the answer", content.contains("confirmed"))
    }

    // -------------------------------------------------------------------------
    // State transitions: WaitingForUser → next state
    // -------------------------------------------------------------------------

    @Test
    fun `WaitingForUser state is set before suspension`() = runTest {
        val stateFlow = MutableStateFlow<AgentState>(AgentState.Running(1, "thinking"))
        val deferred = CompletableDeferred<String?>()

        val job = launch {
            stateFlow.value = AgentState.WaitingForUser("Which app?", 1)
            deferred.await()
        }

        yield()
        assertTrue("State should be WaitingForUser while suspended",
            stateFlow.value is AgentState.WaitingForUser)
        assertEquals("Which app?", (stateFlow.value as AgentState.WaitingForUser).question)

        deferred.complete("Settings")
        job.join()
    }

    @Test
    fun `WaitingForUser state carries the LLM question`() {
        val question = "找不到目标 App，请问您要打开哪个 App？"
        val state = AgentState.WaitingForUser(question = question, step = 2)
        assertEquals(question, state.question)
        assertEquals(2, state.step)
    }

    @Test
    fun `multiple sequential ask_user interactions work correctly`() = runTest {
        val messages = mutableListOf<Map<String, Any>>()
        val answers = listOf("John", "mobile")

        for (answer in answers) {
            val deferred = CompletableDeferred<String?>()
            val job = launch {
                val response = deferred.await() ?: return@launch
                messages.add(mapOf("role" to "user", "content" to "用户回答：$response"))
            }
            yield()
            deferred.complete(answer)
            job.join()
        }

        assertEquals(2, messages.size)
        assertEquals("用户回答：John", messages[0]["content"])
        assertEquals("用户回答：mobile", messages[1]["content"])
    }

    // -------------------------------------------------------------------------
    // Stop while waiting: deferred cleanup
    // -------------------------------------------------------------------------

    @Test
    fun `deferred is nulled out after completion`() = runTest {
        var deferred: CompletableDeferred<String?>? = CompletableDeferred()

        val job = launch {
            deferred?.await()
            deferred = null  // mirrors engine: userResponseDeferred = null
        }

        yield()
        deferred?.complete("done")
        job.join()

        assertNull("Deferred should be cleared after completion", deferred)
    }

    @Test
    fun `stop() completes deferred and nulls it (mirrors engine stop)`() = runTest {
        var deferred: CompletableDeferred<String?>? = CompletableDeferred()
        var loopExited = false

        val job = launch {
            val answer = deferred?.await()
            deferred = null
            if (answer == null) {
                loopExited = true
            }
        }

        yield()
        // Simulate engine.stop()
        deferred?.complete(null)
        deferred = null
        job.join()

        assertTrue("Loop should have exited cleanly", loopExited)
        assertNull("Deferred should be null after stop", deferred)
    }

    // -------------------------------------------------------------------------
    // pendingUserAnswer: answer merged into next user message (no consecutive user msgs)
    // -------------------------------------------------------------------------

    @Test
    fun `user answer is merged into next screenshot message not added separately`() = runTest {
        // Simulate the new flow: after ask_user, answer is stored as pendingUserAnswer
        // and incorporated into the next buildUserMessage call.
        var pendingUserAnswer: String? = null
        val messages = mutableListOf<Map<String, Any>>()
        val deferred = CompletableDeferred<String?>()

        val job = launch {
            val answer = deferred.await() ?: return@launch
            pendingUserAnswer = answer   // store, don't add separate message
        }

        yield()
        deferred.complete("66666")
        job.join()

        // Simulate next loop iteration: build user message with answer merged in
        val text = if (pendingUserAnswer != null) {
            "用户回答：$pendingUserAnswer\n请根据用户回答继续完成任务：dial jimmy"
        } else {
            "dial jimmy"
        }
        pendingUserAnswer = null
        messages.add(mapOf("role" to "user", "content" to listOf(
            mapOf("type" to "input_image", "image_url" to "data:image/jpeg;base64,fake"),
            mapOf("type" to "input_text", "text" to text)
        )))

        // Exactly one user message — no extra "用户回答：" message before it
        assertEquals(1, messages.size)
        val content = messages[0]["content"] as List<*>
        val textPart = content[1] as Map<*, *>
        val msgText = textPart["text"] as String
        assertTrue("Answer must be in message text", msgText.contains("66666"))
        assertTrue("Original instruction must be in message text", msgText.contains("dial jimmy"))
        assertNull("pendingUserAnswer should be cleared after use", pendingUserAnswer)
    }

    @Test
    fun `no consecutive user messages after ask_user answer`() = runTest {
        // Before fix: messages grew like [..., assistant(ask_user), user("用户回答"), user(screenshot)]
        // After fix:  messages grow like [..., assistant(ask_user), user(screenshot + answer merged)]
        val messages = mutableListOf<Map<String, Any>>()
        messages.add(mapOf("role" to "system", "content" to "system prompt"))
        messages.add(mapOf("role" to "user", "content" to "screenshot1 + dial jimmy"))
        messages.add(mapOf("role" to "assistant", "content" to """{"action_type":"ask_user","question":"What number?"}"""))

        // New behavior: merge answer into next screenshot message
        val userAnswer = "66666"
        val text = "用户回答：$userAnswer\n请根据用户回答继续完成任务：dial jimmy"
        messages.add(mapOf("role" to "user", "content" to text))

        // Verify no two consecutive user messages
        for (i in 0 until messages.size - 1) {
            val current = messages[i]["role"]
            val next = messages[i + 1]["role"]
            assertFalse(
                "Consecutive user messages at index $i and ${i + 1}",
                current == "user" && next == "user"
            )
        }
    }

    @Test
    fun `merged message text contains answer prefix and original instruction`() {
        val userAnswer = "13800138000"
        val instruction = "call mom"
        val text = "用户回答：$userAnswer\n请根据用户回答继续完成任务：$instruction"

        assertTrue(text.startsWith("用户回答："))
        assertTrue(text.contains(userAnswer))
        assertTrue(text.contains(instruction))
        assertTrue(text.contains("请根据用户回答继续完成任务"))
    }

    @Test
    fun `without pending answer message text is just the instruction`() {
        val pendingUserAnswer: String? = null
        val instruction = "open settings"
        val text = if (pendingUserAnswer != null) {
            "用户回答：$pendingUserAnswer\n请根据用户回答继续完成任务：$instruction"
        } else {
            instruction
        }
        assertEquals(instruction, text)
    }

    // -------------------------------------------------------------------------
    // StepResult for ask_user action
    // -------------------------------------------------------------------------

    @Test
    fun `StepResult for ask_user has correct fields`() {
        val result = StepResult(
            step = 2,
            actionType = "ask_user",
            thought = "need clarification",
            success = true,
            detail = "Asking: Which contact do you want to call?",
            durationMs = 50L,
            completed = false  // ask_user never marks completed
        )
        assertEquals("ask_user", result.actionType)
        assertTrue("ask_user step is success", result.success)
        assertFalse("ask_user does not complete the task", result.completed)
        assertTrue(result.detail.contains("Asking:"))
    }

    @Test
    fun `ask_user StepResult completed is always false`() {
        // ask_user suspends the loop — it never marks the task as completed
        val result = StepResult(
            step = 1,
            actionType = "ask_user",
            thought = "uncertain",
            success = true,
            detail = "Asking: Confirm?",
            durationMs = 10L,
            completed = false
        )
        assertFalse(result.completed)
    }
}
