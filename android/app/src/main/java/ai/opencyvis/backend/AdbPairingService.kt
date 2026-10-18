package ai.opencyvis.backend

import ai.opencyvis.R
import android.annotation.TargetApi
import android.app.ForegroundServiceStartNotAllowedException
import android.os.Bundle
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.Observer
import ai.opencyvis.capture.ScreenCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore

@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {

        const val NOTIFICATION_CHANNEL = "adb_pairing_v3"

        private const val TAG = "AdbPairingService"

        private const val NOTIFICATION_ID = 1
        private const val REPLY_REQUEST_ID = 1
        private const val STOP_REQUEST_ID = 2
        private const val SETTINGS_REQUEST_ID = 3
        private const val WIRELESS_DEBUG_REQUEST_ID = 4
        private const val STEP_DONE_REQUEST_ID = 5
        private const val FULL_SCREEN_REQUEST_ID = 6

        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_REPLY = "reply"
        private const val ACTION_ALERT = "alert"
        const val ACTION_STEP_DONE = "step_done"
        const val ACTION_OPEN_SETTINGS = "open_settings"

        private const val REMOTE_INPUT_KEY = "pairing_code"
        private const val EXTRA_PORT = "pairing_port"

        private const val GUIDE_ADVANCE_DELAY_MS = 10_000L
        private const val MDNS_TIMEOUT_MS = 30_000L

        /**
         * Time budget for the one-shot mDNS discovery performed at the moment
         * the user submits a pairing code (see [onInputWithDiscovery]). On OEMs
         * that freeze our background process (e.g. ColorOS HansManager) the
         * RemoteInput submit is the point at which we're un-frozen, so we must
         * find the live pairing service quickly within this window.
         */
        private const val PAIR_DISCOVERY_TIMEOUT_MS = 8_000L

        private const val PREFS_SETUP_PROGRESS = "setup_progress"
        private const val KEY_LAST_STEP = "last_step"

        fun startIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(ACTION_START)
        }

        fun alertIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(ACTION_ALERT)
        }

        private fun stopIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(ACTION_STOP)
        }

        private fun replyIntent(context: Context, port: Int): Intent {
            return Intent(context, AdbPairingService::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(EXTRA_PORT, port)
        }

        private fun stepDoneIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(ACTION_STEP_DONE)
        }

        fun getSavedProgress(context: Context): String? {
            return context.getSharedPreferences(PREFS_SETUP_PROGRESS, MODE_PRIVATE)
                .getString(KEY_LAST_STEP, null)
        }

        fun clearProgress(context: Context) {
            context.getSharedPreferences(PREFS_SETUP_PROGRESS, MODE_PRIVATE)
                .edit().remove(KEY_LAST_STEP).apply()
        }

        /** Hot flow of the last discovered pairing port. 0 = not yet discovered. */
        val pairingPort = MutableStateFlow(0)
    }

    // ── Guide step state ──────────────────────────────────────────────

    private enum class GuideStep {
        ENABLE_DEV_OPTIONS,
        ENABLE_WIRELESS_DEBUG,
        WAITING_PAIRING_SERVICE,
        CODE_INPUT,
        PAIRING,
        SUCCESS,
        ERROR
    }

    private var currentStep: GuideStep = GuideStep.WAITING_PAIRING_SERVICE
        set(value) {
            field = value
            // Guard against calls before Context is attached (e.g. field initializer)
            try { saveProgress(value) } catch (_: Throwable) {}
        }

    // ── Progress persistence ──────────────────────────────────────────

    private fun saveProgress(step: GuideStep) {
        getSharedPreferences(PREFS_SETUP_PROGRESS, MODE_PRIVATE)
            .edit().putString(KEY_LAST_STEP, step.name).apply()
    }

    // ── Error classification ──────────────────────────────────────────

    private fun classifyError(exception: Throwable?): String {
        val msg = exception?.message ?: ""
        return when {
            msg.contains("incorrect", ignoreCase = true) ||
            msg.contains("wrong", ignoreCase = true) ->
                getString(R.string.setup_error_wrong_code)
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("expired", ignoreCase = true) ->
                getString(R.string.setup_error_code_expired)
            msg.contains("connect", ignoreCase = true) ->
                getString(R.string.setup_error_connect_fail)
            else -> getString(R.string.setup_error_connect_fail)
        }
    }

    // ── mDNS timeout ──────────────────────────────────────────────────

    private val guideHandler = Handler(Looper.getMainLooper())
    private val guideAdvanceRunnable = Runnable {
        Log.i(TAG, "Guide advance: showing step 2 (pair device)")
        val n = buildWirelessDebugGuideNotification()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private val mdnsTimeoutHandler = Handler(Looper.getMainLooper())
    private val mdnsTimeoutRunnable = Runnable {
        if (currentStep == GuideStep.WAITING_PAIRING_SERVICE) {
            Log.i(TAG, "mDNS timeout: restarting search (user may not have initiated pairing yet)")
            stopSearch()
            startSearch()
        }
    }

    // ── mDNS discovery ────────────────────────────────────────────────

    private var adbMdns: AdbMdns? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val observer = Observer<Int> { port ->
        Log.i(TAG, "Pairing service port: $port")
        if (port <= 0) return@Observer
        pairingPort.value = port

        mdnsTimeoutHandler.removeCallbacks(mdnsTimeoutRunnable)
        guideHandler.removeCallbacks(guideAdvanceRunnable)

        // Show code input notification immediately.
        // Skip auto-connect attempt — if we got here, the previous connection
        // already failed (cert invalid or first-time pairing).
        currentStep = GuideStep.CODE_INPUT
        Log.i(TAG, "Showing CODE_INPUT notification for port $port")
        val notification = if (OemHelper.supportsRemoteInput()) {
            createRemoteInputNotification(port)
        } else {
            createMiuiCodeInputNotification(port)
        }
        Handler(Looper.getMainLooper()).post {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private var started = false

    // ── Service lifecycle ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("adb_pairing")
        nm.deleteNotificationChannel("adb_pairing_v2")
        nm.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.setup_notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                // Vibration (without sound) materially improves the odds that
                // ColorOS actually surfaces a heads-up banner for our otherwise
                // silent foreground-service notification instead of filing it
                // quietly into the shade.
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200)
                setShowBadge(false)
                setAllowBubbles(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            ACTION_START -> {
                onStart()
            }
            ACTION_STEP_DONE -> {
                onStepDone()
            }
            ACTION_REPLY -> {
                val code = (RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_INPUT_KEY) ?: "").toString().trim()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                when (routeReply(code, port)) {
                    ReplyRoute.EMPTY -> {
                        // Empty submit — keep the current notification so the
                        // user can try again.
                        updateNotification()
                        null
                    }
                    ReplyRoute.DIRECT -> onInput(code, port)
                    // No port yet (process was likely frozen before mDNS could
                    // discover it). Submitting un-froze us — discover now.
                    ReplyRoute.DISCOVER -> onInputWithDiscovery(code)
                }
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            ACTION_ALERT -> {
                Log.i(TAG, "ACTION_ALERT: posting step 1 (enter wireless debugging)")
                if (!started) {
                    startSearch()
                }
                guideHandler.removeCallbacks(guideAdvanceRunnable)
                guideHandler.postDelayed(guideAdvanceRunnable, GUIDE_ADVANCE_DELAY_MS)
                buildEnterWirelessDebugNotification()
            }
            else -> {
                return START_NOT_STICKY
            }
        }
        if (notification != null) {
            try {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } catch (e: Throwable) {
                Log.e(TAG, "startForeground failed", e)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e is ForegroundServiceStartNotAllowedException
                ) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }

        // Start mDNS timeout
        mdnsTimeoutHandler.postDelayed(mdnsTimeoutRunnable, MDNS_TIMEOUT_MS)
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        mdnsTimeoutHandler.removeCallbacks(mdnsTimeoutRunnable)
        adbMdns?.stop()
        pairingPort.value = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        guideHandler.removeCallbacks(guideAdvanceRunnable)
        stopSearch()
        serviceScope.cancel()
    }

    // ── Step logic ────────────────────────────────────────────────────

    /**
     * Determine which step to start at based on SetupStateDetector, then
     * show the appropriate notification.
     */
    private fun onStart(): Notification {
        val state = SetupStateDetector.detect(this, false)
        currentStep = when (state) {
            SetupState.NEED_DEVELOPER_OPTIONS -> GuideStep.ENABLE_DEV_OPTIONS
            SetupState.NEED_WIRELESS_DEBUGGING -> GuideStep.ENABLE_WIRELESS_DEBUG
            // NEED_WIFI / UNSUPPORTED_VERSION / ALREADY_CONNECTED / NEED_PAIRING
            // all fall through to WAITING — let existing search logic handle
            else -> GuideStep.WAITING_PAIRING_SERVICE
        }

        if (currentStep == GuideStep.WAITING_PAIRING_SERVICE) {
            startSearch()
        }

        return buildStepNotification(currentStep)
    }

    /**
     * User tapped "I'm done" in a guidance notification — advance to the next step.
     */
    private fun onStepDone(): Notification? {
        when (currentStep) {
            GuideStep.ENABLE_DEV_OPTIONS -> {
                // Re-check: did they actually enable it?
                val nowEnabled = SetupStateDetector.isDevOptionsEnabled(this)
                currentStep = if (nowEnabled) {
                    GuideStep.ENABLE_WIRELESS_DEBUG
                } else {
                    GuideStep.ENABLE_DEV_OPTIONS // stay and show again
                }
            }
            GuideStep.ENABLE_WIRELESS_DEBUG -> {
                currentStep = GuideStep.WAITING_PAIRING_SERVICE
                startSearch()
            }
            else -> { /* no-op for other steps */ }
        }
        return buildStepNotification(currentStep)
    }

    private fun onInput(code: String, port: Int): Notification {
        currentStep = GuideStep.PAIRING
        serviceScope.launch { doPair(code, port) }
        return buildStepNotification(GuideStep.PAIRING)
    }

    /**
     * Reply path used when we don't yet have the pairing port — typically
     * because aggressive OEM power management (ColorOS HansManager) froze our
     * foreground process before the resident mDNS search could discover it.
     *
     * The act of submitting the RemoteInput un-freezes us, and by this point
     * the user has opened the "pair with code" dialog in Settings (that's how
     * they got the code), so the `_adb-tls-pairing._tcp` service is live.
     * Discover the port once, right now, then pair immediately.
     */
    private fun onInputWithDiscovery(code: String): Notification {
        currentStep = GuideStep.PAIRING
        serviceScope.launch {
            // Maybe the resident search already found it before we were frozen.
            val known = pairingPort.value
            val port = if (known > 0) known else discoverPairingPortOnce(PAIR_DISCOVERY_TIMEOUT_MS)
            if (port == null || port <= 0) {
                Log.w(TAG, "onInputWithDiscovery: pairing service not found within ${PAIR_DISCOVERY_TIMEOUT_MS}ms")
                handleResult(false, IllegalStateException("pairing_service_not_found"))
            } else {
                Log.i(TAG, "onInputWithDiscovery: discovered port $port, pairing")
                doPair(code, port)
            }
        }
        return buildStepNotification(GuideStep.PAIRING)
    }

    /** Perform the actual ADB pairing handshake and, on success, connect. */
    private suspend fun doPair(code: String, port: Int) {
        val host = "127.0.0.1"

        val key = try {
            AdbKey(
                PreferenceAdbKeyStore(
                    getSharedPreferences("adb_key", MODE_PRIVATE)
                ),
                "${packageName}@${Build.MODEL}"
            )
        } catch (e: Throwable) {
            e.printStackTrace()
            handleResult(false, e)
            return
        }

        AdbPairingClient(host, port, code, key).runCatching {
            start()
        }.onFailure {
            handleResult(false, it)
        }.onSuccess {
            if (it) {
                // Pairing succeeded — now connect via DirectConnector flow
                connectAfterPairing(key)
            } else {
                handleResult(false, null)
            }
        }
    }

    /**
     * One-shot mDNS discovery of the `_adb-tls-pairing._tcp` port.
     * Returns the port, or null if not found within [timeoutMs].
     */
    private suspend fun discoverPairingPortOnce(timeoutMs: Long): Int? {
        val deferred = CompletableDeferred<Int>()
        val obs = Observer<Int> { p -> if (p > 0 && !deferred.isCompleted) deferred.complete(p) }
        val mdns = AdbMdns(this, AdbMdns.TLS_PAIRING, obs)
        return try {
            mdns.start()
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            try { mdns.stop() } catch (_: Throwable) {}
        }
    }

    /**
     * Try connecting with existing ADB keys via TLS_CONNECT before requiring pairing.
     * Keys survive wireless debugging toggle on most devices.
     */
    private suspend fun tryAutoConnectFirst(): Boolean {
        val service = ai.opencyvis.App.agentService ?: return false
        return try {
            kotlinx.coroutines.withTimeoutOrNull(8000L) {
                service.retryBackendDetection()
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "tryAutoConnectFirst failed: ${e.message}")
            false
        }
    }

    /**
     * After successful pairing, discover the connect port, connect via ADB,
     * launch the privileged service, and update the ScreenCapture backend.
     */
    private fun connectAfterPairing(key: AdbKey) {
        try {
            val connector = DirectConnector(this@AdbPairingService)
            connector.connect()

            // Wait briefly for connection to complete
            var attempts = 0
            while (attempts < 20) {
                val state = connector.state.value
                when (state) {
                    is ConnectionState.Connected -> {
                        Log.i(TAG, "Connected after pairing, updating backend")
                        val svc = IPrivilegedService.Stub.asInterface(state.serviceBinder)
                        val backend = RemoteBackend(connector, svc)
                        ScreenCapture.backend = backend
                        // Update AgentService backend
                        ai.opencyvis.App.agentService?.updateBackend(backend)
                        handleResult(true, null)
                        return
                    }
                    is ConnectionState.Failed -> {
                        Log.w(TAG, "Connection failed after pairing: ${state.error}")
                        handleResult(true, null) // Pairing still succeeded
                        return
                    }
                    else -> {
                        Thread.sleep(500)
                        attempts++
                    }
                }
            }
            // Timed out waiting for connection, but pairing itself succeeded
            handleResult(true, null)
        } catch (e: Exception) {
            Log.e(TAG, "connectAfterPairing failed", e)
            handleResult(true, null) // Pairing succeeded even if connect failed
        }
    }

    private fun handleResult(success: Boolean, exception: Throwable?) {
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (success) {
            Log.i(TAG, "Pair succeeded")
            currentStep = GuideStep.SUCCESS
            stopSearch()
            clearProgress(this)
        } else {
            currentStep = GuideStep.ERROR

            if (exception != null) {
                Log.w(TAG, "Pair failed", exception)
            } else {
                Log.w(TAG, "Pair failed")
            }
        }

        val errorMsg = if (!success) {
            when (exception) {
                is AdbInvalidPairingCodeException -> getString(R.string.setup_error_wrong_code)
                else -> classifyError(exception)
            }
        } else null

        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildStepNotification(currentStep, errorMsg)
        )
        stopSelf()
    }

    // ── Notification helpers ──────────────────────────────────────────

    /**
     * Update the existing notification in-place (without restarting foreground).
     */
    private fun updateNotification(errorMsg: String? = null) {
        val notification = buildStepNotification(currentStep, errorMsg)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Build the appropriate notification for the given [step].
     */
    private fun buildStepNotification(step: GuideStep, errorMsg: String? = null): Notification {
        return when (step) {
            GuideStep.ENABLE_DEV_OPTIONS -> buildEnableDevOptionsNotification()
            GuideStep.ENABLE_WIRELESS_DEBUG -> buildEnableWirelessDebugNotification()
            GuideStep.WAITING_PAIRING_SERVICE -> buildSearchingNotification()
            GuideStep.CODE_INPUT -> buildSearchingNotification() // will be replaced by observer
            GuideStep.PAIRING -> buildPairingNotification()
            GuideStep.SUCCESS -> buildSuccessNotification()
            GuideStep.ERROR -> buildErrorNotification(errorMsg)
        }
    }

    // ── Per-step notification builders ───────────────────────────────

    private fun buildEnableDevOptionsNotification(): Notification {
        val openSettingsIntent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val openSettingsPending = PendingIntent.getActivity(
            this,
            SETTINGS_REQUEST_ID,
            openSettingsIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )
        val stepDonePending = PendingIntent.getForegroundService(
            this,
            STEP_DONE_REQUEST_ID,
            stepDoneIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openSettingsAction = Notification.Action.Builder(
            null,
            getString(R.string.setup_dev_options_btn),
            openSettingsPending
        ).build()

        val doneAction = Notification.Action.Builder(
            null,
            getString(R.string.setup_action_done),
            stepDonePending
        ).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.setup_dev_options_title))
            .setContentText(getString(R.string.setup_dev_options_desc, 7))
            .setStyle(Notification.BigTextStyle()
                .bigText(getString(R.string.setup_dev_options_desc, 7)))
            .addAction(openSettingsAction)
            .addAction(doneAction)
            .setOngoing(true)
            .build()
    }

    private fun buildEnableWirelessDebugNotification(): Notification {
        val wirelessIntent = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val openWirelessIntent: Intent = if (wirelessIntent.resolveActivity(packageManager) != null) {
            wirelessIntent
        } else {
            // Fallback: Developer Options with highlight on wireless debugging toggle
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(":settings:show_fragment_args", Bundle().apply {
                    putString(":settings:fragment_args_key", "toggle_adb_wireless")
                })
            }
        }
        val openWirelessPending = PendingIntent.getActivity(
            this,
            WIRELESS_DEBUG_REQUEST_ID,
            openWirelessIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )
        val stepDonePending = PendingIntent.getForegroundService(
            this,
            STEP_DONE_REQUEST_ID,
            stepDoneIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openAction = Notification.Action.Builder(
            null,
            getString(R.string.setup_wireless_btn),
            openWirelessPending
        ).build()

        val doneAction = Notification.Action.Builder(
            null,
            getString(R.string.setup_action_done),
            stepDonePending
        ).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.setup_wireless_title))
            .setContentText(getString(R.string.setup_wireless_desc))
            .setStyle(Notification.BigTextStyle()
                .bigText(getString(R.string.setup_wireless_desc)))
            .addAction(openAction)
            .addAction(doneAction)
            .setOngoing(true)
            .build()
    }

    private fun buildEnterWirelessDebugNotification(): Notification {
        val stopPending = PendingIntent.getService(
            this,
            STOP_REQUEST_ID,
            stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )
        val stopAction = Notification.Action.Builder(
            null,
            getString(R.string.setup_action_stop),
            stopPending
        ).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.setup_wireless_notif_enter_title))
            .setContentText(getString(R.string.setup_wireless_notif_enter_desc))
            .setStyle(Notification.BigTextStyle()
                .bigText(getString(R.string.setup_wireless_notif_enter_desc)))
            .addAction(replyActionWithInput())
            .addAction(stopAction)
            .setFullScreenIntent(fullScreenPendingIntent(), true)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .build()
    }

    private fun buildWirelessDebugGuideNotification(): Notification {
        val stopPending = PendingIntent.getService(
            this,
            STOP_REQUEST_ID,
            stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )
        val stopAction = Notification.Action.Builder(
            null,
            getString(R.string.setup_action_stop),
            stopPending
        ).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.setup_wireless_notif_guide_title))
            .setContentText(getString(R.string.setup_wireless_notif_guide_desc, 6))
            .setStyle(Notification.BigTextStyle()
                .bigText(getString(R.string.setup_wireless_notif_guide_desc, 6)))
            .addAction(replyActionWithInput())
            .addAction(stopAction)
            .setFullScreenIntent(fullScreenPendingIntent(), true)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .build()
    }

    private fun buildSearchingNotification(silent: Boolean = true): Notification {
        val stopPending = PendingIntent.getService(
            this,
            STOP_REQUEST_ID,
            stopIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )
        val stopAction = Notification.Action.Builder(
            null,
            getString(R.string.setup_action_stop),
            stopPending
        ).build()

        val wirelessIntent = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val openIntent: Intent = if (wirelessIntent.resolveActivity(packageManager) != null) {
            wirelessIntent
        } else {
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(":settings:show_fragment_args", Bundle().apply {
                    putString(":settings:fragment_args_key", "toggle_adb_wireless")
                })
            }
        }
        val openPending = PendingIntent.getActivity(
            this,
            WIRELESS_DEBUG_REQUEST_ID,
            openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.setup_pair_notif_title))
            .setContentText(getString(R.string.setup_pair_notif_desc, 6))
            .setStyle(Notification.BigTextStyle()
                .bigText(getString(R.string.setup_pair_notif_desc, 6)))
            .setContentIntent(openPending)
            .addAction(replyActionWithInput())
            .addAction(stopAction)
            .setOngoing(true)
            .setOnlyAlertOnce(silent)
            .build()
    }

    private fun buildPairingNotification(): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.setup_pair_btn))
            .setOngoing(true)
            .build()
    }

    private fun buildSuccessNotification(): Notification {
        val returnIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: Intent()
        val returnPending = PendingIntent.getActivity(
            this,
            SETTINGS_REQUEST_ID,
            returnIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.setup_success_title))
            .setContentText(getString(R.string.setup_success_notif))
            .setContentIntent(returnPending)
            .setAutoCancel(true)
            .build()
    }

    private fun buildErrorNotification(errorMsg: String?): Notification {
        val retryPending = PendingIntent.getForegroundService(
            this,
            STEP_DONE_REQUEST_ID,
            startIntent(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )
        val retryAction = Notification.Action.Builder(
            null,
            getString(R.string.setup_action_retry),
            retryPending
        ).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Pairing Failed")
            .setContentText(errorMsg)
            .setStyle(Notification.BigTextStyle().bigText(errorMsg))
            .addAction(retryAction)
            .build()
    }

    // ── CODE_INPUT notifications (RemoteInput / MIUI fallback) ────────

    /**
     * Build a "reply" action carrying a RemoteInput code field.
     *
     * [port] may be -1 when the port isn't known yet: we attach this action to
     * the *guidance* notifications too, so that even if the process gets frozen
     * before mDNS discovers the port, the user can still type the code. On
     * submit with port<=0 we discover the port on the spot (see
     * [onInputWithDiscovery]). FLAG_UPDATE_CURRENT means a later real-port
     * action replaces this placeholder if the resident search succeeds first.
     */
    /**
     * High-priority full-screen intent used purely to force the notification to
     * surface as a heads-up banner (and break through ColorOS background-app
     * suppression). It points at [SetupActivity] so that, in the event the OS
     * does promote it to an actual full-screen launch, the user lands on the
     * pairing UI rather than a dead end. The PendingIntent is held by SystemUI,
     * so it still works even while our process is frozen.
     */
    private fun fullScreenPendingIntent(): PendingIntent {
        val intent = Intent(this, SetupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            FULL_SCREEN_REQUEST_ID,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun replyActionWithInput(port: Int = -1): Notification.Action {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(getString(R.string.setup_pair_hint, 6))
            .build()

        val replyPending = PendingIntent.getForegroundService(
            this,
            REPLY_REQUEST_ID,
            replyIntent(this, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Action.Builder(null, getString(R.string.setup_notif_input_btn), replyPending)
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun createRemoteInputNotification(port: Int): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.setup_pair_notif_title))
            .setContentText(getString(R.string.setup_pair_notif_desc, 6))
            .addAction(replyActionWithInput(port))
            .setFullScreenIntent(fullScreenPendingIntent(), true)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .build()
    }

    /**
     * MIUI fallback: RemoteInput is unreliable on MIUI.
     * Open wireless debugging settings so the user can get the code,
     * then they enter it via a system dialog (TODO: PairingDialogActivity when available).
     */
    private fun createMiuiCodeInputNotification(port: Int): Notification {
        val wirelessIntent = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val openWirelessIntent: Intent = if (wirelessIntent.resolveActivity(packageManager) != null) {
            wirelessIntent
        } else {
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(":settings:show_fragment_args", Bundle().apply {
                    putString(":settings:fragment_args_key", "toggle_adb_wireless")
                })
            }
        }
        val openPending = PendingIntent.getActivity(
            this,
            WIRELESS_DEBUG_REQUEST_ID,
            openWirelessIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )
        val openAction = Notification.Action.Builder(
            null,
            getString(R.string.setup_action_manual_input),
            openPending
        ).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.setup_pair_notif_title))
            .setContentText(getString(R.string.setup_wireless_notif_found))
            .addAction(openAction)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

/** How a submitted RemoteInput pairing code should be routed. */
internal enum class ReplyRoute {
    /** No code entered — re-show the current notification. */
    EMPTY,

    /** Code entered and the pairing port is already known — pair directly. */
    DIRECT,

    /**
     * Code entered but the port isn't known yet (e.g. our process was frozen
     * before mDNS could discover it). Discover the port on the spot, then pair.
     */
    DISCOVER,
}

/**
 * Pure decision for [AdbPairingService.onStartCommand]'s ACTION_REPLY handling,
 * extracted so the freeze-resilient "discover on submit" branch is unit-testable.
 */
internal fun routeReply(code: String, port: Int): ReplyRoute = when {
    code.isBlank() -> ReplyRoute.EMPTY
    port > 0 -> ReplyRoute.DIRECT
    else -> ReplyRoute.DISCOVER
}
