import argparse
import asyncio
import json
import os
import shutil
import sys
import time
from pathlib import Path
from typing import Any, Awaitable, Callable, Mapping

from .runner import cleanup_case, evaluate_case, reset_case
from .runner import load_local_cases


BASELINE_NAME = "baseline-laptop-v1"
PILOT_NAME = "pilot-laptop-v1"
PILOT_CASE_IDS = ("LB04", "LD02", "LX02")

AsyncRunner = Callable[[Path], Awaitable[None]]
TeamRunner = Callable[[Any, str], Awaitable[str]]


def _relative_target_path(case: Mapping[str, Any]) -> Path:
    target_path = case.get("target_path")
    if not isinstance(target_path, str) or not target_path:
        raise ValueError(f"{case['id']}: cross cases require target_path")
    target = Path(target_path)
    if target.is_absolute() or ".." in target.parts:
        raise ValueError(f"{case['id']}: target_path must stay below run_root")
    return target


def _case_environment_paths(
    case: Mapping[str, Any],
    bench_home: Path | str,
    reset_result: Mapping[str, Any] | None = None,
) -> tuple[Path | None, Path | None]:
    run_root = (reset_result or {}).get("run_root")
    if run_root is None and "run_root" in case:
        run_root = Path(bench_home) / str(case["run_root"])
    if run_root is None:
        return None, None

    environment_root = Path(run_root)
    if case.get("domain") == "cross":
        return environment_root, environment_root / _relative_target_path(case)
    return environment_root, environment_root


def build_case_request(
    case: Mapping[str, Any],
    bench_home: Path | str,
    *,
    execution_record_dir: Path | None = None,
    case_environment_root: Path | None = None,
    operation_target_path: Path | None = None,
) -> str:
    task = str(case["task"]).strip()
    local_task = str(case.get("local_task", "")).replace(
        "OMNIACT_BENCH_HOME", str(Path(bench_home))
    )
    if os.name == "nt":
        local_task = local_task.replace("/", "\\")
    if not local_task:
        return task

    if case_environment_root is None or operation_target_path is None:
        case_environment_root, operation_target_path = _case_environment_paths(
            case, bench_home
        )
    directory_boundary = ""
    if case_environment_root is not None and operation_target_path is not None:
        execution_directory = execution_record_dir or Path("(created per attempt)")
        directory_boundary = (
            "\n\nBenchmark directories:\n"
            f"- Execution record directory: {execution_directory}\n"
            f"- Case environment root: {case_environment_root}\n"
            f"- Operation target path: {operation_target_path}\n"
            "The execution record directory is only for traces, Worker handoffs, "
            "and evaluation records; it is not the operation target.\n"
        )
    return (
        f"{task}\n\n"
        "Local benchmark boundary:\n"
        f"{local_task.strip()}"
        f"{directory_boundary}\n"
        "Use the operation target path for all filesystem changes. Interact only with the "
        "applications and sites required by this case; do not use the real user Desktop, "
        "Documents, Downloads, or Pictures folders."
    )


def _reporting_metadata(case: Mapping[str, Any]) -> dict[str, str]:
    domain = str(case["domain"])
    execution_scope = str(
        case.get("execution_scope", "cross_application" if domain == "cross" else domain)
    )
    if domain == "browser":
        return {
            "upstream": str(case["upstream"]),
            "evaluator_name": str(case.get("evaluator_name", case["official_evaluator"])),
            "score_scope": str(
                case.get("score_scope", "upstream_official_evaluator_subset")
            ),
            "environment_scope": str(
                case.get("environment_scope", "container_local")
            ),
            "execution_scope": execution_scope,
        }
    verification = case.get("verification", {})
    evaluator_name = case.get("evaluator_name") or case.get("adapted_evaluator")
    if not evaluator_name and isinstance(verification, Mapping):
        evaluator_name = verification.get("kind")
    return {
        "upstream": str(case["upstream"]),
        "evaluator_name": str(evaluator_name or case["official_evaluator"]),
        "score_scope": str(case.get("score_scope", "omniact_adapted")),
        "environment_scope": str(
            case.get("environment_scope", "host_local_unisolated")
        ),
        "execution_scope": execution_scope,
    }


