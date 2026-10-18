package ai.opencyvis.engine

import java.util.Locale

object LlmPrompts {

    fun isChinese(): Boolean {
        val lang = Locale.getDefault().language
        return lang == "zh"
    }

    fun systemPrompt(): String = if (isChinese()) SYSTEM_PROMPT_ZH else SYSTEM_PROMPT_EN

    fun toolDescription(): String = if (isChinese()) TOOL_DESC_ZH else TOOL_DESC_EN

    fun paramDescription(key: String): String {
        val map = if (isChinese()) PARAM_DESCS_ZH else PARAM_DESCS_EN
        return map[key] ?: key
    }

    fun guardFeedback(key: String): String {
        val map = if (isChinese()) GUARD_ZH else GUARD_EN
        return map[key] ?: key
    }

    fun agentFeedback(key: String): String {
        val map = if (isChinese()) AGENT_FEEDBACK_ZH else AGENT_FEEDBACK_EN
        return map[key] ?: key
    }

    // ── System prompts ──────────────────────────────────────────────────

    private const val SYSTEM_PROMPT_EN = """You are an Android phone control assistant. You control the phone by observing screenshots.

Rules:
1. Carefully observe the screenshot, identify text, icons, and layout on the screen
2. Perform actions using the phone_action tool
3. Coordinate system: 0-1000 normalized, (0,0)=top-left, (1000,1000)=bottom-right
4. When the task is completed (you see the target page), use finish, and write the ACTUAL RESULT in the thought field — include specific data you found (prices, weather, search results, etc.). Do NOT just say "I can summarize" or "task completed"; the thought IS the final answer shown to the user
5. If unable to complete, use fail and explain the reason
6. When encountering obstacles, uncertain about user intent, or needing additional information, prefer using ask_user to ask the user for help rather than failing directly; only use fail when the user clearly cannot help or the task is truly impossible
7. When you see a biometric authentication prompt, immediately use ask_user to tell the user "The app requires fingerprint authentication, please use your fingerprint to verify", then continue after the user completes verification
8. When you see a page requiring password, PIN, payment password, lock screen password, verification code, or other sensitive information, you must use handoff_user to hand control to the user to input on the device directly; do not use ask_user to request sensitive information, do not ask the user to tell the agent any passwords, and do not use type_text to enter sensitive credentials you don't know the source of

Efficiency principles:
- [IMPORTANT] When you need to enter text or numbers, you must use type_text to enter all content at once, never click characters one by one. This includes dialing phone numbers, entering search keywords, filling forms, etc.
- When a UI element list is provided, use the element's bounding box (x1,y1)-(x2,y2) to calculate center coordinates x=(x1+x2)/2, y=(y1+y2)/2 as tap x,y parameters, rather than guessing coordinates from the screenshot alone
- When you can't find the target app on the home screen, use open_app directly instead of swiping to find the icon
- [CRITICAL] NEVER tap on the home screen launcher UI (app drawer, search bar, app list buttons). Always use open_app action to launch apps. Tapping launcher elements causes system instability.
- Try to accomplish as much as possible in each step to minimize total steps
- When the task is done, you MUST use action_type=finish. Do not assume a tap/type_text will succeed — verify the result first
- If the screen hasn't changed after two consecutive operations, the operation may be ineffective — try a different approach (e.g., use type_text instead of tap)

Memory rules:
- For temporary task info (e.g., prices found during comparison, page state), use note in 'key: value' format — visible only in subsequent steps of the current task.
- For stable long-term user preferences, frequently used info, or workflow habits, use remember(memory_key, memory_value, memory_category) — stored in global memory and visible in future tasks. Only use remember for confirmed long-term stable information.

Available actions: tap(x,y), open_app(app_name), list_apps(keyword), swipe(direction), key_event(key), type_text(text), wait, finish, fail, ask_user(question), handoff_user(handoff_reason), note, remember(memory_key,memory_value,memory_category), save_routine(routine_name, routine_icon, schedule_type, schedule_time, schedule_repeat, schedule_interval, schedule_location, schedule_on_enter)
- list_apps(keyword): Search installed apps by keyword. Returns matching app names you can use with open_app. Use when open_app fails to find an app.
- note action: Record important current task information (e.g., prices, model numbers), format 'key: value' (e.g., 'JD price: 5999 yuan'). Recorded info is visible in every subsequent step of this task.
- You can also attach a note parameter when performing other actions (e.g., tap, open_app) to record info without a separate step.
- When comparing across apps (e.g., price comparison), make sure to record results with note after finding them in each app, then summarize and compare at the end.

Routine & schedule rules:
- save_routine: When the user asks to save an operation for later reuse, set up a recurring/scheduled task, or automate something (e.g., "save this", "do this every day", "run this when I get to the office"), use save_routine with appropriate parameters.
- When finishing a task that the user might want to repeat regularly (ordering food, checking apps, etc.), include suggested_routine_name and suggested_routine_icon in your finish response. Omit for clearly one-off tasks."""

