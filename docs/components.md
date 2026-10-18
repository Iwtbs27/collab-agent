# AIPhone — 各模块详细说明

## `backend/` — 多后端权限抽象层

将设备特权操作（输入注入、截图、虚拟显示器、任务管理）抽象到 `PrivilegeBackend` 接口后面，使 AIPhone 支持多种特权来源：系统应用、Shizuku（shell uid）、无线 ADB 自配对等。

### 架构

两层抽象：
1. **`PrivilegeBackend`** — 消费者接口（InputInjector、ScreenCapture、VirtualDisplayManager 仅依赖此接口）
2. **`ServiceConnector`** — 启动和连接特权进程的方式（不同连接器使用不同的权限提升方法）

```
消费者 → PrivilegeBackend 接口
                │
        ┌───────┴───────┐
   SystemBackend    RemoteBackend
   (进程内反射)     (AIDL IPC 代理)
                        │
                  ServiceConnector
                        │
              ┌─────────┼──────────┐
        ShizukuConnector  (未来: RootConnector, AdbDirectConnector)
```

### `PrivilegeBackend.kt`

接口定义 + `BackendCapabilities` 数据类。

**接口方法：**
- `injectInputEvent(event, displayId, mode)` — 输入注入
- `injectKeyEvent(action, keyCode, ...)` — 键盘事件注入
- `captureScreen(displayId, maxWidth, quality)` — 截图（返回 JPEG 字节）
- `createVirtualDisplay(name, w, h, dpi, flags)` — 创建虚拟显示器
- `releaseVirtualDisplay()` / `setVirtualDisplaySurface(surface)` — VD 生命周期
- `setDisplayImePolicy(displayId, policy)` — 输入法策略
- `getTopTaskIdOnDisplay(displayId, pkg)` / `moveTaskToDisplay(taskId, targetDisplayId)` — 任务管理

**BackendCapabilities：**
- `fromProbeMask(mask, name)` — 从能力探测位掩码构造
- 预置常量：`SYSTEM`（全能力）、`NONE`（无能力）
- 能力标志：`CAP_INJECT_INPUT`、`CAP_CAPTURE_SCREEN`、`CAP_CAPTURE_SECURE`、`CAP_CREATE_VD`

### `SystemBackend.kt`

进程内实现，uid 1000（平台签名系统应用）。委托给 InputOps/CaptureOps/DisplayOps 执行反射调用。

### `RemoteBackend.kt`

通过 AIDL Binder 代理所有 `PrivilegeBackend` 调用到远程 `PrivilegedService`。
- 使用 `SharedMemory` 接收截图（避免 Binder 1MB 限制）
- 监听 `ServiceConnector.state` 流，在重连时自动更新 binder 和重新探测能力
- 所有调用捕获 `DeadObjectException`

### `ServiceConnector.kt`

连接器接口 + `ConnectionState` 密封类。

**ConnectionState 状态：**
- `Disconnected` → `Connecting` → `Connected(binder)` | `Failed(error)` | `NeedsPairing(port)`

### `ShizukuConnector.kt`

使用 Shizuku SDK 的 `bindUserService()` 在 shell uid (2000) 启动 `PrivilegedService`。
- 指数退避重连（2s→4s→8s→15s→30s）
- `linkToDeath` 监听远程进程死亡
- `isAvailable()` 检查 `Shizuku.pingBinder()` + 权限

### `BackendDetector.kt`

检测可用后端，返回 `DetectionResult`：
- `Ready(backend)` — 后端可用
- `SetupRequired(connectors)` — 需要用户设置
- `NoneAvailable` — 没有可用后端

优先级链：系统应用 (uid=1000) → Shizuku → DirectConnector(ADB 无线) → (未来: Root)

**主要方法：**
```kotlin
fun isSystemUid(uid: Int = Process.myUid()): Boolean
suspend fun detect(context: Context): DetectionResult
```

### `PrivilegedServiceMain.kt`

`app_process` 入口点（`object`，非 Activity/Service）。接受 `--token` 和 `--authority` 参数，创建 `PrivilegedService`，通过 `IActivityManager.getContentProviderExternal()` 或 `ActivityThread` fallback 将 Binder 发送给主应用，然后进入 `Looper.loop()`。注册 `binder.linkToDeath` 在主进程退出时自动 `System.exit(0)`。

文件路径：`app/src/main/java/ai/opencyvis/backend/PrivilegedServiceMain.kt`

---

### `BinderExchangeProvider.kt`

ContentProvider，接收来自提权进程的 IBinder。

- `prepare()` — 生成一次性 UUID token，重置 `CountDownLatch`
- `awaitBinder(timeoutMs)` — 阻塞等待 Binder 到达（最多 10 秒）
- `call("exchangeBinder", ...)` — 只接受来自 uid 2000/0/1000 的调用；token 匹配后 `latch.countDown()`

文件路径：`app/src/main/java/ai/opencyvis/backend/BinderExchangeProvider.kt`

---

### `FakeContext.kt`

为 shell uid `app_process` 进程提供最小化 `Context`（`object`，单例），用于创建 `DisplayManager`。参考 scrcpy 的 `FakeContext` 模式。

- `getSystemService(WINDOW_SERVICE)` — 通过 ServiceManager 反射获取 `IWindowManager`
- `getSystemService(USER_SERVICE)` — 通过 `Unsafe.allocateInstance()` 创建 `UserManager`（无需完整 Context 构造器）
- `getSystemService(DISPLAY_SERVICE)` — 反射调用 `DisplayManager` 构造器
- `getPackageName()` — 返回 `"com.android.shell"`

绕过 API 34+ `DisplayManager.createVirtualDisplay()` 对 `UserManager` 和 `WindowManager` 的依赖。

文件路径：`app/src/main/java/ai/opencyvis/backend/FakeContext.kt`

---

### `DirectConnector.kt`

无需 Shizuku，直接通过 Android 11+ 无线调试（Wireless Debugging）启动 `PrivilegedService`（shell uid 2000）。

**连接策略（按优先级）：**
1. SharedPreferences 中保存的上次连接 host:port → `AdbClient.connect()` 直连（证书不匹配时清除并回退 `NeedsPairing`）
2. mDNS 发现 `TLS_CONNECT` 端口 → 直接连接（已配对设备）
3. mDNS 发现 `TLS_PAIRING` 端口 → 发射 `NeedsPairing(port)`，等待用户输入 6 位码
4. 自动配对（测试）：读取 `filesDir/pairing_code.txt` 中的码完成自动配对

**主要方法：**
```kotlin
override fun connect()                   // 启动连接流程（协程，后台线程）
fun submitPairingCode(code: String)      // 用户输入 6 位码后调用
override fun disconnect()
```

**服务启动：** `launchService(client)` 通过 `AdbClient.shellCommand()` 执行 `app_process` 命令，`BinderExchangeProvider.awaitBinder(10s)` 收到 Binder 后更新 `ConnectionState.Connected`。

**连接信息持久化：** 成功连接后 host:port 保存到 `adb_connection` SharedPreferences，下次启动时跳过 mDNS 发现直接重连。

文件路径：`app/src/main/java/ai/opencyvis/backend/DirectConnector.kt`

---

### `AdbPairingService.kt`

前台服务（`foregroundServiceType=CONNECTED_DEVICE`），通过多步骤 Notification 引导用户完成 ADB 无线配对，支持打断后从上次步骤恢复（步骤状态持久化到 `setup_progress` SharedPreferences）。

**GuideStep 状态机：**

