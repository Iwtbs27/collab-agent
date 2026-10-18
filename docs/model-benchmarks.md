# 模型评测与对比

本文档记录 AIPhone agent 在各 LLM 上的能力与性能测试结果。测试环境为 AOSP 模拟器 + Mac (Apple Silicon)。

## Provider 配置

| Provider | 默认模型 | Endpoint | 备注 |
|----------|---------|----------|------|
| MiMo (小米) | `mimo-v2.5` / `mimo2.5-pro` | `https://token-plan-cn.xiaomimimo.com/v1` | **当前默认**，Token Plan 国内版 |
| OpenAI-compatible | `qwen3.6-plus-2026-04-02` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 阿里云 DashScope |
| Anthropic | `Claude-Opus-4.6` | `https://modelservice.jdcloud.com/anthropic` | 京东云代理 |
| Ollama (本地) | `gemma4:26b-a4b-it-q4_K_M` | `https://your-server:11434` | 无需 API key |

---

## 性能对比

### 云端模型

| 模型 | Provider | 每步延迟 | Tool calling | 备注 |
|------|----------|----------|-------------|------|
| Qwen 3.5 Plus | OpenAI-compat | 4-6s | ✅ 稳定 | 默认生产模型，性价比高 |
| Claude Opus 4.6 | Anthropic | 4-8s | ✅ 稳定 | 推理能力最强，成本较高 |
| Doubao (豆包) | OpenAI-compat | ~5s | ✅ | OkHttp+SSE 优化后冷启动 4.8s (原 5.9s) |
| MiMo v2.5 (小米) | OpenAI-compat | 2.3-4.5s | ✅ 稳定 | 多模态 Agent 模型，1M 上下文，3/4 测试通过，Token Plan 计费 |
| MiMo 2.5-Pro (小米) | OpenAI-compat | 3-7s | ✅ 稳定 | v2.5 Pro 版，3/4 通过，步数偏多效率低于 v2.5 |

> 云端模型主要瓶颈是服务端推理时间，客户端已近最优（2026-03-19 基准测试）。

### 本地模型 (Ollama)

| 模型 | 架构 | 磁盘 / VRAM | 生成速度 | 每步延迟 | Tool calling | UI 测试 |
|------|------|-------------|----------|----------|-------------|---------|
| **Gemma 4 26B-A4B** Q4_K_M | MoE (26B/4B) | 17GB | 63 tok/s | 7-9s | ✅ 稳定 | **4/4** |
| **Gemma 4 E4B** Q4_K_M | Dense (4B) | 3GB | 61 tok/s | 7-9s | ✅ | 3/4 |
| **Gemma 4 E2B** Q4_K_M | Dense (2B) | 1.8GB | 41 tok/s | 7-10s | ✅ | **4/4** |
| **Qwen 3.5 35B-A3B** Q4_K_M | MoE (35B/3B) | 22GB | 47 tok/s | 6.5-10s | ⚠️ 多走 text fallback | 3/4 |
| **Gemma 4 31B** Q4_K_M | Dense | 19GB | 16.3 tok/s | 10-25s | ✅ 较稳定 | 4/4 |
| **Gemma 4 31B** bf16 | Dense | 62GB | 6.8 tok/s | 50-80s | ✅ | 未完整测试（太慢） |
| **GUI-Owl-1.5 8B-Think** Q4_K_M | Dense (8B) | 5.4GB | 75 tok/s | 4-5s | ✅ | 2/4 |
| **GUI-Owl-1.5 32B-Think** Q4_K_M | Dense (32B) | 20.5GB | 23 tok/s | 10-15s | ✅ | 2/4 |

---

## UI 测试详情

标准测试场景：`open_settings`、`dial_66666`、`impossible_task`、`call_jimmy`

### Gemma 4 26B-A4B Q4_K_M (本地, 2026-04-03)

| 场景 | 结果 | 步数 | 总耗时 | 说明 |
|------|------|------|--------|------|
| open_settings | ✅ PASS | 2 | ~25s | 首步含模型冷加载 (18.6s)，热启动后 ~7s/步 |
| dial_66666 | ✅ PASS | 3 | ~22s | 每步 ~7s |
| impossible_task | ✅ ask_user | 2 | ~24s | 尝试 Google Home 后 ask_user |
| call_jimmy | ✅ ask_user | 5 | ~50s | 联系人为空时 ask_user 要号码 |

