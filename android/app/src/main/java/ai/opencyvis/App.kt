package ai.opencyvis

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import ai.opencyvis.remoteim.ImChannelManager
import ai.opencyvis.remoteim.ImPairingManager
import ai.opencyvis.remoteim.RemoteImControlService
import ai.opencyvis.config.ConfigRepository

class App : Application() {

    companion object {
        private const val TAG = "OpencyvisApp"

        @Volatile
        var agentService: AgentService? = null

        @Volatile
        var imChannelManager: ImChannelManager? = null

        @Volatile
        var imPairingManager: ImPairingManager? = null
    }

    override fun onCreate() {
        super.onCreate()
        if (isDebuggableBuild()) {
            TestShellService.register()
        }

        // Start remote IM control if enabled
        val config = ConfigRepository(this)
        if (config.imRemoteEnabled) {
            RemoteImControlService.start(this)
        }
    }

    private fun isDebuggableBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
