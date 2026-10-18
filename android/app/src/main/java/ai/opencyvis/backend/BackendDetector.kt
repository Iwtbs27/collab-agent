package ai.opencyvis.backend

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

sealed class DetectionResult {
    data class Ready(val backend: PrivilegeBackend) : DetectionResult()
    data class SetupRequired(val availableConnectors: List<ServiceConnector>) : DetectionResult()
    object NoneAvailable : DetectionResult()
}

object BackendDetector {
    private const val TAG = "BackendDetector"

    fun isSystemUid(uid: Int = Process.myUid()): Boolean = uid == 1000

    suspend fun detect(context: Context): DetectionResult {
        if (isSystemUid()) {
            Log.i(TAG, "Running as system app (uid=${Process.myUid()})")
            return DetectionResult.Ready(SystemBackend())
        }

        // Shizuku is the preferred standard-app backend. A running Shizuku that has
        // not been authorized yet must lead to the permission UI, not wireless ADB.
        val connectors = buildConnectorList(context)
        if (ShizukuConnector.status() == ShizukuStatus.PERMISSION_REQUIRED) {
            Log.i(TAG, "Shizuku is running and needs app permission")
            return DetectionResult.SetupRequired(connectors)
        }

        for (connector in connectors) {
            if (!connector.isAvailable()) continue
            Log.i(TAG, "Trying ${connector.name}...")
            connector.connect()

            val result = withTimeoutOrNull(10_000L) {
                connector.state.first {
                    it is ConnectionState.Connected || it is ConnectionState.Failed || it is ConnectionState.NeedsPairing
                }
            }

            when (result) {
                is ConnectionState.Connected -> {
                    val svc = IPrivilegedService.Stub.asInterface(result.serviceBinder)
                    Log.i(TAG, "Connected via ${connector.name} (uid=${svc.serviceUid})")
                    return DetectionResult.Ready(RemoteBackend(connector, svc))
                }
                is ConnectionState.NeedsPairing -> {
                    Log.i(TAG, "${connector.name} needs pairing")
                    // Return setup required — UI will handle pairing flow
                    return DetectionResult.SetupRequired(connectors.filter { it.isAvailable() })
                }
                else -> {
                    Log.w(TAG, "${connector.name} failed or timed out")
                    connector.disconnect()
                }
            }
        }

        Log.w(TAG, "No privileged backend available")
        return DetectionResult.NoneAvailable
    }

    private fun buildConnectorList(context: Context): List<ServiceConnector> {
        return listOf(
            ShizukuConnector(context),
            DirectConnector(context),
        )
    }
}
