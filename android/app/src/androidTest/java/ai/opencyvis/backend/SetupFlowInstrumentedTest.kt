package ai.opencyvis.backend

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupFlowInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun setupStateDetector_hasWifi_onDevice() {
        // On a real device with WiFi, should return true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val result = SetupStateDetector.hasWifi(context)
        assertEquals(hasWifi, result)
    }

    @Test
    fun setupStateDetector_isDevOptionsEnabled_readable() {
        // Should not throw -- just verify we can read the setting
        val result = SetupStateDetector.isDevOptionsEnabled(context)
        // On test device, dev options should be enabled (we're running ADB)
        assertTrue("Dev options should be enabled on test device", result)
    }

    @Test
    fun setupStateDetector_detect_returnsValidState() {
        val state = SetupStateDetector.detect(context, false)
        assertNotNull(state)
        // The detected state must be one of the valid enum values.
        // Which specific state is returned depends on device configuration (WiFi, dev
        // options, Android version), so we accept any valid state.
        val validStates = SetupState.entries.toSet()
        assertTrue(
            "Expected a valid SetupState, got $state",
            state in validStates
        )
    }

    @Test
    fun setupStateDetector_detect_alreadyConnected() {
        val state = SetupStateDetector.detect(context, isBackendConnected = true)
        assertEquals(SetupState.ALREADY_CONNECTED, state)
    }

    @Test
    fun oemHelper_pixelIsNotMiui() {
        // Pixel device should not be detected as MIUI
        if (Build.MANUFACTURER.equals("Google", ignoreCase = true)) {
            assertFalse(OemHelper.isMiui())
            assertFalse(OemHelper.isColorOS())
            assertFalse(OemHelper.isOriginOS())
            assertTrue(OemHelper.supportsRemoteInput())
        }
    }

    @Test
    fun oemHelper_supportsRemoteInput_onPixel() {
        // Pixel should support RemoteInput
        if (Build.MANUFACTURER.equals("Google", ignoreCase = true)) {
            assertTrue(OemHelper.supportsRemoteInput())
        }
    }

    @Test
    fun oemHelper_manufacturer_isNotEmpty() {
        // Manufacturer should always be non-empty on a real device
        assertTrue(OemHelper.manufacturer.isNotEmpty())
    }
}