| 步骤 | 触发条件 | 通知内容 |
|------|----------|----------|
| `ENABLE_DEV_OPTIONS` | 未开启开发者选项 | 引导开启，提供"去设置"和"已完成"按钮 |
| `ENABLE_WIRELESS_DEBUG` | 未开启无线调试 | 引导开启，附深链到 Wireless Debugging 页 |
| `WAITING_PAIRING_SERVICE` | mDNS 搜索配对端口（30s 超时） | 搜索中通知 + 停止按钮 |
| `CODE_INPUT` | 发现配对端口 | RemoteInput 内联输入（MIUI 降级到 `createMiuiCodeInputNotification`） |
| `PAIRING` | 用户提交配对码 | 配对中通知 |
| `SUCCESS` | `AdbPairingClient.start()` 成功 | 成功通知；自动调用 `DirectConnector.connect()` 并更新 `ScreenCapture.backend` |
| `ERROR` | 配对失败 | 错误通知 + 重试按钮，错误分类（`AdbInvalidPairingCodeException` / 超时 / 网络错误） |

**主要方法：**
```kotlin
private fun onStart(): Notification          // 根据 SetupStateDetector.detect() 决定起始步骤
private fun onStepDone(): Notification?      // 用户确认完成当前步骤，推进到下一步
private fun onInput(code: String, port: Int) // 用户提交配对码
private fun connectAfterPairing(key: AdbKey) // 配对成功后自动建立连接并更新后端
```

**OEM 适配：** `OemHelper.supportsRemoteInput()` 为 false（MIUI）时，使用 `createMiuiCodeInputNotification()` 打开系统设置页替代内联输入。

文件路径：`app/src/main/java/ai/opencyvis/backend/AdbPairingService.kt`

---

### `SetupActivity.kt`

Setup wizard Activity，当 `BackendDetector` 返回 `SetupRequired` 时从 `ControlPanelActivity` 跳转。`setResult(RESULT_BACKEND_READY)` 通知调用方后端已就绪。

**SetupState 状态机：**
`CHOOSE_METHOD` → `SHIZUKU_CHECK` / `ADB_CHECK_OS` → `ADB_ENABLE_DEV` → `ADB_ENABLE_WIRELESS` → `ADB_PAIR` → `CONNECTING` → `CONNECTED` / `FAILED`

- 若检测到 Shizuku 安装，提供"Use Shizuku"和"Use Wireless ADB"两个选项
- 否则直接进入 ADB 无线配对流程
- `onStart()` 调用 `SetupStateDetector.detect()` 跳过已完成的步骤

文件路径：`app/src/main/java/ai/opencyvis/backend/SetupActivity.kt`

---

### `SetupStateDetector.kt`

无状态检测工具（`object`），在 Setup 流程开始时决定从哪步开始。

**检测顺序：** 已连接 → WiFi → Android 11+ → 开发者选项 → (NEED_PAIRING / NEED_WIRELESS_DEBUGGING)

```kotlin
fun detect(context: Context, isBackendConnected: Boolean): SetupState
fun hasWifi(context: Context): Boolean
fun isDevOptionsEnabled(context: Context): Boolean
```

`SetupState` enum：`NEED_WIFI` / `UNSUPPORTED_VERSION` / `NEED_DEVELOPER_OPTIONS` / `NEED_WIRELESS_DEBUGGING` / `NEED_PAIRING` / `ALREADY_CONNECTED`

文件路径：`app/src/main/java/ai/opencyvis/backend/SetupStateDetector.kt`

---

### `OemHelper.kt`

OEM 检测（`object`），基于 `Build.MANUFACTURER`。

```kotlin
fun isMiui(): Boolean           // Xiaomi / Redmi
fun isColorOS(): Boolean        // OPPO / OnePlus / realme
fun isOriginOS(): Boolean       // vivo
fun isSamsung(): Boolean
fun isHuawei(): Boolean
fun supportsRemoteInput(): Boolean  // !isMiui()：MIUI 的 RemoteInput 不可靠
fun getAboutPhoneIntent(): String?  // MIUI 专用"关于手机"Intent
```

用于 `AdbPairingService` 决定使用 RemoteInput 内联输入还是 MIUI 降级方案，以及 `SetupActivity` 显示 OEM 特定警告。

文件路径：`app/src/main/java/ai/opencyvis/backend/OemHelper.kt`

---

### `PairingDialogActivity.kt`

MIUI 降级方案：当 `OemHelper.supportsRemoteInput()` 为 false 时，`AdbPairingService` 打开此 Activity 而非 RemoteInput 通知，让用户在界面上输入 6 位配对码，提交后通过 Intent 发回 `AdbPairingService`。

文件路径：`app/src/main/java/ai/opencyvis/backend/PairingDialogActivity.kt`

---

### `IPrivilegedService.aidl`

uid 无关的 AIDL IPC 合约。同一接口无论服务运行在 shell (2000)、root (0) 还是 system (1000) uid。

### `PrivilegedService.kt`

实现 `IPrivilegedService.Stub`，在提权进程中运行。
- 调用者认证：首次调用者 uid 被记录，后续调用必须匹配
- 委托 InputOps/CaptureOps/DisplayOps 执行反射操作
- VD 创建使用 `DisplayManagerGlobal` 反射（远程进程无法使用 DisplayManager 公共 API）
- **Mirror VD**：主 VD 创建后自动创建半分辨率镜像 VD，`OnImageAvailableListener` 持续缓存帧
  - `mirrorReady` flag 防止 VD 初始化阶段缓存 OpenCyvis 自身帧
  - 500ms 节流避免过度 JPEG 编码影响 SurfaceView 渲染性能
  - Drain 线程（800ms）清理旧帧并移走 OpenCyvis task
- 截图优先级：Mirror 缓存 → 主 ImageReader → CaptureOps
- 截图通过 `SharedMemory` 返回（避免 1MB Binder 限制）

### `MirrorVdTest.kt`

Mirror VD 集成测试入口，通过 `app_process` 在 shell uid 下运行。
验证：VD 创建、背景/VIEW 模式截图、FLAG_SECURE 黑帧检测、task 跨 display 移动。
运行：`tests/run_mirror_vd_test.sh -s <serial>`

### 厂商代码（Vendored）：`moe/shizuku/manager/adb/`

Apache-2.0 协议，来自 Shizuku 项目。提供完整的 ADB 无线传输栈，被 `DirectConnector` 和 `AdbPairingService` 使用。无需安装 Shizuku 应用。

| 类 | 职责 |
|----|------|
| `AdbKey` | RSA 密钥对生成与管理，持久化到 `AdbKeyStore`（SharedPreferences） |
| `AdbKeyStore` / `PreferenceAdbKeyStore` | 密钥存储接口及其 SharedPreferences 实现 |
| `AdbMdns` | mDNS 服务发现（`_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`），基于 `NsdManager` |
| `AdbPairingClient` | SPAKE2+ 配对协议，完成密钥交换后 ADB 信任此应用 |
| `AdbClient` | TLS ADB 连接 + `shellCommand()` 执行 shell 命令（阻塞直到进程退出） |
| `AdbMessage` / `AdbProtocol` | ADB 协议帧格式 |
| `AdbException` / `AdbInvalidPairingCodeException` | ADB 错误类型 |
| `PairingContext` / `PairingPacketHeader` / `PeerInfo` | SPAKE2+ 配对上下文与数据包格式 |

文件路径：`app/src/main/java/moe/shizuku/manager/adb/`

---

