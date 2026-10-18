package ai.opencyvis.voice

interface SpeechInputEngine {
    interface Listener {
        fun onReady()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(message: String)
        fun onEnd()
    }

    fun startListening(listener: Listener)
    fun stopListening()
    fun destroy()
}