注意事项：
- Tool calling 稳定，正确使用 `tool_calls` 返回格式
- 63 tok/s，本地模型中最快
- 4/4 测试全通过，行为合理
- **当前推荐的本地默认模型**

### Gemma 4 E4B Q4_K_M (本地, 2026-04-03 单次 → 2026-05-07 10轮)

**单次测试 (2026-04-03):**

| 场景 | 结果 | 步数 | 总耗时 | 说明 |
|------|------|------|--------|------|
| open_settings | ✅ PASS | 2 | ~27s | 冷启动 20s + 热 6s |
| dial_66666 | ✅ PASS | 3 | ~23s | 每步 ~7-9s |
| impossible_task | ✅ ask_user | 2 | ~20s | |
| call_jimmy | ❌ 死循环 | 11+ | >120s | 不停在搜索框输入 jimmy，无法判断搜索无结果应换策略 |

**10 轮统计测试 (2026-05-07, via Cloudflare tunnel → Ollama):**

| 场景 | 通过率 | 平均耗时 | 平均步数 | 说明 |
|------|--------|----------|----------|------|
| open_settings | **90%** (9/10) | 35.4s | 2.4 | 仅 1 次超时 |
| dial_66666 | **100%** (10/10) | 45.7s | 3.6 | 全部通过 |
| impossible_task | **100%** (10/10) | 6.4s | 0.0 | 秒判 fail，极快 |
| call_jimmy | **20%** (2/10) | 247.8s | 0.1 | 8 次超时 (301s)，0 步——多数轮次无法生成有效首步 |
| **整体** | **78%** (31/40) | — | — | |

注意事项：
- 简单任务表现优秀：open_settings 90%、dial_66666 100%、impossible_task 100%
- impossible_task 仅 6.4s 平均，所有模型中最快
- call_jimmy 严重不稳定 (20%)：超时时 steps=0，根因见下方"Thinking→Tool Call 兼容性问题"

### Gemma 4 E2B Q4_K_M (本地, 2026-04-03 单次 → 2026-05-07 10轮)

**单次测试 (2026-04-03):**

| 场景 | 结果 | 步数 | 总耗时 | 说明 |
|------|------|------|--------|------|
| open_settings | ✅ PASS | 2 | ~18s | |
| dial_66666 | ✅ PASS | 3 | ~22s | |
| impossible_task | ✅ ask_user | 3 | ~28s | |
| call_jimmy | ✅ ask_user | 4 | ~58s | step 2 犹豫较久 (31s)，但最终正确 ask_user |

**10 轮统计测试 (2026-05-07, via Cloudflare tunnel → Ollama):**

| 场景 | 通过率 | 平均耗时 | 平均步数 | 说明 |
|------|--------|----------|----------|------|
| open_settings | **70%** (7/10) | 121.0s | 5.3 | 3 次超时 (180s)，通过时也较慢 |
| dial_66666 | **30%** (3/10) | 144.7s | 6.4 | 7 次超时，步数高说明在反复尝试 |
| impossible_task | **100%** (10/10) | 57.3s | 0.2 | 全部通过，但比 E4B 慢 9 倍 |
| call_jimmy | **90%** (9/10) | 89.7s | 1.5 | 显著优于 E4B，会正确 ask_user |
| **整体** | **72%** (29/40) | — | — | |

注意事项：
- call_jimmy 90% vs E4B 20% — E2B 的 thinking→tool_call 转换概率更高（见下方根因分析）
- dial_66666 仅 30% — 比 E4B 差很多，步数高 (6.4) 说明反复尝试但不收敛
- 各场景耗时均显著高于 E4B（2-9 倍），说明通过 Cloudflare tunnel 后 E2B 生成质量不稳定
- 与单次测试差异大：单次 4/4 全通过，10 轮暴露了不稳定性

### E2B vs E4B 10 轮对比总结 (2026-05-07)

| 维度 | E2B (2B Dense) | E4B (4B Dense) | 优胜 |
|------|----------------|----------------|------|
| 总通过率 | 72% (29/40) | **78%** (31/40) | E4B |
| 简单任务 (open_settings + dial_66666) | 50% | **95%** | **E4B 压倒性** |
| 判断力 (impossible_task) | 100% | 100% | 平局 |
| 复杂任务 (call_jimmy) | **90%** | 20% | **E2B 压倒性** |
| 平均速度 | 慢 (57-145s) | **快 (6-46s)** | **E4B** |

