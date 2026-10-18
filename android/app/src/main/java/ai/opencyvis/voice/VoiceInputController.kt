package ai.opencyvis.voice

class VoiceInputController(
    private val engine: SpeechInputEngine,
    private val textTarget: TextTarget,
    private val listener: Listener = Listener.NO_OP
) {
    interface TextTarget {
        fun getText(): String
        fun setText(text: String)
    }

    interface Listener {
        fun onListeningChanged(isListening: Boolean)
        fun onError(message: String)

        companion object {
            val NO_OP = object : Listener {
                override fun onListeningChanged(isListening: Boolean) = Unit
                override fun onError(message: String) = Unit
            }
        }
    }

    var isListening: Boolean = false
        private set

    private var originalText: String = ""
    private var destroyed = false

    fun start() {
        if (destroyed || isListening) return
        originalText = textTarget.getText()
        isListening = true
        listener.onListeningChanged(true)
        engine.startListening(engineListener)
    }

    fun stop() {
        if (!isListening) return
        engine.stopListening()
        finishListening()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        isListening = false
        engine.destroy()
    }

    fun injectFinalResult(text: String) {
        if (destroyed) return
        val cleaned = text.trim()
        if (cleaned.isNotEmpty()) {
            textTarget.setText(cleaned)
        }
        finishListening()
    }

    private val engineListener = object : SpeechInputEngine.Listener {
        override fun onReady() = Unit

        override fun onPartialResult(text: String) {
            if (destroyed || !isListening) return
            val cleaned = text.trim()
            if (cleaned.isNotEmpty()) {
                textTarget.setText(cleaned)
            }
        }

        override fun onFinalResult(text: String) {
            injectFinalResult(text)
        }

        override fun onError(message: String) {
            if (destroyed || !isListening) return
            textTarget.setText(originalText)
            listener.onError(message)
            finishListening()
        }

        override fun onEnd() {
            if (destroyed || !isListening) return
            finishListening()
        }
    }

    private fun finishListening() {
        if (!isListening) return
        isListening = false
        listener.onListeningChanged(false)
    }
}
