package ai.opencyvis.remoteim

import android.content.Context
import ai.opencyvis.R

class AndroidImStringProvider(private val context: Context) : ImStringProvider {
    override fun pairSuccess() = context.getString(R.string.im_pair_success)
    override fun pairExpired() = context.getString(R.string.im_pair_expired)
    override fun pairWrongCode() = context.getString(R.string.im_pair_wrong_code)
    override fun pairLockedOut() = context.getString(R.string.im_pair_locked_out)
    override fun pairHint() = context.getString(R.string.im_pair_hint)
    override fun unpairSuccess() = context.getString(R.string.im_unpair_success)
    override fun alreadyPaired() = context.getString(R.string.im_already_paired)
    override fun stopped() = context.getString(R.string.im_stopped)
    override fun busy() = context.getString(R.string.im_busy)
    override fun groupRejected() = context.getString(R.string.im_group_rejected)

    override fun statusNoService() = context.getString(R.string.im_status_no_service)
    override fun statusIdle(resultMessage: String?) = context.getString(R.string.im_status_idle, resultMessage ?: "")
    override fun statusRunning(step: Int, thought: String) = context.getString(R.string.im_status_running, step, thought)
    override fun statusWaitingUser(question: String) = context.getString(R.string.im_status_waiting_user, question)
    override fun statusWaitingHandoff(reason: String) = context.getString(R.string.im_status_waiting_handoff, reason)
    override fun statusError(message: String) = context.getString(R.string.im_status_error, message)
    override fun statusPaused() = context.getString(R.string.im_status_paused)
    override fun statusNoEngine() = context.getString(R.string.im_status_no_engine)

    override fun notifyAskUser(question: String) = context.getString(R.string.im_notify_ask_user, question)
    override fun notifyHandoff(reason: String) = context.getString(R.string.im_notify_handoff, reason)
    override fun notifyTaskComplete(resultMessage: String?) = context.getString(R.string.im_notify_task_complete, resultMessage ?: "")
    override fun notifyError(message: String) = context.getString(R.string.im_notify_error, message)

    override fun statusPhone(app: String) = context.getString(R.string.im_status_phone, app)
    override fun statusDevice(manufacturer: String, model: String, osVersion: String) = context.getString(R.string.im_status_device, manufacturer, model, osVersion)
    override fun statusLlm(provider: String, model: String) = context.getString(R.string.im_status_llm, provider, model)
}
