package ai.opencyvis.backend

import android.os.Build

object OemHelper {

    val manufacturer: String get() = Build.MANUFACTURER ?: ""

    fun isMiui(mfr: String = manufacturer): Boolean =
        mfr.equals("Xiaomi", ignoreCase = true) || mfr.equals("Redmi", ignoreCase = true)

    fun isColorOS(mfr: String = manufacturer): Boolean =
        mfr.equals("OPPO", ignoreCase = true) ||
        mfr.equals("OnePlus", ignoreCase = true) ||
        mfr.equals("realme", ignoreCase = true)

    fun isOriginOS(mfr: String = manufacturer): Boolean =
        mfr.equals("vivo", ignoreCase = true)

    fun isSamsung(mfr: String = manufacturer): Boolean =
        mfr.equals("samsung", ignoreCase = true)

    fun isHuawei(mfr: String = manufacturer): Boolean =
        mfr.equals("HUAWEI", ignoreCase = true) || mfr.equals("HONOR", ignoreCase = true)

    fun supportsRemoteInput(): Boolean = !isMiui()

    fun getAboutPhoneIntent(): String? = when {
        isMiui() -> "android.settings.DEVICE_INFO_SETTINGS"
        else -> null
    }
}
