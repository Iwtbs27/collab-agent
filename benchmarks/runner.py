import argparse
import json
import os
import subprocess
import time
from pathlib import Path
from shutil import copytree, ignore_patterns, rmtree, which
from typing import Any, Sequence
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

import yaml
import psutil

from .verifiers import verify_case


DOMAIN_COUNTS = {"browser": 15, "desktop": 15, "cross": 5}
SHOPPING_BENCHMARK_HEADERS = {
    "X-M2-Customer-Auto-Login": "emma.lopez@gmail.com:Password.123"
}
REQUIRED_FIELDS = {
    "id",
    "upstream",
    "upstream_case_id",
    "difficulty",
    "split",
    "environment",
    "official_evaluator",
    "task",
    "requirements",
    "reset",
}


def load_local_cases(cases_dir: Path | str | None = None) -> list[dict[str, Any]]:
    directory = Path(cases_dir) if cases_dir else Path(__file__).parent / "cases"
    cases: list[dict[str, Any]] = []
    seen_ids: set[str] = set()

    for domain, expected_count in DOMAIN_COUNTS.items():
        path = directory / f"local_{domain}.yaml"
        manifest = yaml.safe_load(path.read_text(encoding="utf-8"))
        if manifest.get("schema_version") != 1:
            raise ValueError(f"{path.name}: unsupported schema version")
        if manifest.get("track") != "local" or manifest.get("domain") != domain:
            raise ValueError(f"{path.name}: track or domain does not match its file")

        domain_cases = manifest.get("cases")
        if not isinstance(domain_cases, list) or len(domain_cases) != expected_count:
            raise ValueError(
                f"{path.name}: expected {expected_count} cases, got "
                f"{len(domain_cases) if isinstance(domain_cases, list) else 0}"
            )

        for item in domain_cases:
            if domain == "browser" and "site" not in item and "site" in manifest:
                item = {**item, "site": manifest["site"]}
            case_id = item.get("id", "<missing-id>")
            missing = sorted(REQUIRED_FIELDS - item.keys())
            if domain == "browser":
                domain_fields = {"start_urls", "site"}
            else:
                domain_fields = {"fixture_root", "run_root", "verification", "launches"}
                if domain == "cross":
                    domain_fields.add("target_path")
            missing.extend(sorted(domain_fields - item.keys()))
            if missing:
                raise ValueError(f"{case_id}: missing fields: {', '.join(missing)}")
            if case_id in seen_ids:
                raise ValueError(f"duplicate case id: {case_id}")
            if not isinstance(item["requirements"], dict):
                raise ValueError(f"{case_id}: requirements must be a mapping")
            if domain == "browser" and not isinstance(item["start_urls"], list):
                raise ValueError(f"{case_id}: start_urls must be a list")
            if domain != "browser" and not isinstance(item["verification"], dict):
                raise ValueError(f"{case_id}: verification must be a mapping")
            if domain != "browser" and not isinstance(item["launches"], list):
                raise ValueError(f"{case_id}: launches must be a list")
            if domain == "cross":
                target = Path(item["target_path"])
                if target.is_absolute() or ".." in target.parts:
                    raise ValueError(f"{case_id}: target_path must stay below run_root")

            seen_ids.add(case_id)
            cases.append({**item, "domain": domain})

    return cases


def check_requirements(
    cases: list[dict[str, Any]], bench_home: Path | str
) -> list[dict[str, Any]]:
    root = Path(bench_home)
    grouped: dict[tuple[str, str], set[str]] = {}
    for case in cases:
        requirements = case["requirements"]
        for kind in ("paths", "commands", "urls"):
            for target in requirements.get(kind, []):
                grouped.setdefault((kind[:-1], str(target)), set()).add(case["id"])

    results = []
    for (kind, target), case_ids in grouped.items():
        ok, detail = _check_requirement(kind, target, root)
        results.append(
            {
                "kind": kind,
                "target": target,
                "ok": ok,
                "detail": detail,
                "cases": sorted(case_ids),
            }
        )
    return results


