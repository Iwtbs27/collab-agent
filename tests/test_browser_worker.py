import asyncio
import json
import shutil
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from omniact_browser.agent.views import ActionResult
from omniact_browser.llm.messages import UserMessage
from collab_agent.browser_worker import BrowserWorker


class FakeModelOutput:
    def model_dump(self, **_kwargs):
        return {
            "thinking": "inspect the current page",
            "next_goal": "finish the task",
            "action": [{"done": {"text": "Task finished", "success": True}}],
        }


class FakeHistory:
    def __init__(self, success=True, text="Task finished"):
        self._success = success
        self._text = text
        self.history = [
            SimpleNamespace(
                state=SimpleNamespace(
                    url="https://example.com/",
                    screenshot_path=None,
                ),
                model_output=FakeModelOutput(),
                result=[
                    ActionResult(
                        is_done=True,
                        success=success,
                        extracted_content=text,
                    )
                ],
            )
        ]

    def is_done(self):
        return True

    def is_successful(self):
        return self._success

    def final_result(self):
        return self._text


class StoppedHistory(FakeHistory):
    def __init__(self):
        super().__init__(success=False, text="Browser Worker was cancelled by the user")

    def is_done(self):
        return False


class FakeSession:
    instances = []

    def __init__(self, browser_profile):
        self.browser_profile = browser_profile
        self.started = False
        self.hidden = False
        self.shown = False
        self.closed = False
        self.urls = []
        self.extra_headers = None
        self.__class__.instances.append(self)

    async def start(self):
        self.started = True

    async def hide_window(self):
        self.hidden = True
        self.shown = False

    async def show_window(self):
        self.shown = True
        self.hidden = False

    async def kill(self):
        self.closed = True

    async def navigate_to(self, url, new_tab=False):
        self.urls.append((url, new_tab))

    async def set_extra_headers(self, headers):
        self.extra_headers = headers


class CompletingAgent:
    def __init__(self, **kwargs):
        self.browser_session = kwargs["browser_session"]
        self.state = SimpleNamespace(paused=False, stopped=False)
        self.takeover_request = None
        self.last_model_input_messages = [UserMessage(content="Current browser state")]
        self.last_model_response_content = "I can finish now."
        self.last_model_tool_call_id = "call-1"
        self.last_model_tool_name = "submit_browser_decision"
        self.history = FakeHistory()

    async def run(self, max_steps, on_step_end):
        await on_step_end(self)
        await self.browser_session.kill()
        return self.history

    def resume(self):
        self.state.paused = False

    def stop(self):
        self.state.stopped = True


class WaitingAgent(CompletingAgent):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._resume_event = asyncio.Event()

    async def run(self, max_steps, on_step_end):
        self.state.paused = True
        self.takeover_request = {
            "kind": "request_user_takeover",
            "reason": "Login required",
            "instruction": "Complete login in the browser",
        }
        self.history = SimpleNamespace(
            history=[
                SimpleNamespace(
                    state=SimpleNamespace(url="https://example.com/login", screenshot_path=None),
                    model_output=FakeModelOutput(),
                    result=[ActionResult(extracted_content="User takeover required")],
                )
            ]
        )
        await on_step_end(self)
        await self._resume_event.wait()
        self.history = StoppedHistory() if self.state.stopped else FakeHistory()
        await on_step_end(self)
        await self.browser_session.kill()
        return self.history

    def resume(self):
        super().resume()
        self.takeover_request = None
        self._resume_event.set()

    def stop(self):
        super().stop()
        self._resume_event.set()


class BrowserWorkerTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp_dir = TemporaryDirectory()
        self.worker_dir = Path(self.temp_dir.name) / "workers" / "research"
        (self.worker_dir / "artifacts").mkdir(parents=True)
        (self.worker_dir / "request.md").write_text("Inspect example.com", encoding="utf-8")
        FakeSession.instances.clear()

    def tearDown(self):
        self.temp_dir.cleanup()

    def worker(self):
        return BrowserWorker(
            api_key="test-key",
            base_url="https://proxy.example/v1",
            model="vision-model",
            session_mode="clean",
        )

    async def test_completed_run_writes_result_trace_and_readable_trajectory(self):
        with (
            patch("collab_agent.browser_worker.BrowserSession", FakeSession),
            patch("collab_agent.browser_worker.Agent", CompletingAgent),
            patch("collab_agent.browser_worker.find_edge_executable", return_value="msedge.exe"),
        ):
            await self.worker()(self.worker_dir)

        result = (self.worker_dir / "result.md").read_text(encoding="utf-8")
        self.assertIn("Status: completed", result)
        self.assertIn("Task finished", result)
        events = [
            json.loads(line)
            for line in (self.worker_dir / "trace.jsonl").read_text(encoding="utf-8").splitlines()
        ]
        self.assertEqual([event["event"] for event in events], ["worker_started", "turn", "worker_finished"])
        self.assertEqual(events[1]["tool_call"]["name"], "submit_browser_decision")
        trajectory = (self.worker_dir / "trajectory.md").read_text(encoding="utf-8")
        self.assertIn("## Turn 1", trajectory)
        self.assertIn("I can finish now.", trajectory)
        self.assertTrue(FakeSession.instances[0].hidden)
        self.assertTrue(FakeSession.instances[0].closed)

    async def test_model_client_uses_gateway_compatible_user_agent(self):
        captured = {}

        class CapturingAgent(CompletingAgent):
            def __init__(self, **kwargs):
                captured["llm"] = kwargs["llm"]
                captured["output_model_schema"] = kwargs.get("output_model_schema")
                super().__init__(**kwargs)

        with (
            patch("collab_agent.browser_worker.BrowserSession", FakeSession),
            patch("collab_agent.browser_worker.Agent", CapturingAgent),
            patch("collab_agent.browser_worker.find_edge_executable", return_value="msedge.exe"),
        ):
            await self.worker()(self.worker_dir)

        self.assertEqual(captured["llm"].default_headers, {"User-Agent": "collab-agent"})
        self.assertIsNone(captured["output_model_schema"])

    async def test_benchmark_mode_records_network_and_final_response_artifacts(self):
        (self.worker_dir / "benchmark.json").write_text(
            json.dumps(
                {
                    "case_id": "LB01",
                    "start_urls": [
                        "http://localhost:7770/first",
                        "http://localhost:7770/second",
                    ],
                    "extra_headers": {"X-M2-Customer-Auto-Login": "test-account"},
                }
            ),
            encoding="utf-8",
        )
        worker = BrowserWorker(
            api_key="test-key",
            base_url="https://proxy.example/v1",
            model="vision-model",
            session_mode="clean",
            benchmark_mode=True,
        )
        recorder = unittest.mock.AsyncMock()
        recorder.write.return_value = self.worker_dir / "artifacts" / "network.har"
        captured = {}

        class StructuredCompletingAgent(CompletingAgent):
            def __init__(self, **kwargs):
                captured["output_model_schema"] = kwargs.get("output_model_schema")
                super().__init__(**kwargs)
                schema = captured["output_model_schema"]
                self.history.structured_output = schema.model_validate(
                    {
                        "task_type": "RETRIEVE",
                        "status": "SUCCESS",
                        "retrieved_data": ["Task finished"],
                        "error_details": None,
                    }
                )
                self.history._text = self.history.structured_output.model_dump_json()

        with (
            patch("collab_agent.browser_worker.BrowserSession", FakeSession),
            patch("collab_agent.browser_worker.Agent", StructuredCompletingAgent),
            patch("collab_agent.browser_worker.NetworkRecorder", return_value=recorder),
            patch("collab_agent.browser_worker.find_edge_executable", return_value="msedge.exe"),
        ):
            await worker(self.worker_dir)

        recorder.start.assert_awaited_once_with(FakeSession.instances[0])
        recorder.write.assert_awaited_once()
        self.assertEqual(
            FakeSession.instances[0].urls,
            [
                ("http://localhost:7770/first", False),
                ("http://localhost:7770/second", True),
            ],
        )
        self.assertEqual(
            FakeSession.instances[0].extra_headers,
            {"X-M2-Customer-Auto-Login": "test-account"},
        )
        response = self.worker_dir / "artifacts" / "agent-response.txt"
        self.assertIsNotNone(captured["output_model_schema"])
        schema = captured["output_model_schema"].model_json_schema()
        self.assertIn("retrieve", schema["properties"]["task_type"]["description"].casefold())
        self.assertIn("fully achieved", schema["properties"]["status"]["description"].casefold())
        self.assertIn("null", schema["properties"]["retrieved_data"]["description"].casefold())
        self.assertEqual(
            json.loads(response.read_text(encoding="utf-8")),
            {
                "task_type": "RETRIEVE",
                "status": "SUCCESS",
                "retrieved_data": ["Task finished"],
                "error_details": None,
            },
        )
        handoff = (self.worker_dir / "result.md").read_text(encoding="utf-8")
        self.assertIn("reported `SUCCESS` for the `RETRIEVE` task", handoff)
        self.assertIn('"Task finished"', handoff)
        self.assertNotIn('"task_type":"RETRIEVE"', handoff)

    async def test_waiting_run_only_finishes_after_takeover_and_resume(self):
        worker = self.worker()
        with (
            patch("collab_agent.browser_worker.BrowserSession", FakeSession),
            patch("collab_agent.browser_worker.Agent", WaitingAgent),
            patch("collab_agent.browser_worker.find_edge_executable", return_value="msedge.exe"),
        ):
            run_task = asyncio.create_task(worker(self.worker_dir))
            for _ in range(20):
                if "research" in worker.active_worker_ids:
                    agent = worker._active["research"]
                    if agent.state.paused:
                        break
                await asyncio.sleep(0)

            self.assertFalse((self.worker_dir / "result.md").exists())
            await worker.takeover("research")
            self.assertTrue(FakeSession.instances[0].shown)
            await worker.resume("research")
            await run_task

        self.assertTrue((self.worker_dir / "result.md").exists())
        self.assertNotIn("research", worker.active_worker_ids)

    async def test_done_false_writes_failed_result(self):
        class FailedAgent(CompletingAgent):
            def __init__(self, **kwargs):
                super().__init__(**kwargs)
                self.history = FakeHistory(success=False, text="The requested data was not available")

        with (
            patch("collab_agent.browser_worker.BrowserSession", FakeSession),
            patch("collab_agent.browser_worker.Agent", FailedAgent),
            patch("collab_agent.browser_worker.find_edge_executable", return_value="msedge.exe"),
        ):
            await self.worker()(self.worker_dir)

        result = (self.worker_dir / "result.md").read_text(encoding="utf-8")
        self.assertIn("Status: failed", result)
        self.assertIn("The requested data was not available", result)

    async def test_cancel_unblocks_waiting_worker_and_writes_failed_result(self):
        worker = self.worker()
        with (
            patch("collab_agent.browser_worker.BrowserSession", FakeSession),
            patch("collab_agent.browser_worker.Agent", WaitingAgent),
            patch("collab_agent.browser_worker.find_edge_executable", return_value="msedge.exe"),
        ):
            run_task = asyncio.create_task(worker(self.worker_dir))
            for _ in range(20):
                if worker.waiting_requests:
                    break
                await asyncio.sleep(0)

            await worker.cancel("research")
            await run_task

        result = (self.worker_dir / "result.md").read_text(encoding="utf-8")
        self.assertIn("Status: failed", result)
        events = [
            json.loads(line)["event"]
            for line in (self.worker_dir / "trace.jsonl").read_text(encoding="utf-8").splitlines()
        ]
        self.assertIn("worker_cancelled", events)
        self.assertNotIn("research", worker.active_worker_ids)

    async def test_inherited_workers_receive_independent_profile_copies(self):
        edge_data = Path(self.temp_dir.name) / "edge-data"
        profile_dir = edge_data / "Default"
        profile_dir.mkdir(parents=True)
        (profile_dir / "Cookies").write_text("login state", encoding="utf-8")
        (edge_data / "Local State").write_text("{}", encoding="utf-8")
        second_worker_dir = self.worker_dir.parent / "research-2"
        (second_worker_dir / "artifacts").mkdir(parents=True)

        worker = BrowserWorker(
            api_key="test-key",
            base_url="https://proxy.example/v1",
            model="vision-model",
            session_mode="inherited",
        )
        worker_profiles = []
        with (
            patch("collab_agent.browser_worker.find_edge_executable", return_value="msedge.exe"),
            patch("collab_agent.browser_worker.get_edge_user_data_dir", return_value=edge_data),
            patch("collab_agent.browser_worker.get_last_used_profile", return_value="Default"),
        ):
            first = await worker._create_profile(self.worker_dir)
            second = await worker._create_profile(second_worker_dir)
            worker_profiles.extend([Path(first.user_data_dir), Path(second.user_data_dir)])

        try:
            self.assertNotEqual(worker_profiles[0], worker_profiles[1])
            for profile_root in worker_profiles:
                self.assertEqual(
                    (profile_root / "Default" / "Cookies").read_text(encoding="utf-8"),
                    "login state",
                )
        finally:
            for profile_root in worker_profiles:
                shutil.rmtree(profile_root, ignore_errors=True)
            await worker.close()


if __name__ == "__main__":
    unittest.main()
