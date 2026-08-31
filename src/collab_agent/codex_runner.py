"""Small async wrapper around the Codex CLI JSONL execution interface."""

import asyncio
from contextlib import suppress
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional


@dataclass
class CodexResult:
    """Facts collected from one Codex turn."""

    thread_id: Optional[str]
    status: str
    final_message: str
    exit_code: int
    events: List[Dict[str, Any]] = field(default_factory=list)
    commands: List[Dict[str, Any]] = field(default_factory=list)
    file_changes: List[Dict[str, Any]] = field(default_factory=list)
    error: Optional[str] = None
    stderr: str = ""


class CodexRunner:
    """Run one new Codex turn or resume an existing thread."""

    def __init__(
        self,
        executable: str = "codex",
        sandbox: str = "workspace-write",
    ):
        self.executable = executable
        self.sandbox = sandbox

    async def run(
        self,
        prompt: str,
        cwd: Path,
        *,
        raw_trace_path: Optional[Path] = None,
        session_id: Optional[str] = None,
    ) -> CodexResult:
        """Start a new thread, or resume ``session_id`` when supplied."""
        cwd = Path(cwd).resolve()
        if session_id:
            return await self.resume(
                session_id,
                prompt,
                cwd,
                raw_trace_path=raw_trace_path,
            )

        args = [
            self.executable,
            "exec",
            "--json",
            "--skip-git-repo-check",
            "--sandbox",
            self.sandbox,
            "--cd",
            str(cwd),
            prompt,
        ]
        return await self._execute(args, cwd, raw_trace_path)

    async def resume(
        self,
        session_id: str,
        prompt: str,
        cwd: Path,
        *,
        raw_trace_path: Optional[Path] = None,
    ) -> CodexResult:
        """Append a turn to an existing Codex thread."""
        cwd = Path(cwd).resolve()
        args = [
            self.executable,
            "exec",
            "resume",
            "--json",
            "--skip-git-repo-check",
            session_id,
            prompt,
        ]
        result = await self._execute(args, cwd, raw_trace_path)
        if result.thread_id is None:
            result.thread_id = session_id
        return result

    async def _execute(
        self,
        args: List[str],
        cwd: Path,
        raw_trace_path: Optional[Path],
    ) -> CodexResult:
        if raw_trace_path is not None:
            raw_trace_path.parent.mkdir(parents=True, exist_ok=True)

        process = await asyncio.create_subprocess_exec(
            *args,
            cwd=str(cwd),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        events: List[Dict[str, Any]] = []
        thread_id: Optional[str] = None
        final_message = ""
        commands: List[Dict[str, Any]] = []
        file_changes: List[Dict[str, Any]] = []
        error: Optional[str] = None
        turn_completed = False
        terminal_event = False

        trace_file = (
            raw_trace_path.open("a", encoding="utf-8")
            if raw_trace_path is not None
            else None
        )
        try:
            async for raw_line in process.stdout:
                line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                if trace_file is not None:
                    trace_file.write(line + "\n")
                    trace_file.flush()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if not isinstance(event, dict):
                    continue
                events.append(event)

                event_type = event.get("type")
                if event_type == "thread.started":
                    value = event.get("thread_id")
                    if isinstance(value, str) and value:
                        thread_id = value
                elif event_type == "turn.completed":
                    turn_completed = True
                    terminal_event = True
                    break
                elif event_type == "turn.failed":
                    error = self._error_message(event.get("error"))
                    terminal_event = True
                    break
                elif event_type == "item.completed":
                    item = event.get("item")
                    if not isinstance(item, dict):
                        continue
                    item_type = item.get("type")
                    if item_type == "agent_message":
                        text = item.get("text")
                        if isinstance(text, str):
                            final_message = text
                    elif item_type == "command_execution":
                        commands.append(item)
                        if item.get("exit_code") not in (None, 0):
                            error = error or self._command_error(item)
                    elif item_type == "file_change":
                        file_changes.append(item)
                    elif item_type == "error":
                        error = error or self._error_message(item.get("message"))
        finally:
            if trace_file is not None:
                trace_file.close()

        if terminal_event:
            exit_code = getattr(process, "returncode", None)
            if exit_code is None:
                kill = getattr(process, "kill", None)
                if kill is not None:
                    with suppress(ProcessLookupError):
                        kill()
                exit_code = 0 if turn_completed else 1
            self._close_transport(process)
        else:
            try:
                exit_code = await asyncio.wait_for(process.wait(), timeout=5)
            except asyncio.TimeoutError:
                kill = getattr(process, "kill", None)
                if kill is not None:
                    with suppress(ProcessLookupError):
                        kill()
                exit_code = 1
            self._close_transport(process)
        stderr = ""
        if exit_code != 0:
            error = error or stderr or f"Codex exited with code {exit_code}"

        status = "completed" if exit_code == 0 and turn_completed else "failed"
        if status == "failed" and error is None:
            error = stderr or "Codex turn did not complete successfully"

        return CodexResult(
            thread_id=thread_id,
            status=status,
            final_message=final_message,
            exit_code=exit_code,
            events=events,
            commands=commands,
            file_changes=file_changes,
            error=error,
            stderr=stderr,
        )

    @staticmethod
    def _error_message(value: Any) -> Optional[str]:
        if isinstance(value, dict):
            value = value.get("message") or value.get("error")
        if value is None:
            return None
        return str(value)

    @staticmethod
    def _command_error(item: Dict[str, Any]) -> str:
        command = item.get("command", "command")
        exit_code = item.get("exit_code")
        output = item.get("aggregated_output") or ""
        message = f"Command failed ({exit_code}): {command}"
        return f"{message}\n{output}".strip()

    @staticmethod
    def _close_transport(process: Any) -> None:
        """Close Windows pipe transports when stopping after a terminal JSONL event."""
        transport = getattr(process, "_transport", None)
        if transport is not None:
            transport.close()
