import json
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

import yaml

sys.path.insert(0, str(Path(__file__).parents[1]))

from benchmarks.runner import (
    check_requirements,
    cleanup_case,
    evaluate_browser_case,
    evaluate_case,
    load_local_cases,
    main,
    reset_case,
)


def _case(case_id: str, domain: str) -> dict:
    case = {
        "id": case_id,
        "upstream": "example",
        "upstream_case_id": case_id.lower(),
        "difficulty": "medium",
        "split": "development",
        "environment": "local-example",
        "official_evaluator": "example_evaluator",
        "task": f"Complete {case_id}",
        "requirements": {
            "paths": ["upstreams/example"],
            "commands": ["example.exe"],
            "urls": ["http://127.0.0.1:7770/status"],
        },
        "reset": "Restore the official fixture.",
        "domain": domain,
    }
    if domain == "browser":
        case["start_urls"] = ["http://localhost:7770/"]
        case["site"] = "shopping"
    else:
        case["fixture_root"] = f"fixtures/{case_id}"
        case["run_root"] = f"runs/{case_id}"
        case["launches"] = []
        case["verification"] = {"kind": "text", "output": "result.txt", "contains": "done"}
        if domain == "cross":
            case["target_path"] = "."
    return case


class BenchmarkRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.cases_dir = self.root / "cases"
        self.cases_dir.mkdir()

    def tearDown(self):
        self.temp_dir.cleanup()

    def _write_manifest(self, domain: str, count: int) -> None:
        prefix = {"browser": "LB", "desktop": "LD", "cross": "LX"}[domain]
        manifest = {
            "schema_version": 1,
            "track": "local",
            "domain": domain,
            "cases": [
                _case(f"{prefix}{index:02d}", domain)
                for index in range(1, count + 1)
            ],
        }
        (self.cases_dir / f"local_{domain}.yaml").write_text(
            yaml.safe_dump(manifest, sort_keys=False), encoding="utf-8"
        )

    def _write_local_35(self) -> None:
        self._write_manifest("browser", 15)
        self._write_manifest("desktop", 15)
        self._write_manifest("cross", 5)

    def test_load_local_cases_requires_15_15_5_and_unique_ids(self):
        self._write_local_35()

        cases = load_local_cases(self.cases_dir)

        self.assertEqual(len(cases), 35)
        self.assertEqual(
            {domain: sum(case["domain"] == domain for case in cases) for domain in ("browser", "desktop", "cross")},
            {"browser": 15, "desktop": 15, "cross": 5},
        )

    def test_load_local_cases_reports_missing_required_field(self):
        self._write_local_35()
        path = self.cases_dir / "local_browser.yaml"
        manifest = yaml.safe_load(path.read_text(encoding="utf-8"))
        del manifest["cases"][0]["reset"]
        path.write_text(yaml.safe_dump(manifest, sort_keys=False), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "LB01.*reset"):
            load_local_cases(self.cases_dir)

    def test_load_local_cases_requires_a_frozen_split(self):
        self._write_local_35()
        path = self.cases_dir / "local_desktop.yaml"
        manifest = yaml.safe_load(path.read_text(encoding="utf-8"))
        del manifest["cases"][0]["split"]
        path.write_text(yaml.safe_dump(manifest, sort_keys=False), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "LD01.*split"):
            load_local_cases(self.cases_dir)

    def test_load_local_cases_rejects_duplicate_ids(self):
        self._write_local_35()
        path = self.cases_dir / "local_cross.yaml"
        manifest = yaml.safe_load(path.read_text(encoding="utf-8"))
        manifest["cases"][0]["id"] = "LB01"
        path.write_text(yaml.safe_dump(manifest, sort_keys=False), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "duplicate case id: LB01"):
            load_local_cases(self.cases_dir)

    def test_load_local_cases_requires_domain_execution_fields(self):
        self._write_local_35()
        browser_path = self.cases_dir / "local_browser.yaml"
        browser = yaml.safe_load(browser_path.read_text(encoding="utf-8"))
        del browser["cases"][0]["start_urls"]
        browser_path.write_text(yaml.safe_dump(browser, sort_keys=False), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "LB01.*start_urls"):
            load_local_cases(self.cases_dir)

    def test_load_local_cases_requires_relative_cross_target_path(self):
        self._write_local_35()
        path = self.cases_dir / "local_cross.yaml"
        manifest = yaml.safe_load(path.read_text(encoding="utf-8"))
        del manifest["cases"][0]["target_path"]
        path.write_text(yaml.safe_dump(manifest, sort_keys=False), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "LX01.*target_path"):
            load_local_cases(self.cases_dir)

        manifest["cases"][0]["target_path"] = "..\\outside"
        path.write_text(yaml.safe_dump(manifest, sort_keys=False), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "LX01.*target_path must stay below run_root"):
            load_local_cases(self.cases_dir)

    @patch("benchmarks.runner.urlopen")
    @patch("benchmarks.runner.which")
    def test_check_requirements_deduplicates_and_reports_failures(
        self, mock_which, mock_urlopen
    ):
        self._write_local_35()
        (self.root / "upstreams" / "example").mkdir(parents=True)
        mock_which.return_value = None
        mock_urlopen.return_value.__enter__.return_value.status = 200

        results = check_requirements(
            load_local_cases(self.cases_dir), bench_home=self.root
        )

        self.assertEqual(len(results), 3)
        by_kind = {result["kind"]: result for result in results}
        self.assertTrue(by_kind["path"]["ok"])
        self.assertFalse(by_kind["command"]["ok"])
        self.assertTrue(by_kind["url"]["ok"])
        self.assertEqual(len(by_kind["command"]["cases"]), 35)

    def test_cli_validate_outputs_machine_readable_summary(self):
        self._write_local_35()

        with patch("builtins.print") as mock_print:
            exit_code = main(["--cases-dir", str(self.cases_dir), "validate", "--json"])

        self.assertEqual(exit_code, 0)
        summary = json.loads(mock_print.call_args.args[0])
        self.assertEqual(summary["total"], 35)
        self.assertEqual(summary["domains"], {"browser": 15, "desktop": 15, "cross": 5})

    def test_reset_case_replaces_run_directory_from_fixture(self):
        fixture = self.root / "fixtures" / "LD01"
        run = self.root / "runs" / "LD01"
        fixture.mkdir(parents=True)
        run.mkdir(parents=True)
        (fixture / "input.txt").write_text("fresh", encoding="utf-8")
        (fixture / "expected").mkdir()
        (fixture / "expected" / "gold.txt").write_text("secret", encoding="utf-8")
        (run / "stale.txt").write_text("stale", encoding="utf-8")
        case = _case("LD01", "desktop")
        case["fixture_root"] = "fixtures/LD01"
        case["run_root"] = "runs/LD01"

        result = reset_case(case, self.root)

        self.assertEqual(result["method"], "fixture_copy")
        self.assertEqual((run / "input.txt").read_text(encoding="utf-8"), "fresh")
        self.assertFalse((run / "stale.txt").exists())
        self.assertFalse((run / "expected").exists())

    @patch("benchmarks.runner._prepare_cross_environment")
    @patch("benchmarks.runner.cleanup_case")
    def test_cross_reset_cleans_previous_processes_then_prepares_case(
        self, cleanup, prepare
    ):
        fixture = self.root / "fixtures" / "LX03"
        fixture.mkdir(parents=True)
        (fixture / "input.txt").write_text("fresh", encoding="utf-8")
        case = _case("LX03", "cross")
        case["fixture_root"] = "fixtures/LX03"
        case["run_root"] = "runs/LX03"
        case["verification"] = {"kind": "process_and_transcript"}
        prepare.return_value = {"terminal_pid": 10, "writer_pids": [20]}

        result = reset_case(case, self.root)

        cleanup.assert_called_once_with(case, self.root)
        prepare.assert_called_once_with(case, self.root / "runs" / "LX03")
        self.assertEqual(result["setup"], prepare.return_value)

    @patch("benchmarks.runner.subprocess.Popen")
    def test_reset_launches_declared_application_inside_run_root(self, popen):
        fixture = self.root / "fixtures" / "LD06"
        (fixture / "Documents").mkdir(parents=True)
        (fixture / "Documents" / "largefile.txt").write_text(
            "example", encoding="utf-8"
        )
        popen.return_value.pid = 123
        case = _case("LD06", "desktop")
        case["fixture_root"] = "fixtures/LD06"
        case["run_root"] = "runs/LD06"
        case["launches"] = [
            {
                "command": "notepad.exe",
                "args": ["{run}\\Documents\\largefile.txt"],
                "cwd": "{run}",
                "track": True,
            }
        ]

        result = reset_case(case, self.root)

        run_root = self.root / "runs" / "LD06"
        popen.assert_called_once_with(
            ["notepad.exe", str(run_root / "Documents" / "largefile.txt")],
            cwd=str(run_root),
            creationflags=0,
        )
        metadata = json.loads(
            (run_root / "case-processes.json").read_text(encoding="utf-8")
        )
        self.assertEqual(metadata, {"pids": [123]})
        self.assertEqual(result["setup"]["launched_pids"], [123])

    @patch("benchmarks.runner.time.sleep")
    @patch("benchmarks.runner.psutil.process_iter")
    @patch("benchmarks.runner.subprocess.Popen")
    def test_explorer_launch_tracks_only_directly_started_process(
        self, popen, process_iter, _sleep
    ):
        fixture = self.root / "fixtures" / "LD02"
        (fixture / "Desktop").mkdir(parents=True)
        popen.return_value.pid = 123
        process_iter.side_effect = [
            [],
            [SimpleNamespace(info={"pid": 123, "name": "explorer.exe"})],
            [SimpleNamespace(info={"pid": 456, "name": "explorer.exe"})],
        ]
        case = _case("LD02", "desktop")
        case["fixture_root"] = "fixtures/LD02"
        case["run_root"] = "runs/LD02"
        case["launches"] = [
            {
                "command": "explorer.exe",
                "args": ["/separate,{run}\\Desktop"],
                "track": True,
            }
        ]

        result = reset_case(case, self.root)

        metadata = json.loads(
            (self.root / "runs" / "LD02" / "case-processes.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(metadata, {"pids": [123]})
        self.assertEqual(result["setup"]["launched_pids"], [123])

    @patch("benchmarks.runner.psutil.Process")
    @patch("benchmarks.runner.subprocess.Popen")
    def test_lx03_writer_opens_the_isolated_fixture_input(self, popen, process):
        fixture = self.root / "fixtures" / "LX03"
        fixture.mkdir(parents=True)
        (fixture / "Writer_Recovery_Input.md").write_text("input", encoding="utf-8")
        popen.side_effect = [SimpleNamespace(pid=10), SimpleNamespace(pid=20)]
        process.return_value.is_running.return_value = True
        process.return_value.children.return_value = [SimpleNamespace(pid=21)]
        case = _case("LX03", "cross")
        case["fixture_root"] = "fixtures/LX03"
        case["run_root"] = "runs/LX03"
        case["launches"] = []
        case["verification"] = {
            "kind": "process_and_transcript",
            "terminal_transcript": "terminal-transcript.txt",
            "process_metadata": "case-writer-process.json",
        }

        reset_case(case, self.root)

        terminal_call = popen.call_args_list[0]
        self.assertEqual(terminal_call.kwargs["cwd"], str(self.root / "runs" / "LX03"))
        writer_command = popen.call_args_list[1].args[0]
        self.assertIn(
            str(self.root / "runs" / "LX03" / "Writer_Recovery_Input.md"),
            writer_command,
        )

    @patch("benchmarks.runner.subprocess.Popen")
    def test_lx02_terminal_isolates_the_code_profile(self, popen):
        fixture = self.root / "fixtures" / "LX02"
        (fixture / "project").mkdir(parents=True)
        popen.return_value = SimpleNamespace(pid=10)
        case = _case("LX02", "cross")
        case["fixture_root"] = "fixtures/LX02"
        case["run_root"] = "runs/LX02"
        case["launches"] = []
        case["verification"] = {
            "kind": "terminal_command_and_workspace",
            "workspace": "project",
            "terminal_transcript": "terminal-transcript.txt",
            "vscode_profile": ".vscode-profile",
        }

        reset_case(case, self.root)

        run_root = self.root / "runs" / "LX02"
        command = popen.call_args.args[0][-1]
        self.assertIn("function global:code", command)
        self.assertIn(str(run_root / ".vscode-profile"), command)
        self.assertTrue((run_root / ".vscode-profile").is_dir())
        self.assertTrue((run_root / ".vscode-extensions").is_dir())

    @patch("benchmarks.runner.psutil.pid_exists", side_effect=lambda pid: pid == 10)
    @patch("benchmarks.runner.psutil.wait_procs", return_value=([], []))
    @patch("benchmarks.runner.psutil.Process")
    def test_cleanup_only_terminates_pids_recorded_for_case(
        self, process, _wait_procs, _pid_exists
    ):
        run_root = self.root / "runs" / "LX03"
        run_root.mkdir(parents=True)
        (run_root / "case-processes.json").write_text(
            json.dumps({"pids": [10, 20]}), encoding="utf-8"
        )
        case = _case("LX03", "cross")
        case["run_root"] = "runs/LX03"
        process.return_value.pid = 10

        result = cleanup_case(case, self.root)

        process.assert_called_once_with(10)
        process.return_value.terminate.assert_called_once()
        self.assertEqual(result, {"terminated_pids": [10]})

    @patch("benchmarks.runner.psutil.Process")
    def test_browser_cleanup_does_not_read_processes_from_benchmark_root(self, process):
        (self.root / "case-processes.json").write_text(
            json.dumps({"pids": [10]}), encoding="utf-8"
        )
        case = _case("LB01", "browser")

        result = cleanup_case(case, self.root)

        self.assertEqual(result, {"terminated_pids": []})
        process.assert_not_called()

    @patch("benchmarks.runner.urlopen")
    def test_reset_case_posts_to_upstream_reset_endpoint(self, mock_urlopen):
        mock_urlopen.return_value.__enter__.return_value.status = 200
        case = _case("LB01", "browser")
        case["reset_url"] = "http://127.0.0.1:7771/init"
        case["start_urls"] = ["http://localhost:7770/start"]

        result = reset_case(case, self.root)

        request = mock_urlopen.call_args.args[0]
        self.assertEqual(request.full_url, case["reset_url"])
        self.assertEqual(request.method, "POST")
        worker_dir = self.root / "runs" / "local-browser" / "LB01" / "workers" / "browser"
        self.assertEqual(
            result,
            {"method": "http_post", "status": 200, "worker_dir": str(worker_dir)},
        )
        self.assertEqual(
            (worker_dir / "request.md").read_text(encoding="utf-8"), "Complete LB01"
        )
        self.assertEqual(
            json.loads((worker_dir / "benchmark.json").read_text(encoding="utf-8")),
            {
                "case_id": "LB01",
                "start_urls": ["http://localhost:7770/start"],
                "extra_headers": {
                    "X-M2-Customer-Auto-Login": "emma.lopez@gmail.com:Password.123"
                },
            },
        )

    @patch("benchmarks.runner.evaluate_browser_case")
    def test_evaluate_case_routes_browser_to_official_evaluator(self, evaluator):
        evaluator.return_value = {"case_id": "LB01", "success": False, "score": 0.0}
        case = _case("LB01", "browser")

        result = evaluate_case(case, self.root)

        evaluator.assert_called_once_with(case, self.root)
        self.assertFalse(result["success"])

    @patch("benchmarks.runner.verify_case")
    def test_evaluate_case_routes_local_artifacts_to_deterministic_verifier(self, verifier):
        verifier.return_value = {"case_id": "LD01", "success": True, "score": 1.0}
        case = _case("LD01", "desktop")

        result = evaluate_case(case, self.root)

        verifier.assert_called_once_with(case, self.root)
        self.assertTrue(result["success"])

    @patch("benchmarks.runner.subprocess.run")
    def test_browser_evaluator_bridge_reads_standard_benchmark_artifacts(self, process):
        artifacts = (
            self.root
            / "runs"
            / "local-browser"
            / "LB01"
            / "workers"
            / "browser"
            / "artifacts"
        )
        artifacts.mkdir(parents=True)
        (artifacts / "agent-response.txt").write_text("answer: 42", encoding="utf-8")
        (artifacts / "network.har").write_text('{"log":{"entries":[]}}', encoding="utf-8")
        payload = {"case_id": "LB01", "success": False, "score": 0.0, "status": "failure"}
        process.return_value = subprocess.CompletedProcess(
            args=[], returncode=0, stdout=f"upstream log\nOMNIACT_RESULT={json.dumps(payload)}\n", stderr=""
        )
        case = _case("LB01", "browser")
        case["upstream_case_id"] = 22

        result = evaluate_browser_case(case, self.root)

        command = process.call_args.args[0]
        self.assertEqual(command[:4], ["conda", "run", "-n", "omniact-up-webarena"])
        self.assertIn(str(artifacts / "agent-response.txt"), command)
        self.assertIn(str(artifacts / "network.har"), command)
        self.assertIn("shopping=http://localhost:7770", command)
        self.assertEqual(result, payload)

    @patch("benchmarks.runner.evaluate_case")
    def test_cli_evaluate_returns_nonzero_for_failed_case(self, evaluator):
        self._write_local_35()
        evaluator.return_value = {"case_id": "LD01", "success": False, "score": 0.0}

        with patch("builtins.print"):
            exit_code = main(
                [
                    "--cases-dir",
                    str(self.cases_dir),
                    "evaluate",
                    "LD01",
                    "--bench-home",
                    str(self.root),
                    "--json",
                ]
            )

        self.assertEqual(exit_code, 1)


if __name__ == "__main__":
    unittest.main()
