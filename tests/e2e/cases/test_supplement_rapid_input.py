"""E2E test: Rapid supplement input during agent execution must not cause ANR.

Bug: When a task is running and the user rapidly types in the "Add task info…"
supplement input on ControlPanelActivity, the app triggers an ANR due to:
  1. AgentService.scope uses Dispatchers.Main and calls refreshControlledTasksFromVd()
     which does synchronous Binder IPC
  2. TypewriterTextView animation floods the main Handler with runnables every 30ms
  3. scrollChatToBottom() is called too frequently

This test starts a mock agent task and rapidly sends supplement text to verify the
app remains responsive (no ANR, no crash, and the agent completes normally).

Usage:
    pytest tests/e2e/cases/test_supplement_rapid_input.py -v
    pytest tests/e2e/cases/test_supplement_rapid_input.py -v --serial emulator-5554
"""

from __future__ import annotations

import time
import re

import pytest

from tests.e2e.framework import AgentTestCase, RunConfig, RunState, TestRunner
from tests.e2e.assertions import Assertion, FinishAction, LogcatPattern
from tests.e2e import adb_utils


# ── Custom assertions ───────────────────────────────────────────────────────

class NoAnrDetected(Assertion):
    """Assert that no ANR was triggered during the test run.

    Checks logcat for ANR indicators:
      - "ANR in ai.opencyvis"
      - "Input dispatching timed out"
      - ActivityManager ANR traces
    """

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        anr_patterns = [
            "ANR in ai.opencyvis",
            "Input dispatching timed out",
            "opencyvis.*not responding",
        ]
        for line in state.logcat_lines:
            for pattern in anr_patterns:
                if re.search(pattern, line, re.IGNORECASE):
                    return False, f"ANR detected: {line[-200:]}"

        # Also check full device logcat for ANR traces that might be outside our filter
        try:
            full_logcat = adb_utils.adb_run(
                "logcat", "-d", "-s", "ActivityManager:E",
                serial=config.serial, timeout=10,
            )
            for line in full_logcat.splitlines():
                if "ANR in ai.opencyvis" in line:
                    return False, f"ANR detected in ActivityManager: {line[-200:]}"
        except Exception:
            pass

        return True, f"no ANR detected in {len(state.logcat_lines)} logcat lines"

    def __str__(self) -> str:
        return "NoAnrDetected"


class AppStillResponsive(Assertion):
    """Assert that the app process is still alive and responsive after the test.

    Runs `dumpsys opencyvis state` and checks that the process didn't crash.
    """

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        if not adb_utils.is_process_running(config.serial):
            return False, "ai.opencyvis process is not running (crashed?)"

        try:
            result = adb_utils.get_state(config.serial)
            if result and "Can't find service" not in result:
                return True, "app is responsive (dumpsys returned state)"
        except Exception as e:
            return False, f"app unresponsive: dumpsys failed with {e}"

        return False, "app unresponsive: dumpsys returned empty or error"

    def __str__(self) -> str:
        return "AppStillResponsive"


class SupplementsReceived(Assertion):
    """Assert that at least N supplement messages were received by the agent.

    Checks logcat for AgentService supplement log lines.
    """

    def __init__(self, min_count: int = 3):
        self.min_count = min_count

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        count = 0
        for line in state.logcat_lines:
            if "submitUserSupplement" in line:
                count += 1

        # Also check full logcat
        if count < self.min_count:
            try:
                full_logcat = adb_utils.adb_run(
                    "logcat", "-d", serial=config.serial, timeout=10,
                )
                for line in full_logcat.splitlines():
                    if "submitUserSupplement" in line:
                        count += 1
            except Exception:
                pass

        if count >= self.min_count:
            return True, f"{count} supplements received (>= {self.min_count})"
        return False, f"only {count} supplements received, expected >= {self.min_count}"

    def __str__(self) -> str:
        return f"SupplementsReceived(min={self.min_count})"


# ── Test case ───────────────────────────────────────────────────────────────

