"""Core E2E test framework: AgentTestCase base class and TestRunner."""

from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
from typing import ClassVar, Optional, Type
from urllib.parse import quote

from tests.e2e import adb_utils
from tests.e2e.assertions import Assertion
from tests.e2e.mock_llm_server import MockLLMServer


# ── Configuration ─────────────────────────────────────────────────────────────

@dataclass
class RunConfig:
    """Runtime configuration shared across all test cases in a run."""
    serial: Optional[str] = None                        # adb device serial
    api_key: Optional[str] = None                       # Doubao API key (for ScreenshotVerify)
    model: str = "doubao-seed-2-0-lite"
    base_url: str = "https://ark.cn-beijing.volces.com/api/v3"


# ── Accumulated run state ─────────────────────────────────────────────────────

@dataclass
class RunState:
    """State accumulated while monitoring logcat during a single test run."""
    steps: list[tuple[int, str]] = field(default_factory=list)   # [(step_num, action_type)]
    errors: list[str] = field(default_factory=list)
    logcat_lines: list[str] = field(default_factory=list)
    finish_reason: str = ""
    finished: bool = False    # received [finish] action
    failed: bool = False      # received [fail] action
    ask_user_questions: list[str] = field(default_factory=list)
    answers_submitted: list[str] = field(default_factory=list)
    elapsed: float = 0.0
    screenshot_png: bytes = b""


# ── Test result ───────────────────────────────────────────────────────────────

@dataclass
class AssertionResult:
    name: str
    passed: bool
    detail: str


@dataclass
class TestResult:
    case_name: str
    passed: bool
    state: RunState
    assertion_results: list[AssertionResult]

    def summary(self) -> str:
        icon = "✓ PASS" if self.passed else "✗ FAIL"
        lines = [
            f"\n{'─' * 60}",
            f"  {icon}   {self.case_name}  ({self.state.elapsed:.1f}s)",
            f"  steps       : {self.state.steps}",
            f"  finish      : {self.state.finish_reason}",
        ]
        if self.state.ask_user_questions:
            lines.append(f"  asked       : {self.state.ask_user_questions}")
            lines.append(f"  answered    : {self.state.answers_submitted}")
        if self.state.errors:
            lines.append(f"  errors      : {self.state.errors}")
        for ar in self.assertion_results:
            tick = "✓" if ar.passed else "✗"
            lines.append(f"  [{tick}] {ar.name}: {ar.detail}")
        lines.append(f"{'─' * 60}")
        return "\n".join(lines)


# ── Base test case ─────────────────────────────────────────────────────────────

class AgentTestCase:
    """Declarative base class for agent E2E test cases.

    Subclass this and set class-level attributes:

        class TestOpenSettings(AgentTestCase):
            instruction = "打开手机设置"
            timeout = 60
            assertions = [
                FinishAction(),
                AdbForegroundApp("com.android.settings"),
                ScreenshotVerify("手机设置页面已打开"),
            ]

    user_answers maps a substring of the question to the answer to submit:
        user_answers = {"jimmy": "66666"}
    """

    instruction: ClassVar[str]
    timeout: ClassVar[int] = 120
    assertions: ClassVar[list[Assertion]] = []

    # {question_substring -> answer}: auto-replies for WaitingForUser prompts
    user_answers: ClassVar[dict[str, str]] = {}

    # Scripted LLM responses for mock mode (per-scenario opt-in)
    mock_responses: ClassVar[list[dict] | None] = None

    # Max steps override (passed to app via deeplink during mock setup)
    max_steps: ClassVar[int] = 20

    # Generic logcat triggers → dumpsys commands (for handoff, supplement, etc.)
    # Each entry: {"wait_for": "<pattern>", "command": "debug ...", "delay": 0.5}
    #         or: {"wait_for": "<pattern>", "answer": "<text>", "delay": 0.5}
    trigger_commands: ClassVar[list[dict] | None] = None

    # ADB commands to run before/after the instruction
    pre_steps: ClassVar[list[str] | None] = None
    post_steps: ClassVar[list[str] | None] = None

    # Extra deeplink params appended to mock setup deeplink (e.g. im_remote_enabled=true)
    extra_deeplink_params: ClassVar[dict[str, str] | None] = None

    # Skip the main dumpsys start instruction — for scenarios driven entirely by post_steps (e.g. IM cold start)
    skip_start_agent: ClassVar[bool] = False

    @classmethod
    def name(cls) -> str:
        return cls.__name__