def reset_case(case: dict[str, Any], bench_home: Path | str) -> dict[str, Any]:
    root = Path(bench_home)
    if "fixture_root" in case and "run_root" in case:
        if case.get("domain") in {"desktop", "cross"}:
            cleanup_case(case, root)
        fixture = root / case["fixture_root"]
        run = root / case["run_root"]
        if not fixture.is_dir():
            raise FileNotFoundError(fixture)
        if run.exists():
            rmtree(run)
        run.parent.mkdir(parents=True, exist_ok=True)
        copytree(fixture, run, ignore=ignore_patterns("expected"))
        result = {"method": "fixture_copy", "run_root": str(run)}
        setup: dict[str, Any] = {}
        if case.get("domain") == "cross":
            setup.update(_prepare_cross_environment(case, run))
        setup.update(_launch_case_applications(case, run))
        if setup:
            result["setup"] = setup
        return result

    if "reset_url" in case:
        request = Request(case["reset_url"], method="POST")
        with urlopen(request, timeout=30) as response:
            status = response.status
        worker_dir = _prepare_browser_run(case, root)
        return {
            "method": "http_post",
            "status": status,
            "worker_dir": str(worker_dir),
        }

    raise ValueError(f"{case['id']}: no executable reset is configured")


def cleanup_case(case: dict[str, Any], bench_home: Path | str) -> dict[str, Any]:
    if "run_root" not in case:
        return {"terminated_pids": []}
    run_root = Path(bench_home) / case["run_root"]
    metadata_path = run_root / "case-processes.json"
    if not metadata_path.is_file():
        return {"terminated_pids": []}
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    processes = []
    for pid in metadata["pids"]:
        if psutil.pid_exists(pid):
            process = psutil.Process(pid)
            process.terminate()
            processes.append(process)
    _, running = psutil.wait_procs(processes, timeout=5)
    for process in running:
        process.kill()
    return {"terminated_pids": [process.pid for process in processes]}


def _prepare_cross_environment(case: dict[str, Any], run: Path) -> dict[str, Any]:
    kind = case["verification"]["kind"]
    if kind not in {"terminal_command_and_workspace", "process_and_transcript"}:
        return {}

    working_directory = run / case["verification"].get("workspace", "")
    transcript = run / case["verification"]["terminal_transcript"]
    command_parts = [
        f"Set-Location -LiteralPath '{working_directory}'",
        f"Set-PSReadLineOption -HistorySavePath '{transcript}'",
        "Set-PSReadLineOption -HistorySaveStyle SaveIncrementally",
    ]
    if kind == "terminal_command_and_workspace":
        profile = run / case["verification"]["vscode_profile"]
        extensions = run / ".vscode-extensions"
        profile.mkdir(parents=True, exist_ok=True)
        extensions.mkdir(parents=True, exist_ok=True)
        code_executable = case["verification"].get(
            "code_executable", r"E:\Microsoft VS Code\Code.exe"
        )
        command_parts.append(
            "function global:code { "
            f"& '{code_executable}' --new-window "
            f"--user-data-dir '{profile}' --extensions-dir '{extensions}' @args "
            "}"
        )
    command = "; ".join(command_parts)
    terminal = subprocess.Popen(
        ["powershell.exe", "-NoLogo", "-NoExit", "-Command", command],
        cwd=str(working_directory),
        creationflags=subprocess.CREATE_NEW_CONSOLE,
    )
    pids = [terminal.pid]
    result: dict[str, Any] = {"terminal_pid": terminal.pid}

    if kind == "process_and_transcript":
        profile = run / ".libreoffice-profile"
        profile.mkdir()
        writer = subprocess.Popen(
            [
                r"C:\Program Files\LibreOffice\program\soffice.exe",
                f"-env:UserInstallation={profile.as_uri()}",
                "--nologo",
                "--norestore",
                "--writer",
                str(run / "Writer_Recovery_Input.md"),
            ]
        )
        writer_pids = {writer.pid}
        writer_process = psutil.Process(writer.pid)
        for _ in range(20):
            if writer_process.is_running():
                writer_pids.update(child.pid for child in writer_process.children(recursive=True))
            if len(writer_pids) > 1:
                break
            time.sleep(0.1)
        pids.extend(sorted(writer_pids))
        (run / case["verification"]["process_metadata"]).write_text(
            json.dumps({"pids": sorted(writer_pids)}, indent=2), encoding="utf-8"
        )
        result["writer_pids"] = sorted(writer_pids)

    (run / "case-processes.json").write_text(
        json.dumps(
            {"pids": sorted(set(pids)), "terminal_pid": terminal.pid}, indent=2
        ),
        encoding="utf-8",
    )
    return result


