"""Pytest entry point: run E2E scenarios from tests/ui/scenarios.yml.

Usage:
    pytest tests/e2e/test_scenarios.py -v                         # smoke only (default)
    pytest tests/e2e/test_scenarios.py --category llm -v          # llm category
    pytest tests/e2e/test_scenarios.py --category all -v          # all scenarios
    pytest tests/e2e/test_scenarios.py --serial emulator-5554 -v  # specific device
    pytest tests/e2e/test_scenarios.py --mock-only -v             # only mock scenarios
    pytest tests/e2e/test_scenarios.py --live-only -v             # only live LLM scenarios
"""

from __future__ import annotations

import pytest

from tests.e2e.framework import RunConfig, TestRunner
from tests.e2e.scenario_loader import build_case, load_scenarios


def _get_scenarios(category: str, mock_only: bool = False, live_only: bool = False) -> list[dict]:
    cat = None if category == "all" else category
    scenarios = load_scenarios(category=cat)
    if mock_only:
        scenarios = [s for s in scenarios if s.get("mock_responses")]
    elif live_only:
        scenarios = [s for s in scenarios if not s.get("mock_responses")]
    return scenarios


def pytest_generate_tests(metafunc):
    if "scenario" in metafunc.fixturenames:
        category = metafunc.config.getoption("category", "smoke")
        mock_only = metafunc.config.getoption("mock_only", False)
        live_only = metafunc.config.getoption("live_only", False)
        scenarios = _get_scenarios(category, mock_only=mock_only, live_only=live_only)
        metafunc.parametrize(
            "scenario",
            scenarios,
            ids=[s["name"] for s in scenarios],
        )


def test_scenario(scenario: dict, run_config: RunConfig):
    case = build_case(scenario)
    runner = TestRunner(run_config)
    result = runner.run(case)
    assert result.passed, result.summary()
