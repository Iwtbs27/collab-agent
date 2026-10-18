package ai.opencyvis.config

import android.content.Context
import android.content.SharedPreferences

class ConfigRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "opencyvis_config"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_API_PROVIDER = "api_provider"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_SHOW_DEBUG_ROUTINES = "show_debug_routines"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_IM_REMOTE_ENABLED = "im_remote_enabled"
        private const val KEY_IM_SEND_STEP_SCREENSHOTS = "im_send_step_screenshots"
        private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_TELEGRAM_ALLOWED_CHAT_ID = "telegram_allowed_chat_id"
        private const val KEY_TELEGRAM_OFFSET = "telegram_offset"
        private const val KEY_FEISHU_APP_ID = "feishu_app_id"
        private const val KEY_FEISHU_APP_SECRET = "feishu_app_secret"
        private const val KEY_FEISHU_ALLOWED_OPEN_ID = "feishu_allowed_open_id"
        private const val KEY_FEISHU_TARGET_CHAT_ID = "feishu_target_chat_id"
        private const val KEY_BLACKLISTED_PACKAGES = "blacklisted_packages"

        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OLLAMA = "ollama"
        const val PROVIDER_KIMI = "kimi"
        const val DEFAULT_MODEL = "gpt-5.5"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-7-20250415"
        const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_OLLAMA_MODEL = "gemma4:26b-a4b-it-q4_K_M"
        const val DEFAULT_OLLAMA_BASE_URL = "https://localhost:11434"
        const val DEFAULT_KIMI_MODEL = "kimi-k2.6"
        const val DEFAULT_KIMI_BASE_URL = "https://api.moonshot.cn/v1"
        const val DEFAULT_MAX_STEPS = 100

        /** Returns true if model or url matches any known provider default.
         *  SettingsActivity uses this to decide whether to auto-fill defaults when
         *  the provider spinner changes. */
        fun isDefaultModelOrUrl(model: String, url: String): Boolean =
            model in listOf(DEFAULT_MODEL, DEFAULT_ANTHROPIC_MODEL, DEFAULT_OLLAMA_MODEL, DEFAULT_KIMI_MODEL) ||
            url in listOf(DEFAULT_BASE_URL, DEFAULT_ANTHROPIC_BASE_URL, DEFAULT_OLLAMA_BASE_URL, DEFAULT_KIMI_BASE_URL)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Migrate a preference stored as Int to String (for EditTextPreference compat). */
    private fun migrateIntToString(key: String, default: Int): Int {
        val value = prefs.getInt(key, default)
        prefs.edit().remove(key).putString(key, value.toString()).apply()
        return value
    }

    var activeProfileName: String
        get() = prefs.getString(KEY_ACTIVE_PROFILE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    fun applyProfile(provider: String, apiKey: String, model: String, baseUrl: String, profileName: String) {
        prefs.edit()
            .putString(KEY_API_PROVIDER, provider)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_MODEL, model)
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_ACTIVE_PROFILE, profileName)
            .apply()
    }

    var apiProvider: String
        get() = prefs.getString(KEY_API_PROVIDER, PROVIDER_OPENAI) ?: PROVIDER_OPENAI
        set(value) = prefs.edit().putString(KEY_API_PROVIDER, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var maxSteps: Int
        get() {
            return try {
                prefs.getString(KEY_MAX_STEPS, null)?.toIntOrNull()
                    ?: migrateIntToString(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
            } catch (_: ClassCastException) {
                migrateIntToString(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
            }
        }
        set(value) = prefs.edit().putString(KEY_MAX_STEPS, value.toString()).apply()

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()

    var showDebugRoutines: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DEBUG_ROUTINES, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DEBUG_ROUTINES, value).apply()

    var imRemoteEnabled: Boolean
        get() = prefs.getBoolean(KEY_IM_REMOTE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IM_REMOTE_ENABLED, value).apply()

    var imSendStepScreenshots: Boolean
        get() = prefs.getBoolean(KEY_IM_SEND_STEP_SCREENSHOTS, false)
        set(value) = prefs.edit().putBoolean(KEY_IM_SEND_STEP_SCREENSHOTS, value).apply()

    var telegramBotToken: String
        get() = prefs.getString(KEY_TELEGRAM_BOT_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TELEGRAM_BOT_TOKEN, value).apply()

    var telegramAllowedChatId: String
        get() = prefs.getString(KEY_TELEGRAM_ALLOWED_CHAT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TELEGRAM_ALLOWED_CHAT_ID, value).apply()

    var telegramOffset: Long
        get() = prefs.getLong(KEY_TELEGRAM_OFFSET, 0L)
        set(value) = prefs.edit().putLong(KEY_TELEGRAM_OFFSET, value).apply()

    var feishuAppId: String
        get() = prefs.getString(KEY_FEISHU_APP_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FEISHU_APP_ID, value).apply()

    var feishuAppSecret: String
        get() = prefs.getString(KEY_FEISHU_APP_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FEISHU_APP_SECRET, value).apply()

    var feishuAllowedOpenId: String
        get() = prefs.getString(KEY_FEISHU_ALLOWED_OPEN_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FEISHU_ALLOWED_OPEN_ID, value).apply()

    var feishuTargetChatId: String
        get() = prefs.getString(KEY_FEISHU_TARGET_CHAT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FEISHU_TARGET_CHAT_ID, value).apply()

    var feishuLastProcessedMsgTime: Long
        get() = prefs.getLong("feishu_last_processed_msg_time", 0L)
        set(value) = prefs.edit().putLong("feishu_last_processed_msg_time", value).apply()

    var blacklistedPackages: Set<String>
        get() = prefs.getString(KEY_BLACKLISTED_PACKAGES, "")!!
            .split(",").filter { it.isNotBlank() }.toSet()
        set(value) = prefs.edit().putString(KEY_BLACKLISTED_PACKAGES, value.joinToString(",")).apply()
}
