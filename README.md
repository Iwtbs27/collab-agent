<p align="center">
  <img src="assets/omniact-logo.png" alt="OmniAct" width="720">
</p>

<p align="center">
  <strong>一个 Agent，跨越每个数字环境。</strong><br>
  One agent. Every digital environment.
</p>

<p align="center">
  <a href="README.en.md">English</a> · <strong>简体中文</strong>
</p>

<p align="center">
  OmniAct 是一个轻量级的跨环境通用行动 Agent，帮助用户在浏览器、桌面应用和 CLI 之间完成真实的数字工作。
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-111827.svg" alt="MIT License"></a>
  <img src="https://img.shields.io/badge/python-3.11-3776AB.svg" alt="Python 3.11">
  <img src="https://img.shields.io/badge/status-early%20development-F59E0B.svg" alt="早期开发">
</p>

## 为什么是 OmniAct

很多 Agent 在单一环境中表现出色：Coding Agent 在代码仓库中工作，Browser Agent 在网页中工作，Desktop Agent 在应用中工作。但真实的数字任务经常跨越这些环境。

OmniAct 围绕这个边界设计：

- **一个目标，多个环境。** TeamLeader 判断是直接推理，还是把工作交给专业 Worker。
- **专业化执行。** Browser、CLI 和 Desktop Worker 保留适合各自环境的观测与动作循环。
- **交付真实结果。** Worker 通过 Markdown 摘要和具体产物交接，而不是只返回一句“已完成”。
- **任务可以继续。** 并行子任务、人工接管、可恢复的 CLI 会话和执行轨迹支持更长的任务。
- **保持核心轻量。** 协作关系清晰直接，底层执行引擎可以独立演进。

## 工作方式

```text
用户目标
   |
   v
TeamLeader
   |
   +-- Browser Worker  -> 浏览器观测与产物
   +-- CLI Worker      -> Codex 会话与文件产物
   +-- Desktop Worker  -> Windows 应用操作与产物
   |
   v
任务结果、执行轨迹和最终交付物
```

TeamLeader 负责整体任务，只派发边界清晰的子任务。每个 Worker 负责自己的执行环境，并将交接内容写入任务目录。Worker 不需要共享内部状态，也不直接依赖其他 Worker。

任务目录让协作过程透明可检查：

```text
tasks/<task-id>/
├─ task.md
├─ trace.jsonl
├─ trajectory.md
├─ workers/
│  └─ <worker-name>/
│     ├─ request.md
│     ├─ result.md
│     └─ artifacts/
└─ final/
   └─ result.md
```

## 快速开始

### 环境要求

- 已安装 Microsoft Edge 的 Windows 环境
- Conda 或其他 Python 环境管理器
- Python 3.11
- 为 TeamLeader 提供原生工具调用能力的 OpenAI 兼容接口
- 为 Browser Worker 提供视觉能力的 OpenAI 兼容接口
- 使用 CLI Worker 时，需要安装 Codex CLI

### 安装

```powershell
conda create -n omniact python=3.11
conda activate omniact
python -m pip install -r requirements.txt
Copy-Item .env.example .env
```

### 配置

在 `.env` 中填写模型服务配置：

```dotenv
TEAM_LEADER_API_KEY=
TEAM_LEADER_BASE_URL=
TEAM_LEADER_MODEL=

BROWSER_WORKER_API_KEY=
BROWSER_WORKER_BASE_URL=
BROWSER_WORKER_MODEL=
BROWSER_WORKER_SESSION_MODE=inherited

# 可选：Windows Desktop Worker
DESKTOP_WORKER_ENABLED=false
DESKTOP_WORKER_API_KEY=
DESKTOP_WORKER_BASE_URL=
DESKTOP_WORKER_MODEL=
DESKTOP_WORKER_MAX_STEPS=50

# 可选：Codex CLI Worker
CLI_WORKER_ENABLED=false
CODEX_EXECUTABLE=codex.cmd
CODEX_SANDBOX=workspace-write
CLI_WORKER_WORKING_DIRECTORY=
CLI_WORKER_MAX_TURNS=3
```

`BROWSER_WORKER_SESSION_MODE` 支持 `inherited` 和 `clean`。`inherited` 会将当前 Edge Profile 复制到隔离的 Worker 浏览器中，复用登录态但不会接管用户正在使用的浏览器窗口。

### 运行

在项目根目录执行：

```powershell
python examples\run_team_leader.py
```

Worker 暂停等待人工接管时，示例程序支持：

```text
takeover <worker-id>
resume <worker-id>
cancel <worker-id>
```

运行产物会写入 `tasks/<task-id>/`，其中包含任务说明、Worker 请求与结果、截图、文件产物和可读的执行轨迹。

## 验证

```powershell
python -m compileall -q src tests examples
python -B -m unittest discover -s tests -v
```

## Roadmap

- 使用 Benchmark 驱动 Browser Worker 和 Desktop Worker 优化
- 提高人工接管和部分失败后的恢复可靠性
- 支持周期性数字工作的定时任务
- 提供提交任务和查看产物的本地任务控制台
- 引入更多执行引擎和可复用 Skill

## 开源说明

OmniAct 仍在积极开发中。公开仓库保持运行时核心聚焦于任务编排、Worker 边界、任务产物和可读执行轨迹；更大的产品界面会在执行流程稳定后逐步加入。

项目包含经过修改的开源组件，相关署名和许可证信息请查看 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可证

OmniAct 使用 [MIT License](LICENSE) 发布。
