package ai.opencyvis.backend

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ai.opencyvis.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull

class SetupActivity : AppCompatActivity() {

    companion object {
        const val RESULT_BACKEND_READY = 100
        const val EXTRA_FORCE_METHOD_CHOOSER = "force_method_chooser"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 41
    }

    private enum class SetupState {
        CHOOSE_METHOD,       // Choose between Shizuku and ADB Direct
        NEED_WIFI,           // WiFi not connected
        SHIZUKU_CHECK,       // Check if Shizuku is installed and running
        SHIZUKU_PERMISSION,  // Request Shizuku permission
        ADB_CHECK_OS,        // Check Android version for wireless debugging
        ADB_ENABLE_DEV,      // Guide: enable developer options
        ADB_ENABLE_WIRELESS, // Guide: enable wireless debugging
        ADB_PAIR,            // Enter pairing code
        CONNECTING,          // Connecting to backend
        CONNECTED,           // Success
        FAILED               // Something went wrong
    }

    private lateinit var titleView: TextView
    private lateinit var descView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var inputLayout: TextInputLayout
    private lateinit var inputField: TextInputEditText
    private lateinit var actionButton: MaterialButton
    private lateinit var secondaryButton: MaterialButton
    private lateinit var otpLabel: TextView
    private lateinit var otpBox: OtpDigitBox
    private lateinit var numberPad: NumberPadView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentState = SetupState.CHOOSE_METHOD
    private var directConnector: DirectConnector? = null
    private var privilegedService: IPrivilegedService? = null
    private var resumeAfterCreate = false
    private var pairingPortJob: Job? = null
    private var shizukuConnectJob: Job? = null
    private var requestedBatteryExemption = false
    private var preferWirelessAdb = false
    private var forceMethodChooser = false

