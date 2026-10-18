<picture>
  <source media="(prefers-color-scheme: dark)" srcset="github-banner-dark.png">
  <source media="(prefers-color-scheme: light)" srcset="github-banner-light.png">
  <img alt="OpenCyvis" src="github-banner-light.png" width="100%">
</picture>

<p align="center">
  <strong>The open-source AI phone.</strong><br>
  Commercial AI phones are black boxes. This one isn't.<br><br>
  <sub><b>Open</b> <b>Cy</b>ber Jar<b>vis</b></sub>
</p>

<p align="center">
  <a href="https://docs.opencyvis.ai">Docs</a> •
  <a href="README_CN.md">中文</a> •
  <a href="#getting-started">Getting Started</a> •
  <a href="#roadmap">Roadmap</a> •
  <a href="CONTRIBUTING.md">Contributing</a>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License"></a>
  <a href="#getting-started"><img src="https://img.shields.io/badge/v2.0-released-34A853.svg?logo=android&logoColor=white" alt="v2.0 released"></a>
  <a href="#"><img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?logo=kotlin&logoColor=white" alt="Kotlin"></a>
  <a href="#supported-models"><img src="https://img.shields.io/badge/LLMs_Tested-9_models-FF6F00.svg" alt="LLMs"></a>
  <a href="#"><img src="https://img.shields.io/badge/Tests-107+-brightgreen.svg" alt="Tests"></a>
</p>

---

## What It Does

OpenCyvis turns Android into an AI phone. Give it a task in natural language — it sees your screen, understands the UI, and operates apps just like you would.

**"Reply 'let's go eat sushi' in WeChat"** — opens WeChat, finds the conversation, types and sends:

<p align="center">
  <img src="docs/demos/wechat_reply.gif" width="300" alt="WeChat reply demo">
</p>

**"Find the best-rated coffee shop nearby and get directions"** — opens Maps, searches, sorts by rating, taps navigate.

**"Set a 7am alarm, turn on Do Not Disturb, and switch to dark mode"** — chains Clock, Settings, and Display in one go.

### It works in the background

Most AI tools lock your screen while they work. OpenCyvis operates on a **virtual display** — an isolated background screen. The AI books your flight while you scroll Twitter.

```
┌─────────────────────┐    ┌─────────────────────┐
│   Your screen        │    │   Virtual display    │
│                      │    │   (AI works here)    │
│   Browse, chat,      │    │                      │
│   watch videos —     │    │   Booking flights,   │
│   phone is yours     │    │   sending messages,  │
│                      │    │   placing orders     │
└─────────────────────┘    └─────────────────────┘
      You use this              AI uses this
```

Watch the AI work anytime. Take over if something looks wrong. Hand it back when you're done.

<p align="center">
  <img src="docs/demos/home_screen.png" width="260" alt="OpenCyvis v2 home screen">
</p>

---

## Why

When a company ships an "AI phone," they get full access to your screen, your apps, your messages — and you can't see what model is running, can't verify what data leaves your device, can't choose an alternative.

**You should at least have the choice.**

OpenCyvis is the open-source alternative: you see every line of code, you pick the AI model, you decide where your data goes. With a local model, nothing ever leaves your device.

---

## Two Install Modes

### Standard Mode

For most users. No custom ROM, no root, no computer.

1. Download and install the APK
2. Open the app and choose ADB wireless pairing or Shizuku as the privilege backend
3. Choose your LLM backend (cloud or local), start using

