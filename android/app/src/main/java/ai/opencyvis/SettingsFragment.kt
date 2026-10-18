package ai.opencyvis

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import ai.opencyvis.config.ConfigDeepLink
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.config.ProfileRepository
import ai.opencyvis.config.ProviderProfile
import ai.opencyvis.remoteim.ImPairingManager
import ai.opencyvis.remoteim.RemoteImControlService
import ai.opencyvis.remoteim.feishu.FeishuRegistrationApi
import ai.opencyvis.ui.MemoryActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var config: ConfigRepository
    private lateinit var profileRepo: ProfileRepository
    private lateinit var pairingManager: ImPairingManager
    private val registrationApi = FeishuRegistrationApi()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var telegramCountdownJob: Job? = null
    private var qrPollingJob: Job? = null
    private var manualConfigVisible = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "opencyvis_config"
        preferenceManager.sharedPreferencesMode = android.content.Context.MODE_PRIVATE

        migrateIntKeys()
        setPreferencesFromResource(R.xml.settings_root, rootKey)

        config = ConfigRepository(requireContext())
        profileRepo = ProfileRepository(config, requireContext())
        profileRepo.ensureMigrated()

        if (App.imPairingManager == null) {
            App.imPairingManager = ImPairingManager(config)
        }
        pairingManager = App.imPairingManager!!

        setupProfilePreference()
        setupProviderPreference()
        setupTextSummaries()
        setupMaxStepsPreference()
        setupAutoSaveOnChange()
        setupMemoryPreference()
        setupBlacklistPreference()
        setupQrScanPreference()
        setupConfigQrPreferences()
        setupManualConfigToggle()
        setupPairingPreferences()
        setupUnpairPreferences()
        setupBackendPreferences()
        setupVersionInfo()
        refreshPairStatus("telegram")
        refreshPairStatus("feishu")

        // Poll pairing status so UI updates when pairing happens via IM
        scope.launch {
            while (true) {
                delay(2000)
                refreshPairStatus("telegram")
                refreshPairStatus("feishu")
            }
        }
    }

    /** Migrate Int-stored SharedPreferences values to String so EditTextPreference doesn't crash. */
    private fun migrateIntKeys() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val editor = prefs.edit()
        for (key in listOf("max_steps")) {
            try {
                val v = prefs.getInt(key, Int.MIN_VALUE)
                if (v != Int.MIN_VALUE) {
                    editor.remove(key).putString(key, v.toString())
                }
            } catch (_: ClassCastException) {
                // Already a String
            }
        }
        editor.apply()
    }

    // --- Provider auto-defaults ---

    private fun setupProviderPreference() {
        val providerPref = findPreference<ListPreference>("api_provider") ?: return
        providerPref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val provider = newValue as String
                val modelPref = findPreference<EditTextPreference>("model")
                val urlPref = findPreference<EditTextPreference>("base_url")
                val currentModel = modelPref?.text ?: ""
                val currentUrl = urlPref?.text ?: ""
                if (ConfigRepository.isDefaultModelOrUrl(currentModel, currentUrl)) {
                    val (defModel, defUrl) = defaultsFor(provider)
                    modelPref?.text = defModel
                    urlPref?.text = defUrl
                }
                val active = config.activeProfileName
                if (active.isNotEmpty()) {
                    val curModel = modelPref?.text ?: ""
                    val curUrl = urlPref?.text ?: ""
                    profileRepo.saveProfile(ProviderProfile(active, provider, config.apiKey, curModel, curUrl))
                    refreshProfileSummary()
                }
                true
            }
    }

    private fun defaultsFor(provider: String): Pair<String, String> = when (provider) {
        ConfigRepository.PROVIDER_ANTHROPIC ->
            ConfigRepository.DEFAULT_ANTHROPIC_MODEL to ConfigRepository.DEFAULT_ANTHROPIC_BASE_URL
        ConfigRepository.PROVIDER_OLLAMA ->
            ConfigRepository.DEFAULT_OLLAMA_MODEL to ConfigRepository.DEFAULT_OLLAMA_BASE_URL
        ConfigRepository.PROVIDER_KIMI ->
            ConfigRepository.DEFAULT_KIMI_MODEL to ConfigRepository.DEFAULT_KIMI_BASE_URL
        else -> ConfigRepository.DEFAULT_MODEL to ConfigRepository.DEFAULT_BASE_URL
    }

    // --- Provider Profiles ---

    private fun setupProfilePreference() {
        // Outer PreferenceScreen on main page — just update summary
        findPreference<Preference>("subscreen_profile")?.let { refreshProfileSummary(it) }

        // Inner Preference inside sub-page — clicking shows profile dialog
        val pref = findPreference<Preference>("provider_profile") ?: return
        refreshProfileSummary(pref)
        pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showProfileDialog()
            true
        }
    }

    private fun refreshProfileSummary(pref: Preference? = null) {
        val active = config.activeProfileName
        val summary = if (active.isNotEmpty()) {
            getString(R.string.pref_profile_summary_active, active)
        } else {
            getString(R.string.pref_profile_summary_none)
        }
        // Update whichever preference is visible in the current fragment
        (pref ?: findPreference<Preference>("provider_profile")
            ?: findPreference<Preference>("subscreen_profile"))?.let {
            it.summary = summary
        }
    }

    private fun showProfileDialog() {
        val profiles = profileRepo.listProfiles()
        val activeProfileName = config.activeProfileName

        val names = profiles.map { it.name }.toMutableList()
        val subtitles = profiles.map { "${it.provider} / ${it.model}" }.toMutableList()
        names.add(getString(R.string.profile_dialog_new))
        subtitles.add("")

        val items = names.mapIndexed { i, name ->
            if (i < profiles.size) {
                val check = if (name == activeProfileName) "● " else "○ "
                "$check$name\n   ${subtitles[i]}"
            } else {
                name
            }
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_dialog_title)
            .setItems(items) { _, which ->
                if (which < profiles.size) {
                    val selected = profiles[which]
                    if (selected.name == activeProfileName) {
                        showProfileOptionsDialog(selected.name)
                    } else {
                        profileRepo.switchTo(selected.name)
                        refreshAllProviderFields()
                        refreshProfileSummary()
                        Toast.makeText(requireContext(),
                            getString(R.string.profile_switched, selected.name),
                            Toast.LENGTH_SHORT).show()
                    }
                } else {
                    showSaveProfileDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSaveProfileDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.profile_name_hint)
            setText(profileRepo.generateProfileName(config.apiProvider, config.model))
            selectAll()
            setPadding(60, 40, 60, 20)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_save_as_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.profile_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (profileRepo.getProfile(name) != null) {
                    Toast.makeText(requireContext(),
                        getString(R.string.profile_name_exists, name),
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                profileRepo.saveCurrentAsProfile(name)
                refreshProfileSummary()
                Toast.makeText(requireContext(),
                    getString(R.string.profile_saved, name),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileOptionsDialog(profileName: String) {
        val options = arrayOf("Delete")
        AlertDialog.Builder(requireContext())
            .setTitle(profileName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        AlertDialog.Builder(requireContext())
                            .setMessage(getString(R.string.profile_delete_confirm, profileName))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                profileRepo.deleteProfile(profileName)
                                refreshProfileSummary()
                                refreshAllProviderFields()
                                Toast.makeText(requireContext(),
                                    getString(R.string.profile_deleted, profileName),
                                    Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun refreshAllProviderFields() {
        findPreference<ListPreference>("api_provider")?.value = config.apiProvider
        findPreference<EditTextPreference>("model")?.text = config.model
        findPreference<EditTextPreference>("base_url")?.text = config.baseUrl
        findPreference<EditTextPreference>("api_key")?.text = config.apiKey
    }

    private fun setupAutoSaveOnChange() {
        for (key in listOf("api_key", "model", "base_url")) {
            val pref = findPreference<EditTextPreference>(key) ?: continue
            val existing = pref.onPreferenceChangeListener
            pref.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { p, newValue ->
                    val result = existing?.onPreferenceChange(p, newValue) ?: true
                    if (result) {
                        val active = config.activeProfileName
                        if (active.isNotEmpty()) {
                            val profile = ProviderProfile(
                                name = active,
                                provider = config.apiProvider,
                                apiKey = if (key == "api_key") newValue as String else config.apiKey,
                                model = if (key == "model") newValue as String else config.model,
                                baseUrl = if (key == "base_url") newValue as String else config.baseUrl
                            )
                            profileRepo.saveProfile(profile)
                        }
                    }
                    result
                }
        }
    }

    // --- Text summaries (SimpleSummaryProvider for plain, custom mask for secrets) ---

    private fun setupTextSummaries() {
        val simpleProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        // Plain text fields — show value as summary
        for (key in listOf("model", "base_url", "feishu_app_id",
                           "telegram_allowed_chat_id", "feishu_allowed_open_id")) {
            findPreference<EditTextPreference>(key)?.summaryProvider = simpleProvider
        }

        // Sensitive fields — show masked value
        val maskProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val v = pref.text
            if (v.isNullOrBlank()) getString(R.string.pref_not_set)
            else if (v.length <= 8) "••••"
            else "${v.take(4)}••••${v.takeLast(4)}"
        }
        for (key in listOf("api_key", "telegram_bot_token", "feishu_app_secret")) {
            findPreference<EditTextPreference>(key)?.summaryProvider = maskProvider
        }
    }

    // --- Max Steps: EditTextPreference writes String, ConfigRepository reads Int ---

    private fun setupMaxStepsPreference() {
        val pref = findPreference<EditTextPreference>("max_steps") ?: return
        pref.text = config.maxSteps.toString()
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            val v = it.text?.toIntOrNull() ?: ConfigRepository.DEFAULT_MAX_STEPS
            getString(R.string.pref_max_steps_summary) + " ($v)"
        }

        pref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val input = (newValue as? String)?.trim()?.toIntOrNull()
                    ?: ConfigRepository.DEFAULT_MAX_STEPS
                val clamped = maxOf(1, input)
                config.maxSteps = clamped
                pref.text = clamped.toString()
                false
            }
    }

    // --- Manage Memory ---

    private fun setupMemoryPreference() {
        findPreference<Preference>("manage_memory")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                startActivity(Intent(requireContext(), MemoryActivity::class.java))
                true
            }

        findPreference<Preference>("scheduled_tasks")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                startActivity(Intent(requireContext(), ai.opencyvis.ui.ScheduleListActivity::class.java))
                true
            }
    }

    private fun setupBlacklistPreference() {
        val pref = findPreference<Preference>("manage_blacklist") ?: return
        updateBlacklistSummary(pref)
        pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showBlacklistDialog()
            true
        }
    }

    private fun updateBlacklistSummary(pref: Preference) {
        val current = config.blacklistedPackages
        pref.summary = if (current.isEmpty()) {
            getString(R.string.pref_app_blacklist_none)
        } else {
            val pm = requireContext().packageManager
            current.take(3).mapNotNull { pkg ->
                try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                catch (_: Exception) { pkg }
            }.joinToString(", ") + if (current.size > 3) " (+${current.size - 3})" else ""
        }
    }

    private fun showBlacklistDialog() {
        val pm = requireContext().packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchableApps = pm.queryIntentActivities(mainIntent, 0)
            .map { it.activityInfo }
            .filter { it.packageName != requireContext().packageName }
            .sortedBy { pm.getApplicationLabel(it.applicationInfo).toString().lowercase() }

        val labels = launchableApps.map { pm.getApplicationLabel(it.applicationInfo).toString() }
            .toTypedArray()
        val packages = launchableApps.map { it.packageName }
        val currentBlacklist = config.blacklistedPackages
        val checkedItems = packages.map { it in currentBlacklist }.toBooleanArray()

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pref_app_blacklist))
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = packages.filterIndexed { i, _ -> checkedItems[i] }.toSet()
                config.blacklistedPackages = selected
                updateBlacklistSummary(findPreference("manage_blacklist")!!)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- QR Scan Registration ---

    private fun setupQrScanPreference() {
        findPreference<Preference>("feishu_qr_scan")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                startQrRegistration()
                true
            }
    }

    private fun setupConfigQrPreferences() {
        findPreference<Preference>("share_config_qr")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                shareConfigQr()
                true
            }
        findPreference<Preference>("scan_config_qr")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                (activity as? SettingsActivity)?.launchQrScanner()
                true
            }
    }

    private fun shareConfigQr() {
        val uri = ConfigDeepLink.buildUri(
            config.apiProvider, config.apiKey, config.model, config.baseUrl, config.maxSteps,
            profile = config.activeProfileName.ifEmpty { null }
        )
        val bitmap = generateQrBitmap(uri, 512) ?: run {
            Toast.makeText(requireContext(), "Failed to generate QR code", Toast.LENGTH_SHORT).show()
            return
        }
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_qr_code, null)
        val qrImage = dialogView.findViewById<ImageView>(R.id.qr_image)
        val qrTimer = dialogView.findViewById<TextView>(R.id.qr_timer)
        val qrInstruction = dialogView.findViewById<TextView>(R.id.qr_instruction)
        qrImage.setImageBitmap(bitmap)
        qrInstruction?.visibility = View.GONE
        qrTimer?.text = "${config.apiProvider} / ${config.model}"

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.qr_share_title))
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun startQrRegistration() {
        scope.launch {
            // Step 1: init
            val initResult = registrationApi.init()
            val nonce = when (initResult) {
                is FeishuRegistrationApi.InitResult.Success -> initResult.nonce
                is FeishuRegistrationApi.InitResult.Error -> {
                    Toast.makeText(requireContext(),
                        getString(R.string.feishu_qr_error, initResult.message),
                        Toast.LENGTH_LONG).show()
                    return@launch
                }
            }

            // Step 2: begin
            val beginResult = registrationApi.begin(nonce)
            when (beginResult) {
                is FeishuRegistrationApi.BeginResult.Success -> {
                    showQrCodeDialog(beginResult.userCode, beginResult.deviceCode, beginResult.expiresIn)
                }
                is FeishuRegistrationApi.BeginResult.Error -> {
                    Toast.makeText(requireContext(),
                        getString(R.string.feishu_qr_error, beginResult.message),
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showQrCodeDialog(userCode: String, deviceCode: String, expiresIn: Int) {
        val qrUrl = "https://open.feishu.cn/page/launcher?user_code=$userCode"
        val bitmap = generateQrBitmap(qrUrl, 512) ?: run {
            Toast.makeText(requireContext(), R.string.feishu_qr_network_error, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_qr_code, null)
        val qrImage = dialogView.findViewById<ImageView>(R.id.qr_image)
        val timerText = dialogView.findViewById<TextView>(R.id.qr_timer)
        qrImage.setImageBitmap(bitmap)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.feishu_qr_dialog_title)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel) { d, _ ->
                qrPollingJob?.cancel()
                d.dismiss()
            }
            .setCancelable(false)
            .show()

        // Countdown timer
        val totalMs = expiresIn * 1000L
        object : CountDownTimer(totalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt()
                timerText.text = getString(R.string.feishu_qr_time_remaining, sec / 60, sec % 60)
            }
            override fun onFinish() {
                timerText.text = getString(R.string.feishu_qr_timeout)
                qrPollingJob?.cancel()
            }
        }.start()

        // Step 3: poll
        qrPollingJob?.cancel()
        qrPollingJob = scope.launch {
            var pollInterval = 5000L
            while (true) {
                delay(pollInterval)
                val pollResult = withContext(Dispatchers.IO) { registrationApi.poll(deviceCode) }
                when (pollResult) {
                    is FeishuRegistrationApi.PollResult.Success -> {
                        // Save credentials
                        config.feishuAppId = pollResult.clientId
                        config.feishuAppSecret = pollResult.clientSecret
                        // Auto-pair
                        if (pollResult.openId.isNotEmpty()) {
                            config.feishuAllowedOpenId = pollResult.openId
                        }
                        // Enable remote IM if not already on
                        if (!config.imRemoteEnabled) {
                            config.imRemoteEnabled = true
                        }
                        // Update UI
                        findPreference<EditTextPreference>("feishu_app_id")?.text = pollResult.clientId
                        findPreference<EditTextPreference>("feishu_app_secret")?.text = pollResult.clientSecret
                        refreshPairStatus("feishu")
                        dialog.dismiss()
                        Toast.makeText(requireContext(), R.string.feishu_qr_success, Toast.LENGTH_LONG).show()
                        // Restart service to pick up new credentials
                        RemoteImControlService.restart(requireContext())
                        return@launch
                    }
                    is FeishuRegistrationApi.PollResult.Pending -> {
                        // Still waiting, continue polling
                    }
                    is FeishuRegistrationApi.PollResult.SlowDown -> {
                        pollInterval = (pollInterval * 2).coerceAtMost(15000L)
                    }
                    is FeishuRegistrationApi.PollResult.Error -> {
                        dialog.dismiss()
                        Toast.makeText(requireContext(),
                            getString(R.string.feishu_qr_error, pollResult.message),
                            Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }
            }
        }
    }

    private fun generateQrBitmap(content: String, size: Int): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    // --- Manual Config Toggle ---

    private fun setupManualConfigToggle() {
        val togglePref = findPreference<Preference>("feishu_manual_config") ?: return
        val appIdPref = findPreference<EditTextPreference>("feishu_app_id") ?: return
        val appSecretPref = findPreference<EditTextPreference>("feishu_app_secret") ?: return

        togglePref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            manualConfigVisible = !manualConfigVisible
            appIdPref.isVisible = manualConfigVisible
            appSecretPref.isVisible = manualConfigVisible
            true
        }

        appIdPref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            view?.post { maybeStartFeishuService(newValue as? String, null) }
            true
        }
        appSecretPref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            view?.post { maybeStartFeishuService(null, newValue as? String) }
            true
        }
    }

    private fun maybeStartFeishuService(pendingAppId: String?, pendingSecret: String?) {
        val appId = pendingAppId ?: config.feishuAppId
        val secret = pendingSecret ?: config.feishuAppSecret
        if (appId.isNotBlank() && secret.isNotBlank()) {
            if (!config.imRemoteEnabled) {
                config.imRemoteEnabled = true
                findPreference<androidx.preference.SwitchPreference>("im_remote_enabled")?.isChecked = true
            }
            RemoteImControlService.restart(requireContext())
        }
        refreshPairStatus("feishu")
    }

    // --- Pairing: Generate Code ---

    private fun setupPairingPreferences() {
        findPreference<Preference>("telegram_generate_code")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                generateAndShowCode("telegram")
                true
            }
    }

    private fun generateAndShowCode(channelId: String) {
        val code = pairingManager.generateCode(channelId)
        val channelName = if (channelId == "telegram") "Telegram" else "Feishu"

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pairing_code_dialog_title)
            .setMessage(getString(R.string.pairing_code_dialog_message, code, channelName))
            .setPositiveButton(android.R.string.ok, null)
            .show()

        startCodeCountdown(channelId)
    }

    private fun startCodeCountdown(channelId: String) {
        telegramCountdownJob?.cancel()

        telegramCountdownJob = scope.launch {
            while (true) {
                delay(5_000)
                if (pairingManager.currentCode(channelId) == null) {
                    refreshPairStatus(channelId)
                    break
                }
            }
        }
    }

    // --- Pairing: Unpair ---

    private fun setupUnpairPreferences() {
        findPreference<Preference>("telegram_unpair")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                pairingManager.unpair("telegram")
                refreshPairStatus("telegram")
                Toast.makeText(requireContext(), R.string.im_unpair_success, Toast.LENGTH_SHORT)
                    .show()
                true
            }

        findPreference<Preference>("feishu_unpair")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                pairingManager.unpair("feishu")
                refreshPairStatus("feishu")
                Toast.makeText(requireContext(), R.string.im_unpair_success, Toast.LENGTH_SHORT)
                    .show()
                true
            }
    }

    // --- Pairing: Status display ---

    private fun refreshPairStatus(channelId: String) {
        val statusPref = findPreference<Preference>("${channelId}_pair_status") ?: return
        val unpairPref = findPreference<Preference>("${channelId}_unpair") ?: return

        val paired = pairingManager.isPaired(channelId)
        if (paired) {
            val id = when (channelId) {
                "telegram" -> config.telegramAllowedChatId
                "feishu" -> config.feishuAllowedOpenId
                else -> ""
            }
            statusPref.summary = getString(R.string.im_paired_with, id)
            unpairPref.isVisible = true
        } else {
            if (channelId == "feishu" && config.feishuAppId.isNotBlank()) {
                statusPref.summary = getString(R.string.feishu_pair_auto_hint)
            } else {
                statusPref.summary = getString(R.string.im_not_paired)
            }
            unpairPref.isVisible = false
        }
    }

    // --- Backend status and revoke ---

    private fun refreshBackendStatus() {
        val statusPref = findPreference<Preference>("backend_status") ?: return
        val revokePref = findPreference<Preference>("backend_revoke") ?: return

        val backendName = App.agentService?.activeBackendName
        statusPref.summary = when (backendName) {
            "system"     -> "System App (full capabilities)"
            "shizuku"    -> "Shizuku — tap to change"
            "adb-direct" -> "ADB Direct — tap to change"
            null, "none" -> "Not connected — tap to set up"
            else         -> backendName
        }
        revokePref.isVisible = (backendName != null && backendName != "none")
    }

    private fun setupBackendPreferences() {
        val statusPref = findPreference<Preference>("backend_status") ?: return
        val revokePref = findPreference<Preference>("backend_revoke") ?: return

        refreshBackendStatus()

        statusPref.setOnPreferenceClickListener {
            val name = App.agentService?.activeBackendName
            if (name == "system") {
                Toast.makeText(requireContext(), "System backend is fixed for this build", Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }
            val intent = android.content.Intent(requireContext(),
                ai.opencyvis.backend.SetupActivity::class.java)
            if (name != null && name != "none" && name != "system") {
                intent.putExtra(
                    ai.opencyvis.backend.SetupActivity.EXTRA_FORCE_METHOD_CHOOSER,
                    true
                )
            }
            startActivity(intent)
            true
        }

        revokePref.setOnPreferenceClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Revoke Access")
                .setMessage(
                    "Disconnect the privilege backend.\n\nTo fully revoke:\n" +
                    "• Shizuku: Revoke permission in Shizuku app\n" +
                    "• ADB: Disable wireless debugging in Developer Options"
                )
                .setPositiveButton("Revoke") { _, _ ->
                    App.agentService?.revokeBackend()
                    refreshBackendStatus()
                    Toast.makeText(requireContext(), "Backend disconnected", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    // --- Version info ---

    private fun setupVersionInfo() {
        val pref = findPreference<Preference>("version_info") ?: return
        try {
            val info = requireContext().packageManager.getPackageInfo(
                requireContext().packageName, 0
            )
            pref.summary = "v${info.versionName}"
        } catch (_: Exception) {
            pref.summary = "1.0"
        }
    }

    // --- Refresh from deep link import ---

    fun refreshFromConfig() {
        val maxStepsPref = findPreference<EditTextPreference>("max_steps")
        maxStepsPref?.text = config.maxSteps.toString()
        refreshPairStatus("telegram")
        refreshPairStatus("feishu")
        refreshAllProviderFields()
        refreshProfileSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshBackendStatus()
    }

    override fun onDestroy() {
        qrPollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
