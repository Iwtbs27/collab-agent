package ai.opencyvis.remoteim

import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.engine.AgentState
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class ImSessionRouterTest {

    private lateinit var agent: ImAgentBridge
    private lateinit var manager: ImChannelManager
    private lateinit var config: ConfigRepository
    private lateinit var pairingManager: ImPairingManager
    private lateinit var stringProvider: ImStringProvider
    private lateinit var router: ImSessionRouter
    private lateinit var fakeChannel: FakeImChannel

    @Before
    fun setup() {
        agent = mockk(relaxed = true)
        manager = mockk(relaxed = true)
        config = mockk(relaxed = true)
        pairingManager = mockk(relaxed = true)
        stringProvider = mockk(relaxed = true)

        every { config.telegramAllowedChatId } returns "12345"
        every { config.feishuAllowedOpenId } returns "ou_abc"
        every { agent.state } returns MutableStateFlow(AgentState.Idle("idle"))
        every { pairingManager.isPaired(any()) } returns true
        every { pairingManager.isLockedOut(any(), any()) } returns false
        every { stringProvider.stopped() } returns "已停止"
        every { stringProvider.busy() } returns "Agent 忙"
        every { stringProvider.pairSuccess() } returns "配对成功"
        every { stringProvider.pairExpired() } returns "配对码已过期"
        every { stringProvider.pairWrongCode() } returns "配对码无效"
        every { stringProvider.pairLockedOut() } returns "尝试次数过多"
        every { stringProvider.pairHint() } returns "请发送 /pair <配对码>"
        every { stringProvider.unpairSuccess() } returns "已解绑"
        every { stringProvider.alreadyPaired() } returns "已配对"
        every { stringProvider.groupRejected() } returns "暂不支持群聊"

        router = ImSessionRouter(agent, manager, config, pairingManager, stringProvider)
        fakeChannel = FakeImChannel("telegram")
    }

    @Test
    fun `status command returns agent status`() = runTest {
        every { agent.statusText() } returns "空闲"
        coEvery { manager.sendText(any(), any(), any()) } just Runs

        router.onInbound(fakeChannel, makeMsg("/status"))

        coVerify { manager.sendText("telegram", "999", "空闲") }
    }

    @Test
    fun `stop command stops agent`() = runTest {
        every { agent.stop() } just Runs
        coEvery { manager.sendText(any(), any(), any()) } just Runs

        router.onInbound(fakeChannel, makeMsg("/stop"))

        verify { agent.stop() }
        coVerify { manager.sendText("telegram", "999", "已停止") }
    }

    @Test
    fun `unpaired user gets pairing hint on first message`() = runTest {
        every { pairingManager.isPaired("telegram") } returns false
        every { config.telegramAllowedChatId } returns ""
        coEvery { manager.sendText(any(), any(), any()) } just Runs

        router.onInbound(fakeChannel, makeMsg("hello", senderId = "99999"))

        coVerify { manager.sendText("telegram", "999", "请发送 /pair <配对码>") }
    }

    @Test
    fun `rejects when agent is running`() = runTest {
        every { agent.state } returns MutableStateFlow(AgentState.Running(step = 1, thought = "thinking"))
        coEvery { manager.sendText(any(), any(), any()) } just Runs

        router.onInbound(fakeChannel, makeMsg("do something"))

        coVerify { manager.sendText("telegram", "999", match { it.contains("忙") }) }
    }

    @Test
    fun `normal command starts task`() = runTest {
        every { agent.bindActiveSession(any(), any()) } just Runs
        coEvery { agent.startTask(any()) } just Runs

        router.onInbound(fakeChannel, makeMsg("打开设置"))

        verify { agent.bindActiveSession("telegram", "999") }
        coVerify { agent.startTask("打开设置") }
    }

    @Test
    fun `unknown slash command is ignored`() = runTest {
        router.onInbound(fakeChannel, makeMsg("/unknown"))

        coVerify(exactly = 0) { manager.sendText(any(), any(), any()) }
    }

    @Test
    fun `command with @BotName suffix is normalized`() = runTest {
        every { agent.statusText() } returns "ok"
        coEvery { manager.sendText(any(), any(), any()) } just Runs

        router.onInbound(fakeChannel, makeMsg("/status@MyBot"))

        coVerify { manager.sendText("telegram", "999", "ok") }
    }

    @Test
    fun `pair with valid code succeeds`() = runTest {
        every { pairingManager.isPaired("telegram") } returns false
        every { pairingManager.attemptPairing("telegram", "12345", "847293") } returns PairingResult.SUCCESS
        coEvery { manager.sendText(any(), any(), any()) } just Runs

        router.onInbound(fakeChannel, makeMsg("/pair 847293"))

        coVerify { manager.sendText("telegram", "999", "配对成功") }
    }

    @Test
    fun `pair with wrong code is rejected`() = runTest {
        every { pairingManager.isPaired("telegram") } returns false
        every { pairingManager.attemptPairing("telegram", "12345", "000000") } returns PairingResult.WRONG_CODE
        coEvery { manager.sendText(any(), any(), any()) } just Runs

        router.onInbound(fakeChannel, makeMsg("/pair 000000"))

        coVerify { manager.sendText("telegram", "999", "配对码无效") }
    }

    @Test
    fun `pair with expired code is rejected`() = runTest {
        every { pairingManager.isPaired("telegram") } returns false
        every { pairingManager.attemptPairing("telegram", "12345", "847293") } returns PairingResult.EXPIRED
        coEvery { manager.sendText(any(), any(), any()) } just Runs

        router.onInbound(fakeChannel, makeMsg("/pair 847293"))

        coVerify { manager.sendText("telegram", "999", "配对码已过期") }
    }

    @Test
    fun `pair when locked out is rejected`() = runTest {
        every { pairingManager.isLockedOut("telegram", "12345") } returns true
        coEvery { manager.sendText(any(), any(), any()) } just Runs

        router.onInbound(fakeChannel, makeMsg("/pair 847293"))

        coVerify { manager.sendText("telegram", "999", "尝试次数过多") }
    }

    @Test
    fun `unpair clears whitelist`() = runTest {
        coEvery { manager.sendText(any(), any(), any()) } just Runs

        router.onInbound(fakeChannel, makeMsg("/unpair"))

        verify { pairingManager.unpair("telegram") }
        coVerify { manager.sendText("telegram", "999", "已解绑") }
    }

    @Test
    fun `group message is rejected`() = runTest {
        coEvery { manager.sendText(any(), any(), any()) } just Runs

        router.onInbound(fakeChannel, makeMsg("/pair 847293", chatType = "group"))

        coVerify { manager.sendText("telegram", "999", "暂不支持群聊") }
    }

    private fun makeMsg(
        text: String,
        senderId: String = "12345",
        chatType: String = "private"
    ) = ImInboundMessage(
        channel = "telegram",
        chatId = "999",
        senderId = senderId,
        text = text,
        chatType = chatType
    )
}