### Ops 工具类
|---|---|
| `InputOps` | InputManager 反射注入（IInputManager via ServiceManager） |
| `CaptureOps` | 截图策略链：ScreenCapture API 36+ → ScreenCaptureInternal → SurfaceControl → screencap |
| `DisplayOps` | ActivityTaskManager 反射（任务管理）、IWindowManager 反射（IME 策略） |

SystemBackend 和 PrivilegedService 共享这些 Ops 类 — 相同反射代码，区别仅在于执行进程。

---

## `engine/` — 核心引擎

### `AgentEngine.kt`

observe-think-act 循环的核心实现（移植自 cli_demo.py）。

**关键字段：**
```kotlin
private val _state: MutableStateFlow<AgentState>
private val _stepResults: MutableSharedFlow<StepResult>
private val actionRepeatGuard: ActionRepeatGuard
@Volatile private var paused: Boolean
@Volatile private var userResponseDeferred: CompletableDeferred<String?>?
// messages 在 runAgentLoop 内为局部变量，对话历史最多 15 条
```

**主要方法：**
```kotlin
fun start(instruction: String)          // 开始执行，最大 max_steps 轮
fun pause() / resume() / stop()         // 流程控制
fun submitUserResponse(response: String)  // 提交用户回答（WaitingForUser 状态时）
fun destroy()                           // 释放资源
private suspend fun runAgentLoop(instruction: String)
private suspend fun captureVirtualDisplay(vdm, step: Int): String?  // VD截图（含FLAG_SECURE回退）
private fun isBitmapBlack(bitmap: Bitmap): Boolean                   // 检测全黑帧
private fun buildUserMessage(screenshotBase64: String, instruction: String, userAnswer: String? = null): Map<String, Any>
private fun stripImagesFromHistory(messages: MutableList<Map<String, Any>>)
```

**状态管理：**
- 暂停：`@Volatile paused` flag + `while(paused) delay(200)` 轮询
- 停止：先 `complete(null)` 解除用户输入等待，再 cancel Job
- 等待用户输入：`CompletableDeferred<String?>` 挂起协程，用户提交答案后 complete
- 对话历史：最多保留 15 条，超出时 `stripImagesFromHistory()` 删除老图片节省 token
- 防重：执行前调用 `ActionRepeatGuard.evaluate()`，拦截重复文本输入、屏幕无变化时的重复点击/Enter，并把反馈合入下一轮 user message

**截图路径：**
1. 优先使用 `ScreenCapture.captureBitmap(displayId)` (SurfaceFlinger, API 36+)
2. 若返回 null（API <36，ScreenCapture API 不可用）或全黑帧（FLAG_SECURE 窗口），回退到 `VirtualDisplayManager.captureViaImageReader()` 直接从 VD Surface 读取
3. `captureBitmap()` 内部：当 `displayId != 0`（虚拟屏）时，跳过只能截取物理屏的降级方法（`captureViaScreenCaptureInternal`/`captureViaOldSurfaceControl`/`captureViaCommand`），避免错误截取主屏画面
4. ImageReader 路径采用**监听器+缓存**机制（见 `VirtualDisplayManager`），即使 VD 静止画面（producer 没有新帧）也能拿到最新一帧；引擎再用 `VD_CAPTURE_MAX_ATTEMPTS=8` × `VD_CAPTURE_RETRY_DELAY_MS=300ms` 兜底活动转场抖动

---

### `ActionRepeatGuard.kt`

通用的高风险动作防重层，位于 `ActionExecutor.execute()` 之前。它不识别业务文案，不针对具体 App 写规则，只根据动作类型、坐标和粗粒度截图变化判断是否需要拦截。

**拦截规则：**
- 连续相同 `TypeText(text)`：直接拦截，避免输入框内容累积成重复文本
- 连续相近 `Tap`/`LongPress`：仅当当前截图和上次执行该动作前的截图相似时拦截
- 连续 `KeyEvent("enter")`：仅当截图相似时拦截
- `OpenApp`/`Swipe` 默认不拦截，并会记录为新的最近动作
- `Wait` 默认不拦截，但不会重置最近动作，避免 `type_text -> wait -> type_text` 继续重复输入

被拦截时，Agent 不执行动作，而是 emit 一个失败的 `StepResult`，并将反馈写入下一轮 prompt，提示模型换策略或在可能存在系统确认/权限/安装等 VD 看不到的弹窗时使用 `ask_user`。

---

### `ScreenFingerprint.kt`

截图粗指纹，用于辅助判断“屏幕是否有明显变化”。实现为 8x8 average hash，并用 Hamming distance 比较相似度。

它不会等待屏幕稳定，也不会要求像素完全一致；只对相邻步骤已有截图做轻量比较。因此光标闪烁、小动画、压缩噪声通常不会触发误判，也不会导致 agent 卡在等待中。

---

### `AgentState.kt`

状态机（sealed class）：

```kotlin
object Idle : AgentState()
data class Running(val step: Int, val thought: String) : AgentState()
object Paused : AgentState()
data class Error(val message: String) : AgentState()
data class WaitingForUser(val question: String, val step: Int) : AgentState()
```

---

### `StepResult.kt`

单步执行结果（data class）：

```kotlin
data class StepResult(
    val step: Int,
    val actionType: String,
    val thought: String,
    val success: Boolean,
    val detail: String,
    val durationMs: Long,
    val completed: Boolean,
    val debugInfo: String? = null
)
```

---

## `llm/` — LLM 集成

### `LLMClientInterface.kt`

LLM 客户端公共接口，支持多 provider：

```kotlin
interface LLMClientInterface {
    suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?>
    fun shutdown()
}
```

---

### `LLMClient.kt`

OkHttp-based HTTP 客户端，调用 OpenAI 兼容的 `/chat/completions` API。实现 `LLMClientInterface`。

**技术细节：**
- **Streaming SSE：** 解析 `function_call_arguments` 增量 chunks，不等 `response.completed`
- **重试逻辑：** 3 次重试，指数退避（1s → 2s → 4s）
- **连接池：** `ConnectionPool(2 connections, 5 min)`
- **超时：** connect 30s，read 90s（适配流式）
- **平台签名：** 使用平台密钥访问 API

**主要方法：**
```kotlin
suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?>
private fun parseSSEStream(response: okhttp3.Response): Map<String, Any?>?
private fun parseFunctionCallArgs(argsStr: String): Map<String, Any?>?
private fun convertMessagesToJson(messages: List<Map<String, Any>>): JSONArray
fun shutdown()  // 关闭 OkHttp dispatcher
```

---

### `AnthropicClient.kt`

OkHttp-based HTTP 客户端，调用 Anthropic Messages API (`/v1/messages`)。实现 `LLMClientInterface`。

**与 LLMClient 的协议差异：**
- **Endpoint：** `/v1/messages`（非 `/chat/completions`）
- **System prompt：** 顶级 `system` 字段（非 messages 中的 role=system）
- **Tool schema：** `input_schema`（非 `parameters`），无 `type: "function"` 包装
- **tool_choice：** `{"type": "any"}`（对象格式）
- **Image：** `{type: "image", source: {type: "base64", media_type, data}}`（自动从 OpenAI 的 `image_url` data URI 格式转换）
- **SSE 流式：** 解析 `content_block_delta` / `input_json_delta` / `partial_json`

**技术细节：** 与 LLMClient 相同的重试逻辑、连接池、超时配置。