    private val shizukuBinderReceivedListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        if (!forceMethodChooser &&
            !preferWirelessAdb &&
            currentState != SetupState.SHIZUKU_CHECK &&
            currentState != SetupState.CONNECTING &&
            currentState != SetupState.ADB_PAIR &&
            currentState != SetupState.CONNECTED
        ) {
            startPreferredSetupFlow()
        }
    }

    private val shizukuPermissionResultListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                forceMethodChooser = false
                updateUi(SetupState.SHIZUKU_CHECK)
            } else {
                showShizukuPermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        titleView = findViewById(R.id.setup_title)
        descView = findViewById(R.id.setup_description)
        progressBar = findViewById(R.id.setup_progress)
        inputLayout = findViewById(R.id.setup_input_layout)
        inputField = findViewById(R.id.setup_input)
        actionButton = findViewById(R.id.setup_action_button)
        secondaryButton = findViewById(R.id.setup_secondary_button)
        otpLabel = findViewById(R.id.setup_otp_label)
        otpBox = findViewById(R.id.setup_otp_box)
        numberPad = findViewById(R.id.setup_number_pad)

        actionButton.setOnClickListener { onActionClick() }
        secondaryButton.setOnClickListener { onSecondaryClick() }

        otpBox.listener = object : OtpDigitBox.OnCodeCompleteListener {
            override fun onCodeComplete(code: String) {
                submitPairingCode(code)
            }
        }

        // In-app number pad drives the OTP boxes so the system keyboard never appears.
        // This keeps the Settings pairing code visible in split-screen (the system IME
        // would otherwise rise from the bottom and cover the adjacent Settings window).
        numberPad.onDigit = { digit -> otpBox.appendDigit(digit) }
        numberPad.onBackspace = { otpBox.deleteDigit() }

        rikka.shizuku.Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener)
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)

        // Persistent observer: mDNS discovers the pairing service port the moment the
        // user opens "Pair device with pairing code" in Settings. That is the signal
        // that the user is ready to type the code — auto-advance to the code-entry step
        // (step 3) regardless of which pre-pairing step we're currently showing.
        observePairingPort()

        forceMethodChooser = intent.getBooleanExtra(EXTRA_FORCE_METHOD_CHOOSER, false)
        if (forceMethodChooser) {
            updateUi(SetupState.CHOOSE_METHOD)
        } else {
            startPreferredSetupFlow()
        }
        resumeAfterCreate = true
    }

    /** Prefer a running Shizuku before evaluating any wireless ADB prerequisites. */
    private fun startPreferredSetupFlow() {
        if (preferWirelessAdb) {
            startAdbSetupFlow()
            return
        }
        when (ShizukuConnector.status()) {
            ShizukuStatus.READY -> updateUi(SetupState.SHIZUKU_CHECK)
            ShizukuStatus.PERMISSION_REQUIRED -> updateUi(SetupState.SHIZUKU_PERMISSION)
            ShizukuStatus.UNAVAILABLE -> startAdbSetupFlow()
        }
    }

    private fun startAdbSetupFlow() {
        val detected = SetupStateDetector.detect(this, false)
        Log.i("SetupActivity", "onCreate: detected=$detected taskId=$taskId")
        val startState = when (detected) {
            ai.opencyvis.backend.SetupState.NEED_WIFI -> SetupState.NEED_WIFI
            ai.opencyvis.backend.SetupState.UNSUPPORTED_VERSION -> SetupState.ADB_CHECK_OS
            ai.opencyvis.backend.SetupState.NEED_DEVELOPER_OPTIONS -> SetupState.CHOOSE_METHOD
            ai.opencyvis.backend.SetupState.NEED_WIRELESS_DEBUGGING -> SetupState.ADB_ENABLE_WIRELESS
            ai.opencyvis.backend.SetupState.NEED_PAIRING -> {
                // Wireless debugging is on — try reconnecting with existing keys first
                // before asking user to re-pair. Keys survive wireless debugging toggle
                // on most devices.
                tryAutoReconnect()
                return // tryAutoReconnect manages its own UI; don't call updateUi here
            }
            ai.opencyvis.backend.SetupState.ALREADY_CONNECTED -> {
                setResult(RESULT_BACKEND_READY); finish(); return
            }
        }
        Log.i("SetupActivity", "onCreate: startState=$startState")
        updateUi(startState)
    }

    override fun onResume() {
        super.onResume()
        if (resumeAfterCreate) {
            Log.i("SetupActivity", "onResume: skipping (after onCreate)")
            resumeAfterCreate = false
            return
        }
        // Guard: don't re-detect if user is mid-code-entry in split-screen
        if (isInMultiWindowMode && currentState == SetupState.ADB_PAIR) {
            Log.i("SetupActivity", "onResume: skipping detection in multi-window ADB_PAIR")
            return
        }

        if (forceMethodChooser && currentState == SetupState.CHOOSE_METHOD) return

        if (!preferWirelessAdb && ShizukuConnector.status() != ShizukuStatus.UNAVAILABLE) {
            startPreferredSetupFlow()
            return
        }

        val detected = SetupStateDetector.detect(this, false)
        Log.i("SetupActivity", "onResume: detected=$detected currentState=$currentState")
        val newState = when (detected) {
            ai.opencyvis.backend.SetupState.NEED_WIFI -> SetupState.NEED_WIFI
            ai.opencyvis.backend.SetupState.UNSUPPORTED_VERSION -> SetupState.ADB_CHECK_OS
            ai.opencyvis.backend.SetupState.NEED_DEVELOPER_OPTIONS -> SetupState.CHOOSE_METHOD
            ai.opencyvis.backend.SetupState.NEED_WIRELESS_DEBUGGING -> SetupState.ADB_ENABLE_WIRELESS
            ai.opencyvis.backend.SetupState.NEED_PAIRING -> {
                tryAutoReconnect()
                return // tryAutoReconnect manages its own UI
            }
            ai.opencyvis.backend.SetupState.ALREADY_CONNECTED -> {
                setResult(RESULT_BACKEND_READY); finish(); return
            }
        }
        if (newState != currentState) updateUi(newState)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (currentState == SetupState.ADB_PAIR) {
            updateUi(SetupState.ADB_PAIR)
        }
        Log.i("SetupActivity", "onMultiWindowModeChanged: $isInMultiWindowMode")
    }

    private fun updateUi(state: SetupState) {
        Log.i("SetupActivity", "updateUi: $state", Throwable("stack"))
        currentState = state
        progressBar.visibility = View.GONE
        inputLayout.visibility = View.GONE
        secondaryButton.visibility = View.GONE
        actionButton.isEnabled = true
        // Reset visibility for all states — ADB_PAIR handles them separately
        titleView.visibility = View.VISIBLE
        descView.visibility = View.VISIBLE
        otpLabel.visibility = View.GONE
        otpBox.visibility = View.GONE
        numberPad.visibility = View.GONE
        actionButton.visibility = View.VISIBLE

        when (state) {
            SetupState.CHOOSE_METHOD -> {
                val shizukuStatus = ShizukuConnector.status()
                if (shizukuStatus != ShizukuStatus.UNAVAILABLE) {
                    // Shizuku detected — offer both options
                    titleView.text = "Choose Backend"
                    descView.text = "Shizuku is available. Choose which privilege backend OpenCyvis should use."
                    actionButton.text = "Use Shizuku"
                    secondaryButton.text = "Use Wireless ADB"
                    secondaryButton.visibility = View.VISIBLE
                } else {
                    titleView.text = "Choose Backend"
                    descView.text = "Shizuku is not running. Wireless ADB is the available standard-app backend."
                    actionButton.text = "Use Wireless ADB"
                }
            }
            SetupState.NEED_WIFI -> {
                titleView.text = getString(R.string.setup_need_wifi_title)
                descView.text = getString(R.string.setup_need_wifi_desc)
                actionButton.text = getString(R.string.setup_action_retry)
            }
            SetupState.SHIZUKU_CHECK -> {
                titleView.text = "Shizuku Setup"
                descView.text = "Checking if Shizuku is available..."
                progressBar.visibility = View.VISIBLE
                actionButton.text = "Checking..."
                actionButton.isEnabled = false
                checkShizuku()
            }
            SetupState.SHIZUKU_PERMISSION -> {
                titleView.text = "Shizuku Permission"
                descView.text = "Shizuku is running. Grant permission to OpenCyvis to continue."
                actionButton.text = "Grant Permission"
                secondaryButton.text = "Use Wireless ADB"
                secondaryButton.visibility = View.VISIBLE
            }
            SetupState.ADB_CHECK_OS -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    titleView.text = getString(R.string.setup_unsupported_title)
                    descView.text = getString(R.string.setup_unsupported_desc, Build.VERSION.SDK_INT)
                    actionButton.text = "Back"
                } else {
                    updateUi(SetupState.ADB_ENABLE_DEV)
                    return
                }
            }
            SetupState.ADB_ENABLE_DEV -> {
                titleView.text = getString(R.string.setup_dev_options_title)
                val desc = StringBuilder(getString(R.string.setup_dev_options_desc, 7))
                if (OemHelper.isMiui()) desc.append("\n\n").append(getString(R.string.setup_miui_sim_warning))
                desc.append("\n\n").append(getString(R.string.setup_dev_options_reassure))
                descView.text = desc
                actionButton.text = getString(R.string.setup_dev_options_btn)
                secondaryButton.text = getString(R.string.setup_dev_options_skip)
                secondaryButton.visibility = View.VISIBLE
            }
            SetupState.ADB_ENABLE_WIRELESS -> {
                startPairingService()
                titleView.text = getString(R.string.setup_wireless_title)
                descView.text = getString(R.string.setup_wireless_desc)
                actionButton.text = getString(R.string.setup_wireless_btn)
                secondaryButton.text = getString(R.string.setup_action_manual_input)
                secondaryButton.visibility = View.VISIBLE
            }
            SetupState.ADB_PAIR -> {
                startPairingService()
                if (isInMultiWindowMode) {
                    // Compact split-screen layout: OTP boxes + in-app number pad at top,
                    // hide title/desc. The in-app number pad means the system keyboard
                    // never appears, so the pairing code in the adjacent Settings window
                    // stays visible.
                    titleView.visibility = View.GONE
                    descView.visibility = View.GONE
                    otpLabel.visibility = View.VISIBLE
                    otpLabel.text = pairLabelForCurrentPort()
                    otpBox.visibility = View.VISIBLE
                    otpBox.clear()
                    otpBox.isEnabled = true
                    numberPad.visibility = View.VISIBLE
                    inputLayout.visibility = View.GONE
                    // Submit via auto-submit on 6th digit, no button needed
                    actionButton.visibility = View.GONE
                    secondaryButton.text = getString(R.string.setup_wireless_btn)
                    secondaryButton.visibility = View.VISIBLE
                } else {
                    // Full-screen layout: OTP boxes + in-app number pad (unified experience,
                    // no system keyboard in either mode)
                    titleView.text = getString(R.string.setup_pair_title)
                    descView.text = getString(R.string.setup_pair_desc, 6)
                    inputLayout.visibility = View.GONE // hide old single EditText
                    otpLabel.visibility = View.VISIBLE
                    otpLabel.text = pairLabelForCurrentPort()
                    otpBox.visibility = View.VISIBLE
                    otpBox.clear()
                    otpBox.isEnabled = true
                    numberPad.visibility = View.VISIBLE
                    // Auto-submit on 6th digit; keep Pair button as a fallback
                    actionButton.visibility = View.VISIBLE
                    actionButton.text = getString(R.string.setup_pair_btn)
                    secondaryButton.text = getString(R.string.setup_wireless_btn)
                    secondaryButton.visibility = View.VISIBLE
                }
            }
            SetupState.CONNECTING -> {
                titleView.text = "Connecting..."
                descView.text = "Establishing connection to the privileged service."
                progressBar.visibility = View.VISIBLE
                actionButton.isEnabled = false
                otpBox.isEnabled = false
            }
            SetupState.CONNECTED -> {
                titleView.text = getString(R.string.setup_success_title)
                descView.text = getString(R.string.setup_success_desc)
                if (OemHelper.isColorOS()) {
                    descView.append("\n\n" + getString(R.string.setup_coloros_auto_close))
                }
                actionButton.text = getString(R.string.setup_done_btn)
                otpBox.visibility = View.GONE
                otpLabel.visibility = View.GONE
            }
            SetupState.FAILED -> {
                titleView.text = getString(R.string.setup_error_wrong_code)
                descView.text = getString(R.string.setup_error_connect_fail)
                actionButton.text = getString(R.string.setup_action_retry)
                otpBox.visibility = View.GONE
                otpLabel.visibility = View.GONE
            }
        }
    }

    private fun onActionClick() {
        when (currentState) {
            SetupState.CHOOSE_METHOD -> {
                forceMethodChooser = false
                when (ShizukuConnector.status()) {
                    ShizukuStatus.READY -> updateUi(SetupState.SHIZUKU_CHECK)
                    ShizukuStatus.PERMISSION_REQUIRED -> updateUi(SetupState.SHIZUKU_PERMISSION)
                    ShizukuStatus.UNAVAILABLE -> {
                        preferWirelessAdb = true
                        startAdbSetupFlow()
                    }
                }
            }
            SetupState.NEED_WIFI -> {
                // Re-check WiFi
                if (SetupStateDetector.hasWifi(this)) {
                    updateUi(SetupState.CHOOSE_METHOD)
                } else {
                    Toast.makeText(this, getString(R.string.setup_need_wifi_title), Toast.LENGTH_SHORT).show()
                }
            }
            SetupState.SHIZUKU_CHECK -> {}
            SetupState.SHIZUKU_PERMISSION -> requestShizukuPermission()
            SetupState.ADB_CHECK_OS -> updateUi(SetupState.CHOOSE_METHOD)
            SetupState.ADB_ENABLE_DEV -> {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
            SetupState.ADB_ENABLE_WIRELESS -> {
                openWirelessDebuggingSettings()
            }
            SetupState.ADB_PAIR -> {
                val code = otpBox.getCode()
                if (code.length < 6) {
                    otpBox.clear()
                    Toast.makeText(this, getString(R.string.setup_error_wrong_code), Toast.LENGTH_SHORT).show()
                } else {
                    submitPairingCode(code)
                }
            }
            SetupState.CONNECTING -> {} // wait
            SetupState.CONNECTED -> {
                setResult(RESULT_BACKEND_READY)
                exitSplitScreenIfNeeded()
                finish()
            }
            SetupState.FAILED -> updateUi(SetupState.CHOOSE_METHOD)
        }
    }

    private fun onSecondaryClick() {
        when (currentState) {
            SetupState.CHOOSE_METHOD -> {
                forceMethodChooser = false
                preferWirelessAdb = true
                updateUi(SetupState.ADB_CHECK_OS)
            }
            SetupState.SHIZUKU_PERMISSION -> {
                preferWirelessAdb = true
                startAdbSetupFlow()
            }
            SetupState.ADB_ENABLE_DEV -> updateUi(SetupState.ADB_ENABLE_WIRELESS)
            SetupState.ADB_ENABLE_WIRELESS -> {
                // "Enter Code Manually"
                updateUi(SetupState.ADB_PAIR)
            }
            SetupState.ADB_PAIR -> {
                openWirelessDebuggingSettings()
            }
            else -> {}
        }
    }

    private fun openWirelessDebuggingSettings() {
        // Trigger heads-up notification to guide user while in Settings
        startService(AdbPairingService.alertIntent(this))

        // Candidate Settings destinations, most specific first. Chinese OEM ROMs (nubia,
        // MIUI, ColorOS, ...) often lack the wireless-debugging deep link, keep the
        // developer-settings activity unexported, or don't honor it via an implicit
        // action — so we fall through to the always-available main Settings page.
        val candidates = listOf(
            Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
            Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                putExtra(":settings:show_fragment_args", Bundle().apply {
                    putString(":settings:fragment_args_key", "toggle_adb_wireless")
                })
            },
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )

        // On API 32+ (Android 12L+), request adjacent split-screen placement. OEMs that
        // don't support split just open the target fullscreen — harmless degradation.
        val flags = if (Build.VERSION.SDK_INT >= 32) {
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        } else {
            Intent.FLAG_ACTIVITY_NEW_TASK
        }

        for (intent in candidates) {
            intent.addFlags(flags)
            try {
                startActivity(intent)
                Log.i("SetupActivity", "Opened settings via action=${intent.action}")
                return
            } catch (e: Exception) {
                Log.w("SetupActivity", "settings intent action=${intent.action} failed: ${e.message}")
            }
        }
        Toast.makeText(this, getString(R.string.setup_open_settings_manual), Toast.LENGTH_LONG).show()
    }

    /** OTP label text reflecting the latest mDNS-discovered pairing port, if any. */
    private fun pairLabelForCurrentPort(): String {
        val port = AdbPairingService.pairingPort.value
        return if (port > 0) {
            getString(R.string.setup_pair_split_label_with_port, port)
        } else {
            getString(R.string.setup_pair_split_label)
        }
    }

    private fun observePairingPort() {
        pairingPortJob?.cancel()
        pairingPortJob = scope.launch {
            AdbPairingService.pairingPort.collect { port ->
                if (port <= 0) return@collect
                when (currentState) {
                    // Discovering the mDNS pairing port means the user just opened
                    // "Pair device with pairing code" in Settings → auto-advance to the
                    // code-entry step (step 3) so the OTP boxes + number pad are ready.
                    SetupState.ADB_CHECK_OS,
                    SetupState.ADB_ENABLE_DEV,
                    SetupState.ADB_ENABLE_WIRELESS -> {
                        updateUi(SetupState.ADB_PAIR)
                    }
                    SetupState.ADB_PAIR -> {
                        otpLabel.text = getString(R.string.setup_pair_split_label_with_port, port)
                    }
                    // Connecting / connected / failed / shizuku — don't disrupt.
                    else -> {}
                }
            }
        }
    }

    private fun checkShizuku() {
        if (shizukuConnectJob?.isActive == true) return
        shizukuConnectJob = scope.launch {
            try {
                val connector = ShizukuConnector(this@SetupActivity)
                if (connector.isAvailable()) {
                    // Shizuku running + permission granted -- try connecting
                    connector.connect()
                    val result = withTimeoutOrNull(10_000L) {
                        connector.state.first {
                            it is ConnectionState.Connected || it is ConnectionState.Failed
                        }
                    }
                    if (result is ConnectionState.Connected) {
                        registerConnectedBackend(connector, result.serviceBinder)
                        updateUi(SetupState.CONNECTED)
                    } else {
                        updateUi(SetupState.FAILED)
                    }
                } else {
                    // Check if Shizuku is running but we lack permission
                    val shizukuRunning = try {
                        rikka.shizuku.Shizuku.pingBinder()
                    } catch (_: Exception) { false }

                    if (shizukuRunning) {
                        updateUi(SetupState.SHIZUKU_PERMISSION)
                    } else {
                        startAdbSetupFlow()
                    }
                }
            } catch (e: Exception) {
                updateUi(SetupState.FAILED)
                descView.text = "Error checking Shizuku: ${e.message}"
            } finally {
                shizukuConnectJob = null
            }
        }
    }

    private fun requestShizukuPermission() {
        try {
            rikka.shizuku.Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to request permission: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShizukuPermissionDenied() {
        updateUi(SetupState.SHIZUKU_PERMISSION)
        descView.text = "Shizuku permission was denied. Grant permission to use Shizuku, or continue with Wireless ADB."
    }

    /**
     * Register the freshly-connected privileged backend with AgentService so the rest
     * of the app sees a live connection. Without this, ControlPanel.onResume() finds
     * activeBackendName == "none" after we finish and re-launches setup / re-runs the
     * reconnect flow. Mirrors AdbPairingService.connectAfterPairing().
     */
    private fun registerConnectedBackend(connector: ServiceConnector, binder: android.os.IBinder) {
        try {
            val svc = IPrivilegedService.Stub.asInterface(binder)
            privilegedService = svc
            val backend = RemoteBackend(connector, svc)
            ai.opencyvis.capture.ScreenCapture.backend = backend
            ai.opencyvis.App.agentService?.updateBackend(backend)
            Log.i("SetupActivity", "Backend registered with AgentService via ${connector.name}")
        } catch (e: Exception) {
            Log.e("SetupActivity", "Failed to register backend after pairing", e)
        }
    }

    /**
     * Dismiss split-screen when finishing setup so the user lands on a fullscreen app.
     *
     * There is no public API to force a standard-signed app out of split-screen on
     * Android 12+ (relaunching via the launcher intent stays in the same pane, and
     * ActivityOptions.setLaunchWindowingMode is blocked by the hidden-API allowlist).
     * Instead we force-stop the adjacent Settings pane via the now-connected privileged
     * service (shell uid): killing one pane collapses split-screen and the remaining
     * pane (our app) becomes fullscreen. Verified on Pixel 6 Pro (API 35).
     *
     * Runs on a detached thread because the binder call blocks (am force-stop) and the
     * activity's own scope is cancelled by finish().
     */
    private fun exitSplitScreenIfNeeded() {
        if (!isInMultiWindowMode) return
        val svc = privilegedService ?: return
        Thread {
            try {
                svc.forceStopPackage("com.android.settings")
            } catch (e: Exception) {
                Log.w("SetupActivity", "exitSplitScreen force-stop failed: ${e.message}")
            }
        }.start()
    }

    private fun submitPairingCode(code: String) {
        updateUi(SetupState.CONNECTING)
        scope.launch {
            val connector = DirectConnector(this@SetupActivity)
            directConnector = connector

            // Initiate connection -- this will discover the pairing port
            connector.connect()

            // Wait for NeedsPairing or fast-path Connected
            val pairingState = withTimeoutOrNull(10_000L) {
                connector.state.first {
                    it is ConnectionState.NeedsPairing ||
                        it is ConnectionState.Connected ||
                        it is ConnectionState.Failed
                }
            }

            if (pairingState is ConnectionState.Connected) {
                registerConnectedBackend(connector, pairingState.serviceBinder)
                updateUi(SetupState.CONNECTED)
                return@launch
            }
            if (pairingState is ConnectionState.Failed) {
                descView.text = "Connection failed: ${pairingState.error}"
                updateUi(SetupState.FAILED)
                return@launch
            }

            // Submit the pairing code
            connector.submitPairingCode(code)

            val result = withTimeoutOrNull(15_000L) {
                connector.state.first {
                    it is ConnectionState.Connected || it is ConnectionState.Failed
                }
            }
            if (result is ConnectionState.Connected) {
                registerConnectedBackend(connector, result.serviceBinder)
                updateUi(SetupState.CONNECTED)
            } else {
                val error = (result as? ConnectionState.Failed)?.error ?: "Timed out"
                descView.text = "Connection failed: $error"
                updateUi(SetupState.FAILED)
            }
        }
    }

    private fun tryAutoReconnect() {
        val service = ai.opencyvis.App.agentService
        Log.i("SetupActivity", "Attempting auto-reconnect before showing pairing UI (service=${service != null})")
        titleView.text = getString(R.string.setup_pair_title)
        descView.text = getString(R.string.setup_reconnecting)
        progressBar.visibility = View.VISIBLE
        actionButton.isEnabled = false
        inputLayout.visibility = View.GONE
        secondaryButton.visibility = View.GONE
        otpLabel.visibility = View.GONE
        otpBox.visibility = View.GONE

        if (service == null) {
            Log.i("SetupActivity", "No AgentService, showing pairing UI")
            progressBar.visibility = View.GONE
            actionButton.isEnabled = true
            updateUi(SetupState.ADB_PAIR)
            return
        }

        scope.launch {
            val result = withTimeoutOrNull(15000L) {
                service.retryBackendDetection()
            }
            if (result == true) {
                Log.i("SetupActivity", "Auto-reconnect succeeded!")
                setResult(RESULT_BACKEND_READY)
                finish()
            } else {
                Log.i("SetupActivity", "Auto-reconnect failed, showing pairing UI")
                progressBar.visibility = View.GONE
                actionButton.isEnabled = true
                updateUi(SetupState.ADB_PAIR)
            }
        }
    }

    /**
     * Ask the user to exempt us from battery optimization before they leave for
     * Settings. On aggressive OEM ROMs (e.g. ColorOS HansManager) a backgrounded
     * foreground service still gets frozen ~6s after losing foreground, which
     * kills the mDNS discovery mid-pairing. Being on the Doze allow-list reduces
     * (though on ColorOS doesn't fully eliminate) that freezing. Best-effort,
     * one-shot per Activity instance — the real safety net is the discover-on-submit
     * path in AdbPairingService.
     */
    private fun ensureBackgroundAllowed() {
        if (requestedBatteryExemption) return
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        requestedBatteryExemption = true
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            Log.w("SetupActivity", "battery-optimization request unavailable: ${e.message}")
        }
    }

    private fun startPairingService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        ensureBackgroundAllowed()
        val intent = AdbPairingService.startIntent(this)
        try {
            startForegroundService(intent)
        } catch (e: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && e is ForegroundServiceStartNotAllowedException
            ) {
                startService(intent)
            }
        }
    }

    override fun onDestroy() {
        rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        scope.cancel()
        pairingPortJob?.cancel()
        shizukuConnectJob?.cancel()
        super.onDestroy()
    }
}
