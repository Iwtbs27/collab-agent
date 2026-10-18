"""Load YAML scenarios and convert them to AgentTestCase subclasses."""

from __future__ import annotations

from pathlib import Path
from typing import Optional, Type

import yaml

from tests.e2e.assertions import Assertion
from tests.e2e.assertions_ui import (
    ChatContains,
    DumpsysAbsent,
    DumpsysContains,
    ImOutboundContains,
    UiElement,
    UiText,
    VdExists,
)
from tests.e2e.assertions_vd import LogcatContains, LogcatNotContains
from tests.e2e.framework import AgentTestCase


SCENARIOS_PATH = Path(__file__).parent.parent / "ui" / "scenarios.yml"


def load_scenarios(
    path: Optional[Path] = None,
    category: Optional[str] = None,
) -> list[dict]:
    """Load scenarios from YAML, optionally filtering by category."""
    p = path or SCENARIOS_PATH
    with open(p) as f:
        scenarios = yaml.safe_load(f)
    if category:
        scenarios = [s for s in scenarios if s.get("category") == category]
    return scenarios


def build_case(scenario: dict) -> Type[AgentTestCase]:
    """Dynamically build an AgentTestCase subclass from a scenario dict."""
    attrs: dict = {
        "instruction": scenario.get("instruction", ""),
        "timeout": scenario.get("timeout", 120),
        "max_steps": scenario.get("max_steps", 20),
        "mock_responses": scenario.get("mock_responses"),
        "pre_steps": scenario.get("pre_steps"),
        "post_steps": scenario.get("post_steps"),
        "assertions": _build_assertions(scenario.get("verify", [])),
        "trigger_commands": _build_triggers(scenario.get("answers")),
        "user_answers": _build_user_answers(scenario.get("answers")),
        "extra_deeplink_params": scenario.get("extra_deeplink_params"),
        "skip_start_agent": scenario.get("skip_start_agent", False),
    }

    name = scenario.get("name", "DynamicScenario")
    cls_name = "".join(w.capitalize() for w in name.split("_"))
    return type(cls_name, (AgentTestCase,), attrs)


def _build_assertions(verify_list: list[dict]) -> list[Assertion]:
    """Convert verify list from YAML to Assertion instances."""
    assertions: list[Assertion] = []
    for item in verify_list:
        if "logcat" in item:
            assertions.append(LogcatContains(item["logcat"]))
        elif "logcat_absent" in item:
            assertions.append(LogcatNotContains(item["logcat_absent"]))
        elif "dumpsys_contains" in item:
            assertions.append(DumpsysContains(item["dumpsys_contains"]))
        elif "dumpsys_absent" in item:
            assertions.append(DumpsysAbsent(item["dumpsys_absent"]))
        elif "im_outbound_contains" in item:
            assertions.append(ImOutboundContains(item["im_outbound_contains"]))
        elif "vd_exists" in item:
            assertions.append(VdExists(expected=bool(item["vd_exists"])))
        elif "chat_contains" in item:
            assertions.append(ChatContains(item["chat_contains"]))
        elif "ui_element" in item:
            assertions.append(UiElement(item["ui_element"]))
        elif "ui_text" in item:
            assertions.append(UiText(item["ui_text"]))
        elif "mirror" in item:
            # mirror requires vision LLM — skip in deterministic runs
            pass
    return assertions


def _build_triggers(answers: Optional[list[dict]]) -> Optional[list[dict]]:
    """Extract trigger_commands entries from answers list."""
    if not answers:
        return None
    triggers = []
    for entry in answers:
        if "command" in entry:
            triggers.append({
                "wait_for": entry["wait_for"],
                "command": entry["command"],
                "delay": entry.get("delay", 1),
            })
        elif "answer" in entry:
            triggers.append({
                "wait_for": entry["wait_for"],
                "answer": entry["answer"],
                "delay": entry.get("delay", 1),
            })
    return triggers or None


def _build_user_answers(answers: Optional[list[dict]]) -> dict[str, str]:
    """Extract user_answers dict from answers list (WaitingForUser pattern)."""
    if not answers:
        return {}
    result = {}
    for entry in answers:
        if "answer" in entry and "wait_for" in entry:
            result[entry["wait_for"]] = entry["answer"]
    return result
