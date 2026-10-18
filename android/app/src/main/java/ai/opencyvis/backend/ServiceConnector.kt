package ai.opencyvis.backend

import android.os.IBinder
import kotlinx.coroutines.flow.StateFlow

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class NeedsPairing(val discoveredPort: Int?) : ConnectionState()
    data class Connected(val serviceBinder: IBinder) : ConnectionState()
    data class Failed(val error: String) : ConnectionState()
}

interface ServiceConnector {
    val name: String
    val state: StateFlow<ConnectionState>
    fun isAvailable(): Boolean
    fun connect()
    fun disconnect()
}
