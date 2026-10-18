package ai.opencyvis.backend

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.opencyvis.App
import ai.opencyvis.capture.ScreenCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PairingBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Start pairing service if requested
        if (intent.hasExtra("start_service")) {
            Log.i(TAG, "Starting AdbPairingService via broadcast")
            try {
                context.startForegroundService(AdbPairingService.startIntent(context))
            } catch (e: Exception) {
                try { context.startService(AdbPairingService.startIntent(context)) } catch (_: Exception) {}
            }
            return
        }
        val code = intent.getStringExtra("pair_code") ?: return
        Log.i(TAG, "Received pairing code: $code")

        val connector = DirectConnector.lastInstance
            ?: DirectConnector(context).also { DirectConnector.lastInstance = it }
        connector.submitPairingCode(code)

        // Monitor for successful connection and update AgentService's backend
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = withTimeoutOrNull(30_000L) {
                connector.state.first { it is ConnectionState.Connected || it is ConnectionState.Failed }
            }
            if (result is ConnectionState.Connected) {
                val svc = IPrivilegedService.Stub.asInterface(result.serviceBinder)
                val backend = RemoteBackend(connector, svc)
                ScreenCapture.backend = backend
                App.agentService?.updateBackend(backend)
                Log.i(TAG, "Backend updated to adb-direct after pairing")
            }
        }
    }

    companion object {
        private const val TAG = "PairingReceiver"
    }
}
