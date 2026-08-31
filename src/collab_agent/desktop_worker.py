"""Desktop Worker backed by the migrated UFO2 Windows agent."""

from __future__ import annotations

import asyncio
import json
import os
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, Optional

from desktop_config.config_loader import get_ufo_config
from omniact_desktop.client.mcp.mcp_server_manager import MCPServerManager
from omniact_desktop.module.context import Context, ContextNames
from omniact_desktop.module.dispatcher import LocalCommandDispatcher


def _append_trace(path: Path, event: str, **details: Any) -> None:
    record = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "event": event,
        **details,
    }
    with path.open("a", encoding="utf-8") as file:
        file.write(json.dumps(record, ensure_ascii=False, default=str) + "\n")


def _clean_value(value: Any) -> Any:
    """Make processor data readable without embedding screenshot base64 in logs."""
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, dict):
        return {str(key): _clean_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_clean_value(item) for item in value]
    if isinstance(value, (str, int, float, bool)) or value is None:
        if isinstance(value, str) and value.startswith("data:image/"):
            return "[screenshot data omitted]"
        return value
    if hasattr(value, "model_dump"):
        return _clean_value(value.model_dump(mode="json"))
    if hasattr(value, "value") and not isinstance(value, type):
        return _clean_value(value.value)
    return str(value)


class _TraceWriter:
    """File-like writer used by UFO2 request logging."""

    def __init__(self, trace_path: Path):
        self.trace_path = trace_path

    def write(self, content: str) -> None:
        try:
            payload = json.loads(content)
        except json.JSONDecodeError:
            payload = {"raw": content}
        _append_trace(self.trace_path, "llm_request", payload=_clean_value(payload))


class _ProcessorTraceWriter:
    """Record UFO2 processor context without its full Session logger."""

    def __init__(self, trace_path: Path):
        self.trace_path = trace_path

    def write(self, content: str) -> None:
        try:
            payload = json.loads(content)
        except json.JSONDecodeError:
            payload = {"raw": content}
        _append_trace(self.trace_path, "processor_context", payload=_clean_value(payload))


@dataclass
class _DesktopSession:
    id: str
    task: str
    context: Context
    current_agent_class: str = "HostAgent"


@dataclass
class _DesktopRun:
    worker_id: str
    worker_dir: Path
    session: _DesktopSession
    agent: Any
    state: Any
    waiting_reason: Optional[str] = None
    takeover_active: bool = False
    resume_event: asyncio.Event = field(default_factory=asyncio.Event)
    cancel_event: asyncio.Event = field(default_factory=asyncio.Event)


