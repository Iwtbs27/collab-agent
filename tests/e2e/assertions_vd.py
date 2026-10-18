"""Assertion types for VirtualDisplay + ControlPanel architecture E2E tests.

These assertions are designed for the new architecture where the agent operates
in a VirtualDisplay and the user interacts through a ControlPanelActivity,
replacing the old floating overlay approach.
"""

from __future__ import annotations

import re
from typing import TYPE_CHECKING

from tests.e2e.assertions import Assertion

if TYPE_CHECKING:
    from tests.e2e.framework import RunConfig, RunState


# ── Activity / UI assertions ─────────────────────────────────────────────────


class ActivityInForeground(Assertion):
    """Assert that a specific Activity (by simple class name) is in the foreground.

    Uses `adb shell dumpsys activity activities` to check the resumed activity.
    Matches on activity class name suffix (e.g. ".ControlPanelActivity").
    """

    def __init__(self, activity_name: str):
        self.activity_name = activity_name

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        from tests.e2e.adb_utils import adb_run
        out = adb_run(
            "shell", "dumpsys", "activity", "activities",
            serial=config.serial, timeout=10,
        )
        for line in out.splitlines():
            if "mResumedActivity" in line or "topResumedActivity" in line:
                if self.activity_name in line:
                    return True, f"{self.activity_name} is in foreground"
        return False, f"{self.activity_name} not found in resumed activity output"

    def __str__(self) -> str:
        return f"ActivityInForeground({self.activity_name!r})"


class FragmentVisible(Assertion):
    """Assert that a specific Fragment is active by checking dumpsys activity output.

    Uses `adb shell dumpsys activity <package>` to look for the fragment class name
    in the fragment manager dump.
    """

    def __init__(self, fragment_name: str, package: str = "ai.opencyvis"):
        self.fragment_name = fragment_name
        self.package = package

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        from tests.e2e.adb_utils import adb_run
        out = adb_run(
            "shell", "dumpsys", "activity", self.package,
            serial=config.serial, timeout=10,
        )
        if self.fragment_name in out:
            return True, f"fragment {self.fragment_name} found in activity dump"
        return False, f"fragment {self.fragment_name} not found in activity dump"

    def __str__(self) -> str:
        return f"FragmentVisible({self.fragment_name!r})"


# ── Logcat assertions ────────────────────────────────────────────────────────


class LogcatContains(Assertion):
    """Assert that a specific message appears in logcat output.

    First checks captured lines from the monitor loop. If not found, falls back
    to full device logcat (catches output from post_steps or late-arriving logs).
    """

    def __init__(self, message: str, *, description: str | None = None):
        self.message = message
        self.description = description or message

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        for line in state.logcat_lines:
            if self.message in line:
                return True, f"found: {self.message!r}"
        # Fall back to full device logcat (post_steps output, late logs)
        from tests.e2e.adb_utils import adb_run
        try:
            full = adb_run("logcat", "-d", serial=config.serial, timeout=10)
            for line in full.splitlines():
                if self.message in line:
                    return True, f"found (device logcat): {self.message!r}"
        except Exception:
            pass
        return False, (
            f"message not found in {len(state.logcat_lines)} logcat lines: "
            f"{self.message!r}"
        )

    def __str__(self) -> str:
        return f"LogcatContains({self.description!r})"


class LogcatNotContains(Assertion):
    """Assert that a specific message does NOT appear in the captured logcat output.

    Useful for verifying that old overlay-related log messages are absent.
    """

    def __init__(self, message: str, *, description: str | None = None):
        self.message = message
        self.description = description or f"NOT {message}"

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        matches = [line for line in state.logcat_lines if self.message in line]
        if matches:
            snippet = matches[0][-120:]
            return False, (
                f"found {len(matches)} line(s) containing {self.message!r}: "
                f"{snippet!r}"
            )
        return True, (
            f"confirmed absent in {len(state.logcat_lines)} logcat lines: "
            f"{self.message!r}"
        )

    def __str__(self) -> str:
        return f"LogcatNotContains({self.description!r})"


