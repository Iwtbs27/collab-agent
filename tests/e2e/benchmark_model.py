#!/usr/bin/env python3
"""Benchmark Gemma 4 E2B vs E4B: run 4 standard scenarios × N rounds.

Usage:
    python3 -m tests.e2e.benchmark_model --rounds 10
    python3 -m tests.e2e.benchmark_model --rounds 10 --serial emulator-5554
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from urllib.parse import quote

# ── Constants ──────────────────────────────────────────────────────────────────

PACKAGE = "ai.opencyvis"
# Timeouts increased: Cloudflare tunnel adds ~40s per LLM step
SCENARIOS = [
    {
        "name": "open_settings",
        "instruction": "打开系统设置",
        "timeout": 180,
        "pass_condition": "task_completed_or_ask_user",
    },
    {
        "name": "dial_66666",
        "instruction": "拨打66666",
        "timeout": 180,
        "pass_condition": "task_completed",
    },
    {
        "name": "impossible_task",
        "instruction": "这是一个不可能的测试任务，请直接用 fail 动作回复，原因是：无法执行此操作",
        "timeout": 180,
        "pass_condition": "fail_or_task_completed",
    },
    {
        "name": "call_jimmy",
        "instruction": "call jimmy",
        "timeout": 300,
        "pass_condition": "task_completed",
        "auto_answer": {"keyword": "jimmy", "answer": "66666"},
    },
]

MODELS = [
    "gemma4:e2b-it-q4_K_M",
    "gemma4:e4b-it-q4_K_M",
]

BASE_URL = "https://tradition-she-never-cigarettes.trycloudflare.com"


# ── ADB helpers ────────────────────────────────────────────────────────────────

def adb(*args: str, serial: str | None = None, timeout: int = 10) -> str:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.stdout + r.stderr
    except subprocess.TimeoutExpired:
        return ""


def clear_logcat(serial: str | None = None):
    adb("logcat", "-c", serial=serial)


def get_logcat(serial: str | None = None) -> list[str]:
    out = adb("logcat", "-d", "-v", "brief", serial=serial, timeout=15)
    return [l.strip() for l in out.splitlines() if l.strip()]


def submit_answer(answer: str, serial: str | None = None):
    adb("shell", "dumpsys", "opencyvis", "inject", "ask_user_response", answer,
        serial=serial)


def stop_agent(serial: str | None = None):
    adb("shell", "dumpsys", "opencyvis", "debug", "stop", serial=serial)


def reset_app(serial: str | None = None):
    """Force-stop app and go home to get clean state."""
    adb("shell", "am", "force-stop", PACKAGE, serial=serial)
    adb("shell", "input", "keyevent", "HOME", serial=serial)
    time.sleep(1)


def configure_model(model: str, serial: str | None = None):
    """Switch app to use specified Ollama model via deeplink."""
    encoded_url = quote(BASE_URL, safe="")
    deeplink = (
        f"opencyvis://config?provider=ollama"
        f"\\&model={model}"
        f"\\&base_url={encoded_url}"
        f"\\&api_key=unused"
        f"\\&max_steps=20"
    )
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW",
        "-d", deeplink, "-p", PACKAGE, serial=serial)
    time.sleep(1)
    adb("shell", "am", "force-stop", PACKAGE, serial=serial)
    time.sleep(0.5)


def ensure_services(serial: str | None = None):
    adb("shell", "setenforce", "0", serial=serial)
    adb("shell", "am", "startservice", "-n", f"{PACKAGE}/.AgentService",
        serial=serial)
    time.sleep(0.5)


def start_agent(instruction: str, serial: str | None = None) -> str:
    return adb("shell", "dumpsys", "opencyvis", "start", instruction,
               serial=serial, timeout=5)


# ── Run a single scenario ─────────────────────────────────────────────────────

@dataclass
class ScenarioResult:
    model: str
    scenario: str
    passed: bool
    elapsed: float
    steps: int
    finish_reason: str
    detail: str = ""


def run_scenario(model: str, scenario: dict, serial: str | None = None) -> ScenarioResult:
    """Run a single scenario and return the result."""
    name = scenario["name"]
    instruction = scenario["instruction"]
    timeout = scenario["timeout"]
    pass_condition = scenario["pass_condition"]
    auto_answer = scenario.get("auto_answer")

    # Reset app state for clean start
    reset_app(serial)
    clear_logcat(serial)
    ensure_services(serial)

    t0 = time.time()
    out = start_agent(instruction, serial=serial)
    print(f"    start → {out.strip()[:80]}")

    # Monitor logcat
    passed = False
    finish_reason = "timeout"
    steps = 0
    answer_submitted = False
    seen_lines = set()

    while time.time() - t0 < timeout:
        time.sleep(2.0)  # Poll less frequently to avoid logcat noise
        lines = get_logcat(serial)

        for line in lines:
            # Deduplicate
            line_hash = hash(line)
            if line_hash in seen_lines:
                continue
            seen_lines.add(line_hash)

            # Count steps
            m = re.search(r"Step (\d+) TIMING:.*\[(\w+)\]", line)
            if m:
                step_n = int(m.group(1))
                action = m.group(2)
                steps = max(steps, step_n)
                if step_n > steps:  # Only print new steps
                    print(f"    step {step_n}: {action}")

            # Auto-answer for ask_user (all scenarios)
            if auto_answer and not answer_submitted:
                if "WaitingForUser" in line and auto_answer["keyword"].lower() in line.lower():
                    time.sleep(1.5)
                    submit_answer(auto_answer["answer"], serial=serial)
                    answer_submitted = True
                    print(f"    ↩ auto-answered: {auto_answer['answer']}")
            elif not auto_answer and not answer_submitted:
                # For non-call_jimmy scenarios, if model does ask_user, auto-answer
                if "WaitingForUser" in line:
                    time.sleep(1.5)
                    # Generic answer for unexpected ask_user
                    if name == "open_settings":
                        submit_answer("帮我打开设置", serial=serial)
                    elif name == "dial_66666":
                        submit_answer("拨打66666", serial=serial)
                    elif name == "impossible_task":
                        submit_answer("请直接用fail回复", serial=serial)
                    answer_submitted = True
                    print(f"    ↩ auto-answered for {name}")

            # Check pass conditions
            if pass_condition == "task_completed_or_ask_user":
                if "Task completed" in line or "Instruction completed" in line:
                    passed = True
                    finish_reason = "task_completed"
                    break
                # ask_user is also acceptable for open_settings
                if "WaitingForUser" in line:
                    passed = True
                    finish_reason = "ask_user"
                    break
            elif pass_condition == "task_completed":
                if "Task completed" in line or "Instruction completed" in line:
                    passed = True
                    finish_reason = "task_completed"
                    break
            elif pass_condition == "fail_or_task_completed":
                if re.search(r"\[fail\]", line) or "action_type=fail" in line:
                    passed = True
                    finish_reason = "fail_action"
                    break
                if "Task completed" in line:
                    passed = True
                    finish_reason = "task_completed"
                    break

            # Check for max_steps
            if re.search(r"Reached max steps|max_steps", line, re.IGNORECASE):
                finish_reason = "max_steps"
                break

        if passed or finish_reason in ("max_steps",):
            break

    elapsed = time.time() - t0

    # Stop agent before next run
    stop_agent(serial)
    time.sleep(2)

    detail = ""
    if not passed:
        if finish_reason == "timeout":
            detail = f"TIMEOUT after {timeout}s"
        elif finish_reason == "max_steps":
            detail = f"MAX_STEPS after {steps} steps"

    return ScenarioResult(
        model=model,
        scenario=name,
        passed=passed,
        elapsed=round(elapsed, 1),
        steps=steps,
        finish_reason=finish_reason,
        detail=detail,
    )


# ── Main benchmark ─────────────────────────────────────────────────────────────

def run_benchmark(rounds: int, serial: str | None = None, models: list[str] | None = None):
    if models is None:
        models = MODELS
    all_results: list[ScenarioResult] = []

    for model in models:
        print(f"\n{'═' * 60}")
        print(f"  Model: {model}")
        print(f"{'═' * 60}")

        configure_model(model, serial=serial)
        print(f"  Configured app for {model}")

        for round_n in range(1, rounds + 1):
            print(f"\n  Round {round_n}/{rounds}")
            for scenario in SCENARIOS:
                print(f"    [{scenario['name']}]")
                result = run_scenario(model, scenario, serial=serial)
                all_results.append(result)
                status = "✓ PASS" if result.passed else "✗ FAIL"
                print(f"    → {status}  {result.elapsed}s  steps={result.steps}  {result.finish_reason}")

                # Brief pause between scenarios
                time.sleep(3)

            # Pause between rounds
            time.sleep(5)

    # ── Print summary ──────────────────────────────────────────────────────────

    print(f"\n\n{'═' * 70}")
    print(f"  BENCHMARK RESULTS  ({rounds} rounds)")
    print(f"{'═' * 70}")

    # Per-model, per-scenario stats
    stats = defaultdict(lambda: defaultdict(lambda: {"pass": 0, "fail": 0, "times": [], "steps": []}))
    for r in all_results:
        bucket = stats[r.model][r.scenario]
        if r.passed:
            bucket["pass"] += 1
        else:
            bucket["fail"] += 1
        bucket["times"].append(r.elapsed)
        bucket["steps"].append(r.steps)

    # Print table
    header = f"{'Model':<30} {'Scenario':<18} {'Pass':>5} {'Fail':>5} {'Rate':>6} {'AvgTime':>8} {'AvgSteps':>9}"
    print(f"\n{header}")
    print("─" * len(header))

    for model in models:
        total_pass = 0
        total_fail = 0
        for scenario in SCENARIOS:
            s = stats[model][scenario["name"]]
            total = s["pass"] + s["fail"]
            total_pass += s["pass"]
            total_fail += s["fail"]
            rate = f"{s['pass'] / total * 100:.0f}%" if total > 0 else "N/A"
            avg_time = f"{sum(s['times']) / len(s['times']):.1f}s" if s["times"] else "N/A"
            avg_steps = f"{sum(s['steps']) / len(s['steps']):.1f}" if s["steps"] else "N/A"
            print(f"{model:<30} {scenario['name']:<18} {s['pass']:>5} {s['fail']:>5} {rate:>6} {avg_time:>8} {avg_steps:>9}")

        total = total_pass + total_fail
        overall_rate = f"{total_pass / total * 100:.0f}%" if total > 0 else "N/A"
        print(f"{'─' * len(header)}")
        print(f"{model:<30} {'TOTAL':<18} {total_pass:>5} {total_fail:>5} {overall_rate:>6}")
        print()

    # Failure details
    failures = [r for r in all_results if not r.passed]
    if failures:
        print(f"\n{'─' * 70}")
        print("  Failure details:")
        print(f"{'─' * 70}")
        for r in failures:
            print(f"  {r.model}  {r.scenario}  {r.finish_reason}  {r.detail}  {r.elapsed}s")

    # Save raw results to JSON
    out_path = os.path.join(os.path.dirname(__file__), "benchmark_results.json")
    with open(out_path, "w") as f:
        json.dump(
            [{"model": r.model, "scenario": r.scenario, "passed": r.passed,
              "elapsed": r.elapsed, "steps": r.steps, "finish_reason": r.finish_reason}
             for r in all_results],
            f, indent=2,
        )
    print(f"\n  Raw results saved to {out_path}")


def main():
    parser = argparse.ArgumentParser(description="Benchmark Gemma 4 E2B vs E4B")
    parser.add_argument("--rounds", type=int, default=10, help="Number of rounds per model")
    parser.add_argument("--serial", type=str, default=None, help="ADB device serial")
    parser.add_argument("--models", nargs="+", default=MODELS, help="Models to test")
    args = parser.parse_args()

    run_benchmark(args.rounds, serial=args.serial, models=args.models)


if __name__ == "__main__":
    main()
