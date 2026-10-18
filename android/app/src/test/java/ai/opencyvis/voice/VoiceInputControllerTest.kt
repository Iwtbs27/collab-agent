package ai.opencyvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputControllerTest {

    @Test
    fun `final result fills target text and stops listening`() {
        val engine = FakeSpeechInputEngine()
        val target = FakeTextTarget()
        val controller = VoiceInputController(engine, target)

        controller.start()
        engine.listener!!.onFinalResult(" 打开设置 ")

        assertEquals("打开设置", target.currentText)
        assertFalse(controller.isListening)
    }

    @Test
    fun `partial result updates draft without stopping`() {
        val engine = FakeSpeechInputEngine()
        val target = FakeTextTarget()
        val controller = VoiceInputController(engine, target)

        controller.start()
        engine.listener!!.onPartialResult("打开")

        assertEquals("打开", target.currentText)
        assertTrue(controller.isListening)
        assertEquals(1, engine.startCount)
    }

    @Test
    fun `error restores original typed text`() {
        val engine = FakeSpeechInputEngine()
        val target = FakeTextTarget("already typed")
        var error = ""
        val controller = VoiceInputController(
            engine,
            target,
            object : VoiceInputController.Listener {
                override fun onListeningChanged(isListening: Boolean) = Unit
                override fun onError(message: String) {
                    error = message
                }
            }
        )

        controller.start()
        engine.listener!!.onPartialResult("wrong draft")
        engine.listener!!.onError("No speech recognized")

        assertEquals("already typed", target.currentText)
        assertEquals("No speech recognized", error)
        assertFalse(controller.isListening)
    }

    @Test
    fun `repeated starts while listening do not start duplicate sessions`() {
        val engine = FakeSpeechInputEngine()
        val controller = VoiceInputController(engine, FakeTextTarget())

        controller.start()
        controller.start()

        assertEquals(1, engine.startCount)
        assertTrue(controller.isListening)
    }

    @Test
    fun `listening state callback toggles around recognition session`() {
        val engine = FakeSpeechInputEngine()
        val states = mutableListOf<Boolean>()
        val controller = VoiceInputController(
            engine,
            FakeTextTarget(),
            object : VoiceInputController.Listener {
                override fun onListeningChanged(isListening: Boolean) {
                    states.add(isListening)
                }

                override fun onError(message: String) = Unit
            }
        )

        controller.start()
        engine.listener!!.onFinalResult("打开设置")

        assertEquals(listOf(true, false), states)
    }

    @Test
    fun `destroy releases engine once and ignores late callbacks`() {
        val engine = FakeSpeechInputEngine()
        val target = FakeTextTarget()
        val controller = VoiceInputController(engine, target)

        controller.start()
        controller.destroy()
        controller.destroy()
        engine.listener!!.onFinalResult("late text")

        assertEquals("", target.currentText)
        assertEquals(1, engine.destroyCount)
        assertFalse(controller.isListening)
    }

    @Test
    fun `injected final result uses the same final result path`() {
        val target = FakeTextTarget()
        val controller = VoiceInputController(FakeSpeechInputEngine(), target)

        controller.injectFinalResult("66666")

        assertEquals("66666", target.currentText)
        assertFalse(controller.isListening)
    }

    private class FakeSpeechInputEngine : SpeechInputEngine {
        var listener: SpeechInputEngine.Listener? = null
        var startCount = 0
        var destroyCount = 0

        override fun startListening(listener: SpeechInputEngine.Listener) {
            this.listener = listener
            startCount += 1
        }

        override fun stopListening() = Unit

        override fun destroy() {
            destroyCount += 1
        }
    }

    private class FakeTextTarget(initial: String = "") : VoiceInputController.TextTarget {
        var currentText = initial
            private set

        override fun getText(): String = currentText

        override fun setText(text: String) {
            currentText = text
        }
    }
}
