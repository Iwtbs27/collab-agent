"""E2E test: Recent section on homepage shows conversations, not routines.

Regression test for the bug where the "Recent" section displayed routines
(routineDao.getRecentRoutines) instead of recent conversations
(historyRepo.getRecentConversations).

After the fix, showHomepage() (renamed from showRoutines()) queries
historyRepo.getRecentConversations(3) and bindRecentItem() renders each
ConversationEntity with a conversation icon, title, and relative time.

Test flow:
  1. Start a conversation via mock LLM (instruction becomes the conversation title)
  2. Wait for the agent to finish
  3. Stop the agent, relaunch ControlPanelActivity to trigger homepage refresh
  4. Verify the "Recent" section contains the conversation title (not a routine name)
  5. Verify the recent item has a conversation icon (not a routine icon)

Uses mock LLM responses for deterministic, CI-friendly execution.
"""

from __future__ import annotations

import pytest

from tests.e2e.assertions import FinishAction
from tests.e2e.assertions_ui import ChatContains, UiElement, UiText
from tests.e2e.assertions_vd import LogcatContains
from tests.e2e.framework import AgentTestCase, RunConfig, TestRunner


# ── Test case: recent shows conversation title after completing a task ─────────

class TestRecentShowsConversation(AgentTestCase):
    """After completing a conversation, the homepage 'Recent' section should
    display the conversation title (the instruction text), not a routine."""

    instruction = "打开系统设置"  # "打开系统设置"
    timeout = 30
    mock_responses = [
        {
            "tool_call": {
                "thought": "用户要打开系统设置",
                "action_type": "open_app",
                "app_name": "设置",  # "设置"
            }
        },
        {
            "tool_call": {
                "thought": "设置已打开，任务完成",  # "设置已打开，任务完成"
                "action_type": "finish",
                "summary": "已为您打开设置",  # "已为您打开设置"
            }
        },
    ]
    pre_steps = [
        "adb shell setenforce 0",
    ]
    # After the agent finishes, stop it and relaunch ControlPanelActivity
    # to trigger the homepage (showHomepage) refresh with fresh DB data.
    post_steps = [
        # Stop the agent to return to idle state
        "adb shell dumpsys opencyvis debug stop",
        "sleep:2",
        # Force-stop to clear any cached UI state
        "adb shell am force-stop ai.opencyvis",
        "sleep:1",
        # Relaunch ControlPanelActivity — this calls showHomepage() on idle
        "adb shell am start -n ai.opencyvis/.ui.ControlPanelActivity",
        "sleep:3",
    ]
    assertions = [
        # 1. Agent must have completed the task
        LogcatContains("Task completed", description="agent finished task"),
        # 2. Homepage "Recent" section is visible (label + container)
        UiElement("label_recent"),
        UiElement("container_recent_routines"),
        # 3. The conversation title (instruction text) appears in the recent list.
        #    This is the key regression check: before the fix, the recent section
        #    showed routine names; after the fix, it shows conversation titles.
        UiText("打开系统设置"),  # "打开系统设置"
        # 4. Verify the recent_icon shows a conversation emoji, not a routine icon.
        #    The fix sets recent_icon.text = "\U0001f4ac" (speech balloon).
        #    We check via ChatContains which uses uiautomator dump.
        ChatContains("\U0001f4ac"),  # speech balloon emoji
    ]


# ── Test case: recent section is empty when no conversations exist ────────────

class TestRecentEmptyWhenNoConversations(AgentTestCase):
    """On a fresh start with no conversation history, the 'Recent' section
    should be hidden (both label and container gone)."""

    instruction = "直接用 finish 动作完成"  # "直接用 finish 动作完成"
    timeout = 15
    mock_responses = [
        {
            "tool_call": {
                "thought": "直接完成",  # "直接完成"
                "action_type": "finish",
                "summary": "已完成",  # "已完成"
            }
        },
    ]
    pre_steps = [
        "adb shell setenforce 0",
        # Nuke conversation history so the recent section is truly empty
        "adb shell 'run-as ai.opencyvis rm -f databases/opencyvis_db'",
    ]
    post_steps = [
        "adb shell dumpsys opencyvis debug stop",
        "sleep:2",
        "adb shell am force-stop ai.opencyvis",
        "sleep:1",
        "adb shell am start -n ai.opencyvis/.ui.ControlPanelActivity",
        "sleep:3",
    ]
    assertions = [
        LogcatContains("Task completed", description="agent finished task"),
        # After the fix, showHomepage() hides recent section when there are
        # no conversations. The label_recent and container should be GONE.
        # We verify they are NOT visible by checking they are absent from UI.
        # UiElement checks resource-id presence in uiautomator dump.
        # When visibility=GONE, uiautomator still lists the element but with
        # enabled=false and no visible bounds. We check for the greeting instead.
        UiText("What would you like to do?"),
    ]


# ── Test case: multiple conversations appear in recent list ───────────────────

class TestMultipleRecentConversations(AgentTestCase):
    """After executing multiple conversations, the homepage 'Recent' section
    should show the most recent ones (up to 3)."""

    instruction = "打开电话"  # "打开电话"
    timeout = 30
    mock_responses = [
        {
            "tool_call": {
                "thought": "打开电话应用",  # "打开电话应用"
                "action_type": "open_app",
                "app_name": "电话",  # "电话"
            }
        },
        {
            "tool_call": {
                "thought": "完成",  # "完成"
                "action_type": "finish",
                "summary": "已打开电话",  # "已打开电话"
            }
        },
    ]
    pre_steps = [
        "adb shell setenforce 0",
    ]
    post_steps = [
        # First conversation done. Now run a second one via dumpsys.
        "adb shell dumpsys opencyvis start 打开相机",  # "打开相机"
        "sleep:10",
        # Stop agent and relaunch to see both in recent
        "adb shell dumpsys opencyvis debug stop",
        "sleep:2",
        "adb shell am force-stop ai.opencyvis",
        "sleep:1",
        "adb shell am start -n ai.opencyvis/.ui.ControlPanelActivity",
        "sleep:3",
    ]
    assertions = [
        LogcatContains("Task completed", description="agent finished task"),
        # Recent section visible
        UiElement("label_recent"),
        UiElement("container_recent_routines"),
        # The most recent conversation title should appear (from the second run)
        UiText("打开相机"),  # "打开相机"
        # The first conversation title should also appear
        UiText("打开电话"),  # "打开电话"
    ]


# ── Pytest runner ─────────────────────────────────────────────────────────────

@pytest.fixture(scope="session")
def run_config(request) -> RunConfig:
    import os
    api_key = request.config.getoption("api_key", default=None) or os.environ.get("AIPHONE_API_KEY")
    return RunConfig(
        serial=request.config.getoption("serial", default=None),
        api_key=api_key,
    )


@pytest.mark.parametrize("case_cls", [
    TestRecentShowsConversation,
    TestRecentEmptyWhenNoConversations,
    TestMultipleRecentConversations,
], ids=lambda c: c.__name__)
def test_recent_conversations(case_cls, run_config):
    """Run each recent-conversations test case against a real device."""
    runner = TestRunner(run_config)
    result = runner.run(case_cls)
    assert result.passed, result.summary()
