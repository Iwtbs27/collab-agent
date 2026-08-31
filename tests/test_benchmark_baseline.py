import asyncio
import json
import os
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import AsyncMock, patch

sys.path.insert(0, str(Path(__file__).parents[1]))
sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from benchmarks.baseline import (
    BASELINE_NAME,
    PILOT_CASE_IDS,
    PILOT_NAME,
    build_case_request,
    create_runtime,
    run_baseline,
    run_case,
    select_cases,
)


def _case(case_id: str, domain: str) -> dict:
    case = {
        "id": case_id,
        "domain": domain,
        "upstream": "example",
        "official_evaluator": "example_evaluator",
        "task": f"Complete {case_id}.",
        "local_task": (
            f"Only use OMNIACT_BENCH_HOME/runs/local-{domain}/{case_id}."
        ),
        "fixture_root": f"fixtures/local-{domain}/{case_id}",
        "run_root": f"runs/local-{domain}/{case_id}",
        "verification": {
            "kind": "text",
            "output": "result.txt",
            "contains": "done",
        },
    }
    if domain == "browser":
        case.pop("fixture_root")
        case.pop("run_root")
        case.pop("verification")
        case.pop("local_task")
    return case


class BenchmarkBaselineTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp_dir = TemporaryDirectory()
        self.home = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def _write_fixture(self, case: dict) -> None:
        fixture = self.home / case["fixture_root"]
        fixture.mkdir(parents=True)
        (fixture / "input.txt").write_text("input", encoding="utf-8")

    def test_build_case_request_combines_original_task_and_isolated_path(self):
        case = _case("LD01", "desktop")

        request = build_case_request(case, self.home)

        self.assertIn("Complete LD01.", request)
        self.assertIn(str(self.home / case["run_root"]), request)
        self.assertNotIn("OMNIACT_BENCH_HOME", request)
        self.assertIn("do not use the real user", request)

    def test_cross_case_request_requires_a_relative_manifest_target(self):
        case = _case("LX02", "cross")

        with self.assertRaisesRegex(ValueError, "LX02: cross cases require target_path"):
            build_case_request(case, self.home)

        case["target_path"] = "..\\outside"
        with self.assertRaisesRegex(ValueError, "LX02: target_path must stay below run_root"):
            build_case_request(case, self.home)

    @patch("benchmarks.baseline.evaluate_case")
    @patch("benchmarks.baseline.reset_case")
    async def test_browser_case_uses_direct_worker_and_archives_artifacts(
        self, reset, evaluate
    ):
        case = _case("LB04", "browser")
        source_worker = (
            self.home
            / "runs"
            / "local-browser"
            / "LB04"
            / "workers"
            / "browser"
        )
        (source_worker / "artifacts").mkdir(parents=True)
        (source_worker / "request.md").write_text(case["task"], encoding="utf-8")
        reset.return_value = {"worker_dir": str(source_worker)}
        evaluate.return_value = {
            "case_id": "LB04",
            "success": False,
            "score": 0.0,
            "status": "failure",
        }

        async def browser_runner(worker_dir):
            self.assertEqual(worker_dir, source_worker)
            (worker_dir / "artifacts" / "network.har").write_text(
                '{"log":{"entries":[]}}', encoding="utf-8"
            )
            (worker_dir / "result.md").write_text("failed", encoding="utf-8")

        result = await run_case(
            case,
            self.home,
            worker_runners={"browser": browser_runner},
            attempt=1,
            timeout_seconds=1,
        )

        archived = (
            self.home
            / "runs"
            / BASELINE_NAME
            / "LB04"
            / "attempt-1"
            / "workers"
            / "browser"
            / "artifacts"
            / "network.har"
        )
        self.assertTrue(archived.is_file())
        self.assertEqual(result["status"], "failure")
        self.assertEqual(result["score"], 0.0)

    @patch("benchmarks.baseline._run_evaluation_postconfig")
    @patch("benchmarks.baseline.evaluate_case")
    @patch("benchmarks.baseline.reset_case")
    async def test_postconfig_runs_before_evaluation(
        self, reset, evaluate, postconfig
    ):
        case = _case("LD08", "desktop")
        case["evaluation_postconfig"] = [{"action": "save"}]
        self._write_fixture(case)
        reset.return_value = {"run_root": str(self.home / case["run_root"])}
        evaluate.return_value = {"case_id": "LD08", "success": True, "score": 1.0}
        events = []
        postconfig.side_effect = lambda *_args: events.append("postconfig")
        evaluate.side_effect = lambda *_args: (events.append("evaluate") or {"case_id": "LD08", "success": True, "score": 1.0})

        async def desktop_runner(_worker_dir):
            return None

        result = await run_case(
            case,
            self.home,
            worker_runners={"desktop": desktop_runner},
            timeout_seconds=1,
        )

        self.assertEqual(result["status"], "success")
        postconfig.assert_called_once_with(case, self.home)
        self.assertEqual(events, ["postconfig", "evaluate"])

    async def test_desktop_case_uses_direct_worker_and_deterministic_verifier(self):
        case = _case("LD01", "desktop")
        self._write_fixture(case)

        async def desktop_runner(worker_dir):
            request = (worker_dir / "request.md").read_text(encoding="utf-8")
            self.assertIn(str(self.home / case["run_root"]), request)
            output = self.home / case["run_root"] / "result.txt"
            output.write_text("done", encoding="utf-8")
            (worker_dir / "result.md").write_text("completed", encoding="utf-8")
            (worker_dir / "trace.jsonl").write_text(
                json.dumps({"event": "llm_request"})
                + "\n"
                + json.dumps({"event": "agent_turn"})
                + "\n",
                encoding="utf-8",
            )

        result = await run_case(
            case,
            self.home,
            worker_runners={"desktop": desktop_runner},
            attempt=1,
            timeout_seconds=1,
        )

        self.assertEqual(result["status"], "success")
        self.assertEqual(result["score"], 1.0)
        self.assertEqual(result["model_calls"], 1)
        self.assertEqual(result["worker_steps"], 1)

    async def test_cross_case_routes_through_team_leader(self):
        case = _case("LX02", "cross")
        case["target_path"] = "project"
        case["local_task"] = "Start VS Code from the declared case target."
        self._write_fixture(case)

        async def team_runner(workspace, request):
            environment_root = self.home / case["run_root"]
            self.assertIn(f"Execution record directory: {workspace.task_dir}", request)
            self.assertIn(f"Case environment root: {environment_root}", request)
            self.assertIn(
                f"Operation target path: {environment_root / 'project'}", request
            )
            self.assertIn("not the operation target", request)

        result = await run_case(
            case,
            self.home,
            worker_runners={},
            team_runner=team_runner,
            attempt=1,
            timeout_seconds=1,
        )

        self.assertEqual(result["status"], "failure")
        self.assertEqual(result["execution_scope"], "cross_application")
        self.assertEqual(result["score_scope"], "omniact_adapted")
        self.assertEqual(result["environment_scope"], "host_local_unisolated")

    @patch("benchmarks.baseline.cleanup_case")
    @patch("benchmarks.baseline.evaluate_case")
    @patch("benchmarks.baseline.reset_case")
    async def test_timeout_is_recorded_and_case_cleanup_still_runs(
        self, reset, evaluate, cleanup
    ):
        case = _case("LD01", "desktop")
        reset.return_value = {"run_root": str(self.home / case["run_root"])}
        evaluate.return_value = {
            "case_id": "LD01",
            "success": False,
            "score": 0.0,
            "detail": "missing output",
        }

        async def slow_runner(_worker_dir):
            await asyncio.sleep(1)

        result = await run_case(
            case,
            self.home,
            worker_runners={"desktop": slow_runner},
            timeout_seconds=0.01,
        )

        self.assertEqual(result["status"], "timeout")
        self.assertEqual(result["score"], 0.0)
        self.assertEqual(result["evaluation"]["detail"], "missing output")
        evaluate.assert_called_once_with(case, self.home)
        cleanup.assert_called_once_with(case, self.home)

    @patch("benchmarks.baseline.run_case", new_callable=AsyncMock)
    async def test_run_baseline_writes_incremental_summary(self, run_one):
        cases = [_case("LB04", "browser"), _case("LD02", "desktop")]
        run_one.side_effect = [
            {
                "case_id": "LB04",
                "domain": "browser",
                "status": "failure",
                "score": 0.0,
                "upstream": "webarena_verified",
                "evaluator_name": "AgentResponseEvaluator",
                "score_scope": "upstream_official_evaluator_subset",
                "environment_scope": "container_local",
                "execution_scope": "browser",
            },
            {
                "case_id": "LD02",
                "domain": "desktop",
                "status": "success",
                "score": 1.0,
                "upstream": "windows_agent_arena",
                "evaluator_name": "same_file",
                "score_scope": "omniact_adapted",
                "environment_scope": "host_local_unisolated",
                "execution_scope": "desktop",
            },
        ]

        results = await run_baseline(
            cases,
            self.home,
            worker_runners={},
            team_runner=None,
            timeout_seconds=1,
            configuration={"browser_model": "vision-model"},
        )

        summary_path = self.home / "runs" / BASELINE_NAME / "summary.json"
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
        self.assertEqual(PILOT_CASE_IDS, ("LB04", "LD02", "LX02"))
        self.assertEqual(PILOT_NAME, "pilot-laptop-v1")
        self.assertEqual(len(results), 2)
        self.assertEqual(summary["completed"], 2)
        self.assertEqual(summary["domains"]["desktop"]["successes"], 1)
        self.assertNotIn("successes", summary)
        self.assertEqual(
            summary["scope_groups"][
                "upstream_official_evaluator_subset|container_local"
            ]["completed"],
            1,
        )
        self.assertEqual(
            summary["scope_groups"]["omniact_adapted|host_local_unisolated"][
                "successes"
            ],
            1,
        )
        self.assertEqual(summary["configuration"]["browser_model"], "vision-model")
        self.assertTrue((summary_path.parent / "summary.md").is_file())

    def test_select_cases_supports_pilot_full_and_one_case(self):
        cases = [
            _case("LB04", "browser"),
            _case("LD02", "desktop"),
            _case("LX02", "cross"),
            _case("LD03", "desktop"),
        ]

        self.assertEqual(
            [case["id"] for case in select_cases(cases, "pilot")],
            list(PILOT_CASE_IDS),
        )
        self.assertEqual(select_cases(cases, "full"), cases)
        self.assertEqual(
            [case["id"] for case in select_cases(cases, "LD03")], ["LD03"]
        )
        with self.assertRaisesRegex(ValueError, "unknown case id"):
            select_cases(cases, "missing")

    @patch.dict(
        os.environ,
        {
            "TEAM_LEADER_API_KEY": "leader-key",
            "TEAM_LEADER_BASE_URL": "https://leader.example/v1",
            "TEAM_LEADER_MODEL": "leader-model",
            "BROWSER_WORKER_API_KEY": "browser-key",
            "BROWSER_WORKER_BASE_URL": "https://browser.example/v1",
            "BROWSER_WORKER_MODEL": "vision-model",
            "DESKTOP_WORKER_API_KEY": "desktop-key",
            "DESKTOP_WORKER_BASE_URL": "https://desktop.example/v1",
            "DESKTOP_WORKER_MODEL": "desktop-model",
            "DESKTOP_WORKER_MAX_STEPS": "50",
        },
        clear=False,
    )
    @patch("collab_agent.OpenAIModelClient")
    @patch("collab_agent.DesktopWorker")
    @patch("collab_agent.BrowserWorker")
    def test_create_runtime_uses_fixed_baseline_worker_configuration(
        self, browser_class, desktop_class, client_class
    ):
        worker_runners, _team_runner, configuration, _close = create_runtime(
            timeout_seconds=1200
        )

        browser_class.assert_called_once_with(
            api_key="browser-key",
            base_url="https://browser.example/v1",
            model="vision-model",
            session_mode="clean",
            max_steps=100,
            benchmark_mode=True,
        )
        desktop_class.assert_called_once_with(max_steps=50)
        client_class.assert_called_once_with(
            api_key="leader-key",
            base_url="https://leader.example/v1",
            model="leader-model",
        )
        self.assertEqual(set(worker_runners), {"browser", "desktop"})
        self.assertEqual(configuration["browser_model"], "vision-model")
        self.assertEqual(configuration["timeout_seconds"], 1200)


if __name__ == "__main__":
    unittest.main()
