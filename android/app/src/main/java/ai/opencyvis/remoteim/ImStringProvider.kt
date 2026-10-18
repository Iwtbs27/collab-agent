package ai.opencyvis.remoteim

interface ImStringProvider {
    fun pairSuccess(): String
    fun pairExpired(): String
    fun pairWrongCode(): String
    fun pairLockedOut(): String
    fun pairHint(): String
    fun unpairSuccess(): String
    fun alreadyPaired(): String
    fun stopped(): String
    fun busy(): String
    fun groupRejected(): String

    // Status texts
    fun statusNoService(): String
    fun statusIdle(resultMessage: String?): String
    fun statusRunning(step: Int, thought: String): String
    fun statusWaitingUser(question: String): String
    fun statusWaitingHandoff(reason: String): String
    fun statusError(message: String): String
    fun statusPaused(): String
    fun statusNoEngine(): String
    fun statusPhone(app: String): String
    fun statusDevice(manufacturer: String, model: String, osVersion: String): String
    fun statusLlm(provider: String, model: String): String

    // State change notifications
    fun notifyAskUser(question: String): String
    fun notifyHandoff(reason: String): String
    fun notifyTaskComplete(resultMessage: String?): String
    fun notifyError(message: String): String
}