**主要方法：**
```kotlin
override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?>
private fun buildRequestPayload(messages, maxTokens): JSONObject
private fun convertContentBlock(block: Map<*, *>): JSONObject  // image_url → image 转换
private fun parseSSEStream(response): Map<String, Any?>?
override fun shutdown()
```

---

### `OllamaClient.kt`

OkHttp-based HTTP 客户端，调用 Ollama 原生 `/api/chat` API。实现 `LLMClientInterface`。

**与 LLMClient/AnthropicClient 的协议差异：**
- **Endpoint：** `/api/chat`（非 `/chat/completions`）
- **Image：** `message.images = [base64_string]`（非 content block 中的 `image_url`）
- **Non-streaming：** `stream: false`，直接解析完整 JSON 响应（本地推理无网络延迟优势）
- **tool_calls 格式：** `arguments` 是对象（非 JSON 字符串）
- **无需 Authorization header**
- **超时：** connect 10s，read 300s（本地推理较慢）
- **重试：** 2 次（本地失败通常非瞬态问题）

**消息转换：** 从 app 的 OpenAI 格式自动转换：
- system message → role=system（直接传递）
- user `[image_url, text]` → 提取 base64 到 `message.images`，文本到 `message.content`
- assistant message → 纯文本传递

**Thinking 模式：** 发送 `"think": false` 禁用思考链（CoT），避免 Qwen 3.5 等模型浪费大量 token 在 thinking 上。

**Fallback：** 本地模型偶尔不走 tool_calls 而是在 content 中返回 JSON 文本，通过 `ResponseParser.extractJsonFromText()` 兜底解析。Qwen 3.5 大部分步骤都走 text fallback。

**模型性能对比：** 详见 [model-benchmarks.md](model-benchmarks.md)

**主要方法：**
```kotlin
override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?>
private fun buildPayload(messages): JSONObject  // OpenAI → Ollama 格式转换, 含 think:false
private fun parseResponse(bodyStr: String): Map<String, Any?>?  // tool_calls + text fallback
override fun shutdown()
```

---

### `ResponseParser.kt`

多格式兼容的响应解析，解析顺序：
1. `output[i].type == "function_call"` → 解析 arguments JSON（主路径）
2. `output[i].type == "message"` → 从 text 中提取 JSON
3. `choices[0].message.tool_calls` → 旧格式兜底
4. `extractJsonFromText()` → 正则从 Markdown 代码块提取

---

### `ToolSchema.kt`

定义 `phone_action` function tool（支持 OpenAI 和 Anthropic 两种格式）：

- `toolsArray()` — OpenAI 格式（`type: "function"` + `parameters`）
- `anthropicToolsArray()` — Anthropic 格式（顶级 `name/description/input_schema`）

**`action_type` 枚举：** `tap`, `open_app`, `swipe`, `key_event`, `type_text`, `wait`, `finish`, `fail`, `ask_user`, `note`, `save_routine`

**参数：**
| 参数 | 类型 | 说明 |
|------|------|------|
| `thought` | string (required) | LLM 的思考过程 |
| `action_type` | string (required) | 动作类型（enum） |
| `x`, `y` | int (0-1000) | 归一化坐标 |
| `app_name` | string | open_app 使用 |
| `direction` | string | swipe 方向：up/down/left/right |
| `key` | string | key_event：back/home/enter/recent |
| `text` | string | type_text 内容 |
| `reason` | string | fail 原因 |
| `question` | string | ask_user 时向用户提问的问题 |
| `note` | string | 记录笔记，格式 `"key: value"`。可与任何 action 同时使用（side-effect），也可单独用 `action_type=note` |
| `routine_name` | string | save_routine: 例行任务名称 |
| `routine_icon` | string | save_routine: emoji 图标 |
| `schedule_type` | string | save_routine: 定时类型 `time`/`interval`/`geofence` |
| `schedule_time` | string | save_routine: 时间 HH:MM 格式 |
| `schedule_repeat` | string | save_routine: 重复模式 `daily`/`weekdays`/自定义 |
| `suggested_routine_name` | string | finish 时 LLM 建议的 routine 名称（可选） |
| `suggested_routine_icon` | string | finish 时 LLM 建议的 routine 图标（可选） |
| `completed` | boolean (required) | 任务完成信号 |

---

## `action/` — 动作执行

### `Action.kt`

所有可执行动作（sealed class）：

```kotlin
data class Tap(val x: Int, val y: Int, ...) : Action("tap", ...)
data class LongPress(val x: Int, val y: Int, ...) : Action("long_press", ...)
data class OpenApp(val appName: String, ...) : Action("open_app", ...)
data class Swipe(val direction: String, ...) : Action("swipe", ...)
data class KeyEvent(val key: String, ...) : Action("key_event", ...)
data class TypeText(val text: String, ...) : Action("type_text", ...)
data class Wait(...) : Action("wait", ...)
data class Finish(...) : Action("finish", ...)
data class Fail(val reason: String, ...) : Action("fail", ...)
data class AskUser(val question: String, ...) : Action("ask_user", ...)
data class Note(val note: String, ...) : Action("note", ...)
```

`companion object fun fromMap(map: Map<String, Any?>): Action` — 从 LLM 响应解析

---

### `ActionExecutor.kt`

将 Action 路由到实际执行器。

**构造参数：**
- `displayId: Int` — 0 = 物理屏幕，>0 = 虚拟显示器
- `displaySize: Point?` — 虚拟显示器分辨率（用于坐标映射）

**主要方法：**
```kotlin
suspend fun execute(action: Action, step: Int): StepResult
```

**Swipe 方向预设：** up/down/left/right → 归一化坐标范围

---

### `AppLauncher.kt`

Intent-based App 启动，支持中英文 App 名。三段式查找：**已知 Intent → 包名别名 → label/包名扫描**。

**已知 Intent 映射（中英文）：**
| App | Intent |
|-----|--------|
| settings / 设置 | `Settings.ACTION_SETTINGS` |
| browser / 浏览器 | `Intent.ACTION_VIEW (google.com)` |
| camera / 相机 | `MediaStore.ACTION_IMAGE_CAPTURE` |
| phone / 电话 | `Intent.ACTION_DIAL` |
| contacts / 联系人 | — |
| messages / 短信 | — |

**包名别名表 `APP_PACKAGE_ALIASES`：** 中文别名 → 候选包名列表，覆盖 ~20 个常用第三方 App（京东 / 京东秒送 / 淘宝 / 天猫 / 拼多多 / 微信 / 支付宝 / 美团 / 饿了么 / 抖音 / 快手 / 微博 / 小红书 / 高德 / 百度地图 / 知乎 / B 站 / QQ / 网易云音乐 / 豆包 等）。

> **为什么需要别名表：** `PackageManager.getApplicationLabel()` 返回的是 App 当前 locale 的 label，通常是英文（如「JD.COM」），不会包含用户口语中的中文名（「京东」）。直接 label 子串匹配会落空，必须先按已知 package name 试一遍 `getLaunchIntentForPackage()`。

**兜底：** `PackageManager` 按 app label 或 package name 子串搜索

**虚拟显示器支持：** `ActivityOptions.makeBasic().setLaunchDisplayId(displayId)`；非 0 display 启动统一加 `NEW_TASK | MULTIPLE_TASK | EXCLUDE_FROM_RECENTS | NO_USER_ACTION`，避免 VD 内被控 task 暴露为用户可从 Recents 直接切入的入口。

