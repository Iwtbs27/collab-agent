"""CLI Worker backed by the Codex command-line client."""

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from .codex_runner import CodexResult, CodexRunner


class CLIWorker:
    """Execute a request through Codex and keep the session resumable."""

    def __init__(
        self,
        runner: Optional[CodexRunner] = None,
        working_directory: Optional[Path] = None,
        max_turns: int = 3,
    ):
        if max_turns < 1:
            raise ValueError("max_turns must be at least 1")
        self.runner = runner or CodexRunner()
        self.working_directory = Path(working_directory) if working_directory else None
        self.max_turns = max_turns

    async def __call__(self, worker_dir: Path) -> None:
        worker_dir = Path(worker_dir)
        prompt = (worker_dir / "request.md").read_text(encoding="utf-8")
        await self._execute(worker_dir, prompt, None, self.max_turns)

    async def resume(
        self,
        worker_dir: Path,
        session_id: str,
        follow_up_request: str,
    ) -> None:
        """Continue a saved Codex thread with user or TeamLeader feedback."""
        if not session_id:
            raise ValueError("session_id must be provided")
        if not follow_up_request:
            raise ValueError("follow_up_request must be provided")
        await self._execute(Path(worker_dir), follow_up_request, session_id, 1)

    async def _execute(
        self,
        worker_dir: Path,
        prompt: str,
        session_id: Optional[str],
        turn_limit: int,
    ) -> None:
        worker_dir.mkdir(parents=True, exist_ok=True)
        artifacts_dir = worker_dir / "artifacts"
        artifacts_dir.mkdir(exist_ok=True)
        trace_path = worker_dir / "trace.jsonl"
        raw_trace_path = artifacts_dir / "codex_trace.jsonl"
        cwd = Path(self.working_directory or worker_dir.parents[1]).resolve()

        turns: List[Dict[str, Any]] = []
        current_session = session_id
        previous_turns = self._session_turn_count(worker_dir)
        for offset in range(turn_limit):
            turn_number = previous_turns + offset + 1
            self._append_trace(
                trace_path,
                "turn_started",
                turn=turn_number,
                session_id=current_session,
                prompt=prompt,
                working_directory=str(cwd),
            )
            try:
                result = await self.runner.run(
                    prompt,
                    cwd,
                    raw_trace_path=raw_trace_path,
                    session_id=current_session,
                )
            except Exception as exc:
                result = CodexResult(
                    thread_id=current_session,
                    status="failed",
                    final_message="",
                    exit_code=1,
                    error=f"{type(exc).__name__}: {exc}",
                )
            turns.append(self._turn_record(turn_number, prompt, result))
            self._append_trace(
                trace_path,
                "turn_finished",
                turn=turn_number,
                session_id=result.thread_id or current_session,
                status=result.status,
                final_message=result.final_message,
                error=result.error,
                exit_code=result.exit_code,
                commands=result.commands,
                file_changes=result.file_changes,
            )

            current_session = result.thread_id or current_session
            total_turns = previous_turns + len(turns)
            self._write_session(worker_dir, current_session, total_turns, result, cwd)
            if result.status == "completed":
                self._write_result(worker_dir, result, total_turns, current_session)
                self._write_trajectory(worker_dir, "completed")
                return

            if not current_session or offset + 1 == turn_limit:
                self._write_result(worker_dir, result, total_turns, current_session)
                self._write_trajectory(worker_dir, "failed")
                return

            prompt = self._retry_prompt(result)

    @staticmethod
    def _retry_prompt(result: CodexResult) -> str:
        details = result.error or result.final_message or "本轮没有完成。"
        return (
            "上一轮执行没有成功。请检查下面的失败信息，继续在当前会话中完成原任务，"
            "不要只解释原因；完成后再次运行必要的命令并给出结果。\n\n"
            f"失败信息：\n{details}"
        )

    @staticmethod
    def _turn_record(turn: int, prompt: str, result: CodexResult) -> Dict[str, Any]:
        return {
            "turn": turn,
            "prompt": prompt,
            "session_id": result.thread_id,
            "status": result.status,
            "final_message": result.final_message,
            "error": result.error,
            "exit_code": result.exit_code,
            "commands": result.commands,
            "file_changes": result.file_changes,
        }

    @staticmethod
    def _append_trace(path: Path, event: str, **details: Any) -> None:
        record = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "event": event,
            **details,
        }
        with path.open("a", encoding="utf-8") as file:
            file.write(json.dumps(record, ensure_ascii=False, default=str) + "\n")

    @staticmethod
    def _write_session(
        worker_dir: Path,
        session_id: Optional[str],
        turn_count: int,
        result: CodexResult,
        cwd: Path,
    ) -> None:
        session = {
            "session_id": session_id,
            "turns": turn_count,
            "status": result.status,
            "working_directory": str(cwd),
            "last_message": result.final_message,
            "last_error": result.error,
        }
        (worker_dir / "session.json").write_text(
            json.dumps(session, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    @staticmethod
    def _write_result(
        worker_dir: Path,
        result: CodexResult,
        turn_count: int,
        session_id: Optional[str],
    ) -> None:
        lines = [
            "# CLI Worker Result",
            "",
            f"Status: {result.status}",
            "",
            "## Handoff",
            "",
            result.final_message or result.error or "Codex 未返回最终消息。",
            "",
            "## Artifacts",
            "",
            "- `artifacts/codex_trace.jsonl`：Codex JSONL 原始轨迹",
            "- `session.json`：可用于后续 resume 的 Codex session",
            f"- Session ID：{session_id or 'Unavailable'}",
        ]
        for change in result.file_changes:
            path = change.get("path")
            if path:
                lines.append(f"- 工作区文件变更：`{path}`")
        lines.extend(
            [
                f"- Codex turns：{turn_count}",
                f"- Exit code：{result.exit_code}",
            ]
        )
        if result.error and result.status != "completed":
            lines.extend(["", "## Error", "", result.error])
        (worker_dir / "result.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    @staticmethod
    def _write_trajectory(
        worker_dir: Path,
        status: str,
    ) -> None:
        lines = ["# CLI Worker Trajectory", ""]
        records = CLIWorker._read_turns(worker_dir / "trace.jsonl")
        for record in records:
            lines.extend(
                [
                    f"## Turn {record['turn']}",
                    "",
                    "### Prompt",
                    "",
                    record["prompt"],
                    "",
                    "### Codex Result",
                    "",
                    f"Status: {record['status']}",
                    f"Exit code: {record['exit_code']}",
                    "",
                    "### Assistant Message",
                    "",
                    record["final_message"] or "（无最终消息）",
                    "",
                ]
            )
            if record["error"]:
                lines.extend(["### Error", "", record["error"], ""])
            if record["commands"]:
                lines.extend(
                    [
                        "### Commands",
                        "",
                        "```json",
                        json.dumps(record["commands"], ensure_ascii=False, indent=2),
                        "```",
                        "",
                    ]
                )
            if record["file_changes"]:
                lines.extend(
                    [
                        "### File Changes",
                        "",
                        "```json",
                        json.dumps(
                            record["file_changes"], ensure_ascii=False, indent=2
                        ),
                        "```",
                        "",
                    ]
                )
        lines.extend(["## Finished", "", f"Status: {status}", ""])
        (worker_dir / "trajectory.md").write_text("\n".join(lines), encoding="utf-8")

    @staticmethod
    def _read_turns(trace_path: Path) -> List[Dict[str, Any]]:
        if not trace_path.is_file():
            return []
        events = [
            json.loads(line)
            for line in trace_path.read_text(encoding="utf-8").splitlines()
            if line
        ]
        starts = {
            event["turn"]: event
            for event in events
            if event.get("event") == "turn_started"
        }
        records = []
        for event in events:
            if event.get("event") != "turn_finished":
                continue
            record = dict(event)
            record["prompt"] = starts.get(event["turn"], {}).get("prompt", "")
            records.append(record)
        return records

    @staticmethod
    def _session_turn_count(worker_dir: Path) -> int:
        session_path = worker_dir / "session.json"
        if not session_path.is_file():
            return 0
        try:
            value = json.loads(session_path.read_text(encoding="utf-8")).get("turns", 0)
        except (OSError, json.JSONDecodeError):
            return 0
        return value if isinstance(value, int) and value >= 0 else 0
