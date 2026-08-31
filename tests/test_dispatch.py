import asyncio
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from collab_agent.dispatch import (
    WorkerTask,
    dispatch_parallel_worker,
    dispatch_worker,
)
from collab_agent.workspace import TaskWorkspace


class DispatchTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp_dir = TemporaryDirectory()
        self.workspace = TaskWorkspace.create(self.temp_dir.name, "task-001")

    def tearDown(self):
        self.temp_dir.cleanup()

    async def test_dispatch_worker_writes_request_and_returns_result(self):
        async def runner(worker_dir):
            self.assertEqual(
                (worker_dir / "request.md").read_text(encoding="utf-8"),
                "Research company A.",
            )
            (worker_dir / "result.md").write_text(
                "status: succeeded\n\nCompany A notes.", encoding="utf-8"
            )

        result = await dispatch_worker(
            self.workspace,
            "browser-company-a",
            "Research company A.",
            runner,
        )

        self.assertTrue(result.success)
        self.assertEqual(result.worker_name, "browser-company-a")
        self.assertEqual(
            result.result_content,
            "status: succeeded\n\nCompany A notes.",
        )
        self.assertEqual(
            result.result_path,
            self.workspace.result_path("browser-company-a"),
        )

    async def test_dispatch_parallel_worker_starts_tasks_concurrently(self):
        started = []
        both_started = asyncio.Event()

        async def runner(worker_dir):
            started.append(worker_dir.name)
            if len(started) == 2:
                both_started.set()
            await asyncio.wait_for(both_started.wait(), timeout=1)
            (worker_dir / "result.md").write_text(
                f"status: succeeded\n\n{worker_dir.name}", encoding="utf-8"
            )

        tasks = [
            WorkerTask("browser-company-a", "Research company A.", runner),
            WorkerTask("browser-company-b", "Research company B.", runner),
        ]
        results = await dispatch_parallel_worker(self.workspace, tasks)

        self.assertEqual(started, ["browser-company-a", "browser-company-b"])
        self.assertEqual([result.worker_name for result in results], [
            "browser-company-a",
            "browser-company-b",
        ])
        self.assertTrue(all(result.success for result in results))

    async def test_parallel_failure_does_not_cancel_other_workers(self):
        async def failing_runner(worker_dir):
            raise RuntimeError("worker crashed")

        async def successful_runner(worker_dir):
            await asyncio.sleep(0.02)
            (worker_dir / "result.md").write_text(
                "status: succeeded\n\nStill completed.", encoding="utf-8"
            )

        tasks = [
            WorkerTask("worker-failed", "Fail.", failing_runner),
            WorkerTask("worker-success", "Succeed.", successful_runner),
        ]
        results = await dispatch_parallel_worker(self.workspace, tasks)

        self.assertFalse(results[0].success)
        self.assertEqual(results[0].error_kind, "worker_error")
        self.assertIn("worker crashed", results[0].error)
        self.assertTrue(results[1].success)

    async def test_missing_result_is_reported_without_being_created(self):
        async def runner(worker_dir):
            return None

        result = await dispatch_worker(
            self.workspace,
            "worker-without-result",
            "Do work.",
            runner,
        )

        self.assertFalse(result.success)
        self.assertEqual(result.error_kind, "result_missing")
        self.assertFalse(result.result_path.exists())

    async def test_failed_result_is_returned_without_being_treated_as_success(self):
        async def runner(worker_dir):
            (worker_dir / "result.md").write_text(
                "# Worker Result\n\nStatus: failed\n\n"
                "## Handoff\n\nLogin was cancelled.\n\n"
                "## Artifacts\n\n- None\n",
                encoding="utf-8",
            )

        result = await dispatch_worker(
            self.workspace,
            "browser-login",
            "Open the account page.",
            runner,
        )

        self.assertFalse(result.success)
        self.assertEqual(result.task_status, "failed")
        self.assertIsNone(result.error_kind)
        self.assertIn("Login was cancelled.", result.result_content)

    async def test_parallel_dispatch_requires_multiple_tasks(self):
        async def runner(worker_dir):
            return None

        with self.assertRaises(ValueError):
            await dispatch_parallel_worker(
                self.workspace,
                [WorkerTask("only-worker", "Do work.", runner)],
            )


if __name__ == "__main__":
    unittest.main()