**测试：** `AppLauncherTest`（JVM 单元测试）直接驱动 `internal val APP_INTENTS` / `APP_PACKAGE_ALIASES` / `launchFlagsForDisplay()`，覆盖关键中英文键、京东别名回归、包名格式合法性、key 必须 lowercase+trim、VD 启动 flags 等不变量。

---

## `overlay/` — 后台运行悬浮提醒

### `OverlayWindow.kt`

精简的悬浮窗口，承担「agent 后台运行时提醒用户」的单一职责。两态：

- **chat-head**（48dp 圆点，颜色随状态变化：绿=Running、琥珀=WaitingForUser、红=Error、灰=Idle）
- **slim pill**（任务名 + `step N / max` + ✕ 停止按钮）。点圆点展开，点药丸主体调用 `onReturnToApp()` 拉回 AIPhone

`Callback` 仅两个方法：`onReturnToApp` / `onStop`。所有指令输入、ask_user 作答都在 ControlPanelActivity / ViewActivity 中完成，悬浮窗不承担。

**生命周期 API（单一职责，全部幂等）：**
```kotlin
fun prepare()                       // 创建并初始化两个 view（不 attach），仅持有 LayoutParams
fun attach()                        // addView 到 WindowManager；已 attach 时 no-op
fun detach()                        // removeView；未 attach 时 no-op
fun setExpanded(expanded: Boolean)  // 在 chat-head ↔ pill 之间切换（attach 后才有视觉效果）
fun updateState(state: AgentState)  // 缓存状态、刷新文案/颜色，并按状态调 setExpanded(...)
```

> **可见性只走 attach/detach；展开收起只走 setExpanded。** `updateState()` 不再决定显隐，避免 onCreate 时序导致的 add/remove 闪烁与"View already added" 异常。

### `OverlayService.kt`

托管 `OverlayWindow`，绑定 `AgentService` 收集状态与 engine 引用。

**可见规则：单点判定 `evaluateVisibility()`**
- 输入：`isForeground`（来自 `ProcessLifecycleOwner`）+ `isActive`（agent 状态为 Running 或 WaitingForUser）
- 输出：`shouldShow = !isForeground && isActive` → 调 `attach()` 或 `detach()`
- 所有触发点（前后台切换、状态变化、engine 重建）都汇集到这一个函数，杜绝双路径竞态

通过 `ProcessLifecycleOwner` 监听整个进程的前后台切换；通过 `Application.ActivityLifecycleCallbacks.onActivityResumed` 维护 `lastForegroundActivityClass`，让"返回 AIPhone"和 ask_user 通知都跳回用户离开前的 Activity（ControlPanel 或 ViewActivity）。

**与 AgentService 的耦合：** 通过 `AgentService.engineFlow: StateFlow<AgentEngine?>` 订阅 engine 句柄。每次 engine 重建（新指令）都会发射新值，OverlayService 重新 collect 新 engine 的 `state`/`stepResults`，避免漏收事件。

### `OverlayStatePolicy.kt`

把"什么状态该显示什么形态"从 OverlayWindow 中抽出来的纯函数策略层，配 JVM 单元测试 `OverlayStatePolicyTest`，覆盖 Idle/Running/WaitingForUser/Error/Completed × 前后台组合。

### `FloatingControlBar.kt`（遗留）

早期 VIEW/TAKEOVER 模式的浮动控制栏。已被 `ViewActivity` 替代，保留在代码中但不再使用。

---

## `ui/` — 用户界面

### `ControlPanelActivity.kt`

聊天式主界面，用于发送指令、显示 agent 状态和结果。

**功能：**
- 聊天输入框发送指令到 AgentService
- RecyclerView 显示对话历史（指令、步骤结果、完成摘要）
- VIEW 按钮：启动 ViewActivity 查看 VD 内容
- Stop 按钮：停止 agent 执行
- LLM 配置区域（API key、model、base URL）

### `ViewActivity.kt`

全屏 Activity，通过 SurfaceView 渲染 VD 内容。替代了旧的 FloatingControlBar overlay 方案。

**两种模式：**
1. **VIEW 模式** — SurfaceView 显示 VD 画面，触摸被 touch interceptor 拦截，agent 继续运行
2. **TAKEOVER 模式** — 触摸通过 `InputInjector.injectToDisplay()` 转发到 VD，键盘事件通过 `dispatchKeyEvent()` + `InputInjector.injectKeyToDisplay()` 转发，agent 暂停

**键盘输入原理：** `DISPLAY_IME_POLICY_LOCAL` 使软键盘渲染在 VD 内部（通过 SurfaceView 可见），用户点击键盘 → 触摸转发 → VD 键盘处理 → InputConnection → 文本输入。硬件键盘事件通过 `dispatchKeyEvent()` 拦截并注入 VD。

**控制栏（可收起 FAB）：** 默认显示右下角圆形 FAB，点击展开底部面板（Back / Takeover / Stop）。FAB 图标随 agent 状态变化（▶ 运行中、❓ 等待回答、✓ 完成、✗ 失败）。

**Ask User 通知：** 当 agent 进入 `WaitingForUser` 状态时，FAB 自动展开显示问题文本 + 输入框，用户可直接在 VIEW 界面回答。

**完成/失败 Banner：** agent 任务完成或失败时，底部弹出 Snackbar 风格通知，4 秒后自动消失。

**状态监听：** 通过 `lifecycleScope.launch` collect `AgentService.stateFlow` 响应状态变化。

**关键方法：**
```kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean  // TAKEOVER 时转发按键到 VD
private fun forwardTouchToVD(event: MotionEvent)          // 1:1 坐标触摸转发
private fun setTakeoverMode(takeover: Boolean)             // 切换模式 UI
private fun expandPanel() / collapsePanel()                // FAB ↔ 面板切换
private fun showAskUser(question: String)                  // 展开输入面板
private fun submitAnswer()                                 // 提交回答
private fun showBanner(message: String, color: String)     // 状态 banner
private fun observeAgentState(service: AgentService)       // collect stateFlow
```

### `GlowBorderView.kt`

自定义 View，在 VIEW 模式下显示动画发光边框，指示 agent 正在操作。

### `ChatAdapter.kt` / `ChatMessage.kt`

RecyclerView 适配器和消息数据类，支持用户消息、agent 消息、系统消息三种类型。

### `ConversationHistoryActivity.kt`

完整对话历史列表，按日期分组（Today/Yesterday/日期）。支持点击查看详情、长按删除。

### `ConversationHistoryAdapter.kt`

RecyclerView 适配器，支持日期分组 Header + 对话 Item 两种 ViewType。提供 `submitList()` 和 `removeConversation()` 方法。

### `ConversationDetailActivity.kt`

只读对话详情页，复用 `ChatAdapter` 展示历史消息。顶栏显示标题、时间、状态，支持删除。

---

## `capture/` — 屏幕截图

### `ScreenCapture.kt`

多策略截图，按 Android API 版本自动降级：

| 优先级 | 策略 | API |
|--------|------|-----|
| 1 | `ScreenCapture.capture()` 公开 API | 36+ |
| 2 | `ScreenCaptureInternal.captureDisplay()` | 36+（需 display token）|
| 3 | `SurfaceControl.screenshot()` 旧版 | 30-35 |
| 4 | `screencap -p` 命令 | 所有（系统 app 有效）|

**主要方法：**
```kotlin
fun captureBase64(displayId: Int = 0, virtualDisplayBitmap: Bitmap? = null): String?
fun captureBitmap(displayId: Int = 0): Bitmap?
```

