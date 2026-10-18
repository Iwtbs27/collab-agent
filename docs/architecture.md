# AIPhone — 整体架构

## 运行模式

AIPhone 支持两种运行模式，由 `ConfigRepository.background_mode` 控制：

| 模式 | 描述 |
|------|------|
| **前台模式** | Agent 操作物理屏幕（Display 0），适合与用户交互 |
| **虚拟显示器（VD）模式** | Agent 在后台独立的虚拟屏幕上运行，用户可同时使用手机 |

---

## 数据流

```
用户指令
   ↓
AgentService
   ↓
AgentEngine（observe-think-act 循环）
   ↓
     ┌─────────────────────────────────────────────┐
     │  观察：截图 + UI 树（AccessibilityService）   │
     ↓  思考：LLMClient → tool_call 解析            │
     │  执行：ActionExecutor → InputInjector        │
     └─────────────────────────────────────────────┘
```

**截图路径（VD 模式）：**

1. `RemoteBackend.captureScreen()` → AIDL → `PrivilegedService.captureScreen()`
2. 优先从 Mirror VD 的 `ImageReader` 缓存读帧（Mirror VD 镜像主 VD，半分辨率，500ms 节流）
3. 回退到主 VD 的 `ImageReader` 直接读帧（仅 surface 未被 SurfaceView 占用时有效）
4. 回退到 `CaptureOps.captureScreen()` 策略链（SurfaceFlinger API 36+ → SurfaceControl → screencap）

**Mirror VD 架构（ADB 模式特有）：**

```
主 VD (displayId=N)
  └─ surface → SurfaceView（VIEW/TAKEOVER 模式）或 ImageReader（后台模式）

Mirror VD (镜像 displayId=N)
  └─ surface → 专用 ImageReader（永久挂载，半分辨率，500ms 节流编码）
```

Mirror VD 使用 `DisplayManager.createVirtualDisplay(name, w, h, displayIdToMirror, surface)` 静态
方法创建，Shell uid 拥有 `CAPTURE_VIDEO_OUTPUT` 权限。Mirror 确保 agent 在用户 VIEW 模式下
（主 VD surface 切到 SurfaceView 时）仍能获取新截图，而非旧缓存。

System flavor 不需要 Mirror VD：VD 在进程内创建，`captureViaImageReader()` 可临时切换 surface。

---

## 构建产物（Product Flavors）

`app/build.gradle.kts` 定义 `privilege` 维度的两个 flavor：

| Flavor | applicationId | 签名 | 特权来源 |
|--------|---------------|------|----------|
| `system` | `ai.opencyvis` | 平台签名（sharedUserId） | SystemBackend（进程内，uid 1000） |
| `standard` | `ai.opencyvis.standard` | 普通签名 | RemoteBackend（shell uid 2000 via Shizuku 或 ADB） |

`standard` flavor 无 `sharedUserId`，可安装在非平台签名设备（普通用户设备、开发机）。

---

## 多后端权限抽象层（v2.0.0）

### 设计目标

将所有需要特殊权限的操作（输入注入、截图、虚拟显示器创建、任务管理）抽象到 `PrivilegeBackend` 接口后面，使 AIPhone 支持多种特权来源，无需修改业务逻辑：

- **system flavor**：以 uid 1000 运行的平台签名系统应用，直接调用隐藏 API（SystemBackend）
- **standard flavor via Shizuku**：Shizuku 将 `PrivilegedService` 启动在 shell uid (2000)，通过 AIDL IPC 代理
- **standard flavor via Wireless ADB**：DirectConnector 通过 ADB 无线调试自配对，同样启动 `PrivilegedService` at shell uid

### 两层抽象

```
消费者（InputInjector, ScreenCapture, VirtualDisplayManager）
               ↓
       PrivilegeBackend 接口
               │
       ┌───────┴────────┐
  SystemBackend      RemoteBackend
  (进程内反射,        (AIDL IPC 代理,
   uid=1000)          uid 无关)
                            │
                    ServiceConnector 接口
                            │
               ┌────────────┼──────────────┐
        ShizukuConnector  DirectConnector  (未来: RootConnector)
        (Shizuku SDK)     (ADB 无线自配对)
```

### PrivilegedService 进程

`PrivilegedService` 在提权进程中运行（shell uid 2000），通过以下路径启动：

```
CLASSPATH=<apk> app_process /system/bin \
  --nice-name=opencyvis:privilege \
  ai.opencyvis.backend.PrivilegedServiceMain \
  --token=<uuid> --authority=<package>.binder_exchange
```

Binder 传递流程：
1. `BinderExchangeProvider.prepare()` 生成一次性 token，重置 `CountDownLatch`
2. 提权进程启动，创建 `PrivilegedService`，通过 `IActivityManager.getContentProviderExternal()` 调用 `BinderExchangeProvider`（token 验证后 latch.countDown）
3. 主进程 `BinderExchangeProvider.awaitBinder(10s)` 收到 Binder，更新 `ConnectionState.Connected`

