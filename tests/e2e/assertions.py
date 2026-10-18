"""Assertion types for OpenCyvis E2E test framework."""

from __future__ import annotations

import base64
import json
import logging
from typing import TYPE_CHECKING

import httpx

if TYPE_CHECKING:
    from tests.e2e.framework import RunConfig, RunState

logger = logging.getLogger(__name__)


class Assertion:
    """Base class for all test assertions."""

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        """Return (passed, detail_message)."""
        raise NotImplementedError

    def __str__(self) -> str:
        return self.__class__.__name__


# ── Logcat-based assertions ────────────────────────────────────────────────────

class FinishAction(Assertion):
    """Agent must end with a finish action (not fail or timeout)."""

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        if state.finished:
            return True, "finish action received"
        return False, f"no finish action — reason: {state.finish_reason or 'unknown'}"

    def __str__(self) -> str:
        return "FinishAction"


class NoFailAction(Assertion):
    """Agent must NOT end with a fail action."""

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        if state.failed:
            return False, f"agent emitted fail action: {state.finish_reason}"
        return True, "no fail action"

    def __str__(self) -> str:
        return "NoFailAction"


class LogcatPattern(Assertion):
    """A regex pattern must appear in the collected logcat lines."""

    def __init__(self, pattern: str):
        self.pattern = pattern

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        import re
        for line in state.logcat_lines:
            if re.search(self.pattern, line):
                return True, f"matched: {self.pattern!r}"
        return False, f"pattern not found: {self.pattern!r}"

    def __str__(self) -> str:
        return f"LogcatPattern({self.pattern!r})"


class StepCountBound(Assertion):
    """Task must complete within at most `max_steps` steps."""

    def __init__(self, max_steps: int):
        self.max_steps = max_steps

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        n = len(state.steps)
        if n <= self.max_steps:
            return True, f"{n} steps ≤ {self.max_steps}"
        return False, f"{n} steps exceeded max {self.max_steps}"

    def __str__(self) -> str:
        return f"StepCountBound(max={self.max_steps})"


class AskUserAnswered(Assertion):
    """Agent must have asked the user at least once, and an answer was submitted."""

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        if not state.ask_user_questions:
            return False, "agent never asked the user a question"
        if not state.answers_submitted:
            return False, "question was asked but no answer was submitted"
        return True, (
            f"asked {len(state.ask_user_questions)} question(s), "
            f"submitted {len(state.answers_submitted)} answer(s)"
        )

    def __str__(self) -> str:
        return "AskUserAnswered"


# ── ADB state assertions ───────────────────────────────────────────────────────

class AdbForegroundApp(Assertion):
    """A specific app package must be in the foreground after the task.

    Checks both main display and virtual displays for the expected package.
    """

    def __init__(self, package: str):
        self.package = package

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        from tests.e2e.adb_utils import get_all_foreground_packages
        packages = get_all_foreground_packages(config.serial)
        if self.package in packages:
            return True, f"{self.package} is in foreground (displays: {packages})"
        return False, f"expected {self.package!r} in foreground, got {packages!r}"

    def __str__(self) -> str:
        return f"AdbForegroundApp({self.package!r})"


# ── Vision assertion ───────────────────────────────────────────────────────────

class ScreenshotVerify(Assertion):
    """Take a screenshot and ask the Doubao vision model if it matches a description.

    The LLM is asked: "Does this screenshot show: {description}? Answer yes or no."
    Passes if the response starts with 'yes'.

    Requires `RunConfig.api_key` to be set.
    """

    def __init__(self, description: str):
        self.description = description

    def evaluate(self, state: RunState, config: RunConfig) -> tuple[bool, str]:
        if not config.api_key:
            return False, "ScreenshotVerify requires api_key in RunConfig"

        # Use cached screenshot if available, otherwise take one now
        png_bytes = state.screenshot_png
        if not png_bytes:
            from tests.e2e.adb_utils import screencap_png
            png_bytes = screencap_png(config.serial)

        if not png_bytes or len(png_bytes) < 100:
            return False, "screencap returned empty data"

        try:
            passed, response = _llm_vision_verify(
                png_bytes=png_bytes,
                description=self.description,
                api_key=config.api_key,
                model=config.model,
                base_url=config.base_url,
            )
        except Exception as e:
            return False, f"LLM vision call failed: {e}"

        detail = f"LLM says: {response!r} — {'✓' if passed else '✗'} {self.description!r}"
        return passed, detail

    def __str__(self) -> str:
        desc = self.description[:40] + "…" if len(self.description) > 40 else self.description
        return f"ScreenshotVerify({desc!r})"


def _llm_vision_verify(
    png_bytes: bytes,
    description: str,
    api_key: str,
    model: str,
    base_url: str,
) -> tuple[bool, str]:
    """Call Doubao Responses API with a screenshot to verify a description.

    Returns (passed, raw_response_text).
    """
    b64 = base64.b64encode(png_bytes).decode()

    payload = {
        "model": model,
        "input": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_image",
                        "image_url": f"data:image/png;base64,{b64}",
                    },
                    {
                        "type": "input_text",
                        "text": (
                            f"请看这张手机截图，判断以下描述是否符合截图内容：\n"
                            f"{description}\n\n"
                            f"只回答 yes 或 no，不要其他文字。"
                        ),
                    },
                ],
            }
        ],
        "max_output_tokens": 16,
        "temperature": 0.0,
    }

    with httpx.Client(
        base_url=base_url,
        headers={"Authorization": f"Bearer {api_key}"},
        timeout=30,
    ) as client:
        resp = client.post("/responses", json=payload)
        resp.raise_for_status()
        data = resp.json()

    text = _extract_text_from_responses(data).strip().lower()
    logger.debug("ScreenshotVerify LLM response: %r", text)

    passed = text.startswith("yes")
    return passed, text


def _extract_text_from_responses(data: dict) -> str:
    """Extract text from Doubao /responses API output."""
    for item in data.get("output", []):
        if item.get("type") == "message":
            for content in item.get("content", []):
                if content.get("type") in ("output_text", "text"):
                    return content.get("text", "")
    # Fallback: chat/completions style
    choices = data.get("choices", [])
    if choices:
        return choices[0].get("message", {}).get("content", "")
    return ""
