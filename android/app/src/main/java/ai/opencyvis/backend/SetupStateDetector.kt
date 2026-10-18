package ai.opencyvis.backend

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings

enum class SetupState {
    NEED_WIFI,
    UNSUPPORTED_VERSION,
    NEED_DEVELOPER_OPTIONS,
    NEED_WIRELESS_DEBUGGING,
    NEED_PAIRING,
    ALREADY_CONNECTED
}

object SetupStateDetector {

    fun detect(context: Context, isBackendConnected: Boolean): SetupState {
        if (isBackendConnected) return SetupState.ALREADY_CONNECTED
        if (!hasWifi(context)) return SetupState.NEED_WIFI
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            return SetupState.UNSUPPORTED_VERSION
        if (!isDevOptionsEnabled(context)) return SetupState.NEED_DEVELOPER_OPTIONS
        if (!isWirelessDebuggingEnabled(context)) return SetupState.NEED_WIRELESS_DEBUGGING
        return SetupState.NEED_PAIRING
    }

    fun hasWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun isDevOptionsEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) == 1
    }

    fun isWirelessDebuggingEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            "adb_wifi_enabled", 0
        ) == 1
    }
}