### 虚拟显示器（RemoteBackend 路径）

`RemoteBackend` 通过 AIDL 调用远程 `PrivilegedService.createVirtualDisplay()`。`PrivilegedService` 中：
- 使用 `DisplayManager` 公共 API（通过 `FakeContext` 提供最小化 Context），调用 `DisplayManagerGlobal.createVirtualDisplay()` 绕过需要 `UserManager` 的 `DisplayManager.createVirtualDisplay()` API 34+ 限制
- 创建 `ImageReader`（RGBA_8888, 4 buffer）作为 VD Surface
- 创建 Mirror VD（半分辨率 ImageReader, 4 buffer）镜像主 VD，带 `OnImageAvailableListener` 持续缓存帧
- `mirrorReady` flag 防止 VD 初始化阶段缓存 OpenCyvis 自身的 UI 帧
- 初始化 drain 线程（800ms 后）清理旧帧并将 OpenCyvis task 从 VD 移走
- `captureScreen()` 优先读 Mirror 缓存 → 回退到主 ImageReader → 回退到 CaptureOps

### Activity 在 VD 上启动

通过 `PrivilegeBackend.startActivityOnDisplay(intentUri, displayId)` 实现，最终调用 `am start --display N <intent>` 在指定 display 上启动 Activity，等价于 AOSP 的 `ActivityOptions.setLaunchDisplayId()`。

### ADB 无线配对流程（DirectConnector + AdbPairingService）

`DirectConnector` 无需安装 Shizuku，使用 Android 11+ 的 Wireless Debugging：

```
connect() 调用
    │
    ├── 尝试 SharedPreferences 中保存的上次连接信息（自动重连）
    │       ↓ TLS 证书错误 → clearConnectionInfo → NeedsPairing
    │       ↓ 其他错误 → 继续 mDNS 发现
    │
    ├── mDNS 发现 TLS_CONNECT 端口 → tryConnect（已配对设备）
    │       ↓ 成功 → Connected
    │       ↓ 证书错误 → 需要重新配对
    │       ↓ 临时错误 → 等 2s 重试一次
    │
    └── mDNS 发现 TLS_PAIRING 端口 → NeedsPairing(port)
            │
            ↓
    SetupActivity 先尝试 retryBackendDetection()（~8s）
      → 成功 → 自动回到主界面
      → 失败 → 显示配对 UI，引导用户输入 6 位配对码
```

`AdbPairingService` 是一个前台服务，通过多步 Notification 引导用户完成配对，每步状态持久化于 `setup_progress` SharedPreferences，支持打断后恢复：

| GuideStep | 说明 |
|-----------|------|
| `ENABLE_DEV_OPTIONS` | 引导开启开发者选项 |
| `ENABLE_WIRELESS_DEBUG` | 引导开启无线调试 |
| `WAITING_PAIRING_SERVICE` | mDNS 发现配对端口（30s 超时） |
| `CODE_INPUT` | RemoteInput 通知输入 6 位码（MIUI 降级到 PairingDialogActivity） |
| `PAIRING` | SPAKE2+ 配对中 |
| `SUCCESS` / `ERROR` | 最终结果 |

`SetupStateDetector.detect()` 在每步开始前检查环境（WiFi、Android 版本、开发者选项）决定从哪步开始。

### BackendDetector 检测优先级

```
isSystemUid() == true  →  SystemBackend（直接返回，无需连接器）
                  │
ShizukuConnector.isAvailable()  →  bindUserService → Connected → RemoteBackend
                  │
DirectConnector.isAvailable()（Android 11+）  →  connect → ...
                  │
NoneAvailable → 引导到 SetupActivity
```

---

## 主要模块

| 包 | 职责 |
|----|------|
| `backend/` | 多后端权限抽象（PrivilegeBackend、连接器、PrivilegedService） |
| `engine/` | AgentEngine、ActionRepeatGuard、状态机 |
| `llm/` | LLMClient（OpenAI compat）、AnthropicClient、OllamaClient |
| `action/` | Action 类型定义、ActionExecutor、AppLauncher |
| `capture/` | ScreenCapture 多策略截图、ImageUtil |
| `input/` | InputInjector、CoordinateMapper |
| `display/` | VirtualDisplayManager、TaskDisplayGuard |
| `accessibility/` | VdAccessibilityService（UI 树） |
| `overlay/` | OverlayWindow、OverlayService（后台运行提示） |
| `ui/` | ControlPanelActivity、ViewActivity |
| `remoteim/` | Telegram / 飞书远程控制 |
| `db/` | Room 数据库（对话历史、例行任务） |
| `schedule/` | 定时任务调度（AlarmManager、地理围栏） |
| `voice/` | Sherpa-ONNX 离线语音识别 |
| `config/` | ConfigRepository（SharedPreferences） |

---

## 相关文档

- `docs/components.md` — 各模块/类详细说明
- `docs/plans/` — 功能设计方案存档
- `docs/scrcpy-shizuku-internals.md` — Shizuku/scrcpy 内部机制参考
