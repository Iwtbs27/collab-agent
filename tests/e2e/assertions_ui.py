"""UI and dumpsys assertion types for YAML-driven E2E scenarios."""

from __future__ import annotations

import re
from typing import TYPE_CHECKING

from tests.e2e.assertions import Assertion

if TYPE_CHECKING:
    from tests.e2e.framework import RunConfig, RunState


class DumpsysContains(Assertion):
    """Assert that `dumpsys opencyvis` output contains a substring."""

    def __init__(self, expected: str):
        self.expected = expected

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        from tests.e2e.adb_utils import adb_run, get_state
        # Ensure SELinux permissive for dumpsys access
        try:
            adb_run("shell", "setenforce", "0", serial=config.serial)
        except Exception:
            pass
        output = get_state(config.serial)
        if self.expected in output:
            return True, f"dumpsys contains: {self.expected!r}"
        return False, f"dumpsys missing: {self.expected!r}\nactual: {output[:300]}"

    def __str__(self) -> str:
        return f"DumpsysContains({self.expected!r})"


class DumpsysAbsent(Assertion):
    """Assert that `dumpsys opencyvis` output does NOT contain a substring."""

    def __init__(self, absent: str):
        self.absent = absent

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        from tests.e2e.adb_utils import adb_run, get_state
        # Ensure SELinux permissive for dumpsys access
        try:
            adb_run("shell", "setenforce", "0", serial=config.serial)
        except Exception:
            pass
        output = get_state(config.serial)
        if self.absent not in output:
            return True, f"dumpsys correctly missing: {self.absent!r}"
        return False, f"dumpsys unexpectedly contains: {self.absent!r}"

    def __str__(self) -> str:
        return f"DumpsysAbsent({self.absent!r})"


class VdExists(Assertion):
    """Assert virtual display is alive (or not) by checking dumpsys display."""

    def __init__(self, expected: bool = True):
        self.expected = expected

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        from tests.e2e.adb_utils import adb_run
        out = adb_run("shell", "dumpsys", "display", serial=config.serial, timeout=10)
        found = "AIPhone-Agent" in out
        if found == self.expected:
            status = "alive" if found else "absent"
            return True, f"VD is {status} as expected"
        status = "alive" if found else "absent"
        expected_str = "alive" if self.expected else "absent"
        return False, f"VD is {status}, expected {expected_str}"

    def __str__(self) -> str:
        return f"VdExists(expected={self.expected})"


class ImOutboundContains(Assertion):
    """Assert that IM outbound ring contains a substring."""

    def __init__(self, expected: str):
        self.expected = expected

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        from tests.e2e.adb_utils import adb_run
        try:
            adb_run("shell", "setenforce", "0", serial=config.serial)
        except Exception:
            pass
        output = adb_run("shell", "dumpsys", "opencyvis", "im", "outbound",
                         serial=config.serial, timeout=10)
        if self.expected in output:
            return True, f"im outbound contains: {self.expected!r}"
        return False, f"im outbound missing: {self.expected!r}\nactual: {output[:300]}"

    def __str__(self) -> str:
        return f"ImOutboundContains({self.expected!r})"


class ChatContains(Assertion):
    """Assert that the chat UI contains specific text via uiautomator dump."""

    def __init__(self, text: str):
        self.text = text

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        from tests.e2e.adb_utils import uiautomator_dump
        xml = uiautomator_dump(config.serial)
        if self.text in xml:
            return True, f"chat contains: {self.text!r}"
        return False, f"text not found in UI: {self.text!r}"

    def __str__(self) -> str:
        return f"ChatContains({self.text!r})"


class UiElement(Assertion):
    """Assert that an element with a specific resource-id exists in the UI."""

    def __init__(self, resource_id: str):
        self.resource_id = resource_id

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        from tests.e2e.adb_utils import uiautomator_dump
        xml = uiautomator_dump(config.serial)
        full_id = f"ai.opencyvis:id/{self.resource_id}"
        if full_id in xml:
            return True, f"element found: {self.resource_id}"
        return False, f"element not found: {self.resource_id}"

    def __str__(self) -> str:
        return f"UiElement({self.resource_id!r})"


class UiText(Assertion):
    """Assert that an element with specific text exists in the UI."""

    def __init__(self, text: str):
        self.text = text

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        from tests.e2e.adb_utils import uiautomator_dump
        xml = uiautomator_dump(config.serial)
        pattern = f'text="{re.escape(self.text)}"'
        if re.search(pattern, xml):
            return True, f"text found: {self.text!r}"
        return False, f"text not found in UI: {self.text!r}"

    def __str__(self) -> str:
        return f"UiText({self.text!r})"
