# OpenCyvis 二次开发与真机调试入门指南

适用环境：Windows 电脑、Android 手机，以及当前 OpenCyvis 仓库。本文使用普通手机对应的 `standardDebug` 构建版本，权限后端沿用应用内的 ADB Direct。

目标是完成第一次环境配置，并掌握以后的日常开发流程：

**电脑修改源码 → Android Studio 构建 → 自动更新安装到手机 → 手机上执行任务 → 电脑查看日志或断点 → 继续修改。**

本文根据当前仓库配置整理，是操作指南；不代表已经在你的电脑完成首次构建。

## 1. 先认识开发工具

| 名称 | 作用 |
| --- | --- |
| Android Studio | Android 开发工作台，集成代码编辑、构建、安装、日志和断点调试 |
| Android SDK | 开发 Android 应用所需的接口与工具 |
| JDK | 构建项目使用的 Java 开发环境 |
| Gradle | 根据项目配置，把源码和资源构建成 APK |
| APK | 安装到手机上的应用安装包 |
| ADB | 电脑与手机通信的工具，可以安装应用、读取日志、执行调试命令 |
| Logcat | Android 日志工具，可以查看应用执行过程和异常 |
| NDK、CMake | 编译本项目中的 C++ 原生代码所需的工具 |

手机运行的是已安装 APK 中的代码。电脑修改源码后，需要重新构建并部署，手机才能运行这些修改。Android Studio 的 **Run** 按钮会自动完成这几步。

你也可以继续用现有编辑器或让编码助手修改仓库，再切到 Android Studio 构建和调试。两边使用同一个项目目录即可。

## 2. 第一次开发前准备什么

- 当前项目源码。
- Windows 电脑和足够的磁盘空间；SDK、NDK、依赖缓存会占用额外空间。
- 能正常下载开发工具和项目依赖的网络。
- Android 手机，以及支持数据传输的 USB 线。
- 手机已经开启开发者选项和 USB 调试。
- 本地保存好当前应用的模型配置；第一次替换下载版 APK 时，可能需要重新安装和配置。

你已经有真实手机，可以先使用真机完成开发闭环，之后再按需要配置模拟器。

## 3. 下载和安装 Android Studio

