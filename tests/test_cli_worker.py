import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from collab_agent.cli_worker import CLIWorker
from collab_agent.codex_runner import CodexResult


def completed(thread_id, message="已完成。"):
    return CodexResult(
        thread_id=thread_id,
        status="completed",
        final_message=message,
        exit_code=0,
        events=[],
        commands=[],
        file_changes=[],
    )


def failed(thread_id, message="测试失败"):
    return CodexResult(
        thread_id=thread_id,
        status="failed",
        final_message=message,
        exit_code=1,
        events=[],
        commands=[{"command": "pytest", "exit_code": 1, "aggregated_output": message}],
        file_changes=[],
        error=message,
    )


class FakeCodexRunner:
    def __init__(self, results):
        self.results = list(results)
        self.calls = []

    async def run(self, prompt, cwd, *, raw_trace_path=None, session_id=None):
        self.calls.append({"prompt": prompt, "cwd": cwd, "session_id": session_id})
        return self.results.pop(0)


class CLIWorkerTests(unittest.IsolatedAsyncioTestCase):
    def make_worker_dir(self, temp_dir):
        worker_dir = Path(temp_dir) / "workers" / "cli-task"
        (worker_dir / "artifacts").mkdir(parents=True)
        (worker_dir / "request.md").write_text(
            "实现一个功能。\n\n验收标准：测试通过。", encoding="utf-8"
        )
        return worker_dir

    async def test_success_writes_result_session_and_trajectory(self):
        with TemporaryDirectory() as temp_dir:
            runner = FakeCodexRunner([completed("thread-1")])
            worker_dir = self.make_worker_dir(temp_dir)
            await CLIWorker(runner, working_directory=Path(temp_dir))(worker_dir)

            result = (worker_dir / "result.md").read_text(encoding="utf-8")
            self.assertIn("Status: completed", result)
            self.assertIn("已完成。", result)
            self.assertIn("Session ID：thread-1", result)
            session = json.loads((worker_dir / "session.json").read_text(encoding="utf-8"))
            self.assertEqual(session["session_id"], "thread-1")
            self.assertTrue((worker_dir / "trace.jsonl").is_file())
            self.assertTrue((worker_dir / "trajectory.md").is_file())
            self.assertEqual(runner.calls[0]["session_id"], None)

    async def test_default_working_directory_is_current_task(self):
        with TemporaryDirectory() as temp_dir:
            runner = FakeCodexRunner([completed("thread-1")])
            worker_dir = self.make_worker_dir(temp_dir)
            await CLIWorker(runner)(worker_dir)

            self.assertEqual(runner.calls[0]["cwd"], Path(temp_dir).resolve())

    async def test_failed_turn_resumes_same_session_until_success(self):
        with TemporaryDirectory() as temp_dir:
            runner = FakeCodexRunner([failed("thread-1"), completed("thread-1", "修复完成。")])
            worker_dir = self.make_worker_dir(temp_dir)
            await CLIWorker(
                runner, working_directory=Path(temp_dir), max_turns=3
            )(worker_dir)

            self.assertEqual(len(runner.calls), 2)
            self.assertEqual(runner.calls[1]["session_id"], "thread-1")
            self.assertIn("测试失败", runner.calls[1]["prompt"])
            result = (worker_dir / "result.md").read_text(encoding="utf-8")
            self.assertIn("修复完成。", result)

    async def test_resume_accepts_user_feedback_and_keeps_session(self):
        with TemporaryDirectory() as temp_dir:
            runner = FakeCodexRunner([completed("thread-1", "已按反馈修改。")])
            worker_dir = self.make_worker_dir(temp_dir)
            (worker_dir / "session.json").write_text(
                json.dumps({"session_id": "thread-1", "turns": 1}), encoding="utf-8"
            )

            await CLIWorker(runner, working_directory=Path(temp_dir)).resume(
                worker_dir, "thread-1", "请把输出改成 Markdown。"
            )

            self.assertEqual(runner.calls[0]["session_id"], "thread-1")
            self.assertEqual(runner.calls[0]["prompt"], "请把输出改成 Markdown。")
            self.assertIn("已按反馈修改。", (worker_dir / "result.md").read_text(encoding="utf-8"))

    async def test_max_turns_returns_failure_without_another_call(self):
        with TemporaryDirectory() as temp_dir:
            runner = FakeCodexRunner([failed("thread-1"), failed("thread-1")])
            worker_dir = self.make_worker_dir(temp_dir)
            await CLIWorker(
                runner, working_directory=Path(temp_dir), max_turns=2
            )(worker_dir)

            self.assertEqual(len(runner.calls), 2)
            result = (worker_dir / "result.md").read_text(encoding="utf-8")
            self.assertIn("Status: failed", result)


if __name__ == "__main__":
    unittest.main()
