# IM 远程控制使用指南

通过 Telegram 或飞书 IM 消息远程控制 AIPhone agent，实现实时任务下发、进度查看、状态控制。

## 功能概述

- 发送文字指令 → agent 自动执行手机操作
- 实时接收 agent 步骤进度、完成/错误通知
- `/status` 查看当前状态，`/stop` 终止任务
- agent 等待用户输入时可直接回复
- 配对码机制：只需配置 Bot 凭证，无需手动查找 Chat ID

## 一、Telegram 配置

### 1. 创建 Bot

1. 打开 Telegram，搜索 `@BotFather`，发送 `/newbot`
2. 按提示输入 bot 显示名和用户名（必须以 `bot` 结尾，如 `aiphone_bot`）
3. BotFather 返回 **Bot Token**（格式：`123456789:ABCdefGHIjklMNOpqrSTUvwxYZ`）

### 2. 在 App 中配置

打开 AIPhone → 设置 → Remote IM Control：

| 配置项 | 值 |
|--------|-----|
| Enable Remote IM | 开启 |
| Telegram Bot Token | 上面获取的 token |

### 3. 配对

1. 在 App 设置页面，找到 Telegram 区域的 **「Generate Code」** 按钮
2. 点击后显示 6 位配对码（10 分钟有效）
3. 在 Telegram 中给 bot 发送：`/pair 123456`（替换为实际配对码）
4. 收到「配对成功」回复即可开始使用

### 4. 使用

在 Telegram 中给 bot 发消息即可：

```
打开系统设置
```
```
帮我打电话给张三
```
```
/status        ← 查看 agent 状态
/stop          ← 终止当前任务
/unpair        ← 解除配对
```

---

## 二、飞书配置

### 1. 创建应用

1. 打开 [飞书开放平台](https://open.feishu.cn/app/)，登录
2. 点击「创建企业自建应用」，填写应用名称和描述
3. 进入应用 → 左侧「凭证与基本信息」，复制 **App ID**（`cli_xxx`）和 **App Secret**

### 2. 启用机器人能力

1. 左侧「应用能力」→「添加应用能力」→ 启用「机器人」

### 3. 配置权限

进入「权限管理」，开启以下权限：

| 权限 | 说明 |
|------|------|
| `im:message` | 发送消息 |
| `im:message.create_as_bot` | 以机器人身份发送消息 |
| `im:message.p2p_msg:readonly` | 读取私聊消息 |
| `im:resource` | 访问消息资源（图片等） |

### 4. 配置事件订阅（WebSocket）

1. 左侧「事件订阅」→ 连接方式选择 **WebSocket**
2. 添加事件：`im.message.receive_v1`（接收消息 v2.0）

### 5. 发布应用

1. 左侧「版本管理与发布」→ 创建版本 → 提交发布
2. 企业管理员审批通过后生效

### 6. 在 App 中配置

打开 AIPhone → 设置 → Remote IM Control：

| 配置项 | 值 |
|--------|-----|
| Enable Remote IM | 开启 |
| Feishu App ID | 上面获取的 app ID |
| Feishu App Secret | 上面获取的 app secret |

### 7. 配对

1. 在 App 设置页面，找到飞书区域的 **「Generate Code」** 按钮
2. 点击后显示 6 位配对码（10 分钟有效）
3. 在飞书中找到你的 bot，发送私聊消息：`/pair 123456`（替换为实际配对码）
4. 收到「配对成功」回复即可开始使用

### 8. 使用

在飞书中给 bot 发送私聊消息即可：

```
打开相机
```
```
帮我发一条短信给 13800138000
```
```
/status        ← 查看 agent 状态
/stop          ← 终止当前任务
/unpair        ← 解除配对
```

---

## 三、命令参考

| 命令 | 说明 |
|------|------|
| `/pair <code>` | 配对：输入 App 设置中显示的 6 位配对码 |
| `/unpair` | 解除配对 |
| `/status` | 查看 agent 当前状态（Idle/Running/步骤数） |
| `/stop` | 终止当前任务 |
| `/start` | 显示配对提示（未配对时） |
| 任意文本 | 作为指令发送给 agent 执行 |

agent 处于 `WaitingForUser` 状态时，直接回复文本即可提交用户输入。

## 四、配对安全机制

- 配对码为 6 位数字，10 分钟有效，过期需重新生成
- 同一发送者连续 5 次输入错误配对码后，将被锁定 10 分钟
- 配对码仅在内存中保存，App 重启后自动失效
- 仅支持私聊配对，群聊消息不支持
- 配对成功后，发送者 ID 被保存到白名单，重启后仍然有效

## 五、调试（dumpsys）

连接设备后可用 adb 命令调试 IM 功能：

```bash
# 查看 IM 状态
adb shell dumpsys opencyvis im state

# 查看出站消息记录
adb shell dumpsys opencyvis im outbound

# 模拟入站消息（调试用）
adb shell dumpsys opencyvis im inbound telegram <senderId> <chatId> <text>

# 设置配对码（调试用，不指定 code 则自动生成）
adb shell dumpsys opencyvis im set-pairing-code telegram
adb shell dumpsys opencyvis im set-pairing-code telegram 123456

# 启用 fake channel（不连真实 IM）
adb shell dumpsys opencyvis im fake on
```

## 六、注意事项

- 仅支持**单用户私聊**，不支持群聊
- 配对机制：通过 6 位配对码完成认证，无需手动查找 Chat ID / Open ID
- 白名单机制：配对成功后，只有配对的用户才能控制 agent
- Agent 忙碌时（Running 状态），新消息会被拒绝，需先 `/stop`
- Telegram offset 自动持久化，重启后不会重复处理旧消息
- 飞书使用 WebSocket 长连接接收消息，无需公网回调地址
- 解除配对后，需重新生成配对码并发送 `/pair` 命令
