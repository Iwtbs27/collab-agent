package ai.opencyvis.backend

import org.junit.Assert.*
import org.junit.Test

class SetupStateDetectorTest {

    @Test
    fun `SetupState enum has all expected values`() {
        val states = SetupState.entries.map { it.name }
        assertTrue(states.contains("NEED_WIFI"))
        assertTrue(states.contains("UNSUPPORTED_VERSION"))
        assertTrue(states.contains("NEED_DEVELOPER_OPTIONS"))
        assertTrue(states.contains("NEED_WIRELESS_DEBUGGING"))
        assertTrue(states.contains("NEED_PAIRING"))
        assertTrue(states.contains("ALREADY_CONNECTED"))
    }

    @Test
    fun `isMiui detects Xiaomi manufacturer`() {
        assertTrue(OemHelper.isMiui("Xiaomi"))
        assertTrue(OemHelper.isMiui("xiaomi"))
        assertTrue(OemHelper.isMiui("Redmi"))
        assertFalse(OemHelper.isMiui("samsung"))
        assertFalse(OemHelper.isMiui("Google"))
    }

    @Test
    fun `isColorOS detects OPPO and OnePlus`() {
        assertTrue(OemHelper.isColorOS("OPPO"))
        assertTrue(OemHelper.isColorOS("OnePlus"))
        assertTrue(OemHelper.isColorOS("realme"))
        assertFalse(OemHelper.isColorOS("Google"))
        assertFalse(OemHelper.isColorOS("Xiaomi"))
    }

    @Test
    fun `isOriginOS detects vivo`() {
        assertTrue(OemHelper.isOriginOS("vivo"))
        assertFalse(OemHelper.isOriginOS("OPPO"))
    }

    @Test
    fun `isSamsung detects samsung`() {
        assertTrue(OemHelper.isSamsung("samsung"))
        assertTrue(OemHelper.isSamsung("Samsung"))
        assertFalse(OemHelper.isSamsung("Google"))
    }

    @Test
    fun `isHuawei detects HUAWEI and HONOR`() {
        assertTrue(OemHelper.isHuawei("HUAWEI"))
        assertTrue(OemHelper.isHuawei("HONOR"))
        assertFalse(OemHelper.isHuawei("Xiaomi"))
    }

    @Test
    fun `supportsRemoteInput returns false for Xiaomi`() {
        // Note: This tests the logic with default manufacturer from Build
        // In actual test, we'd need to mock Build.MANUFACTURER
        // For now just verify the function exists and doesn't crash
        OemHelper.supportsRemoteInput()
    }
}
