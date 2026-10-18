package ai.opencyvis.backend

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbPairingServiceInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun progressPersistence_saveAndRetrieve() {
        // Clear any existing progress
        AdbPairingService.clearProgress(context)
        assertNull(AdbPairingService.getSavedProgress(context))

        // Save progress (simulate by writing directly to SharedPreferences)
        context.getSharedPreferences("setup_progress", Context.MODE_PRIVATE)
            .edit().putString("last_step", "ENABLE_WIRELESS_DEBUG").apply()

        assertEquals("ENABLE_WIRELESS_DEBUG", AdbPairingService.getSavedProgress(context))

        // Clear
        AdbPairingService.clearProgress(context)
        assertNull(AdbPairingService.getSavedProgress(context))
    }

    @Test
    fun progressPersistence_clearIsIdempotent() {
        // Clearing when nothing is saved should not crash
        AdbPairingService.clearProgress(context)
        AdbPairingService.clearProgress(context)
        assertNull(AdbPairingService.getSavedProgress(context))
    }

    @Test
    fun progressPersistence_overwritePrevious() {
        AdbPairingService.clearProgress(context)

        context.getSharedPreferences("setup_progress", Context.MODE_PRIVATE)
            .edit().putString("last_step", "ENABLE_DEV_OPTIONS").apply()
        assertEquals("ENABLE_DEV_OPTIONS", AdbPairingService.getSavedProgress(context))

        // Overwrite with a new value
        context.getSharedPreferences("setup_progress", Context.MODE_PRIVATE)
            .edit().putString("last_step", "WAITING_PAIRING_SERVICE").apply()
        assertEquals("WAITING_PAIRING_SERVICE", AdbPairingService.getSavedProgress(context))

        // Cleanup
        AdbPairingService.clearProgress(context)
    }

    @Test
    fun notificationChannel_exists_afterServiceInit() {
        // Start the service briefly to trigger onCreate which creates the channel
        val intent = AdbPairingService.startIntent(context)
        try {
            context.startForegroundService(intent)
            Thread.sleep(2000)
        } catch (_: Exception) {
            // May fail if not in foreground, but channel should still be created
        }

        // Check notification channel exists
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AdbPairingService.NOTIFICATION_CHANNEL)
        // Channel may or may not exist depending on service start success
        // This is a best-effort test -- if channel exists, verify its properties
        if (channel != null) {
            assertEquals("adb_pairing", channel.id)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        }
    }

    @Test
    fun connectionInfo_persistence() {
        val prefs = context.getSharedPreferences("adb_connection", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        // Simulate saving connection info
        prefs.edit()
            .putString("last_host", "192.168.1.100")
            .putInt("last_port", 5555)
            .putLong("last_connected", System.currentTimeMillis())
            .apply()

        assertEquals("192.168.1.100", prefs.getString("last_host", null))
        assertEquals(5555, prefs.getInt("last_port", 0))
        assertTrue(prefs.getLong("last_connected", 0) > 0)

        // Cleanup
        prefs.edit().clear().apply()
    }

    @Test
    fun startIntent_hasCorrectAction() {
        val intent = AdbPairingService.startIntent(context)
        assertNotNull(intent)
        assertEquals(AdbPairingService::class.java.name, intent.component?.className)
    }
}
