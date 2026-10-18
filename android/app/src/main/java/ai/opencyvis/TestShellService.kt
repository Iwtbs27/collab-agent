package ai.opencyvis

import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import ai.opencyvis.engine.AgentState
import ai.opencyvis.voice.VoiceInputTestBridge
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Shell command handler for `adb shell dumpsys opencyvis`.
 * Registered only on debuggable builds via [App.onCreate].
 *
 * Usage:
 *   adb shell dumpsys opencyvis                             → state JSON (default)
 *   adb shell dumpsys opencyvis state                      → state JSON
 *   adb shell dumpsys opencyvis start <instruction>        → start agent
 *   adb shell dumpsys opencyvis reset                      → stop engine
 *   adb shell dumpsys opencyvis inject ask_user_response <text>
 *   adb shell dumpsys opencyvis inject supplement <text>
 *   adb shell dumpsys opencyvis debug <command>            → debug commands
 *   adb shell dumpsys opencyvis simulate ask_user <question>
 *   adb shell dumpsys opencyvis simulate handoff <reason>
 *   adb shell dumpsys opencyvis voice <target> <text>
 *   adb shell dumpsys opencyvis help
 */
class TestShellService : Binder() {

    companion object {
        private const val TAG = "TestShellCmd"
        const val SERVICE_NAME = "opencyvis"
        private const val TIMEOUT_SECONDS = 5L

        fun register() {
            try {
                val sm = Class.forName("android.os.ServiceManager")
                val addService = sm.getMethod("addService", String::class.java, IBinder::class.java)
                addService.invoke(null, SERVICE_NAME, TestShellService())
                Log.i(TAG, "Registered shell service '$SERVICE_NAME'")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register shell service", e)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>?) {
        val cmd = args?.firstOrNull() ?: "state"

        when (cmd) {
            "state" -> handleState(pw)
            "start" -> handleStart(pw, args ?: emptyArray())
            "reset" -> runOnMain(pw) { it.stopAgent() }
            "inject" -> handleInject(pw, args ?: emptyArray())
            "debug" -> handleDebug(pw, args ?: emptyArray())
            "simulate" -> handleSimulate(pw, args ?: emptyArray())
            "voice" -> handleVoice(pw, args ?: emptyArray())
            "im" -> handleIm(pw, args ?: emptyArray())
            "help" -> handleHelp(pw)
            else -> {
                pw.println("Unknown command: $cmd")
                pw.println("Run 'dumpsys opencyvis help' for usage.")
            }
        }
    }

    private fun handleState(pw: PrintWriter) {
        val service = App.agentService
        if (service == null) {
            pw.println("""{"engine_state":"NOT_RUNNING","service_alive":false}""")
            return
        }

        val engine = service.engineFlow.value
        val state = engine?.state?.value

        val engineState: String
        val step: Int
        val thought: String?
        val extra: String?

        when (state) {
            is AgentState.Idle -> {
                engineState = "Idle"
                step = 0
                thought = state.resultMessage
                extra = null
            }
            is AgentState.Running -> {
                engineState = "Running"
                step = state.step
                thought = state.thought
                extra = null
            }
            is AgentState.Paused -> {
                engineState = "Paused"
                step = 0
                thought = null
                extra = null
            }
            is AgentState.Error -> {
                engineState = "Error"
                step = 0
                thought = null
                extra = state.message
            }
            is AgentState.WaitingForUser -> {
                engineState = "WaitingForUser"
                step = state.step
                thought = null
                extra = state.question
            }
            is AgentState.WaitingForHandoff -> {
                engineState = "WaitingForHandoff"
                step = state.step
                thought = null
                extra = state.reason
            }
            null -> {
                engineState = "NoEngine"
                step = 0
                thought = null
                extra = null
            }
        }

        val displayState = service.displayState.name

        val json = buildString {
            append("""{"engine_state":"$engineState"""")
            append(""","step":$step""")
            append(""","display_state":"$displayState"""")
            append(""","service_alive":true""")
            if (thought != null) append(""","thought":"${escapeJson(thought)}"""")
            if (extra != null) append(""","extra":"${escapeJson(extra)}"""")
            append("}")
        }
        pw.println(json)
    }

    private fun handleStart(pw: PrintWriter, args: Array<out String>) {
        val instruction = args.drop(1).joinToString(" ")
        if (instruction.isBlank()) {
            pw.println("ERROR: usage: dumpsys opencyvis start <instruction>")
            return
        }
        runOnMain(pw) { service ->
            Log.i(TAG, "start: $instruction")
            service.startAgent(instruction)
        }
    }

    private fun handleInject(pw: PrintWriter, args: Array<out String>) {
        val subCmd = args.getOrNull(1)
        val payload = args.drop(2).joinToString(" ")

        val service = App.agentService
        if (service == null) {
            pw.println("ERROR: AgentService not running")
            return
        }

        when (subCmd) {
            "ask_user_response" -> {
                if (payload.isBlank()) {
                    pw.println("ERROR: usage: dumpsys opencyvis inject ask_user_response <text>")
                    return
                }
                runOnMain(pw) { it.submitUserResponse(payload) }
            }
            "supplement" -> {
                if (payload.isBlank()) {
                    pw.println("ERROR: usage: dumpsys opencyvis inject supplement <text>")
                    return
                }
                runOnMain(pw) { it.submitUserSupplement(payload) }
            }
            else -> {
                pw.println("ERROR: unknown inject command: $subCmd")
                pw.println("Available: ask_user_response, supplement")
            }
        }
    }

    private fun handleDebug(pw: PrintWriter, args: Array<out String>) {
        val subCmd = args.getOrNull(1)
        if (subCmd.isNullOrBlank()) {
            pw.println("ERROR: usage: dumpsys opencyvis debug <command>")
            pw.println("Commands: running, view, takeover, return_control, stop, complete_handoff,")
            pw.println("          repeat_type_text_block, repeat_tap_block, repeat_tap_allow")
            return
        }

        runOnMain(pw) { service ->
            Log.i(TAG, "debug: $subCmd")
            when (subCmd) {
                "running" -> service.debugStartRunningAgent()
                "view" -> service.enterViewMode()
                "takeover" -> service.enterTakeoverMode()
                "return_control" -> service.exitTakeoverMode()
                "stop" -> service.stopAgent()
                "complete_handoff" -> service.completeUserHandoff("dumpsys_test")
                "repeat_type_text_block" -> service.debugRepeatGuardTypeText()
                "repeat_tap_block" -> service.debugRepeatGuardTapBlocked()
                "repeat_tap_allow" -> service.debugRepeatGuardTapAllowed()
                else -> throw IllegalArgumentException("Unknown debug command: $subCmd")
            }
        }
    }

    private fun handleSimulate(pw: PrintWriter, args: Array<out String>) {
        val subCmd = args.getOrNull(1)
        val payload = args.drop(2).joinToString(" ")

        if (subCmd.isNullOrBlank() || payload.isBlank()) {
            pw.println("ERROR: usage: dumpsys opencyvis simulate <ask_user|handoff> <text>")
            return
        }

        val service = App.agentService
        if (service == null) {
            pw.println("ERROR: AgentService not running")
            return
        }

        val engine = service.engineFlow.value
        if (engine == null) {
            pw.println("ERROR: no engine running")
            return
        }

        runOnMain(pw) {
            Log.i(TAG, "simulate: $subCmd $payload")
            when (subCmd) {
                "ask_user" -> engine.debugSimulateAskUser(payload)
                "handoff" -> engine.debugSimulateHandoff(payload)
                else -> throw IllegalArgumentException("Unknown simulate command: $subCmd")
            }
        }
    }

    private fun handleVoice(pw: PrintWriter, args: Array<out String>) {
        val target = args.getOrNull(1)
        val text = args.drop(2).joinToString(" ")

        if (target.isNullOrBlank() || text.isBlank()) {
            pw.println("ERROR: usage: dumpsys opencyvis voice <target> <text>")
            pw.println("Targets: command, control_answer, view_answer")
            return
        }

        val service = App.agentService
        if (service == null) {
            pw.println("ERROR: AgentService not running")
            return
        }

        runOnMain(pw) {
            Log.i(TAG, "voice: target=$target text=$text")
            service.sendBroadcast(Intent(VoiceInputTestBridge.ACTION).apply {
                setPackage(service.packageName)
                putExtra(VoiceInputTestBridge.EXTRA_TARGET, target)
                putExtra(VoiceInputTestBridge.EXTRA_RESULT, text)
            })
        }
    }

    /**
     * Dispatch an action to the main thread and block until complete.
     * Returns "OK" on success, "ERROR: ..." on failure or timeout.
     */
    private fun runOnMain(pw: PrintWriter, action: (AgentService) -> Unit) {
        val service = App.agentService
        if (service == null) {
            pw.println("ERROR: AgentService not running")
            return
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                action(service)
                pw.println("OK")
            } catch (e: Exception) {
                pw.println("ERROR: ${e.message}")
            }
            return
        }

        val latch = CountDownLatch(1)
        var error: String? = null

        mainHandler.post {
            try {
                action(service)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            pw.println("ERROR: timeout (${TIMEOUT_SECONDS}s)")
            return
        }

        if (error != null) {
            pw.println("ERROR: $error")
        } else {
            pw.println("OK")
        }
    }

    private fun handleIm(pw: PrintWriter, args: Array<out String>) {
        val subCmd = args.getOrNull(1)

        when (subCmd) {
            "state" -> handleImState(pw)
            "inbound" -> handleImInject(pw, args)
            "outbound" -> handleImOutbound(pw, args)
            "fake" -> handleImFake(pw, args)
            "set-pairing-code" -> handleImSetPairingCode(pw, args)
            else -> {
                pw.println("Unknown im command: $subCmd")
                pw.println("Commands: state, inbound, outbound, fake, set-pairing-code")
            }
        }
    }

    private fun handleImState(pw: PrintWriter) {
        val mgr = App.imChannelManager
        if (mgr == null) {
            pw.println("""{"running":false}""")
            return
        }
        pw.println("""{"running":true,"outbound_count":${mgr.recentOutbound().size}}""")
    }

    private fun handleImInject(pw: PrintWriter, args: Array<out String>) {
        val channel = args.getOrNull(2)
        val senderId = args.getOrNull(3)
        val chatId = args.getOrNull(4)
        val text = args.drop(5).joinToString(" ")

        if (channel.isNullOrBlank() || senderId.isNullOrBlank() || chatId.isNullOrBlank() || text.isBlank()) {
            pw.println("ERROR: usage: dumpsys opencyvis im inbound <channel> <senderId> <chatId> <text>")
            return
        }

        val mgr = App.imChannelManager
        if (mgr == null) {
            pw.println("ERROR: ImChannelManager not running")
            return
        }

        runOnMain(pw) {
            runBlocking {
                mgr.injectInbound(channel, senderId, chatId, text)
            }
        }
    }

    private fun handleImOutbound(pw: PrintWriter, args: Array<out String>) {
        val limit = args.getOrNull(2)?.toIntOrNull() ?: 16
        val mgr = App.imChannelManager
        if (mgr == null) {
            pw.println("[]")
            return
        }
        val records = mgr.recentOutbound(limit)
        val json = records.joinToString(",", "[", "]") { r ->
            """{"channel":"${r.channel}","chatId":"${r.chatId}","kind":"${r.kind}","text":"${escapeJson(r.text ?: "")}","photo_bytes":${r.photoBytes},"ts":${r.timestamp}}"""
        }
        pw.println(json)
    }

    private fun handleImFake(pw: PrintWriter, args: Array<out String>) {
        val mode = args.getOrNull(2)
        val mgr = App.imChannelManager
        if (mgr == null) {
            pw.println("ERROR: ImChannelManager not running")
            return
        }
        when (mode) {
            "on" -> {
                scope.launch {
                    mgr.replaceWithFake("telegram")
                    mgr.replaceWithFake("feishu")
                    pw.println("OK: fake mode enabled")
                }
            }
            "off" -> {
                pw.println("OK: fake mode requires service restart to restore real channels")
            }
            else -> pw.println("ERROR: usage: dumpsys opencyvis im fake on|off")
        }
    }

    private fun handleImSetPairingCode(pw: PrintWriter, args: Array<out String>) {
        val channelId = args.getOrNull(2)
        val code = args.getOrNull(3)

        if (channelId.isNullOrBlank()) {
            pw.println("ERROR: usage: dumpsys opencyvis im set-pairing-code <channel> [code]")
            return
        }

        val mgr = App.imPairingManager
        if (mgr == null) {
            pw.println("ERROR: ImPairingManager not running")
            return
        }

        val generatedCode = if (code.isNullOrBlank()) {
            mgr.generateCode(channelId)
        } else {
            // Inject a known code for testing via reflection
            val codesField = mgr.javaClass.getDeclaredField("codes")
            codesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val codes = codesField.get(mgr) as MutableMap<String, Pair<String, Long>>
            codes[channelId] = Pair(code, System.currentTimeMillis())
            code
        }
        pw.println("""{"channel":"$channelId","code":"$generatedCode"}""")
    }

    private fun handleHelp(pw: PrintWriter) {
        pw.println("Usage: dumpsys opencyvis <command>")
        pw.println()
        pw.println("Commands:")
        pw.println("  state                          Print engine state as JSON (default)")
        pw.println("  start <instruction>            Start agent with instruction")
        pw.println("  reset                          Stop the agent engine")
        pw.println("  inject <subcommand> <text>     Inject events into the agent")
        pw.println("    inject ask_user_response <text>")
        pw.println("    inject supplement <text>")
        pw.println("  debug <command>                Execute debug commands")
        pw.println("    debug running                Start debug running agent")
        pw.println("    debug view                   Enter VIEW mode")
        pw.println("    debug takeover               Enter TAKEOVER mode")
        pw.println("    debug return_control         Exit TAKEOVER → VIEW")
        pw.println("    debug stop                   Stop agent")
        pw.println("    debug complete_handoff       Complete user handoff")
        pw.println("    debug repeat_type_text_block Test repeat guard (type_text)")
        pw.println("    debug repeat_tap_block       Test repeat guard (tap blocked)")
        pw.println("    debug repeat_tap_allow       Test repeat guard (tap allowed)")
        pw.println("  simulate <type> <text>         Simulate agent states")
        pw.println("    simulate ask_user <question>")
        pw.println("    simulate handoff <reason>")
        pw.println("  voice <target> <text>          Inject voice input result")
        pw.println("    Targets: command, control_answer, view_answer")
        pw.println("  im <subcommand>               IM remote control commands")
        pw.println("    im state                     Show IM channel state")
        pw.println("    im inbound <ch> <sid> <cid> <text> Inject inbound message")
        pw.println("    im outbound [limit]          Show recent outbound records")
        pw.println("    im fake on|off               Toggle fake channel mode (debug only)")
        pw.println("    im set-pairing-code <ch> [code] Set pairing code (generates if no code)")
        pw.println("  help                           Show this help")
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
