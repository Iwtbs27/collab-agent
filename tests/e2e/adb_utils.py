"""ADB helpers for E2E test framework."""

from __future__ import annotations

import re
import subprocess
import time
from typing import Optional


PACKAGE = "ai.opencyvis"

LOGCAT_FILTER = [
    "*:S",
    "AgentEngine:D",
    "OverlayWindow:D",
    "OverlayService:D",
    "ViewActivity:D",
    "ViewActivity:I",
    "InputInjector:D",
    "InputInjector:I",
    "LLMClient:D",
    "AgentService:I",
    "TestShellCmd:I",
    "PrivilegedService:D",
    "PrivilegedServiceMain:I",
    "CaptureOps:D",
]


def _adb_cmd(args: list[str], serial: Optional[str]) -> list[str]:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    return cmd + args


def adb_run(*args: str, serial: Optional[str] = None, timeout: float = 10) -> str:
    """Run an adb command and return stdout as a string."""
    r = subprocess.run(
        _adb_cmd(list(args), serial),
        capture_output=True, text=True, timeout=timeout,
    )
    return r.stdout.strip()


# ── dumpsys opencyvis commands ───────────────────────────────────────────────

def dumpsys_cmd(serial: Optional[str] = None, *args: str) -> str:
    """Run `dumpsys opencyvis <args>` and return output."""
    cmd_args = ["shell", "dumpsys", "opencyvis"] + list(args)
    return adb_run(*cmd_args, serial=serial)


def start_agent(serial: Optional[str] = None, instruction: str = "") -> str:
    """Start agent with the given instruction via dumpsys."""
    return dumpsys_cmd(serial, "start", instruction)


def submit_answer(serial: Optional[str] = None, answer: str = "") -> str:
    """Submit an ask_user response via dumpsys."""
    return dumpsys_cmd(serial, "inject", "ask_user_response", answer)


def submit_supplement(serial: Optional[str] = None, text: str = "") -> str:
    """Submit a user supplement via dumpsys."""
    return dumpsys_cmd(serial, "inject", "supplement", text)


def debug_cmd(serial: Optional[str] = None, command: str = "") -> str:
    """Execute a debug command via dumpsys."""
    return dumpsys_cmd(serial, "debug", command)


def get_state(serial: Optional[str] = None) -> str:
    """Get engine state JSON via dumpsys.

    If the service is not registered (app was restarted without setenforce 0),
    restarts the app with permissive SELinux and retries.
    """
    result = dumpsys_cmd(serial, "state")
    if result and "Can't find service" not in result:
        return result
    # Service not registered — restart app with permissive SELinux
    try:
        adb_run("shell", "setenforce", "0", serial=serial)
    except Exception:
        pass
    ensure_services(serial)
    time.sleep(1)
    return dumpsys_cmd(serial, "state")


# ── Service lifecycle ────────────────────────────────────────────────────────

def ensure_services(serial: Optional[str] = None) -> None:
    """Start AgentService and OverlayService if not already running."""
    # SELinux must be permissive for TestShellService.register() (dumpsys opencyvis)
    try:
        adb_run("shell", "setenforce", "0", serial=serial)
    except Exception:
        pass
    adb_run(
        "shell", "am", "start-foreground-service",
        "-n", f"{PACKAGE}/.AgentService",
        serial=serial,
    )
    time.sleep(0.5)
    adb_run(
        "shell", "am", "startservice",
        "-n", f"{PACKAGE}/.OverlayService",
        serial=serial,
    )
    time.sleep(1.0)
    # Launch ControlPanelActivity for tests that check it
    adb_run(
        "shell", "am", "start",
        "-n", f"{PACKAGE}/.ui.ControlPanelActivity",
        serial=serial,
    )
    time.sleep(0.5)


def is_process_running(serial: Optional[str] = None) -> bool:
    """Return True if ai.opencyvis process is alive."""
    ps = adb_run("shell", "ps", "-A", serial=serial)
    return PACKAGE in ps


# ── Screen / UI helpers ──────────────────────────────────────────────────────