def _launch_case_applications(
    case: dict[str, Any], run: Path
) -> dict[str, Any]:
    launches = case.get("launches", [])
    if not launches:
        return {}

    launched_pids = []
    for launch in launches:
        values = {
            "run": str(run),
            "run_uri": run.as_uri(),
        }
        command = str(launch["command"]).format(**values)
        args = [str(value).format(**values) for value in launch["args"]]
        cwd = str(launch["cwd"]).format(**values) if launch.get("cwd") else None
        process = subprocess.Popen(
            [command, *args],
            cwd=cwd,
            creationflags=(
                subprocess.CREATE_NEW_CONSOLE if launch.get("new_console") else 0
            ),
        )
        if launch["track"]:
            launched_pids.append(process.pid)

    if launched_pids:
        metadata_path = run / "case-processes.json"
        recorded = []
        if metadata_path.is_file():
            recorded = json.loads(metadata_path.read_text(encoding="utf-8"))["pids"]
        metadata_path.write_text(
            json.dumps({"pids": sorted(set(recorded + launched_pids))}, indent=2),
            encoding="utf-8",
        )
    return {"launched_pids": launched_pids}


def _prepare_browser_run(case: dict[str, Any], bench_home: Path) -> Path:
    task_dir = bench_home / "runs" / "local-browser" / case["id"]
    if task_dir.exists():
        rmtree(task_dir)
    worker_dir = task_dir / "workers" / "browser"
    (task_dir / "inputs").mkdir(parents=True)
    (worker_dir / "artifacts").mkdir(parents=True)
    (worker_dir / "request.md").write_text(case["task"], encoding="utf-8")
    benchmark = {
        "case_id": case["id"],
        "start_urls": case.get("start_urls", []),
        "extra_headers": SHOPPING_BENCHMARK_HEADERS,
    }
    (worker_dir / "benchmark.json").write_text(
        json.dumps(benchmark, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return worker_dir


def evaluate_browser_case(
    case: dict[str, Any], bench_home: Path | str
) -> dict[str, Any]:
    artifacts = (
        Path(bench_home)
        / "runs"
        / "local-browser"
        / case["id"]
        / "workers"
        / "browser"
        / "artifacts"
    )
    response = artifacts / "agent-response.txt"
    network_trace = artifacts / "network.har"
    bridge = Path(__file__).with_name("webarena_bridge.py")
    start_url = urlsplit(case["start_urls"][0])
    site_url = f"{case['site']}={start_url.scheme}://{start_url.netloc}"
    completed = subprocess.run(
        [
            "conda",
            "run",
            "-n",
            "omniact-up-webarena",
            "python",
            str(bridge),
            case["id"],
            str(case["upstream_case_id"]),
            str(response),
            str(network_trace),
            "--site-url",
            site_url,
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.strip() or completed.stdout.strip())
    marker = "OMNIACT_RESULT="
    line = next(
        (item for item in reversed(completed.stdout.splitlines()) if item.startswith(marker)),
        None,
    )
    if line is None:
        raise RuntimeError("WebArena evaluator did not return a result")
    return json.loads(line[len(marker) :])


def evaluate_case(case: dict[str, Any], bench_home: Path | str) -> dict[str, Any]:
    if case["domain"] == "browser":
        return evaluate_browser_case(case, Path(bench_home))
    return verify_case(case, Path(bench_home))


def _check_requirement(kind: str, target: str, bench_home: Path) -> tuple[bool, str]:
    if kind == "path":
        path = Path(target)
        resolved = path if path.is_absolute() else bench_home / path
        return resolved.exists(), str(resolved)
    if kind == "command":
        command_path = which(target)
        return command_path is not None, command_path or "not found on PATH"
    if kind == "url":
        try:
            with urlopen(target, timeout=5) as response:
                status = response.status
            return status < 400, f"HTTP {status}"
        except OSError as error:
            return False, str(error)
    raise ValueError(f"unsupported requirement kind: {kind}")


def _summary(cases: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "track": "local",
        "total": len(cases),
        "domains": {
            domain: sum(case["domain"] == domain for case in cases)
            for domain in DOMAIN_COUNTS
        },
    }


def _print(value: Any, as_json: bool) -> None:
    if as_json:
        print(json.dumps(value, ensure_ascii=False, indent=2))
    elif isinstance(value, list):
        for item in value:
            print(item)
    else:
        for key, item in value.items():
            print(f"{key}: {item}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Inspect the OmniAct Local-35 track")
    parser.add_argument("--cases-dir", type=Path)
    commands = parser.add_subparsers(dest="command", required=True)

    list_parser = commands.add_parser("list", help="List selected cases")
    list_parser.add_argument("--domain", choices=tuple(DOMAIN_COUNTS))
    list_parser.add_argument("--json", action="store_true")

    validate_parser = commands.add_parser("validate", help="Validate Local-35 manifests")
    validate_parser.add_argument("--json", action="store_true")

    doctor_parser = commands.add_parser("doctor", help="Check declared local requirements")
    doctor_parser.add_argument("--bench-home", type=Path)
    doctor_parser.add_argument("--json", action="store_true")

    reset_parser = commands.add_parser("reset", help="Reset one case without running a model")
    reset_parser.add_argument("case_id")
    reset_parser.add_argument("--bench-home", type=Path)
    reset_parser.add_argument("--json", action="store_true")

    evaluate_parser = commands.add_parser(
        "evaluate", help="Evaluate one case without trusting worker self-report"
    )
    evaluate_parser.add_argument("case_id")
    evaluate_parser.add_argument("--bench-home", type=Path)
    evaluate_parser.add_argument("--json", action="store_true")

    cleanup_parser = commands.add_parser(
        "cleanup", help="Stop only processes launched for one local case"
    )
    cleanup_parser.add_argument("case_id")
    cleanup_parser.add_argument("--bench-home", type=Path)
    cleanup_parser.add_argument("--json", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    cases = load_local_cases(args.cases_dir)

    if args.command == "list":
        selected = [
            case
            for case in cases
            if args.domain is None or case["domain"] == args.domain
        ]
        _print(selected if args.json else [f"{case['id']}  {case['task']}" for case in selected], args.json)
        return 0

    if args.command == "validate":
        _print(_summary(cases), args.json)
        return 0

    bench_home = args.bench_home or os.environ.get("OMNIACT_BENCH_HOME")
    if not bench_home:
        parser.error(f"{args.command} requires --bench-home or OMNIACT_BENCH_HOME")

    if args.command in {"reset", "evaluate", "cleanup"}:
        case = next((item for item in cases if item["id"] == args.case_id), None)
        if case is None:
            parser.error(f"unknown case id: {args.case_id}")
    if args.command == "reset":
        _print(reset_case(case, bench_home), args.json)
        return 0

    if args.command == "evaluate":
        result = evaluate_case(case, bench_home)
        _print(result, args.json)
        return 0 if result["success"] else 1
    if args.command == "cleanup":
        _print(cleanup_case(case, bench_home), args.json)
        return 0

    results = check_requirements(cases, bench_home)
    _print(results, args.json)
    return 0 if all(result["ok"] for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