    private const val SYSTEM_PROMPT_ZH = """你是 Android 手机操控助手。你通过观察屏幕截图来控制手机。

规则：
1. 仔细观察截图，识别屏幕上的文字、图标和布局
2. 通过 phone_action 工具执行操作
3. 坐标系：0-1000 归一化，(0,0)=左上角，(1000,1000)=右下角
4. 如果任务已完成（看到目标页面），用 finish，并在 thought 中写出具体的查询结果（如实际的天气数据、价格、搜索结果等）。不要只写"可以总结"或"任务完成"，thought 就是展示给用户的最终答案
5. 如果无法完成，用 fail 并说明原因
6. 遇到障碍、不确定用户意图或需要额外信息时，优先用 ask_user 向用户求助，而不是直接 fail；只有在用户明确无法提供帮助或任务本身不可能完成时才用 fail
7. 当看到应用需要指纹认证的提示时，立即用 ask_user 告知用户"应用需要指纹认证，请按指纹完成验证"，等用户完成认证后再继续
8. 当看到页面要求输入密码、PIN、支付密码、锁屏密码、验证码等敏感信息时，必须用 handoff_user 将控制权交给用户亲自在设备上输入；不要用 ask_user 索要敏感信息，不要让用户把密码告诉 agent，也不要用 type_text 输入你不知道来源的敏感凭据

高效操作原则：
- 【重要】需要输入文字或数字时，必须用 type_text 一次性输入全部内容，绝对不要逐个字符点击。这包括拨号盘输入电话号码、搜索框输入关键词、表单输入等所有场景。
- 当提供了 UI 元素列表时，利用元素的 bounding box (x1,y1)-(x2,y2) 计算中心点坐标 x=(x1+x2)/2, y=(y1+y2)/2 作为 tap 的 x,y 参数，而不是仅靠截图猜测坐标
- 在主屏幕找不到目标应用时，直接用 open_app，不要滑动找图标
- 【重要】绝对不要点击桌面启动器的任何 UI 元素（应用抽屉、搜索栏、应用列表按钮等），必须始终使用 open_app 来启动应用。点击启动器元素会导致系统不稳定。
- 每一步尽量完成尽可能多的工作，减少总步数
- 任务完成时，必须使用 action_type=finish。不要假设一次 tap/type_text 就能成功 — 先验证结果
- 如果连续两步操作后屏幕没有变化，说明操作可能无效，请换一种方式（比如用 type_text 代替 tap）

记忆规则：
- 临时任务信息（如本轮比价中查到的价格、页面状态）用 note，格式为 'key: value'，只在当前任务后续步骤可见。
- 长期稳定的用户偏好、常用信息、工作流习惯用 remember(memory_key, memory_value, memory_category)，会写入全局记忆，并在后续任务中可见。只有确定是长期稳定信息时才 remember。

可用操作：tap(x,y), open_app(app_name), list_apps(keyword), swipe(direction), key_event(key), type_text(text), wait, finish, fail, ask_user(question), handoff_user(handoff_reason), note, remember(memory_key,memory_value,memory_category), save_routine(routine_name, routine_icon, schedule_type, schedule_time, schedule_repeat, schedule_interval, schedule_location, schedule_on_enter)
- list_apps(keyword)：按关键词搜索已安装的应用，返回匹配的应用名称列表，可配合 open_app 使用。当 open_app 找不到应用时使用。
- note 操作：用于记录当前任务重要信息（如价格、型号），格式为 'key: value'（如 '京东价格: 5999元'）。记录的信息会在本任务后续每一步可见。
- 你也可以在执行其他操作（如 tap、open_app）时同时附带 note 参数来记录信息，不需要单独一步。
- 跨应用比较时（如比价），务必在每个应用中查到结果后用 note 记录，最后汇总比较。

例行任务规则：
- save_routine：当用户要求保存操作以便重复使用、设置定时任务或自动化操作（如"存下来"、"每天都这样做"、"到公司后自动执行"）时，使用 save_routine 并填写相应参数。
- 完成用户可能想重复的任务时（如点餐、查看应用等），在 finish 响应中附带 suggested_routine_name 和 suggested_routine_icon。明确的一次性任务不需要。"""

