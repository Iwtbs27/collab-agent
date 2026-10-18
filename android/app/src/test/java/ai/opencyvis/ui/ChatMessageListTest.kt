package ai.opencyvis.ui

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChatMessageListTest {

    private lateinit var list: ChatMessageList

    @Before
    fun setUp() {
        list = ChatMessageList()
    }

    // --- startCycle ---

    @Test
    fun `startCycle adds AGENT_CYCLE message when none exists`() {
        val change = list.startCycle()
        assertNotNull(change)
        assertEquals(1, list.size)
        assertEquals(MessageType.AGENT_CYCLE, list.get(0).type)
    }

    @Test
    fun `startCycle is no-op when cycle already exists`() {
        list.startCycle()
        val change = list.startCycle()
        assertNull(change)
        assertEquals(1, list.size)
    }

    // --- updateCycleText ---

    @Test
    fun `updateCycleText updates existing cycle message`() {
        list.startCycle()
        val change = list.updateCycleText("Thinking...")
        assertNotNull(change)
        assertEquals(ChatMessageList.ChangeType.CHANGED, change!!.type)
        assertEquals("Thinking...", list.get(0).text)
    }

    @Test
    fun `updateCycleText is no-op when no cycle exists`() {
        val change = list.updateCycleText("Thinking...")
        assertNull(change)
    }

    @Test
    fun `updateCycleText replaces text in-place`() {
        list.startCycle()
        list.updateCycleText("Step 1")
        list.updateCycleText("Step 2")
        assertEquals(1, list.size)
        assertEquals("Step 2", list.get(0).text)
    }

    // --- removeCycle ---

    @Test
    fun `removeCycle removes the cycle message`() {
        list.startCycle()
        val change = list.removeCycle()
        assertNotNull(change)
        assertEquals(ChatMessageList.ChangeType.REMOVED, change!!.type)
        assertEquals(0, list.size)
    }

    @Test
    fun `removeCycle is no-op when no cycle exists`() {
        val change = list.removeCycle()
        assertNull(change)
    }

    @Test
    fun `removeCycle preserves other messages`() {
        list.addMessage(ChatMessage(MessageType.USER_INPUT, "hello"))
        list.startCycle()
        list.addMessage(ChatMessage(MessageType.AGENT_STATUS, "status"))
        list.removeCycle()
        assertEquals(2, list.size)
        assertEquals(MessageType.USER_INPUT, list.get(0).type)
        assertEquals(MessageType.AGENT_STATUS, list.get(1).type)
    }

    // --- hasCycle ---

    @Test
    fun `hasCycle returns false when empty`() {
        assertFalse(list.hasCycle())
    }

    @Test
    fun `hasCycle returns true after startCycle`() {
        list.startCycle()
        assertTrue(list.hasCycle())
    }

    @Test
    fun `hasCycle returns false after removeCycle`() {
        list.startCycle()
        list.removeCycle()
        assertFalse(list.hasCycle())
    }

    // --- convertCycleToResult ---

    @Test
    fun `convertCycleToResult converts cycle to AGENT_RESULT`() {
        list.startCycle()
        list.updateCycleText("working...")
        val change = list.convertCycleToResult("Task done")
        assertEquals(ChatMessageList.ChangeType.CHANGED, change.type)
        assertEquals(1, list.size)
        assertEquals(MessageType.AGENT_RESULT, list.get(0).type)
        assertEquals("Task done", list.get(0).text)
    }

    @Test
    fun `convertCycleToResult preserves timestamp from cycle`() {
        list.startCycle()
        val cycleTimestamp = list.get(0).timestamp
        Thread.sleep(10)
        list.convertCycleToResult("Done")
        assertEquals(cycleTimestamp, list.get(0).timestamp)
    }

    @Test
    fun `convertCycleToResult adds new message when no cycle exists`() {
        val change = list.convertCycleToResult("Task done")
        assertEquals(ChatMessageList.ChangeType.INSERTED, change.type)
        assertEquals(1, list.size)
        assertEquals(MessageType.AGENT_RESULT, list.get(0).type)
    }

    @Test
    fun `convertCycleToResult then hasCycle returns false`() {
        list.startCycle()
        list.convertCycleToResult("Done")
        assertFalse(list.hasCycle())
    }

    // --- updateLastAgentStatus ---

    @Test
    fun `updateLastAgentStatus adds new message when none exists`() {
        val change = list.updateLastAgentStatus("status 1")
        assertEquals(ChatMessageList.ChangeType.INSERTED, change.type)
        assertEquals(1, list.size)
        assertEquals("status 1", list.get(0).text)
    }

    @Test
    fun `updateLastAgentStatus updates in-place when last message is status`() {
        list.updateLastAgentStatus("status 1")
        val change = list.updateLastAgentStatus("status 2")
        assertEquals(ChatMessageList.ChangeType.CHANGED, change.type)
        assertEquals(1, list.size)
        assertEquals("status 2", list.get(0).text)
    }

    @Test
    fun `updateLastAgentStatus adds new when last message is not status`() {
        list.updateLastAgentStatus("status 1")
        list.addMessage(ChatMessage(MessageType.USER_INPUT, "hello"))
        val change = list.updateLastAgentStatus("status 2")
        assertEquals(ChatMessageList.ChangeType.INSERTED, change.type)
        assertEquals(3, list.size)
    }

    // --- clear ---

    @Test
    fun `clear removes all messages`() {
        list.addMessage(ChatMessage(MessageType.USER_INPUT, "a"))
        list.addMessage(ChatMessage(MessageType.AGENT_STATUS, "b"))
        list.startCycle()
        val change = list.clear()
        assertEquals(ChatMessageList.ChangeType.RANGE_REMOVED, change.type)
        assertEquals(3, change.index)
        assertEquals(0, list.size)
    }

    // --- full flow: non-debug mode scenario ---

    @Test
    fun `non-debug flow - cycle through steps then convert to result`() {
        list.addMessage(ChatMessage(MessageType.USER_INPUT, "open JD and search SSD"))

        list.startCycle()
        assertTrue(list.hasCycle())

        list.updateCycleText("Opening JD app...")
        assertEquals("Opening JD app...", list.get(1).text)

        list.updateCycleText("Found search bar, tapping...")
        assertEquals("Found search bar, tapping...", list.get(1).text)
        assertEquals(2, list.size)

        list.convertCycleToResult("Task complete")
        assertEquals(2, list.size)
        assertEquals(MessageType.AGENT_RESULT, list.get(1).type)
        assertEquals("Task complete", list.get(1).text)
        assertFalse(list.hasCycle())
    }

    @Test
    fun `non-debug flow - cycle removed on ask_user then restarted`() {
        list.startCycle()
        list.updateCycleText("Analyzing...")

        // ask_user: remove cycle, add question
        list.removeCycle()
        list.addMessage(ChatMessage(MessageType.AGENT_QUESTION, "Which item?"))
        assertFalse(list.hasCycle())

        // user answers, agent resumes
        list.addMessage(ChatMessage(MessageType.USER_ANSWER, "The first one"))

        // new cycle starts
        list.startCycle()
        list.updateCycleText("Selecting first item...")
        assertTrue(list.hasCycle())
        assertEquals(3, list.size)

        list.convertCycleToResult("Done")
        assertFalse(list.hasCycle())
        assertEquals(MessageType.AGENT_RESULT, list.get(2).type)
    }
}
