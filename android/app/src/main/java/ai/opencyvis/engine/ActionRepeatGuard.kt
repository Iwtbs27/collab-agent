package ai.opencyvis.engine

import ai.opencyvis.action.Action
import kotlin.math.abs

/**
 * Blocks repeated high-risk actions when the screen appears unchanged.
 *
 * The guard is intentionally generic. It does not know about app-specific labels
 * like "京东" or "安装"; it only protects non-idempotent action patterns such as
 * repeated text entry, repeated submit, and repeated same-location taps.
 */
class ActionRepeatGuard(
    private val tapTolerance: Int = 35
) {

    sealed class Decision {
        data object Allow : Decision()
        data class Block(val feedback: String) : Decision()
    }

    private var lastExecutedAction: Action? = null
    private var lastScreenBeforeAction: ScreenFingerprint? = null
    private var consecutiveBlocks: Int = 0

    fun evaluate(candidate: Action, currentScreen: ScreenFingerprint?): Decision {
        if (candidate is Action.Wait) return Decision.Allow
        val previous = lastExecutedAction ?: return Decision.Allow
        val blockReason = when {
            isRepeatedTypeText(previous, candidate) -> {
                LlmPrompts.guardFeedback("repeated_type_text")
            }
            isRepeatedSubmit(previous, candidate) && screenLooksUnchanged(currentScreen) -> {
                LlmPrompts.guardFeedback("repeated_submit")
            }
            isRepeatedTapLike(previous, candidate) && screenLooksUnchanged(currentScreen) -> {
                LlmPrompts.guardFeedback("repeated_tap")
            }
            else -> null
        }

        return if (blockReason == null) {
            Decision.Allow
        } else {
            consecutiveBlocks += 1
            Decision.Block(buildFeedback(blockReason))
        }
    }

    fun recordExecuted(action: Action, screenBeforeAction: ScreenFingerprint?) {
        if (action is Action.Wait) {
            return
        }
        lastExecutedAction = action
        lastScreenBeforeAction = screenBeforeAction
        consecutiveBlocks = 0
    }

    private fun screenLooksUnchanged(currentScreen: ScreenFingerprint?): Boolean {
        val previousScreen = lastScreenBeforeAction ?: return false
        return currentScreen?.isSimilarTo(previousScreen) == true
    }

    private fun isRepeatedTypeText(previous: Action, candidate: Action): Boolean {
        return previous is Action.TypeText &&
                candidate is Action.TypeText &&
                previous.text == candidate.text
    }

    private fun isRepeatedSubmit(previous: Action, candidate: Action): Boolean {
        return previous is Action.KeyEvent &&
                candidate is Action.KeyEvent &&
                previous.key.equals("enter", ignoreCase = true) &&
                candidate.key.equals("enter", ignoreCase = true)
    }

    private fun isRepeatedTapLike(previous: Action, candidate: Action): Boolean {
        val previousPoint = tapPoint(previous) ?: return false
        val candidatePoint = tapPoint(candidate) ?: return false
        return abs(previousPoint.first - candidatePoint.first) <= tapTolerance &&
                abs(previousPoint.second - candidatePoint.second) <= tapTolerance
    }

    private fun tapPoint(action: Action): Pair<Int, Int>? {
        return when (action) {
            is Action.Tap -> action.x to action.y
            is Action.LongPress -> action.x to action.y
            else -> null
        }
    }

    private fun buildFeedback(reason: String): String {
        val escalation = if (consecutiveBlocks >= 2) {
            LlmPrompts.guardFeedback("escalation_high")
        } else {
            LlmPrompts.guardFeedback("escalation_low")
        }
        return "$reason$escalation"
    }
}
