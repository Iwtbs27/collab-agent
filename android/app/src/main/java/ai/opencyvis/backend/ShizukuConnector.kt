package ai.opencyvis.backend

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

enum class ShizukuStatus {
    UNAVAILABLE,
    PERMISSION_REQUIRED,
    READY
}

class ShizukuConnector(private val context: Context) : ServiceConnector {

    companion object {
        private const val TAG = "ShizukuConnector"

        fun status(): ShizukuStatus {
            return try {
                if (!Shizuku.pingBinder()) {
                    ShizukuStatus.UNAVAILABLE
                } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    ShizukuStatus.READY
                } else {
                    ShizukuStatus.PERMISSION_REQUIRED
                }
            } catch (_: Exception) {
                ShizukuStatus.UNAVAILABLE
            }
        }
    }

    override val name = "shizuku"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private var reconnectAttempt = 0
    private val reconnectDelays = longArrayOf(2000, 4000, 8000, 15000, 30000)
    private var userServiceArgs: Shizuku.UserServiceArgs? = null
    private var connectedBinder: IBinder? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                _state.value = ConnectionState.Failed("null binder")
                return
            }
            if (connectedBinder === binder && _state.value is ConnectionState.Connected) {
                Log.d(TAG, "Ignoring duplicate onServiceConnected callback")
                return
            }
            connectedBinder = binder
            binder.linkToDeath({
                Log.w(TAG, "Remote process died")
                connectedBinder = null
                _state.value = ConnectionState.Disconnected
                scheduleReconnect()
            }, 0)
            _state.value = ConnectionState.Connected(binder)
            reconnectAttempt = 0
            Log.i(TAG, "Connected via Shizuku")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Disconnected — scheduling reconnect")
            connectedBinder = null
            _state.value = ConnectionState.Disconnected
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        val delay = reconnectDelays[reconnectAttempt.coerceAtMost(reconnectDelays.lastIndex)]
        reconnectAttempt++
        Handler(Looper.getMainLooper()).postDelayed({
            if (_state.value is ConnectionState.Disconnected) {
                Log.i(TAG, "Reconnect attempt $reconnectAttempt (delay=${delay}ms)")
                connect()
            }
        }, delay)
    }

    override fun isAvailable(): Boolean {
        return status() == ShizukuStatus.READY
    }

    override fun connect() {
        if (!isAvailable()) {
            _state.value = ConnectionState.Failed("Shizuku is unavailable or permission is not granted")
            return
        }
        _state.value = ConnectionState.Connecting
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, PrivilegedService::class.java)
            ).processNameSuffix("privilege")
            userServiceArgs = args
            Shizuku.bindUserService(args, serviceConnection)
        } catch (e: Exception) {
            Log.e(TAG, "bind failed: ${e.message}")
            _state.value = ConnectionState.Failed(e.message ?: "unknown")
        }
    }

    override fun disconnect() {
        try {
            val args = userServiceArgs
            if (args != null) {
                Shizuku.unbindUserService(args, serviceConnection, false)
            }
        } catch (_: Exception) {}
        connectedBinder = null
        _state.value = ConnectionState.Disconnected
    }
}