**结论：**
- E4B 在简单/中等任务上速度和成功率都碾压 E2B，推荐作为快速响应场景默认
- E2B 在需要 ask_user 判断的复杂任务上更可靠，但整体速度慢、不稳定
- call_jimmy 对 E4B 的失败根因：Gemma4 thinking 模式与 Ollama tool_call 的兼容性问题（详见下方分析）
- 生产环境建议：简单场景用 E4B，复杂场景仍需 26B-A4B 或云端模型

### Thinking→Tool Call 兼容性问题（根因分析, 2026-05-08）

E4B call_jimmy 20% 通过率的根因**不是英文指令、不是模型"overconfident"**，而是 Gemma4 thinking 架构与 Ollama tool calling 机制的兼容性问题。

**发现过程：** 通过 curl 直接请求 Ollama API 抓取 raw response，发现：

```
# E4B non-streaming response (Phone app UI + "call jimmy"):
{
  "message": {
    "role": "assistant",
    "content": "",                    ← 空
    "thinking": "The user wants to call jimmy. I should type jimmy into search bar..."  ← 正确推理
    // 没有 tool_calls 字段！
  },
  "done_reason": "stop",             ← 直接停止
  "eval_count": 81                   ← 只有 81 tokens（thinking 消耗后立刻 EOS）
}
```

**对比 E2B 同一场景：**
```
{
  "message": {
    "thinking": "The user wants to call jimmy...",   ← 也有 thinking
    "tool_calls": [{"function": {"arguments": ...}}] ← 但继续输出了 tool_call！
  },
  "done_reason": "stop",
  "eval_count": 360                  ← 360 tokens（thinking 后继续生成 tool_call）
}
```

**隔离实验结果（同一 prompt: Phone app + "call jimmy"）：**

| 条件 | E4B 产出 tool_call | E2B 产出 tool_call |
|------|---|---|
| non-streaming, 5 次 | **0/5** (0%) | **3/5** (60%) |
| streaming, 5 次 | **1/5** (20%) | **3/5** (60%) |

**机制解释：**

两个模型都是 Gemma4 thinking model，都会先生成 `thinking` 字段做内部推理。差异在于：

1. **E4B** 在 thinking 完成后，几乎总是直接输出 EOS token（`done_reason: "stop"`），不再生成 tool_call 输出。eval_count 仅 81，说明 thinking→tool_call 的转换概率（transition probability）接近于零。模型"认为"自己在 thinking 中已经完成了推理，不需要再外化为 tool call。

2. **E2B** 在 thinking 完成后，有 ~60% 的概率继续生成 tool_call 格式的输出。eval_count 132-452，说明它的 thinking→tool_call transition probability 显著高于 E4B。

3. **为什么简单场景（open_settings）E4B 能成功？** 简单场景的 thinking 更短，模型更容易在 thinking 后"惯性"继续生成 tool_call。但一旦 UI 变复杂（Phone app 43 个 UI 节点），thinking 变长变复杂，转换到 tool_call 的概率急剧下降。

4. **为什么 benchmark 里 E2B call_jimmy 能到 90%？** OllamaClient 使用 streaming + 最多 6 次 retry。每次有 ~60% 概率成功，6 次 retry 后综合成功率 ≈ 1 - 0.4⁶ ≈ 99.6%。

5. **与语言无关：** 中文版 "打电话给jimmy" 同样产出空 tool_call，排除了英文指令假说。

**这是 Ollama 的已知局限：** Gemma4 thinking 模式下 tool calling 不稳定，属于 Ollama 对 thinking model + tool_call 的支持不完善。可能的缓解方案：
- 在 Ollama 请求中设置 `"think": false` 禁用 thinking（但实测对 E4B 无效，仍然空输出）
- 增加 retry 次数（当前 6 次对 E2B 足够，但 E4B 的 ~20% 成功率需要更多次）
- 等待 Ollama 修复 thinking model 的 tool_call 生成问题

### Qwen 3.5 35B-A3B (本地, 2026-04-03)

| 场景 | 结果 | 步数 | 总耗时 | 说明 |
|------|------|------|--------|------|
| open_settings | ✅ PASS | 2 | ~13s | |
| dial_66666 | ✅ PASS | 3 | ~23s | |
| impossible_task | ✅ ask_user | 3 | ~31s | 先尝试找 Google Assistant 和 Settings，最终 ask_user |
| call_jimmy | ❌ fail | 4 | ~41s | 联系人为空时直接 fail，未 ask_user 要号码 |