class TestSupplementRapidInput(AgentTestCase):
    """Start a multi-step agent task then rapidly send supplement text.

    Uses a mock LLM with several steps to keep the agent running while
    supplements are sent. The mock responses include enough steps to give
    time for rapid input before the agent finishes.
    """

    instruction = "打开设置查看网络信息"
    timeout = 90
    max_steps = 8

    # 8-step mock scenario: agent takes multiple steps, giving us time to
    # inject rapid supplements while it's running.
    mock_responses = [
        {"tool_call": {"thought": "正在打开设置", "action_type": "open_app", "app_name": "设置"}},
        {"tool_call": {"thought": "设置已打开，查找网络", "action_type": "wait", "seconds": 2}},
        {"tool_call": {"thought": "正在查看网络选项", "action_type": "tap", "x": 540, "y": 800}},
        {"tool_call": {"thought": "进入网络设置", "action_type": "wait", "seconds": 2}},
        {"tool_call": {"thought": "正在查看WiFi信息", "action_type": "tap", "x": 540, "y": 600}},
        {"tool_call": {"thought": "查看连接详情", "action_type": "wait", "seconds": 1}},
        {"tool_call": {"thought": "获取到网络信息", "action_type": "tap", "x": 540, "y": 400}},
        {"tool_call": {"thought": "任务完成", "action_type": "finish", "summary": "已查看网络信息"}},
    ]

    # Trigger rapid supplement injection after the first step is observed.
    # Each trigger fires once when its pattern appears in logcat.
    trigger_commands = [
        # After step 1, start sending rapid supplements via dumpsys
        {"wait_for": "step 1", "command": "inject supplement 补充信息1", "delay": 0.3},
        {"wait_for": "step 2", "command": "inject supplement 补充信息2-快速输入", "delay": 0.1},
        {"wait_for": "step 2", "command": "inject supplement 补充信息3-更多文字", "delay": 0.1},
        {"wait_for": "step 3", "command": "inject supplement 第四条补充信息", "delay": 0.1},
        {"wait_for": "step 3", "command": "inject supplement 第五条补充", "delay": 0.1},
        {"wait_for": "step 4", "command": "inject supplement 补充6", "delay": 0.05},
        {"wait_for": "step 4", "command": "inject supplement 补充7", "delay": 0.05},
        {"wait_for": "step 5", "command": "inject supplement 补充8-fast", "delay": 0.05},
        {"wait_for": "step 5", "command": "inject supplement 补充9-fast", "delay": 0.05},
        {"wait_for": "step 6", "command": "inject supplement 最后的补充10", "delay": 0.05},
    ]

    assertions = [
        FinishAction(),
        NoAnrDetected(),
        AppStillResponsive(),
        SupplementsReceived(min_count=3),
    ]


class TestSupplementBurstInput(AgentTestCase):
    """Burst 10 supplements as fast as possible in a single post_step sequence.

    This is the worst-case scenario: all supplements arrive in rapid succession
    without waiting for agent steps in between.
    """

    instruction = "打开相册"
    timeout = 60
    max_steps = 5

    mock_responses = [
        {"tool_call": {"thought": "打开相册应用", "action_type": "open_app", "app_name": "相册"}},
        {"tool_call": {"thought": "等待加载", "action_type": "wait", "seconds": 3}},
        {"tool_call": {"thought": "相册已打开", "action_type": "wait", "seconds": 2}},
        {"tool_call": {"thought": "查看内容", "action_type": "wait", "seconds": 1}},
        {"tool_call": {"thought": "完成", "action_type": "finish", "summary": "相册已打开"}},
    ]

    # Send a burst of supplements right after agent starts, via post_steps
    # that execute after the agent instruction is sent but before monitoring ends.
    # We use trigger_commands on step 1 to fire the burst.
    trigger_commands = [
        {"wait_for": "step 1", "command": "inject supplement burst1", "delay": 0.05},
        {"wait_for": "step 1", "command": "inject supplement burst2", "delay": 0.05},
        {"wait_for": "step 1", "command": "inject supplement burst3-快速", "delay": 0.05},
        {"wait_for": "step 1", "command": "inject supplement burst4-输入", "delay": 0.05},
        {"wait_for": "step 1", "command": "inject supplement burst5-测试", "delay": 0.05},
        {"wait_for": "step 1", "command": "inject supplement burst6", "delay": 0.05},
        {"wait_for": "step 1", "command": "inject supplement burst7", "delay": 0.05},
        {"wait_for": "step 1", "command": "inject supplement burst8", "delay": 0.05},
        {"wait_for": "step 1", "command": "inject supplement burst9", "delay": 0.05},
        {"wait_for": "step 1", "command": "inject supplement burst10", "delay": 0.05},
    ]

    assertions = [
        FinishAction(),
        NoAnrDetected(),
        AppStillResponsive(),
    ]


# ── Pytest integration ──────────────────────────────────────────────────────

# Fixtures from conftest.py provide run_config

@pytest.fixture(params=[TestSupplementRapidInput, TestSupplementBurstInput])
def anr_test_case(request):
    return request.param


def test_supplement_rapid_input(anr_test_case, run_config: RunConfig):
    """Rapid supplement input during agent execution must not cause ANR."""
    runner = TestRunner(run_config)
    result = runner.run(anr_test_case)
    assert result.passed, result.summary()
