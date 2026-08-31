import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import AsyncMock, patch

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from collab_agent.codex_runner import CodexRunner


class FakeStream:
    def __init__(self, lines):
        self.lines = [line.encode("utf-8") for line in lines]
        self.index = 0

    def __aiter__(self):
        return self

    async def __anext__(self):
        if self.index >= len(self.lines):
            raise StopAsyncIteration
        line = self.lines[self.index]
        self.index += 1
        return line

    async def read(self):
        return b""


class FakeProcess:
    def __init__(self, lines, returncode=0, stderr=b""):
        self.stdout = FakeStream(lines)
        self.stderr = FakeStream([])
        self._returncode = returncode
        self._stderr = stderr

    async def wait(self):
        return self._returncode


def event_lines(*events):
    return [json.dumps(event, ensure_ascii=False) for event in events]


class CodexRunnerTests(unittest.IsolatedAsyncioTestCase):
    async def test_run_parses_events_and_persists_raw_jsonl(self):
        process = FakeProcess(
            event_lines(
                {"type": "thread.started", "thread_id": "thread-1"},
                {"type": "turn.started"},
                {
                    "type": "item.completed",
                    "item": {
                        "type": "command_execution",
                        "command": "pytest",
                        "aggregated_output": "1 passed",
                        "exit_code": 0,
                        "status": "completed",
                    },
                },
                {
                    "type": "item.completed",
                    "item": {"type": "file_change", "changes": [{"path": "app.py"}]},
                },
                {
                    "type": "item.completed",
                    "item": {"type": "agent_message", "text": "已完成任务。"},
                },
                {"type": "turn.completed"},
            )
        )

        with TemporaryDirectory() as temp_dir:
            raw_trace = Path(temp_dir) / "codex.jsonl"
            with patch(
                "collab_agent.codex_runner.asyncio.create_subprocess_exec",
                new=AsyncMock(return_value=process),
            ) as create_process:
                result = await CodexRunner(executable="codex.cmd").run(
                    "完成任务",
                    Path(temp_dir),
                    raw_trace_path=raw_trace,
                )

            self.assertEqual(result.thread_id, "thread-1")
            self.assertEqual(result.status, "completed")
            self.assertEqual(result.final_message, "已完成任务。")
            self.assertEqual(result.commands[0]["exit_code"], 0)
            self.assertEqual(result.file_changes[0]["changes"][0]["path"], "app.py")
            self.assertEqual(len(raw_trace.read_text(encoding="utf-8").splitlines()), 6)
            args = create_process.await_args.args
            self.assertEqual(args[0:3], ("codex.cmd", "exec", "--json"))
            self.assertIn("--cd", args)
            self.assertIn(str(Path(temp_dir)), args)

    async def test_completed_turn_survives_recovered_command_failure(self):
        process = FakeProcess(
            event_lines(
                {"type": "thread.started", "thread_id": "thread-1"},
                {
                    "type": "item.completed",
                    "item": {
                        "type": "command_execution",
                        "command": "git status",
                        "aggregated_output": "dubious ownership",
                        "exit_code": 1,
                    },
                },
                {
                    "type": "item.completed",
                    "item": {"type": "agent_message", "text": "文件已生成。"},
                },
                {"type": "turn.completed"},
            )
        )

        with TemporaryDirectory() as temp_dir:
            with patch(
                "collab_agent.codex_runner.asyncio.create_subprocess_exec",
                new=AsyncMock(return_value=process),
            ):
                result = await CodexRunner(executable="codex.cmd").run(
                    "生成文件", Path(temp_dir)
                )

        self.assertEqual(result.status, "completed")
        self.assertEqual(result.final_message, "文件已生成。")
        self.assertIn("Command failed", result.error)

    async def test_resume_uses_existing_session_and_working_directory(self):
        process = FakeProcess(
            event_lines(
                {"type": "item.completed", "item": {"type": "agent_message", "text": "已修改。"}},
                {"type": "turn.completed"},
            )
        )

        with TemporaryDirectory() as temp_dir:
            with patch(
                "collab_agent.codex_runner.asyncio.create_subprocess_exec",
                new=AsyncMock(return_value=process),
            ) as create_process:
                result = await CodexRunner(executable="codex.cmd").resume(
                    "thread-1", "继续修改", Path(temp_dir)
                )

        self.assertEqual(result.thread_id, "thread-1")
        self.assertEqual(result.final_message, "已修改。")
        args = create_process.await_args.args
        self.assertEqual(args[0:4], ("codex.cmd", "exec", "resume", "--json"))
        self.assertIn("thread-1", args)
        self.assertEqual(create_process.await_args.kwargs["cwd"], str(Path(temp_dir)))

    async def test_relative_working_directory_is_resolved_before_launch(self):
        process = FakeProcess(
            event_lines(
                {"type": "thread.started", "thread_id": "thread-1"},
                {"type": "turn.completed"},
            )
        )

        with TemporaryDirectory():
            relative_dir = Path("tasks").resolve()
            with patch(
                "collab_agent.codex_runner.asyncio.create_subprocess_exec",
                new=AsyncMock(return_value=process),
            ) as create_process:
                result = await CodexRunner(executable="codex.cmd").run(
                    "执行任务", Path("tasks")
                )

        self.assertEqual(result.status, "completed")
        args = create_process.await_args.args
        self.assertEqual(args[args.index("--cd") + 1], str(relative_dir))
        self.assertEqual(create_process.await_args.kwargs["cwd"], str(relative_dir))

    async def test_failed_turn_is_not_reported_as_completed(self):
        process = FakeProcess(
            event_lines(
                {"type": "thread.started", "thread_id": "thread-1"},
                {"type": "turn.failed", "error": {"message": "命令失败"}},
            ),
            returncode=1,
        )

        with TemporaryDirectory() as temp_dir:
            with patch(
                "collab_agent.codex_runner.asyncio.create_subprocess_exec",
                new=AsyncMock(return_value=process),
            ):
                result = await CodexRunner(executable="codex.cmd").run(
                    "执行任务", Path(temp_dir)
                )

        self.assertEqual(result.status, "failed")
        self.assertIn("命令失败", result.error)
        self.assertEqual(result.exit_code, 1)


if __name__ == "__main__":
    unittest.main()
