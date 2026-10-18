# Repository Guidelines

## 项目结构

- `android/app/src/main/java/ai/opencyvis/`：Android 应用主要 Kotlin 代码；资源、模型文件与 JNI/C++ 代码分别位于 `res/`、`assets/`、`jni/`。
- `android/app/src/test/` 和 `src/androidTest/`：本地单元测试与设备端测试。
- `tests/`：Python pytest 端到端测试、场景定义与模拟服务；`docs/`、`scripts/` 提供文档和开发辅助脚本。
- `android/` 包含 Gradle 与 AOSP 集成配置；应用区分 `system`、`standard` 两种构建 flavor。

## 构建、测试与开发

从仓库根目录运行 Python 测试：`python -m pytest tests/ -v`。Android 命令在 `android/` 目录运行：`./gradlew assembleDebug` 构建调试包，`./gradlew test` 运行单元测试，`./gradlew lint` 执行 Android lint。设备端 E2E 测试需要已连接并部署应用的 Android 模拟器；CI 使用 `pytest tests/e2e/test_scenarios.py -v --serial emulator-5554 --category smoke`。

## 代码风格与命名

Kotlin 遵循标准 Kotlin 惯例，类型使用 `UpperCamelCase`，函数和属性使用 `lowerCamelCase`。Python 遵循 PEP 8；测试文件使用 `test_*.py`。保持改动聚焦，并为新功能或缺陷修复补充对应测试。

## 测试指南

优先运行受影响模块的测试；涉及 Android UI、ADB 或虚拟显示行为时，在模拟器上验证。新增 Kotlin 单元测试放入 `android/app/src/test/`，仪器测试放入 `android/app/src/androidTest/`；Python 场景测试放入 `tests/` 下合适目录。

## 提交与 Pull Request

提交应聚焦且标题清楚描述改动；当前工作树未包含 Git 历史，且贡献说明未规定固定提交格式。PR 请说明改动目的与验证命令，关联相关 Issue（如有）；界面改动附截图，设备相关改动注明设备或模拟器环境。

## 配置提示

本地 Android SDK 路径写入 `android/local.properties`，不要提交该机器专属配置或平台签名密钥。AOSP 构建与签名步骤见 `android/README-AOSP.md`。