async def run_case(
    case: dict[str, Any],
    bench_home: Path | str,
    *,
    worker_runners: Mapping[str, AsyncRunner],
    team_runner: TeamRunner | None = None,
    attempt: int = 1,
    timeout_seconds: float = 1200,
    run_name: str = BASELINE_NAME,
) -> dict[str, Any]:
    from collab_agent.workspace import TaskWorkspace

    root = Path(bench_home)
    case_root = root / "runs" / run_name / case["id"]
    case_root.mkdir(parents=True, exist_ok=True)
    workspace = TaskWorkspace.create(case_root, f"attempt-{attempt}")
    started = time.perf_counter()
    run_error: tuple[str, str] | None = None
    evaluation: dict[str, Any] | None = None
    evaluation_error: str | None = None
    source_worker: Path | None = None
    reset_result: dict[str, Any] | None = None

    try:
        try:
            reset_result = reset_case(case, root)
            environment_root, target_path = _case_environment_paths(
                case, root, reset_result
            )
            request = build_case_request(
                case,
                root,
                execution_record_dir=workspace.task_dir,
                case_environment_root=environment_root,
                operation_target_path=target_path,
            )
            workspace.write_task(request)
            domain = case["domain"]
            if domain == "browser":
                source_worker = Path(reset_result["worker_dir"])
                runner = worker_runners["browser"]
                await asyncio.wait_for(runner(source_worker), timeout=timeout_seconds)
            elif domain == "desktop":
                worker_dir = workspace.worker_dir("desktop")
                workspace.write_request("desktop", request)
                runner = worker_runners["desktop"]
                await asyncio.wait_for(runner(worker_dir), timeout=timeout_seconds)
            else:
                if team_runner is None:
                    raise ValueError("cross cases require a TeamLeader runner")
                await asyncio.wait_for(
                    team_runner(workspace, request), timeout=timeout_seconds
                )
        except asyncio.TimeoutError:
            run_error = ("timeout", f"case exceeded {timeout_seconds:g} seconds")
        except Exception as error:
            run_error = ("error", f"{type(error).__name__}: {error}")

        if reset_result is not None:
            try:
                _run_evaluation_postconfig(case, root)
                evaluation = evaluate_case(case, root)
            except Exception as error:
                evaluation_error = f"{type(error).__name__}: {error}"
                if run_error is None:
                    run_error = ("evaluator_error", evaluation_error)
    finally:
        if source_worker is not None and source_worker.is_dir():
            shutil.copytree(
                source_worker,
                workspace.worker_dir("browser"),
                dirs_exist_ok=True,
            )
        cleanup_case(case, root)

    if run_error is None and evaluation is not None:
        status = "success" if evaluation["success"] else "failure"
        score = evaluation["score"]
        error = None
    else:
        status, error = run_error or ("error", "evaluation result is missing")
        score = 0.0

    metrics = _collect_metrics(workspace.task_dir)
    result = {
        "case_id": case["id"],
        "domain": case["domain"],
        "attempt": attempt,
        "status": status,
        "score": score,
        "elapsed_seconds": round(time.perf_counter() - started, 3),
        "error": error,
        "evaluation": evaluation,
        "evaluation_error": evaluation_error,
        "task_dir": str(workspace.task_dir),
        **_reporting_metadata(case),
        **metrics,
    }
    (workspace.task_dir / "evaluation.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return result


def _run_evaluation_postconfig(case: Mapping[str, Any], bench_home: Path) -> None:
    actions = case.get("evaluation_postconfig", [])
    if not actions:
        return
    import pyautogui

    for action in actions:
        kind = action["action"]
        if kind == "save":
            pyautogui.hotkey("ctrl", "s")
        elif kind == "save_as":
            pyautogui.hotkey("ctrl", "s")
            pyautogui.write(str(bench_home / action["path"]))
            pyautogui.press("enter")
        elif kind == "sleep":
            time.sleep(float(action["seconds"]))
        else:
            raise ValueError(f"{case['id']}: unsupported evaluation postconfig: {kind}")


async def run_baseline(
    cases: list[dict[str, Any]],
    bench_home: Path | str,
    *,
    worker_runners: Mapping[str, AsyncRunner],
    team_runner: TeamRunner | None,
    timeout_seconds: float = 1200,
    run_name: str = BASELINE_NAME,
    attempt: int = 1,
    configuration: Mapping[str, Any] | None = None,
) -> list[dict[str, Any]]:
    root = Path(bench_home) / "runs" / run_name
    root.mkdir(parents=True, exist_ok=True)
    results = []
    for case in cases:
        result = await run_case(
            case,
            bench_home,
            worker_runners=worker_runners,
            team_runner=team_runner,
            attempt=attempt,
            timeout_seconds=timeout_seconds,
            run_name=run_name,
        )
        results.append(result)
        _write_summary(root, results, run_name, configuration or {})
    return results


def select_cases(
    cases: list[dict[str, Any]], target: str
) -> list[dict[str, Any]]:
    if target == "full":
        return cases
    by_id = {case["id"]: case for case in cases}
    if target == "pilot":
        return [by_id[case_id] for case_id in PILOT_CASE_IDS]
    if target not in by_id:
        raise ValueError(f"unknown case id: {target}")
    return [by_id[target]]


def _collect_metrics(task_dir: Path) -> dict[str, int]:
    events = []
    for trace_path in task_dir.rglob("trace.jsonl"):
        events.extend(
            json.loads(line)
            for line in trace_path.read_text(encoding="utf-8").splitlines()
            if line
        )
    return {
        "model_calls": sum(
            event.get("event") in {"model_request", "llm_request", "turn"}
            for event in events
        ),
        "worker_steps": sum(
            event.get("event") in {"agent_turn", "turn"} for event in events
        ),
    }


def create_runtime(timeout_seconds: float) -> tuple[
    dict[str, AsyncRunner], TeamRunner, dict[str, Any], Callable[[], Awaitable[None]]
]:
    from collab_agent import (
        BrowserWorker,
        DesktopWorker,
        OpenAIModelClient,
        TeamLeader,
    )

    leader_client = OpenAIModelClient(
        api_key=os.environ["TEAM_LEADER_API_KEY"],
        base_url=os.environ["TEAM_LEADER_BASE_URL"],
        model=os.environ["TEAM_LEADER_MODEL"],
    )
    browser_worker = BrowserWorker(
        api_key=os.environ["BROWSER_WORKER_API_KEY"],
        base_url=os.environ["BROWSER_WORKER_BASE_URL"],
        model=os.environ["BROWSER_WORKER_MODEL"],
        session_mode="clean",
        max_steps=100,
        benchmark_mode=True,
    )
    desktop_steps = int(os.environ["DESKTOP_WORKER_MAX_STEPS"])
    desktop_worker = DesktopWorker(max_steps=desktop_steps)

    # Read the Desktop model settings now so a missing benchmark configuration
    # fails before any case is reset or any application is opened.
    desktop_model = os.environ["DESKTOP_WORKER_MODEL"]
    os.environ["DESKTOP_WORKER_API_KEY"]
    os.environ["DESKTOP_WORKER_BASE_URL"]

    async def team_runner(workspace: Any, request: str) -> str:
        async def bounded_desktop(worker_dir: Path) -> None:
            request_path = worker_dir / "request.md"
            dispatched = request_path.read_text(encoding="utf-8")
            request_path.write_text(
                f"{dispatched}\n\nBenchmark boundary:\n{request}", encoding="utf-8"
            )
            await desktop_worker(worker_dir)

        leader = TeamLeader(
            leader_client,
            worker_runners={"desktop": bounded_desktop},
            max_turns=20,
        )
        return await leader.run(workspace, request)

    async def close_runtime() -> None:
        await asyncio.gather(
            browser_worker.close(),
            desktop_worker.close(),
            leader_client.close(),
        )

    configuration = {
        "team_leader_model": os.environ["TEAM_LEADER_MODEL"],
        "browser_model": os.environ["BROWSER_WORKER_MODEL"],
        "desktop_model": desktop_model,
        "team_leader_max_turns": 20,
        "browser_max_steps": 100,
        "desktop_max_steps": desktop_steps,
        "timeout_seconds": timeout_seconds,
        "browser_session_mode": "clean",
    }
    return (
        {"browser": browser_worker, "desktop": desktop_worker},
        team_runner,
        configuration,
        close_runtime,
    )


def _write_summary(
    root: Path,
    results: list[dict[str, Any]],
    run_name: str,
    configuration: Mapping[str, Any],
) -> None:
    domains = {}
    for domain in ("browser", "desktop", "cross"):
        selected = [result for result in results if result.get("domain") == domain]
        if selected:
            domains[domain] = {
                "completed": len(selected),
                "successes": sum(
                    result["status"] == "success" for result in selected
                ),
            }
    scope_groups: dict[str, dict[str, Any]] = {}
    for result in results:
        score_scope = result["score_scope"]
        environment_scope = result["environment_scope"]
        key = f"{score_scope}|{environment_scope}"
        group = scope_groups.setdefault(
            key,
            {
                "score_scope": score_scope,
                "environment_scope": environment_scope,
                "completed": 0,
                "successes": 0,
                "case_ids": [],
            },
        )
        group["completed"] += 1
        group["successes"] += result["status"] == "success"
        group["case_ids"].append(result["case_id"])
    payload = {
        "baseline": run_name,
        "configuration": dict(configuration),
        "completed": len(results),
        "domains": domains,
        "scope_groups": scope_groups,
        "results": results,
    }
    (root / "summary.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    lines = [
        f"# {run_name}",
        "",
    ]
    for group in scope_groups.values():
        lines.extend(
            [
                f"## {group['score_scope']} / {group['environment_scope']}",
                "",
                "| Case | Execution Scope | Status | Score | Seconds |",
                "| --- | --- | --- | ---: | ---: |",
            ]
        )
        lines.extend(
            f"| {result['case_id']} | {result['execution_scope']} | "
            f"{result['status']} | {result['score']} | "
            f"{result.get('elapsed_seconds', '')} |"
            for result in results
            if result["score_scope"] == group["score_scope"]
            and result["environment_scope"] == group["environment_scope"]
        )
        lines.append("")
    (root / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run the OmniAct laptop benchmark with real workers"
    )
    parser.add_argument(
        "target", help="pilot, full, or one Local-35 case id such as LB04"
    )
    parser.add_argument("--bench-home", type=Path)
    parser.add_argument("--attempt", type=int, default=1)
    parser.add_argument("--timeout-seconds", type=float, default=1200)
    return parser


async def _run_cli(args: argparse.Namespace) -> list[dict[str, Any]]:
    from dotenv import load_dotenv

    project_root = Path(__file__).parents[1]
    src_dir = project_root / "src"
    if str(src_dir) not in sys.path:
        sys.path.insert(0, str(src_dir))
    load_dotenv(project_root / ".env", override=False)
    bench_home = args.bench_home or os.environ.get("OMNIACT_BENCH_HOME")
    if not bench_home:
        raise ValueError("baseline requires --bench-home or OMNIACT_BENCH_HOME")

    cases = select_cases(load_local_cases(), args.target)
    run_name = PILOT_NAME if args.target == "pilot" else BASELINE_NAME
    worker_runners, team_runner, configuration, close_runtime = create_runtime(
        args.timeout_seconds
    )
    try:
        return await run_baseline(
            cases,
            bench_home,
            worker_runners=worker_runners,
            team_runner=team_runner,
            timeout_seconds=args.timeout_seconds,
            run_name=run_name,
            attempt=args.attempt,
            configuration=configuration,
        )
    finally:
        await close_runtime()


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    results = asyncio.run(_run_cli(args))
    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
