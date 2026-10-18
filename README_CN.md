<picture>
  <source media="(prefers-color-scheme: dark)" srcset="github-banner-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="github-banner-light.png">
  <img alt="OpenCyvis" src="github-banner-light.png" width="100%">
</picture>

<p align="center">
  <strong>你的开源 AI 手机。</strong><br>
  商业 AI 手机是黑箱。这个不是。<br><br>
  <sub><b>Open</b> <b>Cy</b>ber Jar<b>vis</b></sub>
</p>

<p align="center">
  <a href="https://docs.opencyvis.ai/zh/">文档</a> •
  <a href="README.md">English</a> •
  <a href="#快速开始">快速开始</a> •
  <a href="#路线图">路线图</a> •
  <a href="CONTRIBUTING.md">参与贡献</a>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License"></a>
  <a href="#快速开始"><img src="https://img.shields.io/badge/v2.0-已发布-34A853.svg?logo=android&logoColor=white" alt="v2.0 已发布"></a>
  <a href="#"><img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?logo=kotlin&logoColor=white" alt="Kotlin"></a>
  <a href="#支持的模型"><img src="https://img.shields.io/badge/已测试模型-9个-FF6F00.svg" alt="LLMs"></a>
  <a href="#"><img src="https://img.shields.io/badge/测试-107+-brightgreen.svg" alt="Tests"></a>
</p>

---

## 它做什么

OpenCyvis 把 Android 变成 AI 手机。用自然语言给它一个任务——它看到你的屏幕，理解 UI，像你一样操作应用。

**"微信里发去吃寿司郎"** — 打开微信、找到对话、输入消息并发送：

<p align="center">
  <img src="docs/demos/wechat_reply.gif" width="300" alt="微信回复 demo">
</p>

**"找附近评分最高的咖啡店，导航过去"** — 打开地图，搜索，按评分排序，点击导航。

**"设个早上 7 点的闹钟，打开勿扰模式，切换到暗色主题"** — 一口气串联时钟、设置、显示三个应用。

### 后台运行

大多数 AI 工具在工作时会锁定你的屏幕。OpenCyvis 在**虚拟显示器**上运行——一个隔离的后台屏幕。AI 帮你订机票的同时，你照常刷微博。

```
┌─────────────────────┐    ┌─────────────────────┐
│   你的屏幕            │    │   虚拟显示器          │
│                      │    │   (AI 在这里工作)     │
│   刷微博、聊微信、     │    │                      │
│   看视频——            │    │   订机票、发消息、     │
│   手机照常用          │    │   下单购物            │
│                      │    │                      │
└─────────────────────┘    └─────────────────────┘
      你用这个                   AI 用这个
```

随时观看 AI 工作。觉得不对就接管。处理完交还，AI 从中断处继续。

<p align="center">
  <img src="docs/demos/home_screen.png" width="260" alt="OpenCyvis v2 主界面">
</p>

---

## 为什么

当一家公司推出「AI 手机」，他们获得了你屏幕、应用、消息的完整访问权——而你看不到运行的是什么模型，无法验证什么数据离开了设备，也无法选择替代方案。

**你至少应该有选择的权利。**

OpenCyvis 是开源替代方案：你能看到每一行代码，你来选 AI 模型，你决定数据去向。用本地模型时，任何数据都不会离开你的设备。

---

## 两种安装模式

### 标准模式

适合大多数用户。不需要刷机，不需要 Root，不需要连接电脑。

1. 下载 APK，正常安装
2. 打开 App，选择 ADB 无线配对或 Shizuku 作为权限后端
3. 选择 LLM 后端（云端或本地），开始使用