# ── Test runner ───────────────────────────────────────────────────────────────

class TestRunner:
    """Drives AgentTestCase instances against a real Android device.

    Flow per test:
      1. Clear logcat
      2. (If mock_responses) Setup mock LLM server + configure app
      3. Execute pre_steps
      4. Ensure AgentService + OverlayService are running
      5. Start agent via `dumpsys opencyvis start <instruction>`
      6. Monitor logcat until agent finishes / timeout
      7. Execute post_steps
      8. Take screenshot (used by ScreenshotVerify assertions)
      9. (If mock_responses) Teardown mock server + restore config
      10. Evaluate all assertions → return TestResult
    """

    __test__ = False  # prevent pytest collection

    MOCK_PORT = 9876

    def __init__(self, config: RunConfig):
        self.config = config
        self._mock_server: MockLLMServer | None = None
        self._saved_config: str | None = None

    def run(self, case: Type[AgentTestCase]) -> TestResult:
        state = RunState()
        t0 = time.time()
        use_mock = case.mock_responses is not None

        _ts = lambda: time.strftime("%H:%M:%S")
        mock_label = " [MOCK]" if use_mock else ""
        print(f"\n[{_ts()}] ▶  {case.name()}{mock_label}: {case.instruction!r}")

        # 1. Clear logcat
        adb_utils.clear_logcat(self.config.serial)

        # 2. Setup mock LLM if needed
        if use_mock:
            print(f"[{_ts()}]    setting up mock LLM server…")
            if not self._setup_mock_llm(case.mock_responses, max_steps=case.max_steps, extra_params=case.extra_deeplink_params):
                state.finish_reason = "mock LLM setup failed"
                state.elapsed = time.time() - t0
                return self._make_result(case, state)

        try:
            # 3. Execute pre_steps
            if case.pre_steps:
                print(f"[{_ts()}]    running {len(case.pre_steps)} pre_steps…")
                adb_utils.execute_steps(self.config.serial, case.pre_steps)

            # 4. Ensure services
            print(f"[{_ts()}]    starting services…")
            adb_utils.ensure_services(self.config.serial)

            if not adb_utils.is_process_running(self.config.serial):
                state.finish_reason = "ai.opencyvis process not running — is the APK installed?"
                state.elapsed = time.time() - t0
                return self._make_result(case, state)

            # 4. Start agent via dumpsys (unless skip_start_agent)
            if case.skip_start_agent:
                print(f"[{_ts()}]    skipping start_agent (driven by post_steps)")
                # For skip_start_agent scenarios, execute post_steps BEFORE monitor
                # since they drive the agent (e.g. IM inbound messages)
                if case.post_steps:
                    print(f"[{_ts()}]    running {len(case.post_steps)} post_steps (pre-monitor)…")
                    adb_utils.execute_steps(self.config.serial, case.post_steps)
            else:
                out = adb_utils.start_agent(self.config.serial, instruction=case.instruction)
                print(f"[{_ts()}]    start → {out.strip()}")

            # 5. Monitor logcat
            if case.trigger_commands:
                for t in case.trigger_commands:
                    t.pop("_fired", None)
            proc = adb_utils.open_logcat(self.config.serial)
            try:
                self._monitor(proc, case, state, t0)
            finally:
                # Flush remaining logcat lines (catches late logs from triggers/commands)
                import select
                time.sleep(1.0)
                while select.select([proc.stdout], [], [], 0.2)[0]:
                    line = proc.stdout.readline().strip()
                    if line:
                        state.logcat_lines.append(line)
                proc.terminate()

            state.elapsed = time.time() - t0

            # 6. Execute post_steps (already ran for skip_start_agent)
            if case.post_steps and not case.skip_start_agent:
                print(f"[{_ts()}]    running {len(case.post_steps)} post_steps…")
                adb_utils.execute_steps(self.config.serial, case.post_steps)

            # 7. Take final screenshot for ScreenshotVerify assertions
            _needs_screenshot = any(
                a.__class__.__name__ == "ScreenshotVerify" for a in case.assertions
            )
            if _needs_screenshot:
                print(f"[{_ts()}]    taking final screenshot…")
                state.screenshot_png = adb_utils.screencap_png(self.config.serial)

            # 8. Evaluate assertions (before mock teardown — assertions may need
            #    app state set up by post_steps, e.g. dumpsys_contains after debug view)
            result = self._make_result(case, state)

        finally:
            # 9. Teardown mock
            if use_mock:
                self._teardown_mock_llm()

        return result

    def _setup_mock_llm(self, responses: list[dict], max_steps: int = 20, extra_params: dict | None = None) -> bool:
        """Start mock server, configure adb reverse, set app config via deeplink."""
        try:
            self._mock_server = MockLLMServer(self.MOCK_PORT)
            self._mock_server.start()
            self._mock_server.load_responses(responses)

            # adb reverse so device can reach host mock server at localhost:PORT
            adb_utils.adb_run(
                "reverse", f"tcp:{self.MOCK_PORT}", f"tcp:{self.MOCK_PORT}",
                serial=self.config.serial,
            )

            # Save current config for restoration after test
            self._saved_config = adb_utils.adb_run(
                "shell", "cat",
                f"/data/data/{adb_utils.PACKAGE}/shared_prefs/opencyvis_config.xml",
                serial=self.config.serial, timeout=5,
            )

            # Force stop app to ensure clean config read
            adb_utils.adb_run("shell", "am", "force-stop", adb_utils.PACKAGE,
                              serial=self.config.serial)
            time.sleep(0.5)

            # Configure app via deeplink to point to mock server
            # Quote & for device shell since adb shell joins args into a shell command
            mock_url = quote(f"http://localhost:{self.MOCK_PORT}/v1", safe="")
            deeplink = (
                f"opencyvis://config?provider=openai"
                f"\\&model=mock-smoke-test"
                f"\\&base_url={mock_url}"
                f"\\&api_key=fake-test-key"
                f"\\&max_steps={max_steps}"
            )
            # Append per-scenario extra deeplink params
            if extra_params:
                for k, v in extra_params.items():
                    deeplink += f"\\&{k}={v}"
            adb_utils.adb_run(
                "shell", "am", "start", "-a", "android.intent.action.VIEW",
                "-d", deeplink, "-p", adb_utils.PACKAGE,
                serial=self.config.serial,
            )
            time.sleep(1.0)

            # Force stop again so next ensure_services gets fresh config
            adb_utils.adb_run("shell", "am", "force-stop", adb_utils.PACKAGE,
                              serial=self.config.serial)
            time.sleep(0.5)

            return True
        except Exception as e:
            print(f"    ✗ mock setup failed: {e}")
            self._teardown_mock_llm()
            return False

    def _teardown_mock_llm(self):
        """Stop mock server, clean up adb reverse, restore original config."""
        try:
            adb_utils.adb_run(
                "reverse", "--remove", f"tcp:{self.MOCK_PORT}",
                serial=self.config.serial,
            )
        except Exception:
            pass

        if self._mock_server:
            self._mock_server.stop()
            self._mock_server = None

        if self._saved_config and "base_url" in self._saved_config:
            try:
                import tempfile
                with tempfile.NamedTemporaryFile(mode="w", suffix=".xml", delete=False) as f:
                    f.write(self._saved_config)
                    tmp = f.name
                adb_utils.adb_run("shell", "am", "force-stop", adb_utils.PACKAGE,
                                  serial=self.config.serial)
                adb_utils.adb_run(
                    "push", tmp,
                    f"/data/data/{adb_utils.PACKAGE}/shared_prefs/opencyvis_config.xml",
                    serial=self.config.serial, timeout=5,
                )
                import os
                os.unlink(tmp)
            except Exception:
                pass
            self._saved_config = None

    def _monitor(
        self,
        proc,
        case: Type[AgentTestCase],
        state: RunState,
        t0: float,
    ) -> None:
        import select
        _ts = lambda: time.strftime("%H:%M:%S")

        while True:
            elapsed = time.time() - t0
            if elapsed > case.timeout:
                state.finish_reason = f"TIMEOUT after {case.timeout}s"
                print(f"[{_ts()}]    ✗ TIMEOUT")
                break

            # Wait up to 1s for logcat data, then re-check timeout
            ready, _, _ = select.select([proc.stdout], [], [], 1.0)
            if not ready:
                continue

            raw_line = proc.stdout.readline()
            if not raw_line:
                break
            line = raw_line.strip()

            if not line:
                continue

            state.logcat_lines.append(line)

            # Step timing
            m = re.search(r"Step (\d+) TIMING:.*\[(\w+)\]", line)
            if m:
                step_n, action = int(m.group(1)), m.group(2)
                state.steps.append((step_n, action))
                print(f"[{_ts()}]    step {step_n}: {action}")
                if action == "unknown":
                    state.errors.append(f"unknown action at step {step_n}")

            # LLM errors
            if "LLM returned unrecognized action_type" in line:
                state.errors.append(f"unrecognized_action: {line[-200:]}")
                print(f"[{_ts()}]    ⚠  unrecognized action_type")

            if "(ERROR)" in line and "TIMING" in line:
                state.errors.append(f"llm_error: {line[-120:]}")
                print(f"[{_ts()}]    ✗ LLM ERROR")

            # WaitingForUser — auto-submit answer
            if "WaitingForUser" in line:
                m2 = re.search(r"WaitingForUser\(question=(.*?), step=", line)
                question = m2.group(1) if m2 else line
                state.ask_user_questions.append(question)
                print(f"[{_ts()}]    ❓ ask_user: {question}")

                answer = self._find_answer(question, case.user_answers)
                if answer is not None:
                    time.sleep(0.8)
                    adb_utils.submit_answer(serial=self.config.serial, answer=answer)
                    state.answers_submitted.append(answer)
                    print(f"[{_ts()}]    ↩  submitted: {answer!r}")
                else:
                    print(f"[{_ts()}]    ⚠  no matching answer configured, leaving unanswered")

            # Generic trigger → command dispatch (for handoff, supplement, etc.)
            if case.trigger_commands:
                for trigger in case.trigger_commands:
                    pattern = trigger["wait_for"]
                    if pattern in line and not trigger.get("_fired"):
                        trigger["_fired"] = True
                        delay = trigger.get("delay", 0.5)
                        time.sleep(delay)
                        if "command" in trigger:
                            cmd_parts = trigger["command"].split()
                            result = adb_utils.dumpsys_cmd(self.config.serial, *cmd_parts)
                            print(f"[{_ts()}]    ⚙  command: {trigger['command']} → {result}")
                        elif "answer" in trigger:
                            adb_utils.submit_answer(serial=self.config.serial, answer=trigger["answer"])
                            state.answers_submitted.append(trigger["answer"])
                            print(f"[{_ts()}]    ↩  trigger-answer: {trigger['answer']!r}")

            # Success signals
            if "Instruction completed" in line or "Task completed" in line:
                state.finished = True
                state.finish_reason = "Instruction completed"
                print(f"[{_ts()}]    ✓ COMPLETED")
                break

            if re.search(r"\[finish\]", line):
                state.finished = True
                state.finish_reason = "finish action"
                print(f"[{_ts()}]    ✓ FINISH")
                break

            if re.search(r"\[fail\]", line):
                state.failed = True
                state.finish_reason = "fail action"
                print(f"[{_ts()}]    ✗ FAIL action")
                break

            if re.search(r"Reached max steps|max_steps", line, re.IGNORECASE):
                state.finish_reason = "max_steps reached"
                print(f"[{_ts()}]    ✗ MAX STEPS")
                break

    @staticmethod
    def _find_answer(question: str, user_answers: dict[str, str]) -> Optional[str]:
        """Find a configured answer whose key is a substring of the question."""
        q_lower = question.lower()
        for key, answer in user_answers.items():
            if key.lower() in q_lower:
                return answer
        return None

    def _make_result(self, case: Type[AgentTestCase], state: RunState) -> TestResult:
        assertion_results = []
        all_passed = True

        for assertion in case.assertions:
            try:
                passed, detail = assertion.evaluate(state, self.config)
            except Exception as e:
                passed, detail = False, f"assertion raised: {e}"
            assertion_results.append(AssertionResult(
                name=str(assertion),
                passed=passed,
                detail=detail,
            ))
            if not passed:
                all_passed = False

        result = TestResult(
            case_name=case.name(),
            passed=all_passed,
            state=state,
            assertion_results=assertion_results,
        )
        print(result.summary())
        return result


# ── Convenience: run a list of cases ─────────────────────────────────────────

def run_all(
    cases: list[Type[AgentTestCase]],
    config: RunConfig,
) -> list[TestResult]:
    runner = TestRunner(config)
    results = [runner.run(c) for c in cases]

    passed = sum(1 for r in results if r.passed)
    total = len(results)
    print(f"\n{'═' * 60}")
    print(f"  Results: {passed}/{total} passed")
    print(f"{'═' * 60}\n")
    return results