def screencap_png(serial: Optional[str] = None) -> bytes:
    """Capture screen and return raw PNG bytes."""
    r = subprocess.run(
        _adb_cmd(["shell", "screencap", "-p"], serial),
        capture_output=True, timeout=15,
    )
    return r.stdout


def get_foreground_package(serial: Optional[str] = None) -> Optional[str]:
    """Return the package name of the currently resumed Activity, or None."""
    out = adb_run(
        "shell", "dumpsys activity activities",
        serial=serial, timeout=10,
    )
    for line in out.splitlines():
        if "mResumedActivity" in line or "topResumedActivity" in line:
            m = re.search(r"([\w.]+)/([\w.]+)", line)
            if m:
                return m.group(1)
    return None


def get_all_foreground_packages(serial: Optional[str] = None) -> list[str]:
    """Return all package names of resumed Activities (main + virtual displays)."""
    out = adb_run(
        "shell", "dumpsys activity activities",
        serial=serial, timeout=10,
    )
    packages = []
    for line in out.splitlines():
        if "mResumedActivity" in line or "topResumedActivity" in line:
            m = re.search(r"([\w.]+)/([\w.]+)", line)
            if m:
                pkg = m.group(1)
                if pkg not in packages:
                    packages.append(pkg)
    return packages


# ── Logcat ───────────────────────────────────────────────────────────────────

def clear_logcat(serial: Optional[str] = None) -> None:
    adb_run("logcat", "-c", serial=serial)
    time.sleep(0.3)


def open_logcat(serial: Optional[str] = None) -> subprocess.Popen:
    """Open a logcat subprocess and return the Popen handle."""
    return subprocess.Popen(
        _adb_cmd(["logcat", "-v", "time"] + LOGCAT_FILTER, serial),
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
    )


# ── UI Automator helpers ────────────────────────────────────────────────

def uiautomator_dump(serial: Optional[str] = None) -> str:
    """Dump UI hierarchy XML and return as string."""
    adb_run("shell", "uiautomator", "dump", "/sdcard/ui_dump.xml", serial=serial)
    return adb_run("shell", "cat", "/sdcard/ui_dump.xml", serial=serial, timeout=15)


def tap_ui_element(serial: Optional[str] = None, resource_id: str = "") -> bool:
    """Find element by resource-id in uiautomator dump and tap its center.

    Returns True if element was found and tapped.
    """
    xml = uiautomator_dump(serial)
    full_id = f"ai.opencyvis:id/{resource_id}" if ":" not in resource_id else resource_id
    pattern = re.compile(
        rf'resource-id="{re.escape(full_id)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    )
    m = pattern.search(xml)
    if not m:
        return False
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    adb_run("shell", "input", "tap", str(x), str(y), serial=serial)
    return True


def tap_text_element(serial: Optional[str] = None, text: str = "") -> bool:
    """Find element by text content in uiautomator dump and tap its center.

    Returns True if element was found and tapped.
    """
    xml = uiautomator_dump(serial)
    pattern = re.compile(
        rf'text="{re.escape(text)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    )
    m = pattern.search(xml)
    if not m:
        return False
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    adb_run("shell", "input", "tap", str(x), str(y), serial=serial)
    return True


def execute_steps(serial: Optional[str] = None, steps: Optional[list[str]] = None) -> None:
    """Execute a list of test step commands (DSL from scenarios.yml)."""
    if not steps:
        return
    for step in steps:
        if step.startswith("tap_ui:"):
            rid = step[len("tap_ui:"):]
            tap_ui_element(serial, rid)
        elif step.startswith("tap_text:"):
            text = step[len("tap_text:"):]
            tap_text_element(serial, text)
        elif step.startswith("sleep:"):
            time.sleep(float(step[len("sleep:"):]))
        elif step.startswith("sleep "):
            time.sleep(float(step[len("sleep "):]))
        elif step.startswith("adb shell "):
            cmd = step[len("adb shell "):]
            adb_run("shell", *cmd.split(), serial=serial, timeout=30)
        elif step.startswith("adb "):
            parts = step[len("adb "):].split()
            adb_run(*parts, serial=serial, timeout=30)
        else:
            subprocess.run(step, shell=True, timeout=30)