注意事项：
- **必须关闭 thinking 模式**（`"think": false`），否则每次请求浪费 400+ token 在 CoT 上
- Tool calling 不稳定：4 步中通常只有第 1 步用 `tool_calls`，后续走 text fallback
- 中文推理质量好

### Gemma 4 31B Q4_K_M (本地, 2026-04-03)

| 场景 | 结果 | 步数 | 总耗时 | 说明 |
|------|------|------|--------|------|
| open_settings | ✅ PASS | 2 | ~30s | |
| dial_66666 | ✅ PASS | 3 | ~50s | |
| impossible_task | ✅ fail | 2 | ~30s | 正确识别为不可能任务 |
| call_jimmy | ✅ ask_user | 4 | ~70s | 联系人为空时 ask_user 要号码 |

### GUI-Owl-1.5 8B-Think Q4_K_M (llama.cpp, 2026-04-07)

运行方式：llama-server + mmproj（Ollama 不支持第三方 Qwen3-VL GGUF，需用 llama.cpp）

| 场景 | 结果 | 步数 | 总耗时 | 说明 |
|------|------|------|--------|------|
| open_settings | ✅ PASS | 2 | ~10s | 干净的 2 步完成 |
| dial_66666 | ❌ FAIL | 20 (max) | ~126s | type_text 死循环：思维链正确识别问题但 action 不变 |
| impossible_task | ✅ PASS | 1 | ~4s | 正确使用 fail action |
| call_jimmy | ❌ FAIL | 20 (max) | ~120s | 从未使用 ask_user，尝试盲搜联系人 |

注意事项：
- 75 tok/s，所有测试模型中最快
- **推理-行动脱节**：思维链中正确分析了问题（如"type_text 是追加不是替换"），但输出的 action 不匹配
- 不会使用 `ask_user` — GUI-Owl 训练侧重 GUI 操作（tap/type/scroll），非对话式 agent 工具
- llama-server 每次请求首次连接 Broken pipe，需重试（+1s/步开销）

### GUI-Owl-1.5 32B-Think Q4_K_M (llama.cpp, 2026-04-07)

| 场景 | 结果 | 步数 | 总耗时 | 说明 |
|------|------|------|--------|------|
| open_settings | ✅ PASS | 2 | ~30s | 干净完成 |
| dial_66666 | ❌ FAIL | 3 | ~82s | 思维链中重复 "666..." 数百次导致 JSON 截断 |
| impossible_task | ❌ FAIL | 3+ | ~129s | 忽略元指令，继续操作上一任务残留的拨号界面 |
| call_jimmy | ✅ PASS | 7 | ~114s | 正确使用 ask_user，但 type_text 仍有幻觉 |

注意事项：
- 32B 修复了 8B 的 ask_user 问题（call_jimmy 通过），但引入新问题
- 思维链幻觉：`<think>` 中重复数字导致 JSON buffer 溢出
- 指令遵循退化：impossible_task 在 8B 上通过但 32B 失败
- **结论：更大参数不一定更好，GUI-Owl 的训练分布与我们的工具 schema 不匹配是根本原因**

### MiMo v2.5 (小米云端, 2026-05-06)

| 场景 | 结果 | 步数 | 总耗时 | 说明 |
|------|------|------|--------|------|
| open_settings | ✅ PASS | 1 | ~4s | 已在前台，直接 finish |
| dial_66666 | ✅ PASS | 4 | ~12s | 正确拨号，识别无网络用 fail |
| impossible_task | ❌ FAIL | 42+ | >3min | 死循环找京东 app，不会 ask_user 或 fail |
| call_jimmy | ✅ PASS | 6 | ~25s | 检测无联系人后正确 ask_user |

注意事项：
- **每步延迟 ~2.3-4.5s**，当前测试过的云端模型中最快
- Tool calling 稳定，正确使用 `tool_calls` 格式，thought 描述准确
- 多模态模型（支持文本/图片/视频/音频），1M 上下文，128K 最大输出
- impossible_task 表现差：app 未安装时反复尝试打开应用抽屉，不会判断为不可能任务
- 部署命令：`./android/deploy-emu.sh mimo`

### MiMo 2.5-Pro (小米云端, 2026-05-14)