The built-in ADB wireless pairing flow completes entirely on-device. Alternatively, if [Shizuku](https://shizuku.rikka.app/) is already running, grant OpenCyvis access once and no wireless debugging or pairing is needed. Supports Android 11+.

> **ADB wireless pairing tips**
> - The wizard sends you to **Wireless debugging → Pair device with pairing code**. Once you see the 6-digit code, **pull down the notification shade** and type the code straight into the OpenCyvis notification — no need to switch back to the app.
> - Some vendor ROMs (ColorOS / OnePlus, MIUI, …) freeze apps once they go to the background. If the system asks you to **allow background activity / ignore battery optimization** during pairing, choose **Allow** for a smoother experience.
> - If you don't see the notification with the input field, just pull down the shade once to find it.

### System App Mode

For developers and power users.

Flash an AOSP system image. The app runs as a system application with full platform signing privileges. Screenshots use `SurfaceControl` directly — fastest possible. Full virtual display task management via system APIs.

### How they relate

Same AI engine, same LLM backends, same UI, same capabilities. The only difference is how the app obtains system permissions. Standard mode uses ADB shell or Shizuku privileges; System App mode uses platform signing. For everyday tasks, you won't notice the difference.

---

## v2.0 New Features

### Remote Control via IM

Send messages to a bot in your IM app to control the phone's AI remotely. Currently supports **Feishu** and **Telegram**.

Use case: Install OpenCyvis on a parent's phone. Mom says "the text is too small" — you send "set font size to largest" in IM. The AI does it and sends back a confirmation screenshot. No need for both parties to watch the screen simultaneously.

Supports: sending commands, receiving progress, viewing screenshots, answering the AI's questions, stopping tasks. Pairing uses a 6-digit code.

<p align="center">
  <img src="docs/demos/feishu_remote.png" width="300" alt="Remote control via Feishu">
</p>

### Routines

Save frequent operations and run them on a schedule or with one tap.

Example: "Check calendar, weather, and unread emails every morning at 8am" — the AI runs automatically and pushes a summary to chat. Also supports geofencing — auto clock-in when arriving at the office.

### Provider Profiles

Save multiple AI configurations (e.g., cloud Qwen, local Gemma 4, Claude) and switch between them with one tap. No need to re-enter API URLs and keys each time.

### Dark Mode

Full day/night theme. Follows system settings or set manually. All screens — home, chat, settings, watch mode — have matching dark variants.

### Multi-ROM Support

Standard mode now supports MIUI, ColorOS, OriginOS, and other vendor ROMs. Different manufacturers have vastly different wireless debugging entry points — OemHelper handles the differences.

---

## How It Compares

| | Commercial AI Phones | Cloud Phones | Phone-control Scripts | **OpenCyvis** |
|---|:---:|:---:|:---:|:---:|
| **Open source** | ❌ | ❌ | ⚠️ | ✅ |
| **Choose your AI model** | ❌ | ❌ | ⚠️ | ✅ |
| **Data stays on device** | ❌ | ❌ | ⚠️ | ✅ |
| **Phone usable while AI works** | ⚠️ | ✅ | ❌ | ✅ |
| **Works with any app** | ⚠️ | ⚠️ | ⚠️ | ✅ |
| **No computer setup** | ⚠️ | ⚠️ | ❌ | ✅ |
| **Works on everyday phones** | ✅ | ⚠️ | ❌ | ✅ |

---

## Supported Models

OpenCyvis is model-agnostic. Bring your own AI account, connect a private service, or run a local model.

### Cloud Models

| Model | Latency per step | Pass Rate | Notes |
|:---|:---:|:---:|:---|
| **Qwen 3.5 Plus** | 4-6s | 4/4 | Stable, recommended |
| **Claude Opus 4** | 4-8s | 4/4 | Highest reasoning quality |
| **MiMo v2.5** | 2.3-4.5s | 4/4 | Fastest |
| **GPT-4o** | 3-6s | 3/4 | Occasionally ignores tool_choice |

### Local Models (via Ollama)

| Model | Size | Speed | Pass Rate |
|:---|:---:|:---:|:---:|
| **Gemma 4 26B-A4B** Q4 | 17 GB | 63 tok/s | **4/4** |
| **Gemma 4 E2B** Q4 | 1.8 GB | 41 tok/s | **4/4** |
| **Qwen 3.5 35B-A3B** Q4 | 22 GB | 47 tok/s | 3/4 |
| **Gemma 4 E4B** Q4 | 3 GB | 61 tok/s | 3/4 |

> **Recommended:** Gemma 4 26B-A4B — best balance of speed, quality, and memory.  
> **Minimal:** Gemma 4 E2B — just 1.8 GB, still passes all 4 tests.

---

## Architecture

Both install modes share all upper-layer code. The difference is only in the privilege layer, isolated behind a `PrivilegeBackend` interface:

| | SystemBackend | RemoteBackend |
|---|---|---|
| Privilege source | Platform signing (uid system) | ADB shell or Shizuku |
| Input injection | InputManager reflection | AIDL proxy to PrivilegedService |
| Screenshot | SurfaceControl.screenshot() | ImageReader from VD Surface |
| VD task management | ActivityTaskManager reflection | PrivilegedService proxy |

The backend is selected automatically at runtime.

<p align="center">
  <img src="docs/demos/backend_architecture.png" width="500" alt="Dual backend architecture">
</p>

---

## Privacy & Security

An AI agent with full phone access is one of the most privileged pieces of software you can run. This is not a place for "trust us."

- **You choose the AI service** — hosted, private, or local
- **No telemetry, no analytics, no phone-home** — zero tracking code
- **Open source** — anyone can audit
- **Local model option** — nothing leaves your device

---

## Getting Started

### Download

Two APKs are available on the [Releases](https://github.com/opencyvis/opencyvis-phone/releases) page:

| APK | For | Package ID |
|:---|:---|:---|
| `opencyvis-standard-release.apk` | Most users — install on any Android 11+ phone | `ai.opencyvis.standard` |
| `opencyvis-system-release.apk` | Developers — flash into AOSP as a system app | `ai.opencyvis` |

### Standard Mode (recommended)

1. Download `opencyvis-standard-release.apk` and install
2. Open the app and choose ADB wireless pairing or Shizuku
3. Configure your LLM provider in Settings
4. Start sending tasks

Use the built-in ADB wireless pairing flow by entering the 6-digit code in the OpenCyvis notification and allowing background activity if your ROM prompts for it. Alternatively, if Shizuku is already running, simply grant OpenCyvis access—no wireless pairing is required.

### System App Mode

For developers building a custom AOSP image:

```bash
git clone https://github.com/opencyvis/opencyvis-phone.git
cd opencyvis-phone/android
./gradlew assembleSystemRelease
```

See [android/README-AOSP.md](android/README-AOSP.md) for AOSP deployment and platform key signing.

### Configure LLM

Set your provider in-app, or via deeplink:

```bash
# Local Ollama (fully private)
adb shell am start -a android.intent.action.VIEW \
  -d "opencyvis://config?provider=ollama&base_url=http://localhost:11434&model=gemma4:26b"

# Cloud API
adb shell am start -a android.intent.action.VIEW \
  -d "opencyvis://config?provider=openai&base_url=https://api.example.com/v1&api_key=YOUR_KEY&model=qwen-vl-max"
```

---

## Roadmap

- Explore more convenient privilege acquisition methods
- Further optimize local model support
- Cross-device coordination (phone + desktop)

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). We welcome code, bug reports, security audits, translations, and documentation.

## License

[Apache 2.0](LICENSE)

## Acknowledgments

- [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) — on-device speech recognition (Apache 2.0)
