"""Pytest conftest for E2E scenario tests."""

from __future__ import annotations

import os

import pytest

from tests.e2e.framework import RunConfig


def pytest_addoption(parser):
    parser.addoption("--serial", default=None, help="ADB device serial")
    parser.addoption(
        "--category", default="smoke",
        help="Scenario category to run (smoke, llm, nightly, or 'all')",
    )
    parser.addoption(
        "--api-key", default=None, dest="api_key",
        help="Vision LLM API key (for mirror assertions)",
    )
    parser.addoption(
        "--mock-only", action="store_true", default=False,
        help="Only run scenarios that use mock_responses",
    )
    parser.addoption(
        "--live-only", action="store_true", default=False,
        help="Only run scenarios that do NOT use mock_responses (require real LLM)",
    )


@pytest.fixture(scope="session")
def run_config(request) -> RunConfig:
    api_key = request.config.getoption("api_key") or os.environ.get("AIPHONE_API_KEY")
    return RunConfig(
        serial=request.config.getoption("serial"),
        api_key=api_key,
    )