| 场景 | 结果 | 步数 | 总耗时 | 说明 |
|------|------|------|--------|------|
| open_settings | ✅ PASS | 2 | ~7s | tap Settings → finish |
| dial_66666 | ✅ PASS | 5 | ~17s | open_app → type_text → tap 拨号 → 识别无网络 fail |
| impossible_task | ❌ FAIL | 18+ | >3min | 打开京东 app、搜索 iPhone、遇登录页反复 handoff_user，最终 ask_user，但未判断不可能 |
| call_jimmy | ✅ PASS | 20 | ~100s | 兜圈较多（拨号→联系人搜索），最终搜 jimmy 无结果后 ask_user |

注意事项：
- **每步延迟 ~3-7s**，比 v2.5 稍慢
- Tool calling 稳定，thought 推理详细
- impossible_task 比 v2.5 有微弱改善：会 handoff_user 而非纯死循环，但仍未识别为不可能任务
- call_jimmy 步数偏多（20 vs v2.5 的 6），效率较低
- 部署命令：`MODEL=mimo2.5-pro ./android/deploy-emu.sh mimo`

---

## 能力矩阵

| 能力 | Qwen 3.5 Plus (云) | Claude Opus 4.6 (云) | MiMo v2.5 (云) | MiMo 2.5-Pro (云) | Gemma 4 26B-A4B | Gemma 4 E4B | Gemma 4 E2B | Qwen 3.5 35B | Gemma 4 31B | GUI-Owl 8B | GUI-Owl 32B |
|------|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| Vision (截图理解) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Tool calling | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ✅ | ✅ |
| 中文理解 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 不可能任务识别 | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| ask_user 判断 | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ (90%) | ❌ | ✅ | ❌ | ✅ |
| 复杂任务推理 | ✅ | ✅ | ⚠️ | ⚠️ | ✅ | ❌ (20%) | ⚠️ (72%) | ⚠️ | ✅ | ❌ | ❌ |
| 离线可用 | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 零成本 | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

---

## 测试方法

```bash
# 部署指定 provider
./android/deploy-emu.sh              # 默认 (qwen cloud)
./android/deploy-emu.sh mimo         # 小米 MiMo
./android/deploy-emu.sh ollama       # 本地 Ollama

# 切换 Ollama 模型（修改 ConfigRepository.DEFAULT_OLLAMA_MODEL）

# 发送测试指令（新方式，dumpsys 接口）
adb shell dumpsys opencyvis start "打开系统设置"

# 发送测试指令（旧方式，broadcast）
adb shell "am broadcast -a ai.opencyvis.TEST -p ai.opencyvis --es instruction '...'"

# 多轮统计基准测试（10 轮 × 2 模型 × 4 场景 = 80 次执行）
python3 -m tests.e2e.benchmark_model --rounds 10 --serial emulator-5554

# 查看性能日志
adb logcat | grep -E "AgentEngine.*TIMING|AgentEngine.*Task completed"
```

## 更新记录

- **2026-05-14**: 新增小米 MiMo 2.5-Pro 云端测试 — 3/4 通过，与 v2.5 一致；每步 3-7s（比 v2.5 慢），步数偏多效率低；impossible_task 微弱改善（handoff_user 而非纯死循环）
- **2026-05-08**: E4B call_jimmy 根因分析 — 非英文/overconfident 问题，是 Gemma4 thinking→tool_call 转换概率差异 (E4B ~0% vs E2B ~60%)
- **2026-05-07**: E2B vs E4B 10 轮统计基准测试 — E4B 简单任务碾压 (95% vs 50%)，E2B call_jimmy 更可靠 (90% vs 20%)，E4B 速度优势显著
- **2026-05-06**: 新增小米 MiMo v2.5 云端完整测试 — 每步 ~2.3-4.5s（当前最快云端模型），3/4 通过，impossible_task 死循环
- **2026-04-07**: 新增 GUI-Owl-1.5 8B/32B-Think 测试 — 通过 llama.cpp 运行，均 2/4；GUI 专用模型在自定义工具 schema 上不如通用模型
- **2026-04-03**: 新增 Gemma 4 E2B/E4B 小模型测试 — E2B 意外 4/4 通过，E4B call_jimmy 死循环
- **2026-04-03**: 新增 Gemma 4 26B-A4B MoE 测试 — 63 tok/s, 4/4 通过，设为本地默认
- **2026-04-03**: 新增 Qwen 3.5 35B-A3B 本地测试，OllamaClient 添加 `think:false`
- **2026-04-03**: Gemma 4 31B Q4/bf16 本地测试
- **2026-03-19**: OkHttp+SSE 优化，Doubao 云端基准测试
