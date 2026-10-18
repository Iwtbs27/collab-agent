package ai.opencyvis.action

import org.junit.Assert.*
import org.junit.Test

class ActionTest {

    @Test
    fun `Tap action has correct type name and properties`() {
        val action = Action.Tap(x = 100, y = 200, thought = "tap the button")
        assertEquals("tap", action.typeName)
        assertEquals(100, action.x)
        assertEquals(200, action.y)
        assertEquals("tap the button", action.thought)
    }

    @Test
    fun `LongPress action has correct type name and properties`() {
        val action = Action.LongPress(x = 300, y = 400, thought = "long press")
        assertEquals("long_press", action.typeName)
        assertEquals(300, action.x)
        assertEquals(400, action.y)
        assertEquals("long press", action.thought)
    }

    @Test
    fun `OpenApp action has correct type name and properties`() {
        val action = Action.OpenApp(appName = "settings", thought = "opening settings")
        assertEquals("open_app", action.typeName)
        assertEquals("settings", action.appName)
        assertEquals("opening settings", action.thought)
    }

    @Test
    fun `Swipe action has correct type name and properties`() {
        val action = Action.Swipe(direction = "up", thought = "scroll up")
        assertEquals("swipe", action.typeName)
        assertEquals("up", action.direction)
    }

    @Test
    fun `KeyEvent action has correct type name and properties`() {
        val action = Action.KeyEvent(key = "back", thought = "go back")
        assertEquals("key_event", action.typeName)
        assertEquals("back", action.key)
    }

    @Test
    fun `TypeText action has correct type name and properties`() {
        val action = Action.TypeText(text = "hello world", thought = "typing")
        assertEquals("type_text", action.typeName)
        assertEquals("hello world", action.text)
    }

    @Test
    fun `Wait action has correct type name`() {
        val action = Action.Wait(thought = "waiting for load")
        assertEquals("wait", action.typeName)
        assertEquals("waiting for load", action.thought)
    }

    @Test
    fun `Finish action has correct type name`() {
        val action = Action.Finish(thought = "task done")
        assertEquals("finish", action.typeName)
    }

    @Test
    fun `Fail action has correct type name and reason`() {
        val action = Action.Fail(reason = "cannot proceed", thought = "stuck")
        assertEquals("fail", action.typeName)
        assertEquals("cannot proceed", action.reason)
    }

    @Test
    fun `default thought is empty string`() {
        val action = Action.Tap(x = 0, y = 0)
        assertEquals("", action.thought)
    }

    // --- fromMap tests ---

    @Test
    fun `fromMap parses tap action`() {
        val map = mapOf<String, Any?>(
            "action_type" to "tap",
            "x" to 500,
            "y" to 300,
            "thought" to "I see the home screen"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.Tap)
        val tap = action as Action.Tap
        assertEquals(500, tap.x)
        assertEquals(300, tap.y)
        assertEquals("I see the home screen", tap.thought)
    }

    @Test
    fun `fromMap parses long_press action`() {
        val map = mapOf<String, Any?>(
            "action_type" to "long_press",
            "x" to 100,
            "y" to 200,
            "thought" to "hold"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.LongPress)
        val lp = action as Action.LongPress
        assertEquals(100, lp.x)
        assertEquals(200, lp.y)
    }

    @Test
    fun `fromMap parses open_app action`() {
        val map = mapOf<String, Any?>(
            "action_type" to "open_app",
            "app_name" to "camera",
            "thought" to "open camera"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.OpenApp)
        assertEquals("camera", (action as Action.OpenApp).appName)
    }

    @Test
    fun `fromMap parses swipe action`() {
        val map = mapOf<String, Any?>(
            "action_type" to "swipe",
            "direction" to "down",
            "thought" to "scroll"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.Swipe)
        assertEquals("down", (action as Action.Swipe).direction)
    }

    @Test
    fun `fromMap parses key_event action`() {
        val map = mapOf<String, Any?>(
            "action_type" to "key_event",
            "key" to "home",
            "thought" to "go home"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.KeyEvent)
        assertEquals("home", (action as Action.KeyEvent).key)
    }

    @Test
    fun `fromMap parses type_text action`() {
        val map = mapOf<String, Any?>(
            "action_type" to "type_text",
            "text" to "test input",
            "thought" to "typing"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.TypeText)
        assertEquals("test input", (action as Action.TypeText).text)
    }

    @Test
    fun `fromMap parses wait action`() {
        val map = mapOf<String, Any?>(
            "action_type" to "wait",
            "thought" to "loading"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.Wait)
    }

    @Test
    fun `fromMap parses finish action`() {
        val map = mapOf<String, Any?>(
            "action_type" to "finish",
            "thought" to "done"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.Finish)
    }

    @Test
    fun `fromMap parses fail action`() {
        val map = mapOf<String, Any?>(
            "action_type" to "fail",
            "reason" to "stuck",
            "thought" to "cannot do it"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.Fail)
        assertEquals("stuck", (action as Action.Fail).reason)
    }