class LogcatPatternNotFound(Assertion):
    """Assert that a regex pattern does NOT match any logcat line."""

    def __init__(self, pattern: str, *, description: str | None = None):
        self.pattern = pattern
        self.description = description or f"NOT /{pattern}/"

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        for line in state.logcat_lines:
            if re.search(self.pattern, line):
                snippet = line[-120:]
                return False, f"pattern {self.pattern!r} matched: {snippet!r}"
        return True, (
            f"pattern {self.pattern!r} not found in "
            f"{len(state.logcat_lines)} logcat lines"
        )

    def __str__(self) -> str:
        return f"LogcatPatternNotFound({self.description!r})"


# ── VirtualDisplay assertions ────────────────────────────────────────────────


class VirtualDisplayCreated(Assertion):
    """Assert that a VirtualDisplay was created by checking logcat for the
    creation log message from AgentService/VirtualDisplayManager."""

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        for line in state.logcat_lines:
            if "Virtual display created" in line:
                return True, "VirtualDisplay creation confirmed via logcat"
        return False, "no 'Virtual display created' message found in logcat"

    def __str__(self) -> str:
        return "VirtualDisplayCreated"


# ── Step / progress assertions ───────────────────────────────────────────────


class MinSteps(Assertion):
    """Agent must have executed at least N steps, proving it made real progress."""

    def __init__(self, min_steps: int = 2):
        self.min_steps = min_steps

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        n = len(state.steps)
        if n >= self.min_steps:
            return True, f"{n} steps >= {self.min_steps}"
        return False, f"only {n} step(s), expected at least {self.min_steps}"

    def __str__(self) -> str:
        return f"MinSteps(min={self.min_steps})"


# ── Permission assertions ───────────────────────────────────────────────────


class PermissionNotGranted(Assertion):
    """Assert that a specific Android permission is NOT granted to the package.

    Uses `adb shell dumpsys package <pkg>` and checks the permissions section.
    """

    def __init__(self, permission: str, package: str = "ai.opencyvis"):
        self.permission = permission
        self.package = package

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        from tests.e2e.adb_utils import adb_run
        out = adb_run(
            "shell", "dumpsys", "package", self.package,
            serial=config.serial, timeout=10,
        )
        # Look for the permission in the granted permissions section
        if f"{self.permission}: granted=true" in out:
            return False, f"{self.permission} is granted (should not be)"
        return True, f"{self.permission} is not granted"

    def __str__(self) -> str:
        return f"PermissionNotGranted({self.permission!r})"


# ── Agent state assertions ───────────────────────────────────────────────────


class AgentStateSeen(Assertion):
    """Assert that a specific AgentState was observed in logcat.

    Looks for state transition log lines containing the state name
    (e.g. "Paused", "Running", "WaitingForUser").
    """

    def __init__(self, state_name: str, *, description: str | None = None):
        self.state_name = state_name
        self.description = description or f"state={state_name}"

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        for line in state.logcat_lines:
            if self.state_name in line:
                return True, f"state {self.state_name!r} observed in logcat"
        return False, f"state {self.state_name!r} not found in logcat"

    def __str__(self) -> str:
        return f"AgentStateSeen({self.description!r})"


class ScreenshotNoOverlayElements(Assertion):
    """Use the vision LLM to verify the screenshot does NOT contain overlay UI.

    This delegates to ScreenshotVerify but with a negative description.
    """

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        from tests.e2e.assertions import ScreenshotVerify
        verifier = ScreenshotVerify(
            "截图中没有悬浮窗、浮动气泡、或OpenCyvis overlay控制面板等overlay元素"
        )
        return verifier.evaluate(state, config)

    def __str__(self) -> str:
        return "ScreenshotNoOverlayElements"