class DesktopWorker:
    """Run the UFO2 HostAgent/AppAgent loop for one task directory."""

    def __init__(
        self,
        *,
        max_steps: int = 50,
        host_agent_factory: Optional[Callable[[str], Any]] = None,
        dispatcher_factory: Optional[Callable[[Any, MCPServerManager], Any]] = None,
    ) -> None:
        if max_steps < 1:
            raise ValueError("max_steps must be at least 1")
        self.max_steps = max_steps
        self.host_agent_factory = host_agent_factory
        self.dispatcher_factory = dispatcher_factory or LocalCommandDispatcher
        self._active: Dict[str, _DesktopRun] = {}

    @property
    def active_worker_ids(self) -> tuple[str, ...]:
        return tuple(self._active)

    @property
    def waiting_requests(self) -> Dict[str, Dict[str, Any]]:
        return {
            worker_id: {
                "reason": run.waiting_reason,
                "instruction": "请调用 takeover 接管当前桌面，完成必要操作后调用 resume。",
                "takeover": run.takeover_active,
            }
            for worker_id, run in self._active.items()
            if run.waiting_reason
        }

    async def __call__(self, worker_dir: Path) -> None:
        await self.run(worker_dir)

    def _create_host_agent(self, request: str) -> Any:
        if self.host_agent_factory:
            return self.host_agent_factory(request)

        from omniact_desktop.agents.agent.host_agent import HostAgent

        config = get_ufo_config()
        return HostAgent(
            name="HostAgent",
            is_visual=True,
            main_prompt=config.system.hostagent_prompt,
            example_prompt=_example_prompt_path(config),
            api_prompt=config.system.api_prompt,
        )

    async def run(self, worker_dir: Path) -> None:
        worker_dir = Path(worker_dir)
        worker_id = worker_dir.name
        request_path = worker_dir / "request.md"
        request = request_path.read_text(encoding="utf-8")
        artifacts_dir = worker_dir / "artifacts"
        log_dir = artifacts_dir / "ufo"
        log_dir.mkdir(parents=True, exist_ok=True)
        trace_path = worker_dir / "trace.jsonl"
        trajectory_path = worker_dir / "trajectory.md"
        trace_path.write_text("", encoding="utf-8")
        _append_trace(
            trace_path,
            "worker_started",
            worker_id=worker_id,
            request=request,
            engine="ufo2",
        )

        context = Context()
        context.set(ContextNames.ID, worker_id)
        context.set(ContextNames.MODE, "normal")
        context.set(ContextNames.LOG_PATH, str(log_dir) + os.sep)
        context.set(ContextNames.REQUEST, request)
        context.set(ContextNames.SESSION_STEP, 0)
        context.set(ContextNames.CURRENT_ROUND_ID, 0)
        context.set(ContextNames.CURRENT_ROUND_STEP, 0)
        context.set(ContextNames.PREVIOUS_SUBTASKS, [])
        context.set(ContextNames.HOST_MESSAGE, [])
        context.set(ContextNames.LOGGER, _ProcessorTraceWriter(trace_path))
        context.set(ContextNames.REQUEST_LOGGER, _TraceWriter(trace_path))

        session = _DesktopSession(id=worker_id, task=request, context=context)
        dispatcher = self.dispatcher_factory(session, MCPServerManager())
        context.attach_command_dispatcher(dispatcher)
        host_agent = self._create_host_agent(request)
        run = _DesktopRun(
            worker_id=worker_id,
            worker_dir=worker_dir,
            session=session,
            agent=host_agent,
            state=host_agent.state,
        )
        self._active[worker_id] = run

        status = "failed"
        message = "Desktop Worker stopped without a final result."
        try:
            for step in range(1, self.max_steps + 1):
                if run.cancel_event.is_set():
                    status = "cancelled"
                    message = "Desktop Worker was cancelled by the caller."
                    break

                if str(getattr(run.agent, "status", "")).upper() in {
                    "PENDING",
                    "CONFIRM",
                }:
                    await self._wait_for_user(run, str(run.agent.status).upper())
                    if run.cancel_event.is_set():
                        status = "cancelled"
                        message = "Desktop Worker was cancelled by the caller."
                        break
                    self._continue_after_takeover(run)

                run.session.current_agent_class = type(run.agent).__name__
                state_before = run.state
                await run.agent.handle(context)
                snapshot = self._agent_snapshot(run.agent)
                _append_trace(
                    trace_path,
                    "agent_turn",
                    step=step,
                    agent=type(run.agent).__name__,
                    state=type(state_before).__name__,
                    status=getattr(run.agent, "status", None),
                    snapshot=snapshot,
                )
                _render_trajectory(trace_path, trajectory_path)

                raw_agent_status = getattr(run.agent, "status", None)
                agent_status = str(raw_agent_status).upper() if raw_agent_status else ""
                if agent_status in {"ERROR", "FAIL"} or not agent_status:
                    status = "failed"
                    message = self._result_message(run.agent, context, status)
                    if not raw_agent_status:
                        message = "Desktop Agent failed without returning a valid status."
                    break
                if agent_status in {"PENDING", "CONFIRM"}:
                    await self._wait_for_user(run, agent_status)
                    if run.cancel_event.is_set():
                        status = "cancelled"
                        message = "Desktop Worker was cancelled by the caller."
                        break
                    self._continue_after_takeover(run)
                    continue

                next_agent = state_before.next_agent(run.agent)
                next_state = state_before.next_state(run.agent)
                if _is_terminal_host_state(next_agent, next_state, host_agent):
                    status = "completed" if agent_status == "FINISH" else "failed"
                    message = self._result_message(run.agent, context, status)
                    break

                run.agent = next_agent
                run.state = next_state
                run.agent.set_state(next_state)
            else:
                status = "failed"
                message = f"Desktop Worker reached max_steps={self.max_steps}."

            self._write_result(worker_dir, status, message)
            _append_trace(
                trace_path,
                "worker_finished",
                status=status,
                result_file="result.md",
            )
            _render_trajectory(trace_path, trajectory_path)
        except Exception as error:
            status = "failed"
            message = f"{type(error).__name__}: {error}"
            self._write_result(worker_dir, status, message)
            _append_trace(trace_path, "worker_failed", error=message)
            _render_trajectory(trace_path, trajectory_path)
            raise
        finally:
            self._active.pop(worker_id, None)

    async def _wait_for_user(self, run: _DesktopRun, reason: str) -> None:
        run.waiting_reason = reason
        instruction = (
            "Desktop Worker 已暂停。请通过 takeover 接管当前桌面并完成必要操作，"
            "完成后调用 resume。"
        )
        _append_trace(
            run.worker_dir / "trace.jsonl",
            "waiting_for_user",
            reason=reason,
            instruction=instruction,
        )
        _render_trajectory(
            run.worker_dir / "trace.jsonl", run.worker_dir / "trajectory.md"
        )
        await run.resume_event.wait()
        run.resume_event.clear()
        run.waiting_reason = None

    @staticmethod
    def _continue_after_takeover(run: _DesktopRun) -> None:
        run.agent.status = "CONTINUE"
        next_state = type(run.state)()
        run.state = next_state
        run.agent.set_state(next_state)
        run.takeover_active = False

    def _agent_snapshot(self, agent: Any) -> Dict[str, Any]:
        processor = getattr(agent, "processor", None)
        if processor is None:
            return {"status": getattr(agent, "status", None)}
        local = processor.processing_context.local_data
        keys = (
            "prompt_message",
            "response_text",
            "observation",
            "thought",
            "status",
            "function_name",
            "function_arguments",
            "subtask",
            "plan",
            "host_message",
            "result",
            "execution_result",
            "clean_screenshot_path",
            "annotated_screenshot_path",
            "ui_tree_path",
            "desktop_screenshot_path",
        )
        return {
            key: _clean_value(local[key])
            for key in keys
            if key in local and local[key] is not None
        }

    @staticmethod
    def _result_message(agent: Any, context: Context, status: str) -> str:
        processor = getattr(agent, "processor", None)
        if processor:
            result = processor.processing_context.get_local("result")
            if result:
                return str(result)
        result = context.get(ContextNames.ROUND_RESULT)
        return str(result) if result else f"Desktop Worker finished with status: {status}."

    @staticmethod
    def _write_result(worker_dir: Path, status: str, message: str) -> None:
        artifacts = sorted(
            path.relative_to(worker_dir).as_posix()
            for path in (worker_dir / "artifacts").rglob("*")
            if path.is_file()
        )
        artifact_lines = [f"- `{path}`" for path in artifacts] or ["- None"]
        lines = [
            "# Desktop Worker Result",
            "",
            f"Status: {status}",
            "",
            "## Handoff",
            "",
            message,
            "",
            "## Artifacts",
            "",
            *artifact_lines,
            "",
        ]
        (worker_dir / "result.md").write_text("\n".join(lines), encoding="utf-8")

    def _get_active(self, worker_id: str) -> _DesktopRun:
        run = self._active.get(worker_id)
        if run is None:
            raise ValueError(f"Desktop Worker is not active: {worker_id}")
        return run

    async def takeover(self, worker_id: str) -> None:
        run = self._get_active(worker_id)
        if not run.waiting_reason:
            raise ValueError(f"Desktop Worker is not waiting for user: {worker_id}")
        run.takeover_active = True
        _append_trace(run.worker_dir / "trace.jsonl", "takeover_started")
        _render_trajectory(run.worker_dir / "trace.jsonl", run.worker_dir / "trajectory.md")

    async def resume(self, worker_id: str) -> None:
        run = self._get_active(worker_id)
        if not run.waiting_reason:
            raise ValueError(f"Desktop Worker is not paused: {worker_id}")
        _append_trace(run.worker_dir / "trace.jsonl", "worker_resumed")
        run.resume_event.set()

    async def cancel(self, worker_id: str) -> None:
        run = self._get_active(worker_id)
        _append_trace(run.worker_dir / "trace.jsonl", "worker_cancelled")
        run.cancel_event.set()
        run.resume_event.set()

    async def close(self) -> None:
        for worker_id in list(self._active):
            await self.cancel(worker_id)