    @Test
    fun `fromMap returns Fail for unknown action type`() {
        val map = mapOf<String, Any?>(
            "action_type" to "fly_away",
            "thought" to "???"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.Fail)
        assertTrue((action as Action.Fail).reason.contains("Unknown action type"))
    }

    @Test
    fun `fromMap defaults to fail when action_type is missing`() {
        val map = mapOf<String, Any?>(
            "thought" to "no action type"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.Fail)
    }

    @Test
    fun `fromMap throws IllegalArgumentException when tap x is missing`() {
        val map = mapOf<String, Any?>(
            "action_type" to "tap",
            "y" to 300,
            "thought" to "missing x"
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Action.fromMap(map)
        }
        assertTrue(ex.message!!.contains("'x'"))
    }

    @Test
    fun `fromMap throws IllegalArgumentException when tap y is missing`() {
        val map = mapOf<String, Any?>(
            "action_type" to "tap",
            "x" to 300,
            "thought" to "missing y"
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Action.fromMap(map)
        }
        assertTrue(ex.message!!.contains("'y'"))
    }

    @Test
    fun `fromMap throws IllegalArgumentException when tap x and y are both missing`() {
        val map = mapOf<String, Any?>(
            "action_type" to "tap",
            "thought" to "no coords"
        )
        assertThrows(IllegalArgumentException::class.java) {
            Action.fromMap(map)
        }
    }

    @Test
    fun `fromMap throws IllegalArgumentException when tap x is null`() {
        val map = mapOf<String, Any?>(
            "action_type" to "tap",
            "x" to null,
            "y" to 300,
            "thought" to "null x"
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Action.fromMap(map)
        }
        assertTrue(ex.message!!.contains("'x'"))
    }

    @Test
    fun `fromMap throws IllegalArgumentException when tap y is null`() {
        val map = mapOf<String, Any?>(
            "action_type" to "tap",
            "x" to 300,
            "y" to null,
            "thought" to "null y"
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Action.fromMap(map)
        }
        assertTrue(ex.message!!.contains("'y'"))
    }

    @Test
    fun `fromMap throws IllegalArgumentException when long_press x is missing`() {
        val map = mapOf<String, Any?>(
            "action_type" to "long_press",
            "y" to 200,
            "thought" to "missing x"
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Action.fromMap(map)
        }
        assertTrue(ex.message!!.contains("'x'"))
    }

    @Test
    fun `fromMap throws IllegalArgumentException when long_press y is missing`() {
        val map = mapOf<String, Any?>(
            "action_type" to "long_press",
            "x" to 100,
            "thought" to "missing y"
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Action.fromMap(map)
        }
        assertTrue(ex.message!!.contains("'y'"))
    }

    @Test
    fun `fromMap throws IllegalArgumentException when long_press x is null`() {
        val map = mapOf<String, Any?>(
            "action_type" to "long_press",
            "x" to null,
            "y" to 200,
            "thought" to "null x"
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Action.fromMap(map)
        }
        assertTrue(ex.message!!.contains("'x'"))
    }

    @Test
    fun `fromMap throws IllegalArgumentException when long_press y is null`() {
        val map = mapOf<String, Any?>(
            "action_type" to "long_press",
            "x" to 100,
            "y" to null,
            "thought" to "null y"
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Action.fromMap(map)
        }
        assertTrue(ex.message!!.contains("'y'"))
    }

    @Test
    fun `fromMap handles Number types for coordinates`() {
        val map = mapOf<String, Any?>(
            "action_type" to "tap",
            "x" to 100.0,  // Double instead of Int
            "y" to 200L,   // Long instead of Int
            "thought" to "numeric"
        )
        val action = Action.fromMap(map) as Action.Tap
        assertEquals(100, action.x)
        assertEquals(200, action.y)
    }

    @Test
    fun `fromMap defaults thought to empty string`() {
        val map = mapOf<String, Any?>(
            "action_type" to "wait"
        )
        val action = Action.fromMap(map)
        assertEquals("", action.thought)
    }

    // --- AskUser tests ---

    @Test
    fun `AskUser action has correct type name and properties`() {
        val action = Action.AskUser(question = "Which contact do you want to call?", thought = "need clarification")
        assertEquals("ask_user", action.typeName)
        assertEquals("Which contact do you want to call?", action.question)
        assertEquals("need clarification", action.thought)
    }

    @Test
    fun `AskUser default thought is empty string`() {
        val action = Action.AskUser(question = "Confirm?")
        assertEquals("", action.thought)
    }

    @Test
    fun `fromMap parses ask_user action with question field`() {
        val map = mapOf<String, Any?>(
            "action_type" to "ask_user",
            "question" to "Which app do you want to open?",
            "thought" to "multiple apps match"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.AskUser)
        val askUser = action as Action.AskUser
        assertEquals("Which app do you want to open?", askUser.question)
        assertEquals("multiple apps match", askUser.thought)
    }

