package ai.opencyvis.remoteim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import ai.opencyvis.AgentService
import ai.opencyvis.App
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.db.ChatHistoryRepository
import ai.opencyvis.remoteim.feishu.FeishuChannel
import ai.opencyvis.remoteim.telegram.TelegramChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RemoteImControlService : Service() {

    companion object {
        private const val TAG = "RemoteImControlService"
        private const val CHANNEL_ID = "opencyvis_remote_im"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, RemoteImControlService::class.java)
            context.startForegroundService(intent)
        }

        /** Restart the service to pick up new channel config (e.g. after QR scan). */
        fun restart(context: Context) {
            context.stopService(Intent(context, RemoteImControlService::class.java))
            // Small delay to allow cleanup, then restart
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                start(context)
            }, 500)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var channelManager: ImChannelManager
    private lateinit var agentBridge: ImAgentBridge
    private lateinit var router: ImSessionRouter
    private lateinit var config: ConfigRepository
    private lateinit var stringProvider: AndroidImStringProvider

    private var agentService: AgentService? = null
    private val agentConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
            agentService = (binder as? AgentService.AgentBinder)?.getService()
            agentService?.let { svc ->
                agentBridge.bind(svc)
                Log.i(TAG, "Bound to AgentService")
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            agentBridge.unbind()
            agentService = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = ConfigRepository(this)

        if (!config.imRemoteEnabled) {
            Log.i(TAG, "Remote IM disabled, stopping")
            stopSelf()
            return
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        channelManager = ImChannelManager()
        stringProvider = AndroidImStringProvider(this)
        agentBridge = ImAgentBridge(channelManager, config, stringProvider)
        val pairingManager = App.imPairingManager ?: ImPairingManager(config).also {
            App.imPairingManager = it
        }
        router = ImSessionRouter(agentBridge, channelManager, config, pairingManager, stringProvider)
        channelManager.setRouter(router)
        App.imPairingManager = pairingManager

        // Register channels
        scope.launch {
            if (config.telegramBotToken.isNotBlank()) {
                channelManager.register(TelegramChannel(config))
            }
            if (config.feishuAppId.isNotBlank() && config.feishuAppSecret.isNotBlank()) {
                channelManager.register(FeishuChannel(config))
            }
            channelManager.startAll()
        }

        // Bind to AgentService
        bindService(
            Intent(this, AgentService::class.java),
            agentConnection,
            Context.BIND_AUTO_CREATE
        )

        // Expose for TestShellService
        App.imChannelManager = channelManager
        App.imPairingManager = pairingManager
    }

    override fun onDestroy() {
        scope.launch {
            channelManager.stopAll()
        }
        unbindService(agentConnection)
        App.imChannelManager = null
        App.imPairingManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Remote IM Control",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "IM remote control channel"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AIPhone Remote Control")
            .setContentText("IM channel running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