**虚拟显示器支持：**
- 传入 `virtualDisplayBitmap` 时跳过截图，直接处理该帧
- `captureBitmap(displayId)` 当 `displayId != 0` 时，仅尝试策略 1（支持 displayId 参数），跳过策略 2-4（只能截取物理屏），返回 null 交由 ImageReader 回退

---

### `ImageUtil.kt`

图片处理管道：**缩放（≤540px 宽）→ RGBA→RGB 转换 → JPEG 编码（85% 质量）**

```kotlin
fun processScreenshot(bitmap: Bitmap): String  // 完整管道，返回 base64
fun resizeToMaxWidth(bitmap: Bitmap, maxWidth: Int = 540): Bitmap
fun convertToRgb(bitmap: Bitmap): Bitmap
fun toJpegBase64(bitmap: Bitmap, quality: Int = 85): String
```

---

## `input/` — 输入注入

### `InputInjector.kt`

通过隐藏 API `InputManager.injectInputEvent()` 注入事件（需平台签名）。

**真实设备 ID：** 初始化时通过 `InputDevice.getDeviceIds()` 查找真实触屏设备 ID，构建更接近真实输入的 MotionEvent（参考豆包 MotionEventInj 方案），避免部分 app 检测到模拟输入。

**主要方法：**
```kotlin
fun tap(nx: Int, ny: Int): Boolean                    // 归一化坐标点击
suspend fun longPress(nx: Int, ny: Int, durationMs: Long = 1000): Boolean
suspend fun swipe(nx1: Int, ny1: Int, nx2: Int, ny2: Int, durationMs: Long = 300): Boolean  // 20步插值
fun keyEvent(keyName: String): Boolean                // back/home/enter/recent
suspend fun typeText(text: String): Boolean           // ASCII逐键，非ASCII剪贴板(Ctrl+V)
fun getDisplaySize(): Point
```

**跨显示器注入（静态方法，供 ViewActivity TAKEOVER 模式使用）：**
```kotlin
fun injectToDisplay(context, event: MotionEvent, targetDisplayId: Int): Boolean  // 触摸转发
fun injectKeyToDisplay(context, event: KeyEvent, targetDisplayId: Int): Boolean  // 按键转发
```

**虚拟显示器路由：** 通过反射在事件中设置 `displayId`

---

### `CoordinateMapper.kt`

归一化坐标（0-1000）↔ 像素坐标的转换，处理屏幕旋转。

**主要方法：**
```kotlin
fun normalizedToPixel(nx: Int, ny: Int): Point  // 处理 ROTATION_0/90/180/270
fun pixelToNormalized(px: Int, py: Int): Pair<Int, Int>
fun getDisplaySize(): Point
fun getRotationDegrees(): Int
```

**虚拟显示器支持：** 传入固定尺寸时跳过系统查询

---

## `accessibility/` — UI 树捕获

### `VdAccessibilityService.kt`

AccessibilityService 实现，在每步截图时同时获取 VirtualDisplay 上的 UI 树结构化信息，追加到 LLM prompt 中。

**核心原理：**
- 通过 `getWindowsOnAllDisplays().get(displayId)` 获取 VD 上的窗口列表
- 递归遍历 `AccessibilityNodeInfo` 树，提取 text、contentDescription、className、bounds、交互属性
- 输出紧凑文本格式，可交互元素带 `[id]` 编号，坐标归一化到 0-1000

**Singleton 模式：** `companion object { var instance }` + `captureViewTree(displayId, width, height)` 静态方法

**过滤规则：**
- 排除 `com.android.systemui` 窗口
- 跳过不可见节点、无内容且无交互的叶子节点
- 最大深度 15 层，最大 200 节点

**自动启用：** `AgentService.onCreate()` 通过 `Settings.Secure.putString()` 自动注册（需系统签名权限）

---

## `display/` — 虚拟显示器

### `VirtualDisplayManager.kt`

创建和管理离屏虚拟显示器，支持 Surface 切换（SurfaceView 镜像）和任务迁移。

**默认配置：** 物理屏幕分辨率（`AgentService` 传入 `displayMetrics.widthPixels × heightPixels @densityDpi`，函数签名默认值 540×1170 @240dpi 仅作兜底）

**技术细节：**
- Surface：`ImageReader`（8 buffer，RGBA_8888）零拷贝帧访问，防止 agent 步骤间帧阻塞
- **帧缓存（防静态画面饿死）：** 创建后通过 `OnImageAvailableListener` 在后台 `HandlerThread` 持续 drain `ImageReader`，把最新帧解码为 `Bitmap` 缓存到 `cachedBitmap`。`captureLatestBitmap()` 返回缓存的副本而不是直接调用 `acquireLatestImage()`。这样即使屏幕静止（producer 无新帧），截图仍能拿到最新画面。
  > 修复前：`acquireLatestImage()` 仅在「上次 acquire 后有新帧」时返回非 null。两次 `open_app` 失败后 launcher 静止不变，连续 8 次重试全部返回 null，agent 任务被中断。
- Flags（参考豆包 mFlags=120533）：`PUBLIC | SECURE | AUTO_MIRROR | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT | SHOULD_SHOW_SYSTEM_DECORATIONS | TRUSTED | ALWAYS_UNLOCKED | OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED | TOUCH_FEEDBACK_DISABLED`
- `SHOULD_SHOW_SYSTEM_DECORATIONS` — VD 显示导航栏、状态栏、输入法
- `OWN_FOCUS` — VD 有独立焦点，不抢 Display 0 焦点
- `ALWAYS_UNLOCKED` — VD 无需解锁
- IME 策略：创建后调用 `IWindowManager.setDisplayImePolicy(DISPLAY_IME_POLICY_LOCAL=0)` 使键盘渲染在 VD 内部
- 任务迁移通过反射调用 `IActivityTaskManager` 隐藏 API

**主要方法：**
```kotlin
fun create(width: Int, height: Int, dpi: Int): Int        // 返回 displayId，失败返回 -1
fun captureLatestBitmap(): Bitmap?                          // 返回 cachedBitmap 的副本（由 listener 持续更新）
fun captureViaImageReader(timeoutMs: Long = 150): Bitmap?   // 等待 listener 至少抓到一帧；从 SurfaceView 切回时先废弃缓存
fun setSurface(surface: Surface?)                           // 切换渲染目标：SurfaceView(VIEW) ↔ ImageReader(BACKGROUND)
fun getTopTaskIdOnDisplay(displayId: Int): Int?             // 获取指定 display 上的顶层非 AIPhone taskId
fun getRunningTasks(limit: Int = 50): List<TaskSnapshot>    // 通过 ActivityTaskManager.getTasks() 获取 task/display 快照
fun moveTaskToDisplay(taskId: Int, targetDisplayId: Int): Boolean  // 迁移任务到目标 display
fun destroy()                                               // 释放 VD、ImageReader、HandlerThread、cachedBitmap
private fun setDisplayImePolicy(dm, displayId: Int)         // 设置 IME 本地渲染策略（IWindowManager 反射）
private fun imageToBitmap(image: Image): Bitmap?            // listener 回调内的解码 helper
```

**属性：** `displayId`, `isCreated`, `width`, `height`, `isUsingExternalSurface`

### `TaskDisplayGuard.kt`

防止 VD 被控 task 通过 Recents、通知、deeplink 等路径逃逸到 Display 0。

**检测策略：**
- 主路径：隐藏 API `TaskStackListener`，监听 `onTaskMovedToFront` / `onTaskDisplayChanged` / `onTaskCreated` / `onTaskRemoved`
- 兜底：低频 `ActivityTaskManager.getTasks()` 扫描