1. 在电脑打开 [Android Studio 官方下载页](https://developer.android.com/studio)。
2. 下载 Windows 安装程序，选择稳定版。
3. 运行安装程序，完成安装。
4. 第一次启动时，按照初始化向导配置，可以选择 **Standard**。
5. 等待所需开发组件下载完成。

安装与开发组件准备是首次配置工作，后续日常开发通常不需要重复。

官方参考：[安装 Android Studio](https://developer.android.com/studio/install)。

## 4. 打开本项目的 Android 工程

在 Android Studio 欢迎页点击 **Open**，选择：

```text
E:\python_notebook\LLM26fall\GUI-Phone\opencyvis-phone-main\android
```

注意选择末尾的 `android` 文件夹。它包含 `settings.gradle.kts` 和 `build.gradle.kts`，是本仓库的 Android 工程根目录。

打开后会执行 **Gradle Sync**，即读取项目配置并准备依赖。如果首次同步出现错误，先完成下面的 JDK 和 SDK 配置，再重新同步。

首次跑通阶段先保留仓库中的 Gradle 和插件版本。遇到升级建议时，可以先关闭建议；若工具明确报版本不兼容，再根据具体错误处理。

## 5. 处理仓库中的 Java 路径，并配置 JDK 17

当前仓库的 [android/gradle.properties](../android/gradle.properties) 中包含：

```properties
org.gradle.java.home=/opt/homebrew/opt/openjdk@17
```

这是 macOS 上的机器专属路径，Windows 上无法直接使用。在 Android Studio 打开该文件，将这一行注释掉：

```properties
# org.gradle.java.home=/opt/homebrew/opt/openjdk@17
```

然后打开：

```text
File → Settings
→ Build, Execution, Deployment
→ Build Tools → Gradle
```

找到 **Gradle JDK**，选择 **JDK 17**。如果没有可选的版本 17，通过 **Download JDK** 下载，再选中它。

当前仓库使用 Android Gradle Plugin 8.7.3，并将 Java/Kotlin 编译目标设为 17，使用 JDK 17 构建是合适的配置。

如果以后从普通 PowerShell 直接运行 Gradle，还需要让该终端使用合适的 Java 环境。刚入门时可以先通过 Android Studio 的按钮和 Gradle 面板构建。

官方参考：[Android 构建中的 Java 版本](https://developer.android.com/build/jdks)。

## 6. 安装项目需要的 SDK、NDK 和 CMake

打开 **Tools → SDK Manager**。

### SDK Platforms

安装 **Android 16 / API 36 的 Android SDK Platform**，因为当前项目配置了：

```kotlin
compileSdk = 36
minSdk = 30
targetSdk = 36
```

`compileSdk` 是电脑编译时使用的接口版本，手机不必与其版本完全相同。项目最低支持 Android 11，你的 Android 15 手机在项目声明的安装范围内。

### SDK Tools

确认安装以下组件：

| 组件 | 选择说明 |
| --- | --- |
| Android SDK Platform-Tools | 包含 `adb` |
| Android SDK Build-Tools | 按 Gradle 提示补充所需版本 |
| Android SDK Command-line Tools | SDK 管理工具 |
| NDK (Side by side) | 当前 AGP 8.7 默认版本是 `27.0.12077973` |
| CMake | 可安装 `3.22.1`，满足仓库要求的最低版本 3.22 |

需要选择具体版本时，勾选 **Show Package Details**。

本项目的 `android/app/src/main/jni/` 包含原生代码，因此需要 NDK 和 CMake。仓库没有显式固定 `ndkVersion`，这里列出的是当前插件的默认版本。

完成后，点击 **File → Sync Project with Gradle Files** 重新同步。同步成功后再继续安装运行。

官方参考：[安装 NDK 和 CMake](https://developer.android.com/studio/projects/install-ndk)、[AGP 8.7 兼容性要求](https://developer.android.com/build/releases/agp-8-7-0-release-notes)。

## 7. 选择 standardDebug 构建版本

打开：

```text
View → Tool Windows → Build Variants
```

找到 `app` 模块，将 **Active Build Variant** 设置成：

```text
standardDebug
```

- `standard`：普通手机版本，通过应用内 ADB 或 Shizuku 后端获得操作能力。
- `debug`：开发调试版本，便于日志和断点调试。

当前 `standard` 版本的应用包名为：

```text
ai.opencyvis.standard
```

官方参考：[选择构建版本](https://developer.android.com/studio/run#change-the-build-variant)。

## 8. 通过 USB 连接手机

1. 手机打开开发者选项中的 **USB 调试**。
2. 使用数据线连接电脑并解锁手机。
3. 手机弹出“允许 USB 调试”时，点击允许。
4. 在 Android Studio 顶部的设备列表中选择你的手机。

你之前已经成功抓取日志，可以继续使用原来的 ADB 环境检查连接：

```powershell
adb devices
```

正常输出类似：

```text
List of devices attached
设备编号    device
```

`unauthorized` 表示需要在手机上确认授权。如果 Android Studio 无法识别设备，再检查数据线、USB 授权和 Windows 驱动。

如果 `adb` 没有加入 PATH，可以在 SDK 的 `platform-tools` 文件夹打开 PowerShell，使用：

```powershell
.\adb.exe devices
```

**电脑通过 USB 调试应用，与 OpenCyvis 内部的 ADB Direct 是两个连接。** 你当前使用 ADB Direct 后端，因此仍需保留手机上的无线调试，并确认应用设置中的 Active Backend 已连接。

官方参考：[在硬件设备上运行应用](https://developer.android.com/studio/run/device)。

## 9. 第一次把源码构建成应用并安装

Android Studio 顶部选择运行配置 `app`，目标设备选择你的手机，然后点击绿色三角形 **Run ▶**。

它会自动完成：

```text
读取源码和资源 → 构建 APK → 安装或更新到手机 → 启动应用
```

首次构建可能需要下载依赖。进度和构建错误可以在 **Build** 窗口查看。

### 第一次替换下载版 APK：留意签名

手机上从网上下载的 APK，与自己电脑生成的调试 APK，可能使用不同的签名。此时会出现：

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

这表示相同包名的已安装应用，与新 APK 的签名不一致，无法直接更新。

先在本地保存需要保留的模型配置和其他数据，再处理旧版卸载。卸载通常会清掉应用设置和历史记录，之后需要重新安装、配置模型，并检查权限后端连接。

成功安装自己电脑生成的调试版后，后续保持同一包名和签名，通常可以直接更新并保留应用数据；实际数据行为也取决于你后续对存储逻辑的修改。

官方参考：[构建和运行应用](https://developer.android.com/studio/run)、[应用签名](https://developer.android.com/studio/publish/app-signing)。

## 10. 先验证原项目，再进行功能修改

第一次先保持业务代码不变，只完成必要的本机构建环境配置。

安装后检查模型设置和 Active Backend，再在手机应用中发送：

```text
帮我打开手机设置
```

确认自己编译的版本能完成任务。这样以后修改出问题时，就有一个可比较的基准。

首次同步或构建若失败，保存 **Build** 窗口中具体的错误信息，再按错误处理。当前这份指南没有实际执行首次构建，不能保证仓库在新环境中不会遇到依赖或构建问题。

## 11. 做一次最小修改，体验更新流程

可以先修改应用名称。

打开 [android/app/src/main/res/values/strings.xml](../android/app/src/main/res/values/strings.xml)，找到：

```xml
<string name="app_name">OpenCyvis</string>
```

修改成：

```xml
<string name="app_name">我的手机助手</string>
```

保存文件，再点击 **Run ▶**。更新成功后，回到手机桌面查看应用名称。

这就完成了一次完整的开发操作：修改源码、重新构建、更新安装、查看效果。

### 以后每次修改代码的日常顺序

1. 明确本次要改变的行为，并阅读相关代码。
2. 在电脑修改并保存文件。
3. 适当运行受影响逻辑的测试。
4. 点击 Run，将新代码部署到手机。
5. 在手机执行对应任务，同时在电脑观察日志。
6. 根据结果继续修改，完成后保存本次改动。

普通更新通常不需要每次卸载应用，也不需要每次清理整个构建缓存。

## 12. 用 Logcat 查看运行过程

打开：

```text
View → Tool Windows → Logcat
```

选择手机，在搜索框中输入：

```text
tag:AgentEngine
```

可以查看任务步骤、截图、模型返回动作和任务完成信息。

同时查看截图与权限后端时，可使用：

```text
tag:AgentEngine | tag:RemoteBackend | tag:PrivilegedService | tag:CaptureOps | tag:VirtualDisplayManager | tag:DirectConnector
```

不要同时只过滤当前应用包名，否则可能排除运行在独立 shell 进程中的权限服务日志。

### 在自己的代码中添加日志

例如在 Kotlin 文件中使用：

```kotlin
import android.util.Log

// 放在实际需要观察的方法中。
Log.d("MyAgentDebug", "开始处理我的新功能")
```

然后在 Logcat 中搜索：

```text
tag:MyAgentDebug
```

日志适合确认某段代码有没有执行、某一步花费多久，以及异常发生前执行到了哪里。记录必要的状态即可，避免把 API Key 写进日志。

### 保存日志到文件

也可以继续使用已经验证可用的 PowerShell 命令：

```powershell
adb logcat -b all -v threadtime -s AgentEngine RemoteBackend PrivilegedService PrivilegedServiceMain CaptureOps VirtualDisplayManager DirectConnector | Tee-Object -FilePath .\capture-detail.txt
```

先运行命令，再在手机复现任务，结束后按 **Ctrl+C**。文件保存在启动命令时的当前目录。使用 `-b all` 会包含缓冲区中的历史日志，分析时要核对本次任务的时间。

官方参考：[使用 Logcat 查看日志](https://developer.android.com/studio/debug/logcat)。

## 13. 用断点查看变量和执行位置

当日志已经把问题缩小到某个方法时，可以使用断点：

1. 打开相关 Kotlin 文件，点击代码行号旁边的空白处，出现红点。
2. 使用 Android Studio 的 **Debug** 按钮启动应用。
3. 在手机执行对应操作。
4. 代码运行到断点后，电脑上的调试器暂停执行。
5. 查看变量、调用栈，并选择单步执行或继续运行。

刚开始可以先熟悉 Logcat，再逐渐使用断点。

本项目的主应用和权限服务是不同进程。普通 Debug 启动主要连接应用进程；ADB Direct 启动的 shell 权限服务不会自动获得相同的断点调试体验。排查截图、输入注入或虚拟屏幕问题时，需要结合权限服务日志。

## 14. 修改底层权限服务后，确认加载了新代码

应用中的页面、Agent 流程，以及 ADB/Shizuku 权限服务，不一定具有相同的进程生命周期。

| 修改内容 | 更新后重点验证 |
| --- | --- |
| 文案、布局、页面交互 | 手机显示和操作是否符合预期 |
| 模型请求、Agent 逻辑 | 真实任务结果和 AgentEngine 日志 |
| 截图、虚拟屏幕、输入注入 | 权限服务日志，以及服务是否加载了新版本 |
| 数据存储、核心逻辑 | 对应测试、数据兼容性与真机行为 |

修改权限服务代码后，如果更新 APK 后表现仍像旧版本，需要确认旧权限进程已经退出、新进程已启动。刚入门时，可以通过重启手机、重新连接应用的 ADB 后端来排除旧进程干扰；普通页面改动不需要因此每次重启手机。

日常先使用完整 Run 流程。熟悉后可以学习 Android Studio 的 Apply Changes 来加快部分改动的部署，具体是否可用取决于改动内容。

## 15. 项目代码从哪里开始看

以下路径均相对于仓库根目录：

| 路径 | 内容 |
| --- | --- |
| `android/app/src/main/java/ai/opencyvis/ui/` | 主要页面和交互 |
| `android/app/src/main/java/ai/opencyvis/engine/` | Agent 执行流程和状态 |
| `android/app/src/main/java/ai/opencyvis/llm/` | 模型接口请求与响应解析 |
| `android/app/src/main/java/ai/opencyvis/backend/` | ADB/Shizuku 权限服务、截图与输入操作 |
| `android/app/src/main/java/ai/opencyvis/display/` | 虚拟屏幕管理 |
| `android/app/src/main/java/ai/opencyvis/config/` | 模型及应用配置 |
| `android/app/src/main/res/` | 文案、布局、图标等资源 |
| `android/app/src/main/jni/` | C++ 原生代码 |
| `android/app/src/test/` | 本地单元测试 |
| `android/app/src/androidTest/` | 设备端测试 |
| `tests/` | Python E2E 测试与场景 |

进一步理解项目时，可以阅读 [架构说明](architecture.md) 和 [组件说明](components.md)。

## 16. 运行测试与生成独立 APK

以下是 **Gradle 任务名**，可以在 Android Studio 的 Gradle 工具窗口中通过 Execute Gradle Task 执行。它们不是直接输入 PowerShell 的可执行命令。

| 任务 | 用途 |
| --- | --- |
| `:app:assembleStandardDebug` | 构建普通手机调试版 APK |
| `:app:testStandardDebugUnitTest` | 运行该版本的本地单元测试 |
| `:app:lintStandardDebug` | 运行该版本的 Android lint 检查 |

当前仓库提供的是 `android/gradlew` shell 脚本，没有 `gradlew.bat`，因此本文的 Windows 构建流程优先使用 Android Studio 按钮或 Gradle 面板。

需要单独的安装包时，运行 `:app:assembleStandardDebug`。生成的文件通常位于：

```text
android\app\build\outputs\apk\standard\debug\app-standard-debug.apk
```

安装自己生成、签名一致的后续调试 APK，可以使用：

```powershell
adb install -r "E:\python_notebook\LLM26fall\GUI-Phone\opencyvis-phone-main\android\app\build\outputs\apk\standard\debug\app-standard-debug.apk"
```

`-r` 表示更新安装，不能绕过签名不一致的问题。日常开发直接点击 Run，Android Studio 会处理构建和部署。

## 17. 常见现象怎么区分

| 现象 | 先检查的位置 |
| --- | --- |
| Gradle Sync 失败 | JDK 路径、SDK/NDK/CMake 缺失、依赖下载错误 |
| 编译失败，应用没有更新 | Android Studio 的 Build 窗口 |
| 电脑找不到手机 | USB 调试授权、数据线、`adb devices` |
| 更新安装提示签名冲突 | 已安装 APK 与本地 APK 的签名 |
| 修改后手机没有变化 | 是否部署了最新构建、选中的设备及构建版本是否正确 |
| 普通问答能用，Agent 任务失败 | 找到失败步骤，结合截图、模型请求和动作日志判断 |
| Active Backend 已连接但截图失败 | 远端截图日志；连接成功不代表每次截图都成功 |
| 修改底层代码后仍表现为旧逻辑 | 独立权限服务是否重新加载 |

此前日志出现过两种不同现象：

- `Failed to capture screenshot`：任务未获取到截图，当前步骤无法继续。
- `Failed to save debug screenshot`，同时伴随 `/sdcard/... EPERM`：调试图片写入公共存储失败。如果前面已显示 `captured via backend`，并且后续模型请求和动作正常，这条保存警告没有阻断任务。

已有日志显示同一个虚拟屏幕在不同时间既有截图失败，也有截图成功。因此首次截图偶发失败的具体原因尚未确认，不能仅凭一次成功就认为已经修复，也不能仅凭失败就判断整台手机完全不兼容。

## 18. 第一次配置完成的检查清单

- [ ] Android Studio 已安装。
- [ ] 打开的是仓库的 `android` 工程。
- [ ] macOS 专属 Java 路径已注释，并选择 JDK 17。
- [ ] SDK API 36、NDK 和 CMake 已准备，Gradle Sync 成功。
- [ ] 构建版本为 `standardDebug`。
- [ ] Android Studio 能识别 USB 连接的手机。
- [ ] 自己编译的应用能安装并启动。
- [ ] 模型设置与 ADB Direct 后端可用。
- [ ] 能完成一次“帮我打开手机设置”。
- [ ] 能从电脑 Logcat 中看到对应的任务日志。
- [ ] 修改一次应用名称后，Run 能把变化更新到手机。

完成这些步骤后，就可以在同一套流程中逐步实现自己的功能：每次修改一个明确行为，部署到手机，用任务结果、日志和测试判断是否符合预期。