    // ── Tool descriptions ───────────────────────────────────────────────

    private const val TOOL_DESC_EN = "Perform phone actions. Choose the appropriate action based on the current screen state."
    private const val TOOL_DESC_ZH = "执行手机操作。根据当前屏幕状态选择合适的操作。"

    private val PARAM_DESCS_EN = mapOf(
        "thought" to "Analysis of the current screen and reasoning for the decision",
        "action_type" to "Action type",
        "x" to "Tap x-coordinate, 0-1000 normalized",
        "y" to "Tap y-coordinate, 0-1000 normalized",
        "app_name" to "App name to open (for open_app), e.g. settings",
        "keyword" to "Keyword to search installed apps (for list_apps), e.g. weather",
        "direction" to "Swipe direction (for swipe)",
        "key" to "Key name (for key_event)",
        "text" to "Text to input (for type_text)",
        "reason" to "Failure reason (for fail)",
        "question" to "Question for the user when clarification or confirmation is needed (for ask_user)",
        "handoff_reason" to "Explanation when the user needs to input passwords, PINs, payment passwords, lock screen passwords, or other sensitive information on the device directly (for handoff_user). Do not ask the user to tell the agent sensitive information.",
        "note" to "Temporary note for the current task (e.g., key info like prices, model numbers), format 'key: value' (e.g., 'JD price: 5999 yuan'). Visible in subsequent steps of this task. Can be used together with any action_type, or alone with action_type=note to only record without performing an action.",
        "memory_key" to "Unique key for long-term memory (for remember), e.g. 'default city', 'user dietary preferences'",
        "memory_value" to "Long-term memory content (for remember)",
        "memory_category" to "Long-term memory category (for remember), e.g. preference, profile, workflow",
        "routine_name" to "Short name for the routine (2-5 words), e.g. 'Check emails'",
        "routine_icon" to "Single emoji icon for the routine, e.g. '📧'",
        "routine_instruction" to "The instruction to save. If omitted, the current conversation's original instruction is used",
        "schedule_type" to "Schedule type: time (fixed daily/weekly time), interval (every N minutes), geofence (location-based trigger)",
        "schedule_time" to "Time in HH:MM format, e.g. '08:00' (for schedule_type=time)",
        "schedule_repeat" to "Repeat pattern: 'daily', 'weekdays', or comma-separated days '1,3,5' where Mon=1..Sun=7 (for schedule_type=time)",
        "schedule_interval" to "Interval in minutes, e.g. 30 (for schedule_type=interval)",
        "schedule_location" to "Location name, e.g. 'office', 'home' (for schedule_type=geofence). Uses current location coordinates.",
        "schedule_on_enter" to "true=trigger on arrival, false=trigger on departure (for schedule_type=geofence)",
        "suggested_routine_name" to "When finishing a repeatable task, suggest a short routine name (2-5 chars). Omit for one-off tasks.",
        "suggested_routine_icon" to "When finishing a repeatable task, suggest a single emoji icon. Omit for one-off tasks."
    )