**恢复策略：** `AgentService` 收到逃逸回调后，如果 agent 正在运行则短暂停止输入，调用 `moveTaskToDisplay(taskId, vdDisplayId)` 搬回 VD，再拉起 `ViewActivity` 或 `ControlPanelActivity`；TAKEOVER 逃逸会降级回 VIEW。

---

## `db/` — 数据持久化

### `AppDatabase.kt`

Room 数据库单例，管理对话历史存储。

**实体：**
- `ConversationEntity` — 对话记录（title, status, timestamps）
- `ChatMessageEntity` — 消息记录（conversationId 外键, type, text, timestamp）

**DAO：** `ChatDao` — 对话列表查询、消息查询、插入、更新状态、删除（CASCADE 自动删消息）

### `ChatHistoryRepository.kt`

协程安全的数据库操作封装，所有方法在 `Dispatchers.IO` 执行。

**主要方法：**
```kotlin
suspend fun startConversation(instruction: String): Long
suspend fun addMessage(conversationId: Long, type: MessageType, text: String)
suspend fun updateStatus(conversationId: Long, status: String)
suspend fun getAllConversations(): List<ConversationEntity>
suspend fun getRecentConversations(limit: Int = 3): List<ConversationEntity>
suspend fun getMessages(conversationId: Long): List<ChatMessageEntity>
suspend fun deleteConversation(conversationId: Long)
```

---

## `voice/` — 语音输入

### `SherpaOnnxSpeechInputEngine.kt`

离线流式语音识别，基于 [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx)，用于 ControlPanelActivity / ViewActivity 中的语音指令输入。

**离线模型：** 中英双语流式 zipformer，int8 量化（约 60 MB），随 APK 打包在 `assets/asr/zh_en_small_int8/`：
- `encoder-epoch-99-avg-1.int8.onnx` — 编码器（int8）
- `decoder-epoch-99-avg-1.onnx` — 解码器
- `joiner-epoch-99-avg-1.int8.onnx` — joiner（int8）
- `bpe.model` — 分词器
- `tokens.txt` — 字典

**模型路径解析：** `SherpaOnnxModelFiles.kt` 列出五个 asset 文件，由 `SherpaOnnxSpeechInputEngine` 在初始化时解压到 app data dir 后供 native 库加载。文件清单的完整性由 JVM 单元测试 `SherpaOnnxModelFilesTest` 守护——任何缺失或路径漂移都会立即失败。

**接口：** 实现统一的 `SpeechInputEngine` SAM，UI 侧只感知 `start(callback)` / `stop()` / `release()` 三个方法。

> **替换历史：** 早期使用 `AndroidSpeechInputEngine`（系统 `SpeechRecognizer` API），但在豆包 AOSP 上系统 ASR 服务缺失；切换到 Sherpa-ONNX 后离线可用、识别延迟和准确度均优于 Google ASR。

---

## `config/` — 配置管理

### `ConfigRepository.kt`

SharedPreferences 封装，存储所有持久化配置。

**配置项：**
| Key | 类型 | 默认值 | 说明 |
|-----|------|--------|------|
| `api_provider` | String | `"openai"` | API 协议：`"openai"` / `"anthropic"` |
| `api_key` | String | — | LLM API Key |
| `model` | String | `"qwen3.5-plus"` | 模型名（Anthropic 默认 `"Claude-Opus-4.6"`） |
| `base_url` | String | `"https://dashscope.aliyuncs.com/compatible-mode/v1"` | API endpoint |
| `max_steps` | Int | 20 | 最大执行步数 |
| `background_mode` | Boolean | false | 是否使用虚拟显示器 |
| `destroy_vd_on_complete` | Boolean | false | 任务完成后是否销毁虚拟显示器 |
| `im_remote_enabled` | Boolean | false | 启用 IM 远程控制 |
| `im_send_step_screenshots` | Boolean | false | IM 推送步骤截图 |
| `telegram_bot_token` | String | — | Telegram Bot Token |
| `telegram_allowed_chat_id` | String | — | 允许的 Telegram Chat ID |
| `telegram_offset` | Long | 0 | Telegram polling offset |
| `feishu_app_id` | String | — | 飞书 App ID |
| `feishu_app_secret` | String | — | 飞书 App Secret |
| `feishu_allowed_open_id` | String | — | 允许的飞书 Open ID |
| `feishu_target_chat_id` | String | — | 飞书目标 Chat ID |
| `active_profile` | String | — | 当前激活的 Provider Profile 名称 |

**批量写入方法：**
- `applyProfile(provider, apiKey, model, baseUrl, profileName)` — 原子写入所有 provider 字段 + profile 名称

### `ProviderProfile.kt`

Provider 配置快照数据类：`{name, provider, apiKey, model, baseUrl}`。支持 JSON 序列化/反序列化。`fromJsonArray` 容错处理（malformed JSON 返回空列表）。

### `ProfileRepository.kt`

Provider profile 管理。profiles 存储在独立 SharedPreferences 文件 `opencyvis_profiles`（JSON 格式）。

- `listProfiles()` — 返回所有已保存的 profile
- `saveCurrentAsProfile(name)` — 将当前配置保存为指定名称的 profile
- `saveProfile(profile)` — 直接保存 profile 对象（auto-save 场景）
- `switchTo(name)` — 自动保存当前 profile 后原子加载目标 profile（`@MainThread`）
- `deleteProfile(name)` — 删除 profile，维护 `activeProfileName` 非空不变量
- `generateProfileName(provider, model)` — 生成不重复的默认 profile 名称
- `ensureMigrated()` — 升级迁移：如果 `activeProfileName` 为空，从当前配置创建 profile

### `ConfigDeepLink.kt`

Deep link 解析（`opencyvis://config?...`）。`ImportedConfig` 包含可选 `profile` 字段（最后一个参数，默认 null）。导入时自动生成 profile 名称。

## `remoteim/` — IM 远程控制

通过 Telegram/飞书 IM 消息远程控制 AI agent。详见 [remote-im-usage.md](remote-im-usage.md)。

### `ImChannel.kt`

IM 通道抽象接口：`channelId`、`isConnected: StateFlow<Boolean>`、`start()`、`stop()`、`sendText()`、`sendPhoto()`、`setMessageHandler()`。数据类 `ImInboundMessage`（含 `chatType` 字段区分私聊/群聊）、`ImOutboundRecord`（Kind.TEXT/PHOTO）。

### `ImChannelManager.kt`

通道注册中心 + 出站环形缓冲区（容量 64，Mutex 保护）。先调通道发送，成功后写入 ring。支持 `replaceWithFake/restoreReal` 用于 dumpsys 测试。

### `ImPairingManager.kt`

配对码管理：6 位数字码（SecureRandom），10 分钟有效，仅内存存储。5 次失败锁定 10 分钟。成功后写入 ConfigRepository 白名单持久化。API：`generateCode(channelId)`、`attemptPairing(channelId, senderId, code)`、`isPaired(channelId)`、`unpair(channelId)`、`isLockedOut(channelId, senderId)`、`currentCode(channelId)`。

注：Feishu 通道使用 QR 注册 + auto-pair 流程（见 ImSessionRouter），不再需要手动 pair code。

### `ImStringProvider.kt` + `AndroidImStringProvider.kt`

IM 回复文本抽象接口（10 个路由回复方法 + 8 个状态文本方法 + 4 个通知方法），`AndroidImStringProvider` 通过 `R.string.*` 实现，保持路由逻辑可测试。

