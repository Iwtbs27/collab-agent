package ai.opencyvis.backend

import android.util.Log
import android.view.InputEvent
import android.view.Surface

class SystemBackend : PrivilegeBackend {
    override val capabilities = BackendCapabilities.SYSTEM

    companion object {
        private const val TAG = "SystemBackend"
    }

    override fun injectInputEvent(event: InputEvent, displayId: Int, mode: Int): Boolean {
        return InputOps.injectInputEvent(event, displayId, mode)
    }

    override fun injectKeyEvent(action: Int, keyCode: Int, repeat: Int, metaState: Int, displayId: Int, mode: Int): Boolean {
        return InputOps.injectKeyEvent(action, keyCode, repeat, metaState, displayId, mode)
    }

    override fun captureScreen(displayId: Int, maxWidth: Int, quality: Int): ByteArray? {
        return CaptureOps.captureScreen(displayId, maxWidth, quality)
    }

    // VD creation — SystemBackend's VDM creates the VirtualDisplay locally via
    // DisplayManager.createVirtualDisplay(), so these backend methods are no-ops.
    // They exist for RemoteBackend where VD lives in a different process.
    override fun createVirtualDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int): Int = -1
    override fun releaseVirtualDisplay() {}
    override fun setVirtualDisplaySurface(surface: Surface?) {}

    // Task management — delegate to DisplayOps reflection
    override fun setDisplayImePolicy(displayId: Int, policy: Int) {
        DisplayOps.setDisplayImePolicy(displayId, policy)
    }

    override fun startActivityOnDisplay(intentUri: String, displayId: Int): Boolean {
        // System app has full permissions — direct startActivity with launchDisplayId works.
        // This codepath is only used for system flavor; standard flavor uses RemoteBackend.
        Log.i(TAG, "SystemBackend.startActivityOnDisplay not needed — system app can use Context.startActivity directly")
        return false  // Caller will fall through to normal Context.startActivity path
    }

    override fun ensureVdHasContent(displayId: Int) {
        // Launch home on the specified display to ensure a frame renders
        try {
            val cmd = arrayOf(
                "am", "start", "--display", displayId.toString(),
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.HOME"
            )
            Runtime.getRuntime().exec(cmd).waitFor()
            Log.i(TAG, "ensureVdHasContent: launched home on display $displayId")
        } catch (e: Exception) {
            Log.w(TAG, "ensureVdHasContent failed: ${e.message}")
        }
    }

    override fun getTopTaskIdOnDisplay(displayId: Int, callerPackage: String): Int {
        return DisplayOps.getTopTaskIdOnDisplay(displayId, callerPackage)
    }

    override fun moveTaskToDisplay(taskId: Int, targetDisplayId: Int): Boolean {
        return DisplayOps.moveTaskToDisplay(taskId, targetDisplayId)
    }

    override fun destroy() {}
}
