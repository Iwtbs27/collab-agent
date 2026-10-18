package ai.opencyvis.backend

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.adb.AdbClient as ShizukuAdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore

/**
 * Connects to the device's own wireless debugging, then launches
 * PrivilegedService via app_process at shell uid (2000).
 * No Shizuku dependency -- self-contained ADB client.
 *
 * Uses vendored Shizuku ADB implementation (Apache-2.0) for the
 * full ADB transport: SPAKE2+ pairing, TLS connect, shell commands.
 *
 * Requires Android 11+ (wireless debugging API).
 */
class DirectConnector(private val context: Context) : ServiceConnector {

    companion object {
        private const val TAG = "DirectConnector"
        // Expose last instance for broadcast-triggered pairing (testing)
        @Volatile var lastInstance: DirectConnector? = null

        private const val PREFS_ADB_CONNECTION = "adb_connection"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_LAST_PORT = "last_port"
        private const val KEY_LAST_CONNECTED = "last_connected"
    }

    override val name = "adb-direct"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var adbKey: AdbKey? = null
    private var adbClient: ShizukuAdbClient? = null
    private var discoveredPort: Int? = null

    init {
        lastInstance = this
    }

    override fun isAvailable(): Boolean {
        // Wireless debugging requires Android 11+
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    override fun connect() {
        _state.value = ConnectionState.Connecting
        scope.launch {
            try {
                val prefs = context.getSharedPreferences("adb_key", Context.MODE_PRIVATE)
                val key = AdbKey(
                    PreferenceAdbKeyStore(prefs),
                    "${context.packageName}@${Build.MODEL}"
                )
                adbKey = key

                // Try auto-reconnect with saved connection info (skips mDNS discovery)
                val connPrefs = context.getSharedPreferences(PREFS_ADB_CONNECTION, Context.MODE_PRIVATE)
                val lastHost = connPrefs.getString(KEY_LAST_HOST, null)
                val lastPort = connPrefs.getInt(KEY_LAST_PORT, 0)
                if (lastHost != null && lastPort > 0) {
                    Log.i(TAG, "Attempting auto-reconnect to $lastHost:$lastPort")
                    try {
                        tryConnect(key, lastPort, lastHost)
                        return@launch // success
                    } catch (e: Exception) {
                        val isCertError = e is javax.net.ssl.SSLException ||
                            e is java.security.cert.CertificateException ||
                            e.message?.contains("CERTIFICATE_VERIFY_FAILED", ignoreCase = true) == true ||
                            e.message?.contains("handshake", ignoreCase = true) == true

                        if (isCertError) {
                            Log.w(TAG, "Auto-reconnect cert mismatch (likely app reinstall), clearing saved info")
                            clearConnectionInfo(context)
                            _state.value = ConnectionState.NeedsPairing(null)
                            return@launch
                        } else {
                            Log.w(TAG, "Auto-reconnect failed, will try mDNS discovery: ${e.message}")
                        }
                    }
                }

                // Try to discover the connect port via mDNS (already paired)
                val connectPort = discoverPort(AdbMdns.TLS_CONNECT)
                if (connectPort != null) {
                    try {
                        tryConnect(key, connectPort)
                        return@launch // success
                    } catch (e: Exception) {
                        val isCertError = e is javax.net.ssl.SSLException ||
                            e is java.security.cert.CertificateException ||
                            e.message?.contains("CERTIFICATE", ignoreCase = true) == true ||
                            e.message?.contains("handshake", ignoreCase = true) == true
                        if (isCertError) {
                            Log.i(TAG, "Direct connect cert error (needs re-pairing): ${e.message}")
                            // Fall through to pairing
                        } else {
                            // Non-cert error (timeout, connection refused, etc.) — retry once
                            // Wireless debugging port may still be initializing after toggle
                            Log.i(TAG, "Direct connect failed (retrying in 2s): ${e.message}")
                            delay(2000)
                            try {
                                val retryPort = discoverPort(AdbMdns.TLS_CONNECT) ?: connectPort
                                tryConnect(key, retryPort)
                                return@launch // success on retry
                            } catch (e2: Exception) {
                                Log.i(TAG, "Direct connect retry failed: ${e2.message}")
                                // Fall through to pairing
                            }
                        }
                    }
                }
                // Need pairing first -- discover pairing port
                val pairingPort = discoverPort(AdbMdns.TLS_PAIRING)
                discoveredPort = pairingPort
                if (pairingPort != null) {
                    // Auto-pair if code file exists (for testing)
                    val codeFile = java.io.File(context.filesDir, "pairing_code.txt")
                    val autoCode = if (codeFile.exists()) codeFile.readText().trim().also { codeFile.delete() } else null
                    if (autoCode != null && autoCode.length == 6) {
                        Log.i(TAG, "Auto-pairing with code from file (port=$pairingPort)")
                        try {
                            val pairingClient = AdbPairingClient("127.0.0.1", pairingPort, autoCode, key)
                            val success = pairingClient.use { it.start() }
                            if (success) {
                                Log.i(TAG, "Pairing succeeded! Reconnecting...")
                                val newConnectPort = discoverPort(AdbMdns.TLS_CONNECT)
                                if (newConnectPort != null) {
                                    tryConnect(key, newConnectPort)
                                    return@launch
                                }
                            } else {
                                Log.w(TAG, "Auto-pairing failed")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Auto-pairing error", e)
                        }
                    }
                    _state.value = ConnectionState.NeedsPairing(pairingPort)
                    Log.i(TAG, "Needs pairing (discovered port: $pairingPort)")
                } else {
                    _state.value = ConnectionState.Failed(
                        "No wireless debugging port found. " +
                            "Is wireless debugging enabled in Developer Options?"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "connect() failed", e)
                _state.value = ConnectionState.Failed("Connection error: ${e.message}")
            }
        }
    }

    /**
     * Called by UI after user enters the 6-digit pairing code.
     */
    fun submitPairingCode(code: String) {
        scope.launch {
            val prefs = context.getSharedPreferences("adb_key", Context.MODE_PRIVATE)
            val key = adbKey ?: AdbKey(
                PreferenceAdbKeyStore(prefs),
                "${context.packageName}@${Build.MODEL}"
            ).also { adbKey = it }
            val port = discoveredPort ?: discoverPort(AdbMdns.TLS_PAIRING) ?: run {
                Log.e(TAG, "No pairing port found")
                _state.value = ConnectionState.Failed("No pairing port found")
                return@launch
            }
            _state.value = ConnectionState.Connecting

            try {
                val pairingClient = AdbPairingClient("127.0.0.1", port, code, key)
                val success = pairingClient.use { it.start() }
                if (!success) {
                    _state.value = ConnectionState.Failed("Pairing failed -- invalid code?")
                    return@launch
                }
                Log.i(TAG, "Pairing succeeded, connecting...")

                // After pairing, discover connect port and connect
                val connectPort = discoverPort(AdbMdns.TLS_CONNECT)
                if (connectPort == null) {
                    _state.value = ConnectionState.Failed(
                        "Pairing succeeded but connect port not found"
                    )
                    return@launch
                }
                tryConnect(key, connectPort)
            } catch (e: Exception) {
                Log.e(TAG, "submitPairingCode failed", e)
                _state.value = ConnectionState.Failed("Pairing error: ${e.message}")
            }
        }
    }

    private fun tryConnect(key: AdbKey, port: Int, host: String = "127.0.0.1") {
        val client = ShizukuAdbClient(host, port, key)
        client.connect()
        adbClient = client
        Log.i(TAG, "ADB connected, launching service...")
        saveConnectionInfo(host, port)
        launchService(client)
    }

    private fun saveConnectionInfo(host: String, port: Int) {
        context.getSharedPreferences(PREFS_ADB_CONNECTION, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_HOST, host)
            .putInt(KEY_LAST_PORT, port)
            .putLong(KEY_LAST_CONNECTED, System.currentTimeMillis())
            .apply()
    }

    private fun clearConnectionInfo(context: Context) {
        context.getSharedPreferences(PREFS_ADB_CONNECTION, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun launchService(client: ShizukuAdbClient) {
        try {
            val token = BinderExchangeProvider.prepare()
            val apkPath = context.applicationInfo.sourceDir
            val authority = "${context.packageName}.binder_exchange"

            val cmd = "CLASSPATH=$apkPath app_process /system/bin " +
                "--nice-name=opencyvis:privilege " +
                "ai.opencyvis.backend.PrivilegedServiceMain " +
                "--token=$token --authority=$authority"

            // shellCommand blocks until the process exits (it enters Looper.loop()).
            // Run it in a separate thread — binder exchange happens asynchronously.
            Thread {
                try {
                    client.shellCommand(cmd) { data ->
                        Log.d(TAG, "Service output: ${String(data)}")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "shellCommand ended: ${e.message}")
                }
            }.start()

            val binder = BinderExchangeProvider.awaitBinder(10_000)
            if (binder != null) {
                binder.linkToDeath({
                    Log.w(TAG, "Privileged service binder died")
                    _state.value = ConnectionState.Disconnected
                }, 0)
                _state.value = ConnectionState.Connected(binder)
                Log.i(TAG, "Service started, binder received")
            } else {
                _state.value = ConnectionState.Failed(
                    "Service did not start (no binder received)"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "launchService failed", e)
            _state.value = ConnectionState.Failed(e.message ?: "unknown error")
        }
    }

    /**
     * Discover a wireless debugging port via mDNS.
     * @param serviceType one of [AdbMdns.TLS_CONNECT] or [AdbMdns.TLS_PAIRING]
     * @return the port number, or null if not discovered within timeout
     */
    private fun discoverPort(serviceType: String, timeoutMs: Long = 5000): Int? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Int? = null

        val mdns = AdbMdns(context, serviceType, androidx.lifecycle.Observer { port ->
            if (port > 0) {
                result = port
                latch.countDown()
            }
        })
        mdns.start()
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        mdns.stop()
        return result
    }

    override fun disconnect() {
        adbClient?.close()
        adbClient = null
        _state.value = ConnectionState.Disconnected
    }
}