### `ImAgentBridge.kt`

桥接 IM 层与 agent 引擎。800ms debounce 步骤结果，关键状态（ask_user/handoff/finish/error）立即推送。所有文本通过 `ImStringProvider` 国际化。

### `ImSessionRouter.kt`

消息路由（Mutex）。`/pair <code>` 无需白名单，`/unpair`/`/status`/`/stop`/普通指令需白名单。未配对用户首次消息发送配对提示（1 小时节流）。群聊消息拒绝。

**Feishu Auto-pair:** 当 feishu 通道已有 appId/appSecret（通过 QR 注册创建）但 `feishuAllowedOpenId` 为空时，第一个发消息的用户自动完成 pair，无需手动 `/pair`。

### `telegram/TelegramApi.kt` + `TelegramChannel.kt`

OkHttp 长轮询 getUpdates，offset 持久化（bot-token 作用域），429 Retry-After，stale poll 检测。指数退避 [2s, 4s, 8s, 30s]。

### `feishu/FeishuOpenApi.kt` + `FeishuWsClient.kt` + `FeishuChannel.kt`

飞书 tenant_access_token TTL 90% 刷新。WebSocket 通过 POST `https://open.feishu.cn/callback/ws/endpoint`（参数 `AppID`+`AppSecret`）获取动态 WSS URL 后连接。消息以 protobuf 二进制帧到达，解析时在帧中搜索 `{"schema":"2.0"` 起始的嵌入 JSON，提取完整事件。每 30 秒发送 `{"type":"ping"}` 心跳。断线后 5 秒自动重连。

### `feishu/FeishuRegistrationApi.kt`

飞书未公开的 bot 注册 API（`accounts.feishu.cn/oauth/v1/app/registration`）。三步协议：
1. `init` → 获取 nonce
2. `begin`（archetype=PersonalAgent）→ 获取 device_code + user_code，用于生成 QR 码
3. `poll`（轮询）→ `authorization_pending` / `cool_down`（降速重试）/ 成功返回 client_id + client_secret

错误处理：通过 `error` 字段检测失败（API 不使用 `status` 字段包装）。`cool_down`/`slow_down` 视为非致命，倍增轮询间隔（最大 15s）继续重试。

### `RemoteImControlService.kt`

前台服务（foregroundServiceType="specialUse"），创建并组装所有 IM 组件，绑定 AgentService。

---

## `schedule/` — 定时任务调度

例行任务（Routine）的定时执行调度，支持三种触发方式。

### `ScheduleManager.kt`

核心调度管理器（`object`），负责注册/取消 AlarmManager 闹钟和地理围栏。

**主要方法：**
```kotlin
fun register(context: Context, routine: RoutineEntity)   // 注册调度
fun cancel(context: Context, routineId: Int)              // 取消调度
fun rescheduleAll(context: Context)                       // 重启后恢复所有调度
fun calculateNextTrigger(routine: RoutineEntity): Long?   // 计算下次触发时间
```

**精确度策略：**
- system flavor：`setExactAndAllowWhileIdle()`（系统应用有 Doze 豁免）
- standard flavor：先检查 `canScheduleExactAlarms()`，有权限用精确闹钟，否则回退到 `setAndAllowWhileIdle()`

### `ScheduleReceiver.kt`

BroadcastReceiver，接收闹钟触发和地理围栏事件。收到触发后：
1. 从 RoutineDao 查询 routine
2. 解析 instruction（支持 string resource key）
3. 启动 AgentService（`START_SCHEDULED` intent）
4. 更新 lastTriggeredAt，计算并注册下一次触发

### `BootReceiver.kt`

监听 `BOOT_COMPLETED`，调用 `ScheduleManager.rescheduleAll()` 重新注册所有活跃调度。

### `GeofenceManager.kt`

封装 `LocationManager.addProximityAlert()` 的地理围栏管理。不依赖 Google Play Services，适用于 AOSP 环境。

---

## `TestShellService.kt` — 测试命令接口

通过 `adb shell dumpsys opencyvis` 暴露同步 Binder 调用的测试接口。仅在 debuggable 构建中由 `App.onCreate()` 注册到 ServiceManager。

**线程模型：** `dump()` 在 Binder 线程执行，所有状态修改操作通过 `Handler(MainLooper).post{}` + `CountDownLatch(5s)` 分发到主线程。

**IM 相关命令：**
- `im state` — 显示 IM 通道状态 JSON
- `im inbound <channel> <senderId> <chatId> <text>` — 注入入站消息
- `im outbound [limit]` — 显示最近出站记录
- `im fake on|off` — 切换 fake channel 模式
- `im set-pairing-code <channel> [code]` — 设置配对码（不指定则自动生成）

**命令列表：**
| 命令 | 说明 |
|------|------|
| `state` | 返回 engine 状态 JSON |
| `start <instruction>` | 启动 agent（多词 instruction 支持） |
| `reset` | 停止 engine |
| `inject ask_user_response <text>` | 提交 ask_user 回答 |
| `inject supplement <text>` | 提交用户补充信息 |
| `debug running\|view\|takeover\|return_control\|stop` | 调试状态切换 |
| `debug complete_handoff` | 完成用户 handoff |
| `debug repeat_type_text_block\|repeat_tap_block\|repeat_tap_allow` | 重复防护测试 |
| `simulate ask_user\|handoff <text>` | 模拟 agent 状态 |
| `voice <target> <text>` | 注入语音结果 |
| `im state` | IM 通道状态 |
| `im inbound <ch> <sid> <cid> <text>` | 注入入站消息 |
| `im outbound [limit]` | 查看出站记录 |
| `im fake on\|off` | 切换 fake channel |
| `im state` | IM 通道状态 |
| `im inbound <ch> <sid> <cid> <text>` | 注入入站消息 |
| `im outbound [limit]` | 查看出站记录 |
| `im fake on\|off` | 切换 fake channel |

**返回格式：** 成功 `OK`，失败 `ERROR: <reason>`，超时 `ERROR: timeout (5s)`。

---

## tests/e2e/ — E2E 测试框架

YAML 驱动的 E2E 测试框架，通过 pytest 执行。场景定义在 `tests/ui/scenarios.yml`。

| 文件 | 职责 |
|------|------|
| `test_scenarios.py` | pytest 入口，`pytest_generate_tests` 从 YAML 参数化 |
| `conftest.py` | pytest fixtures：`--serial`, `--category`, `--api-key` |
| `scenario_loader.py` | `load_scenarios(category=)` 加载 YAML；`build_case(dict)` 动态生成 AgentTestCase |
| `framework.py` | `AgentTestCase`（声明式基类）+ `TestRunner`（执行器，含 mock/pre/post_steps） |
| `adb_utils.py` | ADB 命令封装：dumpsys、logcat、uiautomator_dump、tap_ui_element、execute_steps |
| `assertions.py` | 核心断言：FinishAction, LogcatPattern, ScreenshotVerify |
| `assertions_vd.py` | LogcatContains, LogcatNotContains, VirtualDisplayCreated, MinSteps |
| `assertions_ui.py` | DumpsysContains, DumpsysAbsent, VdExists, ImOutboundContains, ChatContains, UiElement, UiText |
| `mock_llm_server.py` | OpenAI-compat mock HTTP server（SSE streaming，脚本化响应） |
| `runner.py` | 传统 CLI 入口（`python -m tests.e2e.runner`） |
| `cases/*.py` | Python class-based 测试用例 |