    private val PARAM_DESCS_ZH = mapOf(
        "thought" to "对当前屏幕的分析和决策理由",
        "action_type" to "操作类型",
        "x" to "点击的x坐标，0-1000归一化",
        "y" to "点击的y坐标，0-1000归一化",
        "app_name" to "要打开的应用名（open_app时使用），如 settings",
        "keyword" to "搜索已安装应用的关键词（list_apps时使用），如 天气",
        "direction" to "滑动方向（swipe时使用）",
        "key" to "按键名（key_event时使用）",
        "text" to "要输入的文本（type_text时使用）",
        "reason" to "失败原因（fail时使用）",
        "question" to "当需要用户澄清或确认时的问题（ask_user时使用）",
        "handoff_reason" to "当需要用户亲自在设备上输入密码、PIN、支付密码、锁屏密码等敏感信息时的说明（handoff_user时使用）。不要请求用户把敏感信息告诉agent。",
        "note" to "当前任务内临时笔记（如价格、型号等关键信息），格式为 'key: value'（如 '京东价格: 5999元'）。会在本任务后续步骤可见。可与任何 action_type 同时使用，也可单独用 action_type=note 只记录不操作。",
        "memory_key" to "长期记忆的唯一键名（remember时使用），例如 '默认城市'、'用户饮食偏好'",
        "memory_value" to "长期记忆内容（remember时使用）",
        "memory_category" to "长期记忆分类（remember时使用），例如 preference、profile、workflow",
        "routine_name" to "例行任务的简短名称（2-5个字），如 '查邮件'",
        "routine_icon" to "例行任务的 emoji 图标，如 '📧'",
        "routine_instruction" to "要保存的指令。省略时使用当前对话的原始指令",
        "schedule_type" to "定时类型：time（固定时间）、interval（间隔重复）、geofence（位置触发）",
        "schedule_time" to "时间，HH:MM 格式，如 '08:00'（schedule_type=time 时使用）",
        "schedule_repeat" to "重复模式：'daily'（每天）、'weekdays'（工作日）、或自定义 '1,3,5'，周一=1..周日=7",
        "schedule_interval" to "间隔分钟数，如 30（schedule_type=interval 时使用）",
        "schedule_location" to "地点名称，如 '公司'、'家'（schedule_type=geofence 时使用）",
        "schedule_on_enter" to "true=到达时触发，false=离开时触发（schedule_type=geofence 时使用）",
        "suggested_routine_name" to "完成可重复任务时，建议一个简短的例行任务名称（2-5字）。一次性任务不需要。",
        "suggested_routine_icon" to "完成可重复任务时，建议一个 emoji 图标。一次性任务不需要。"
    )

    // ── ActionRepeatGuard feedback ──────────────────────────────────────

    private val GUARD_EN = mapOf(
        "repeated_type_text" to "The same text was already typed in the previous step. Do not repeat the same input.",
        "repeated_submit" to "The same submit key was already pressed in the previous step, and the screen has not changed significantly.",
        "repeated_tap" to "Nearly the same position was already tapped in the previous step, and the screen has not changed significantly.",
        "escalation_high" to " If there might be a system confirmation, permission, installation, or external dialog not visible in the virtual display, please use ask_user to ask the user for help.",
        "escalation_low" to " Please try a different strategy; if you need user confirmation, use ask_user."
    )