内置的 ADB 无线配对可完全在手机上完成。或者，如果设备上已运行 [Shizuku](https://shizuku.rikka.app/)，授予 OpenCyvis 权限一次即可使用，无需开启无线调试或进行无线配对。支持 Android 11 及以上版本。

> **ADB 无线配对小贴士**
> - 设置向导会引导你打开系统的「无线调试 → 使用配对码配对」。看到 6 位配对码后，**下拉通知栏**，在 OpenCyvis 的通知里直接输入配对码即可——不用切回 App。
> - 部分国产 ROM（ColorOS / 一加、MIUI 等）会在切到后台后冻结应用。配对时如果系统弹出「允许后台运行 / 忽略电池优化」的提示，请选择**允许**，配对会更顺畅。
> - 如果没看到带输入框的通知，手动下拉一次通知栏即可找到。

### 系统 App 模式

适合开发者和极客用户。

刷入 AOSP 系统镜像，App 以系统应用身份运行，拥有最完整的平台签名权限。截屏使用 `SurfaceControl` 直接调用，速度最快。VD 任务管理走系统内部 API，稳定性最高。

### 两种模式的关系

核心 AI 引擎、LLM 后端、UI 界面、操作能力——两种模式完全一样。差异仅在底层：系统 App 直接调用系统 API，标准模式通过 ADB shell 或 Shizuku 权限获得同等能力。绝大多数日常场景下，用户感受不到区别。

---

## 2.0 新增功能

### IM 远程控制

在 IM 里给 Bot 发消息，远程控制手机上的 AI。目前支持**飞书**和 **Telegram**。

典型场景：给父母手机装上 OpenCyvis，妈妈说"字太小了看不清"——你在 IM 里发条"把字体调到最大"，AI 操作完截图发回来确认。不需要双方同时盯屏幕，不需要电话里教点哪里。

支持完整的交互：下发指令、接收进度、查看截图、回答 AI 的追问、停止任务。配对过程用 6 位数字码。

<p align="center">
  <img src="docs/demos/feishu_remote.png" width="300" alt="飞书远程控制">
</p>

### 例行任务（Routines）

把常用操作保存下来，定时自动执行或一键触发。

比如设置"每天早上 8 点查日程"，AI 到点自动查日历、查天气、查未读邮件，汇总推送到聊天里。也支持地理围栏——到了公司自动打卡。

### Provider Profiles

可以保存多个 AI 配置方案（比如一个云端 Qwen、一个本地 Gemma 4、一个 Claude），在设置里一键切换，不需要每次重新填写 API 地址和密钥。

### 深色模式

完整的日夜主题支持。跟随系统设置自动切换，也可以手动指定。所有界面——主页、聊天、设置、Watch 模式——都有对应的深色配色。

### 多 ROM 适配

标准模式支持 MIUI、ColorOS、OriginOS 等主流国产 ROM。不同厂商的 ADB 无线调试入口差异较大，通过 OemHelper 做了兼容处理。

---

## 横向对比

| | 商业 AI 手机 | 云手机 | 手机控制脚本 | **OpenCyvis** |
|---|:---:|:---:|:---:|:---:|
| **开源** | ❌ | ❌ | ⚠️ | ✅ |
| **自选 AI 模型** | ❌ | ❌ | ⚠️ | ✅ |
| **数据留在设备** | ❌ | ❌ | ⚠️ | ✅ |
| **AI 工作时手机照常用** | ⚠️ | ✅ | ❌ | ✅ |
| **支持所有应用** | ⚠️ | ⚠️ | ⚠️ | ✅ |
| **无需电脑设置** | ⚠️ | ⚠️ | ❌ | ✅ |
| **适用于日常手机** | ✅ | ⚠️ | ❌ | ✅ |

---

## 支持的模型

OpenCyvis 不绑定模型。用户自行配置 LLM 后端。

### 云端模型

| 模型 | 每步延迟 | 通过率 | 备注 |
|:---|:---:|:---:|:---|
| **Qwen 3.5 Plus** | 4-6s | 4/4 | 稳定，推荐 |
| **Claude Opus 4** | 4-8s | 4/4 | 推理质量最高 |
| **MiMo v2.5** | 2.3-4.5s | 4/4 | 速度最快 |
| **GPT-4o** | 3-6s | 3/4 | 偶尔忽略 tool_choice |

### 本地模型（通过 Ollama）

| 模型 | 体积 | 速度 | 通过率 |
|:---|:---:|:---:|:---:|
| **Gemma 4 26B-A4B** Q4 | 17 GB | 63 tok/s | **4/4** |
| **Gemma 4 E2B** Q4 | 1.8 GB | 41 tok/s | **4/4** |
| **Qwen 3.5 35B-A3B** Q4 | 22 GB | 47 tok/s | 3/4 |
| **Gemma 4 E4B** Q4 | 3 GB | 61 tok/s | 3/4 |

> **推荐：** Gemma 4 26B-A4B — 速度、质量、显存的最佳平衡。  
> **极简：** Gemma 4 E2B — 仅 1.8 GB，依然通过全部 4 项测试。

---

## 架构

两种安装模式共享全部上层代码，差异仅在权限层。通过 `PrivilegeBackend` 接口隔离：

| | SystemBackend | RemoteBackend |
|---|---|---|
| 权限来源 | 平台签名（uid system） | ADB shell 或 Shizuku |
| 输入注入 | InputManager 反射 | AIDL 代理到 PrivilegedService |
| 截屏 | SurfaceControl.screenshot() | ImageReader 从 VD Surface 读取 |
| VD 任务管理 | ActivityTaskManager 反射 | PrivilegedService 代理 |

运行时自动选择 Backend。

<p align="center">
  <img src="docs/demos/backend_architecture.png" width="500" alt="双 Backend 架构">
</p>

---

## 隐私 & 安全

拥有完整手机访问权限的 AI 智能体，是你能运行的最高特权软件之一。这不是一个可以说「请相信我们」的地方。

- **AI 服务你来选** — 托管、私有或本地
- **无遥测、无分析、不偷偷联网** — 零追踪代码
- **开源** — 任何人都能审计
- **本地模型选项** — 数据不出设备

---

## 快速开始

### 下载

[Releases](https://github.com/opencyvis/opencyvis-phone/releases) 页面提供两种 APK：

| APK | 适用 | 包名 |
|:---|:---|:---|
| `opencyvis-standard-release.apk` | 大多数用户 — 安装到任何 Android 11+ 手机 | `ai.opencyvis.standard` |
| `opencyvis-system-release.apk` | 开发者 — 刷入 AOSP 作为系统应用 | `ai.opencyvis` |

### 标准模式（推荐）

1. 下载 `opencyvis-standard-release.apk` 并安装
2. 打开 App，选择 ADB 无线配对或 Shizuku
3. 在设置中配置 LLM Provider
4. 开始发送任务

使用内置 ADB 无线配对时，下拉通知栏并在 OpenCyvis 通知里输入 6 位配对码；若 ROM 提示允许后台运行，请选择允许。或者，设备上已运行 Shizuku 时，只需授予 OpenCyvis 权限，无需无线配对。

### 系统 App 模式

适用于构建自定义 AOSP 镜像的开发者：

```bash
git clone https://github.com/opencyvis/opencyvis-phone.git
cd opencyvis-phone/android
./gradlew assembleSystemRelease
```

部署到 AOSP 设备详见 [android/README-AOSP.md](android/README-AOSP.md)。

### 配置 LLM

在 App 内设置，或通过 deeplink：

```bash
# 本地 Ollama（完全私密）
adb shell am start -a android.intent.action.VIEW \
  -d "opencyvis://config?provider=ollama&base_url=http://localhost:11434&model=gemma4:26b"

# 云端 API
adb shell am start -a android.intent.action.VIEW \
  -d "opencyvis://config?provider=openai&base_url=https://api.example.com/v1&api_key=YOUR_KEY&model=qwen-vl-max"
```

---

## 路线图

- 探索更便捷的权限支持方式
- 进一步优化本地模型支持
- 跨设备协同（手机 + 桌面）

---

## 参与贡献

详见 [CONTRIBUTING.md](CONTRIBUTING.md)。欢迎代码、Bug 报告、安全审计、翻译和文档贡献。

## 许可证

[Apache 2.0](LICENSE)

## 致谢

- [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) — 设备端语音识别 (Apache 2.0)