    @Test
    fun `fromMap parses ask_user falls back to thought when question missing`() {
        val map = mapOf<String, Any?>(
            "action_type" to "ask_user",
            "thought" to "I need more info"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.AskUser)
        // question falls back to thought value
        assertEquals("I need more info", (action as Action.AskUser).question)
    }

    @Test
    fun `fromMap ask_user question takes priority over thought`() {
        val map = mapOf<String, Any?>(
            "action_type" to "ask_user",
            "question" to "Explicit question?",
            "thought" to "fallback thought"
        )
        val action = Action.fromMap(map) as Action.AskUser
        assertEquals("Explicit question?", action.question)
    }

    @Test
    fun `HandoffUser action has correct type name and properties`() {
        val action = Action.HandoffUser(
            reason = "需要用户输入支付密码",
            thought = "sensitive input required"
        )
        assertEquals("handoff_user", action.typeName)
        assertEquals("需要用户输入支付密码", action.reason)
        assertEquals("sensitive input required", action.thought)
    }

    @Test
    fun `fromMap parses handoff_user action`() {
        val map = mapOf<String, Any?>(
            "action_type" to "handoff_user",
            "handoff_reason" to "请输入锁屏密码",
            "thought" to "password page"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.HandoffUser)
        val handoff = action as Action.HandoffUser
        assertEquals("请输入锁屏密码", handoff.reason)
        assertEquals("password page", handoff.thought)
    }

    @Test
    fun `fromMap parses handoff_user falls back to thought`() {
        val map = mapOf<String, Any?>(
            "action_type" to "handoff_user",
            "thought" to "Need user handoff"
        )
        val action = Action.fromMap(map) as Action.HandoffUser
        assertEquals("Need user handoff", action.reason)
    }

    // --- trim whitespace in action_type ---

    @Test
    fun `fromMap trims leading and trailing spaces in action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to " tap ",
            "x" to 100,
            "y" to 200,
            "thought" to "tap with spaces"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected Tap but got ${action::class.simpleName}", action is Action.Tap)
        val tap = action as Action.Tap
        assertEquals(100, tap.x)
        assertEquals(200, tap.y)
    }

    @Test
    fun `fromMap trims leading spaces in action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to "  finish",
            "thought" to "done with leading spaces"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected Finish but got ${action::class.simpleName}", action is Action.Finish)
    }

    @Test
    fun `fromMap trims trailing spaces in action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to "swipe  ",
            "direction" to "up",
            "thought" to "swipe with trailing spaces"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected Swipe but got ${action::class.simpleName}", action is Action.Swipe)
        assertEquals("up", (action as Action.Swipe).direction)
    }

    @Test
    fun `fromMap trims spaces in open_app action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to " open_app ",
            "app_name" to "settings",
            "thought" to "open settings"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected OpenApp but got ${action::class.simpleName}", action is Action.OpenApp)
        assertEquals("settings", (action as Action.OpenApp).appName)
    }

    @Test
    fun `fromMap trims spaces in key_event action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to " key_event ",
            "key" to "home",
            "thought" to "go home"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected KeyEvent but got ${action::class.simpleName}", action is Action.KeyEvent)
        assertEquals("home", (action as Action.KeyEvent).key)
    }

    @Test
    fun `fromMap trims spaces in type_text action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to "\ttype_text\t",
            "text" to "hello",
            "thought" to "typing"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected TypeText but got ${action::class.simpleName}", action is Action.TypeText)
        assertEquals("hello", (action as Action.TypeText).text)
    }

    @Test
    fun `fromMap trims spaces in wait action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to " wait ",
            "thought" to "loading"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected Wait but got ${action::class.simpleName}", action is Action.Wait)
    }

    @Test
    fun `fromMap trims spaces in fail action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to " fail ",
            "reason" to "something went wrong",
            "thought" to "error"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected Fail but got ${action::class.simpleName}", action is Action.Fail)
        assertEquals("something went wrong", (action as Action.Fail).reason)
    }

    @Test
    fun `fromMap trims spaces in ask_user action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to " ask_user ",
            "question" to "Are you sure?",
            "thought" to "need confirmation"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected AskUser but got ${action::class.simpleName}", action is Action.AskUser)
        assertEquals("Are you sure?", (action as Action.AskUser).question)
    }

    @Test
    fun `fromMap trims spaces in remember action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to " remember ",
            "memory_key" to "city",
            "memory_value" to "Beijing",
            "memory_category" to "preference",
            "thought" to "saving memory"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected Remember but got ${action::class.simpleName}", action is Action.Remember)
        val remember = action as Action.Remember
        assertEquals("city", remember.key)
        assertEquals("Beijing", remember.value)
        assertEquals("preference", remember.category)
    }
}
