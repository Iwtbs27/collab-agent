package ai.opencyvis.action

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import ai.opencyvis.backend.PrivilegeBackend
import ai.opencyvis.backend.RemoteBackend

/**
 * Result of a launch attempt.
 * @param description Human-readable result (e.g. "Opened Settings")
 * @param packageName The package that was actually launched, or null on failure
 */
data class LaunchResult(val description: String, val packageName: String?)

/**
 * Launches apps by name using Intent-based approach.
 * Ports APP_INTENTS from Python actions.py.
 *
 * When using RemoteBackend on a non-zero displayId, launches are delegated
 * to the privileged service (shell uid) via startActivityOnDisplay() to avoid
 * Permission Denial from the app process not owning the VD.
 */
class AppLauncher(
    private val context: Context,
    private val displayId: Int = 0,
    private val backend: PrivilegeBackend? = null
) {

    companion object {
        private const val TAG = "AppLauncher"

        /**
         * Known app name -> package name. Used when implicit intents (like
         * Settings.ACTION_SETTINGS) don't carry a component/package field.
         */
        internal val KNOWN_APP_PACKAGES: Map<String, String> = mapOf(
            "settings" to "com.android.settings",
            "设置" to "com.android.settings",
            "browser" to "com.android.browser",
            "浏览器" to "com.android.browser",
            "camera" to "com.android.camera",
            "相机" to "com.android.camera",
            "phone" to "com.android.dialer",
            "电话" to "com.android.dialer",
            "contacts" to "com.android.contacts",
            "联系人" to "com.android.contacts",
            "messages" to "com.android.mms",
            "短信" to "com.android.mms",
        )

        /**
         * Well-known app name -> Intent mappings.
         * Ported from Python APP_INTENTS.
         *
         * `internal` so tests can assert against the real map instead of a duplicate.
         */
        internal val APP_INTENTS: Map<String, () -> Intent> = mapOf(
            "settings" to { Intent(Settings.ACTION_SETTINGS) },
            "设置" to { Intent(Settings.ACTION_SETTINGS) },
            "browser" to {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            },
            "浏览器" to {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            },
            "camera" to { Intent(MediaStore.ACTION_IMAGE_CAPTURE) },
            "相机" to { Intent(MediaStore.ACTION_IMAGE_CAPTURE) },
            "phone" to { Intent(Intent.ACTION_DIAL) },
            "电话" to { Intent(Intent.ACTION_DIAL) },
            "contacts" to {
                Intent(Intent.ACTION_VIEW).apply {
                    type = "vnd.android.cursor.dir/contact"
                }
            },
            "联系人" to {
                Intent(Intent.ACTION_VIEW).apply {
                    type = "vnd.android.cursor.dir/contact"
                }
            },
            "messages" to {
                Intent(Intent.ACTION_VIEW).apply {
                    type = "vnd.android-dir/mms-sms"
                }
            },
            "短信" to {
                Intent(Intent.ACTION_VIEW).apply {
                    type = "vnd.android-dir/mms-sms"
                }
            }
        )

        /**
         * Alias -> candidate package names for popular third-party apps whose
         * Chinese display name does NOT contain the Chinese alias the user types
         * (e.g. JD's English label is "JD.COM" but users say "京东").
         *
         * Each value is a list because some apps ship under multiple package IDs
         * (mainline vs lite vs regional builds). The launcher tries them in order.
         */
        internal val APP_PACKAGE_ALIASES: Map<String, List<String>> = mapOf(
            // 京东 (JD.com) — both the main app and the "秒送" instant-delivery
            // feature live inside the same package.
            "京东" to listOf("com.jingdong.app.mall"),
            "京东秒送" to listOf("com.jingdong.app.mall"),
            "jd" to listOf("com.jingdong.app.mall"),
            "jd.com" to listOf("com.jingdong.app.mall"),
            // 淘宝 / 天猫
            "淘宝" to listOf("com.taobao.taobao"),
            "taobao" to listOf("com.taobao.taobao"),
            "天猫" to listOf("com.tmall.wireless"),
            "tmall" to listOf("com.tmall.wireless"),
            // 拼多多
            "拼多多" to listOf("com.xunmeng.pinduoduo"),
            "pinduoduo" to listOf("com.xunmeng.pinduoduo"),
            "pdd" to listOf("com.xunmeng.pinduoduo"),
            // 微信 / WeChat
            "微信" to listOf("com.tencent.mm"),
            "wechat" to listOf("com.tencent.mm"),
            // 支付宝 / Alipay
            "支付宝" to listOf("com.eg.android.AlipayGphone"),
            "alipay" to listOf("com.eg.android.AlipayGphone"),
            // 美团 / 饿了么 (food delivery)
            "美团" to listOf("com.sankuai.meituan"),
            "meituan" to listOf("com.sankuai.meituan"),
            "饿了么" to listOf("me.ele"),
            "eleme" to listOf("me.ele"),
            // 抖音 / 快手 (short video)
            "抖音" to listOf("com.ss.android.ugc.aweme"),
            "douyin" to listOf("com.ss.android.ugc.aweme"),
            "tiktok" to listOf("com.zhiliaoapp.musically"),
            "快手" to listOf("com.smile.gifmaker", "com.kuaishou.nebula"),
            // 微博 / 小红书
            "微博" to listOf("com.sina.weibo"),
            "weibo" to listOf("com.sina.weibo"),
            "小红书" to listOf("com.xingin.xhs"),
            "xiaohongshu" to listOf("com.xingin.xhs"),
            "rednote" to listOf("com.xingin.xhs"),
            // 高德 / 百度地图
            "高德地图" to listOf("com.autonavi.minimap"),
            "高德" to listOf("com.autonavi.minimap"),
            "百度地图" to listOf("com.baidu.BaiduMap"),
            // 百度
            "百度" to listOf("com.baidu.searchbox"),
            "baidu" to listOf("com.baidu.searchbox"),
            // 知乎 / 哔哩哔哩
            "知乎" to listOf("com.zhihu.android"),
            "zhihu" to listOf("com.zhihu.android"),
            "哔哩哔哩" to listOf("tv.danmaku.bili"),
            "bilibili" to listOf("tv.danmaku.bili"),
            "b站" to listOf("tv.danmaku.bili"),
            // QQ / QQ音乐 / 网易云音乐
            "qq" to listOf("com.tencent.mobileqq"),
            "qq音乐" to listOf("com.tencent.qqmusic"),
            "网易云音乐" to listOf("com.netease.cloudmusic"),
            "网易云" to listOf("com.netease.cloudmusic"),
        )

        internal fun launchFlagsForDisplay(displayId: Int): Int {
            var flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (displayId != 0) {
                flags = flags or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
            return flags
        }
    }

    private fun launchOptionsBundle(): Bundle? {
        if (displayId == 0) return null
        return ActivityOptions.makeBasic().apply {
            launchDisplayId = displayId
        }.toBundle()
    }

    /**
     * Whether to route activity launch through the privileged backend.
     * True when using RemoteBackend on a non-zero display (shell uid owns the VD).
     */
    private val useRemoteLaunch: Boolean
        get() = displayId != 0 && backend is RemoteBackend

    /**
     * Start an activity, routing through the backend if needed for display ownership.
     * Returns true on success.
     */
    private fun startActivityForDisplay(intent: Intent): Boolean {
        if (useRemoteLaunch) {
            // Route through privileged service — shell uid can start on any display
            intent.addFlags(launchFlagsForDisplay(displayId))
            val intentUri = intent.toUri(Intent.URI_INTENT_SCHEME)
            Log.i(TAG, "Launching via backend.startActivityOnDisplay (display=$displayId)")
            return backend!!.startActivityOnDisplay(intentUri, displayId)
        }
        // Direct launch (system app or display 0)
        context.startActivity(intent, launchOptionsBundle())
        return true
    }

    /**
     * Launch an app by name. Returns a [LaunchResult] with description and package name.
     */
    fun launch(appName: String): LaunchResult {
        val key = appName.lowercase().trim()

        // 1. Known intent (settings, dialer, etc.)
        val intentFactory = APP_INTENTS[key]
        if (intentFactory != null) {
            return try {
                val intent = intentFactory().apply {
                    addFlags(launchFlagsForDisplay(displayId))
                }
                val ok = startActivityForDisplay(intent)
                if (!ok) {
                    return LaunchResult("Failed to open $appName on display $displayId (backend rejected)", null)
                }
                val pkg = intent.component?.packageName
                    ?: intent.`package`
                    ?: KNOWN_APP_PACKAGES[key]
                LaunchResult(
                    "Opened $appName" + if (displayId != 0) " on display $displayId" else "",
                    pkg
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch $appName via known intent", e)
                LaunchResult("Failed to open $appName: ${e.message}", null)
            }
        }

        // 2. Known package alias (Chinese name -> 3rd-party package).
        APP_PACKAGE_ALIASES[key]?.let { candidates ->
            launchByPackages(appName, candidates)?.let { return it }
        }

        // 3. Fallback: scan installed packages by label / package name.
        return launchByPackageSearch(appName)
    }

    /**
     * Try a list of candidate package names; launch the first one that's installed.
     * Returns null if none are installed (so the caller can fall through to label search).
     */
    private fun launchByPackages(appName: String, packages: List<String>): LaunchResult? {
        val pm = context.packageManager
        for (pkg in packages) {
            val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: continue
            return try {
                launchIntent.addFlags(launchFlagsForDisplay(displayId))
                val ok = startActivityForDisplay(launchIntent)
                if (!ok) {
                    return LaunchResult("Failed to open $appName on display $displayId (backend rejected)", null)
                }
                LaunchResult(
                    "Opened $appName ($pkg)" + if (displayId != 0) " on display $displayId" else "",
                    pkg
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch $pkg for alias $appName", e)
                LaunchResult("Failed to open $appName: ${e.message}", null)
            }
        }
        return null
    }

    private fun launchByPackageSearch(appName: String): LaunchResult {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchableApps = pm.queryIntentActivities(intent, 0)

        // 1. Exact label match
        for (ri in launchableApps) {
            val label = ri.loadLabel(pm).toString()
            if (label.equals(appName, ignoreCase = true)) {
                return launchResolveInfo(ri, appName, pm)
            }
        }

        // 2. Substring match on label or package name
        for (ri in launchableApps) {
            val label = ri.loadLabel(pm).toString()
            val pkg = ri.activityInfo.packageName
            if (label.contains(appName, ignoreCase = true) ||
                appName.contains(label, ignoreCase = true) ||
                pkg.contains(appName, ignoreCase = true)
            ) {
                return launchResolveInfo(ri, appName, pm)
            }
        }

        Log.w(TAG, "App '$appName' not found in known intents or packages")
        return LaunchResult(
            "Could not find app: $appName. Use list_apps(keyword) to search installed apps",
            null
        )
    }

    private fun launchResolveInfo(ri: ResolveInfo, appName: String, pm: PackageManager): LaunchResult {
        val pkg = ri.activityInfo.packageName
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
            ?: return LaunchResult("App $appName ($pkg) has no launch intent", null)
        return try {
            launchIntent.addFlags(launchFlagsForDisplay(displayId))
            val ok = startActivityForDisplay(launchIntent)
            if (!ok) {
                return LaunchResult("Failed to open $appName on display $displayId (backend rejected)", null)
            }
            LaunchResult(
                "Opened $appName ($pkg)" + if (displayId != 0) " on display $displayId" else "",
                pkg
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $pkg", e)
            LaunchResult("Failed to open $appName: ${e.message}", null)
        }
    }

    fun listApps(keyword: String? = null): List<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ownPackages = setOf(context.packageName, "ai.opencyvis")
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName !in ownPackages }
            .map { it.loadLabel(pm).toString() }
            .filter { label ->
                keyword.isNullOrBlank() ||
                label.contains(keyword, ignoreCase = true)
            }
            .distinct()
            .sorted()
    }
}