def _example_prompt_path(config: Any) -> str:
    if config.system.action_sequence:
        return config.system.appagent_example_prompt_as
    return config.system.appagent_example_prompt


def _is_terminal_host_state(next_agent: Any, next_state: Any, host_agent: Any) -> bool:
    from omniact_desktop.agents.states.host_agent_state import FinishHostAgentState

    return next_agent is host_agent and isinstance(next_state, FinishHostAgentState)


def _render_trajectory(trace_path: Path, trajectory_path: Path) -> None:
    events = [
        json.loads(line)
        for line in trace_path.read_text(encoding="utf-8").splitlines()
        if line
    ]
    lines = ["# Desktop Worker Trajectory", ""]
    for event in events:
        kind = event["event"]
        if kind == "worker_started":
            lines.extend(["## Task", "", event["request"], ""])
        elif kind == "llm_request":
            lines.extend(
                [
                    "### LLM Request",
                    "",
                    "```json",
                    json.dumps(event["payload"], ensure_ascii=False, indent=2),
                    "```",
                    "",
                ]
            )
        elif kind == "agent_turn":
            snapshot = event.get("snapshot", {})
            lines.extend(
                [
                    f"## Step {event['step']} - {event['agent']}",
                    "",
                    f"State: {event['state']}",
                    f"Status: {event.get('status')}",
                    "",
                ]
            )
            for key in ("observation", "thought", "response_text", "subtask", "result"):
                if snapshot.get(key):
                    lines.extend([f"### {key}", "", str(snapshot[key]), ""])
            for key in ("function_name", "function_arguments", "execution_result"):
                if snapshot.get(key):
                    lines.extend(
                        [
                            f"### {key}",
                            "",
                            "```json",
                            json.dumps(snapshot[key], ensure_ascii=False, indent=2),
                            "```",
                            "",
                        ]
                    )
        elif kind == "waiting_for_user":
            lines.extend(
                [
                    "## Waiting For User",
                    "",
                    f"Reason: {event['reason']}",
                    "",
                    event["instruction"],
                    "",
                ]
            )
        elif kind == "takeover_started":
            lines.extend(["## User Took Over", ""])
        elif kind == "worker_resumed":
            lines.extend(["## Worker Resumed", ""])
        elif kind == "worker_cancelled":
            lines.extend(["## Worker Cancelled", ""])
        elif kind == "worker_finished":
            lines.extend(["## Finished", "", f"Status: {event['status']}", ""])
        elif kind == "worker_failed":
            lines.extend(["## Worker Error", "", event["error"], ""])
    trajectory_path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


__all__ = ["DesktopWorker"]