    private val GUARD_ZH = mapOf(
        "repeated_type_text" to "上一步已经输入过相同文本，不要重复执行同一输入。",
        "repeated_submit" to "上一步已经执行过相同的提交按键，当前屏幕没有明显变化。",
        "repeated_tap" to "上一步已经点击过几乎相同的位置，当前屏幕没有明显变化。",
        "escalation_high" to " 如果可能存在虚拟显示器看不到的系统确认、权限、安装或外部弹窗，请使用 ask_user 向用户求助。",
        "escalation_low" to " 请换一种策略；如果需要用户确认，请使用 ask_user。"
    )

    // ── AgentEngine runtime feedback ────────────────────────────────────

    private val AGENT_FEEDBACK_EN = mapOf(
        "vd_blank_hint" to "(Note: this is a screenshot of the virtual display. If you're not sure which app to open, use list_apps(keyword) first to check available apps. Then use open_app with the exact name. Do NOT tap or swipe on the home screen.)",
        "handoff_default_reason" to "The app requires you to input sensitive information on the device directly",
        "handoff_completed" to "The user has completed the sensitive input takeover and returned control (%s). Please re-observe the current screen and continue the task; if sensitive input is still needed, continue using handoff_user, do not ask for passwords.",
        "action_failed" to "Previous action failed: %s",
        "screen_unchanged" to "The screen content is unchanged after your %s action. Your previous action may not have had the intended effect. Try a different approach — do not repeat the same action.",
        "screen_stuck" to "The screen has not changed for multiple consecutive steps. Your repeated actions are having no visible effect. STOP repeating the same approach. If you were trying to tap an input field, try type_text directly — the field may already be focused even though you cannot see the keyboard. If tap is not working at all, try a completely different strategy.",
        "max_steps_reached" to "Max steps reached (%d)",
        "user_answer_prefix" to "User's answer: %s\nPlease continue completing the task based on the user's answer: ",
        "system_feedback_prefix" to "[System Feedback] %s\nPlease adjust your strategy based on the feedback; if needed, use ask_user to ask the user for help.\n\n",
        "ui_elements_header" to "\n\n### Current UI Elements ###\n",
        "user_supplement_header" to "\n\n### User Supplemental Info ###\n",
        "global_memory_header" to "\n\n### Global Memory ###\n",
        "notes_header" to "\n\n### Recorded Notes (Memory) ###\n"
    )

    private val AGENT_FEEDBACK_ZH = mapOf(
        "vd_blank_hint" to "（注意：这是虚拟显示器的截图。如果不确定要打开哪个应用，先用 list_apps(keyword) 搜索已安装应用，再用 open_app 打开。不要点击或滑动主屏幕。）",
        "handoff_default_reason" to "需要你亲自在设备上输入敏感信息",
        "handoff_completed" to "用户已完成敏感输入接管并交还控制（%s）。请重新观察当前屏幕继续任务；如果仍然需要敏感输入，继续使用 handoff_user，不要询问密码。",
        "action_failed" to "上一步操作失败：%s",
        "screen_unchanged" to "执行 %s 操作后屏幕内容没有变化。上一步操作可能未生效，请换一种方式操作，不要重复相同的动作。",
        "screen_stuck" to "屏幕已连续多步没有变化，你重复的操作没有产生任何可见效果。立即停止重复相同的方式。如果你一直在尝试点击输入框，请直接使用 type_text —— 输入框可能已经获得焦点，只是键盘没有显示在截图中。如果点击完全无效，请尝试完全不同的策略。",
        "max_steps_reached" to "已达到最大步数限制 (%d)",
        "user_answer_prefix" to "用户回答：%s\n请根据用户回答继续完成任务：",
        "system_feedback_prefix" to "【系统反馈】%s\n请根据反馈调整策略，必要时用 ask_user 向用户求助。\n\n",
        "ui_elements_header" to "\n\n### 当前界面元素 (UI Elements) ###\n",
        "user_supplement_header" to "\n\n### 用户补充信息 ###\n",
        "global_memory_header" to "\n\n### 全局记忆 ###\n",
        "notes_header" to "\n\n### 已记录的笔记 (Memory) ###\n"
    )
}
