package ai.opencyvis.action

import org.junit.Assert.*
import org.junit.Test
import android.content.Intent

/**
 * Tests for [AppLauncher]'s static lookup tables.
 *
 * Drives the real `APP_INTENTS` and `APP_PACKAGE_ALIASES` maps so regressions
 * in the production data (typos, accidental deletions, alias loss) fail the build.
 *
 * Note: pure-JVM unit tests can't invoke the Intent-building lambdas because
 * `android.content.Intent` is stubbed by the test runtime — its constructor
 * doesn't actually store the action. So these tests verify map *membership*
 * only; intent-resolution behavior lives in instrumented / UI tests.
 */
class AppLauncherTest {

    private fun hasIntent(appName: String): Boolean =
        AppLauncher.APP_INTENTS.containsKey(appName.lowercase().trim())

    private fun packagesFor(appName: String): List<String>? =
        AppLauncher.APP_PACKAGE_ALIASES[appName.lowercase().trim()]

    // ── APP_INTENTS: required English keys ────────────────────────────────

    @Test
    fun `APP_INTENTS contains expected English keys`() {
        val required = listOf(
            "settings", "browser", "camera", "phone",
            "contacts", "messages"
        )
        for (k in required) {
            assertTrue("APP_INTENTS missing key '$k'", hasIntent(k))
        }
    }

    // ── APP_INTENTS: required Chinese keys ────────────────────────────────

    @Test
    fun `APP_INTENTS contains expected Chinese keys`() {
        val required = listOf(
            "设置", "浏览器", "相机", "电话", "联系人", "短信"
        )
        for (k in required) {
            assertTrue("APP_INTENTS missing key '$k'", hasIntent(k))
        }
    }

    @Test
    fun `every English APP_INTENTS key has Chinese counterpart`() {
        // Pairs of (English, Chinese) that must both exist.
        val pairs = listOf(
            "settings" to "设置",
            "browser" to "浏览器",
            "camera" to "相机",
            "phone" to "电话",
            "contacts" to "联系人",
            "messages" to "短信"
        )
        for ((en, zh) in pairs) {
            assertTrue("missing English key '$en'", hasIntent(en))
            assertTrue("missing Chinese key '$zh'", hasIntent(zh))
        }
    }

    // ── APP_INTENTS: input normalisation ──────────────────────────────────

    @Test
    fun `intent key lookup is case insensitive`() {
        assertTrue(hasIntent("Settings"))
        assertTrue(hasIntent("CAMERA"))
    }

    @Test
    fun `intent key lookup trims whitespace`() {
        assertTrue(hasIntent("  settings  "))
    }

    @Test
    fun `unknown intent keys are absent`() {
        assertFalse(hasIntent("nonexistent_app"))
        assertFalse(hasIntent(""))
        assertFalse(hasIntent("随便什么"))
    }

    // ── APP_PACKAGE_ALIASES: 京东 / 京东秒送 regression ────────────────────
    //
    // Regression guard for the 04-29 bug where the agent failed to open JD
    // ("京东" / "京东秒送") because PackageManager label scan returns "JD.COM"
    // and never matches the Chinese alias.

    @Test
    fun `京东 resolves to JD package`() {
        assertEquals(listOf("com.jingdong.app.mall"), packagesFor("京东"))
    }

    @Test
    fun `京东秒送 resolves to JD package`() {
        assertEquals(listOf("com.jingdong.app.mall"), packagesFor("京东秒送"))
    }

    @Test
    fun `JD English aliases resolve to JD package`() {
        assertEquals(listOf("com.jingdong.app.mall"), packagesFor("jd"))
        assertEquals(listOf("com.jingdong.app.mall"), packagesFor("jd.com"))
        assertEquals(listOf("com.jingdong.app.mall"), packagesFor("JD"))  // case
    }

    // ── APP_PACKAGE_ALIASES: other popular Chinese apps ───────────────────

    @Test
    fun `popular Chinese apps have package aliases`() {
        val expected = listOf(
            "淘宝" to "com.taobao.taobao",
            "天猫" to "com.tmall.wireless",
            "拼多多" to "com.xunmeng.pinduoduo",
            "微信" to "com.tencent.mm",
            "支付宝" to "com.eg.android.AlipayGphone",
            "美团" to "com.sankuai.meituan",
            "饿了么" to "me.ele",
            "抖音" to "com.ss.android.ugc.aweme",
            "微博" to "com.sina.weibo",
            "小红书" to "com.xingin.xhs",
            "高德地图" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "知乎" to "com.zhihu.android",
            "哔哩哔哩" to "tv.danmaku.bili",
            "qq" to "com.tencent.mobileqq",
            "网易云音乐" to "com.netease.cloudmusic"
        )
        for ((alias, pkg) in expected) {
            val pkgs = packagesFor(alias)
            assertNotNull("missing alias '$alias'", pkgs)
            assertTrue(
                "alias '$alias' should resolve to '$pkg', got $pkgs",
                pkgs!!.contains(pkg)
            )
        }
    }

    @Test
    fun `package alias lookup is case insensitive`() {
        assertEquals(packagesFor("taobao"), packagesFor("Taobao"))
        assertEquals(packagesFor("wechat"), packagesFor("WeChat"))
    }

    @Test
    fun `unknown alias returns null`() {
        assertNull(packagesFor("not_a_real_app"))
        assertNull(packagesFor(""))
    }

    @Test
    fun `physical display launch flags create a normal new task`() {
        val flags = AppLauncher.launchFlagsForDisplay(0)

        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
        assertFalse(flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
        assertFalse(flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION != 0)
    }

    @Test
    fun `virtual display launch flags isolate task from recents`() {
        val flags = AppLauncher.launchFlagsForDisplay(2)

        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION != 0)
    }

    @Test
    fun `package alias values are non-empty and look like package names`() {
        val pkgPattern = Regex("^[a-z][a-z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        for ((alias, pkgs) in AppLauncher.APP_PACKAGE_ALIASES) {
            assertTrue("alias '$alias' has empty package list", pkgs.isNotEmpty())
            for (pkg in pkgs) {
                assertTrue(
                    "alias '$alias' has malformed package '$pkg'",
                    pkgPattern.matches(pkg)
                )
            }
        }
    }

    @Test
    fun `package alias keys are stored lowercase`() {
        // Lookup normalises with lowercase().trim(), so all keys must already
        // be lowercase or those entries become unreachable.
        for (key in AppLauncher.APP_PACKAGE_ALIASES.keys) {
            assertEquals("alias key '$key' must be lowercase", key.lowercase(), key)
            assertEquals("alias key '$key' must be trimmed", key.trim(), key)
        }
    }

    // ── Action.OpenApp integration ────────────────────────────────────────

    @Test
    fun `OpenApp action carries app name through unchanged`() {
        val a = Action.OpenApp(appName = "京东", thought = "open jd")
        assertEquals("open_app", a.typeName)
        assertEquals("京东", a.appName)
    }
}
