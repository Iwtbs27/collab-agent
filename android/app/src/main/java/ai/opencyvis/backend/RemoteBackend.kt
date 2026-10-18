package ai.opencyvis.backend

import android.os.DeadObjectException
import android.os.Parcel
import android.os.SharedMemory
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RemoteBackend(
    val connector: ServiceConnector,
    initialBinder: IPrivilegedService
) : PrivilegeBackend {

    companion object {
        private const val TAG = "RemoteBackend"
    }

    @Volatile private var binder: IPrivilegedService = initialBinder
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override var capabilities: BackendCapabilities = probeRemote(initialBinder, connector.name)
        private set

    private fun probeRemote(svc: IPrivilegedService, name: String): BackendCapabilities {
        return try {
            val mask = svc.probeCapabilities()
            BackendCapabilities.fromProbeMask(mask, name)
        } catch (_: Exception) { BackendCapabilities.NONE }
    }

    init {
        scope.launch {
            connector.state.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        val svc = IPrivilegedService.Stub.asInterface(state.serviceBinder)
                        binder = svc
                        capabilities = probeRemote(svc, connector.name)
                        Log.i(TAG, "Binder updated (reconnect), caps re-probed")
                    }
                    is ConnectionState.Disconnected -> {
                        Log.w(TAG, "Remote service disconnected")
                    }
                    else -> {}
                }
            }
        }
    }

    override fun injectInputEvent(event: InputEvent, displayId: Int, mode: Int): Boolean {
        return try {
            when (event) {
                is MotionEvent -> {
                    val parcel = Parcel.obtain()
                    try {
                        event.writeToParcel(parcel, 0)
                        binder.injectMotionEvent(parcel.marshall(), displayId, mode)
                    } finally { parcel.recycle() }
                }
                is KeyEvent -> binder.injectKeyEvent(
                    event.action, event.keyCode, event.repeatCount, event.metaState, displayId, mode)
                else -> false
            }
        } catch (e: DeadObjectException) { Log.w(TAG, "inject: dead"); false }
          catch (e: Exception) { false }
    }

    override fun injectKeyEvent(action: Int, keyCode: Int, repeat: Int, metaState: Int, displayId: Int, mode: Int): Boolean {
        return try { binder.injectKeyEvent(action, keyCode, repeat, metaState, displayId, mode) }
               catch (_: Exception) { false }
    }

    override fun captureScreen(displayId: Int, maxWidth: Int, quality: Int): ByteArray? {
        return try {
            val shm = binder.captureScreen(displayId, maxWidth, quality) ?: return null
            try {
                val buffer = shm.mapReadOnly()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                SharedMemory.unmap(buffer)
                bytes
            } finally {
                shm.close()
            }
        } catch (_: Exception) { null }
    }

    override fun createVirtualDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int): Int {
        return try { binder.createVirtualDisplay(name, width, height, dpi, flags) }
               catch (_: Exception) { -1 }
    }

    override fun releaseVirtualDisplay() { try { binder.releaseVirtualDisplay() } catch (_: Exception) {} }

    override fun setVirtualDisplaySurface(surface: Surface?) {
        try { binder.setVirtualDisplaySurface(surface) } catch (_: Exception) {}
    }

    override fun setDisplayImePolicy(displayId: Int, policy: Int) {
        try { binder.setDisplayImePolicy(displayId, policy) } catch (_: Exception) {}
    }

    override fun startActivityOnDisplay(intentUri: String, displayId: Int): Boolean {
        return try { binder.startActivityOnDisplay(intentUri, displayId) }
               catch (_: Exception) { false }
    }

    override fun ensureVdHasContent(displayId: Int) {
        try { binder.ensureVdHasContent(displayId) }
        catch (_: Exception) {}
    }

    override fun getTopTaskIdOnDisplay(displayId: Int, callerPackage: String): Int {
        return try { binder.getTopTaskIdOnDisplay(displayId, callerPackage) }
               catch (_: Exception) { -1 }
    }

    override fun moveTaskToDisplay(taskId: Int, targetDisplayId: Int): Boolean {
        return try { binder.moveTaskToDisplay(taskId, targetDisplayId) }
               catch (_: Exception) { false }
    }

    override fun destroy() {
        scope.cancel()
        connector.disconnect()
    }
}
