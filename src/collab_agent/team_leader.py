import json
from dataclasses import dataclass
from pathlib import Path
from typing import (
    Any,
    Awaitable,
    Callable,
    Dict,
    List,
    Mapping,
    Optional,
    Sequence,
    Tuple,
)

from .dispatch import (
    DispatchResult,
    WorkerTask,
    dispatch_parallel_worker,
    dispatch_worker,
)
from .model_client import ToolCall, parse_native_response
from .workspace import TaskWorkspace


WorkerRunner = Callable[[Path], Awaitable[None]]


TEAM_LEADER_SYSTEM_PROMPT = """你是 TeamLeader，一个负责完成用户任务的 React Agent。

你的职责是理解用户目标、生成验收标准、拆解任务、选择合适的执行方式、整合结果并完成最终交付。

规则：
1. 第一轮必须调用 set_task_criteria，然后调用 set_task_plan。
2. Plan 只描述子任务目标、依赖关系和交接要求，不预先指定 Worker 类型。
3. 执行子任务时，根据任务性质选择 browser、cli 或 desktop Worker，也可以自己完成。
4. 有前后依赖时使用 dispatch_worker；多个互不依赖且当前可执行的子任务使用 dispatch_parallel_worker。
   配置了 CLI Worker 时，启动新的 Codex 会话使用 dispatch_cli_worker，继续已有会话使用 resume_cli_worker。
5. 每轮最多调用一个工具。派发后等待工具返回，不查询 Worker 状态。
6. 不要伪造 Worker 结果。根据工具返回的真实结果决定下一步。
7. 任务完成或无法继续时，必须调用 finish_task。普通文本不会结束任务。

决策示例：
- 用户只要求解释一个概念：set_task_criteria -> set_task_plan（一个直接回答的子任务）-> finish_task。
- 用户要求先收集资料再写报告：set_task_criteria -> set_task_plan（报告依赖资料）-> dispatch_worker（资料）-> 收到结果 -> dispatch_worker（报告）-> finish_task。
- 用户要求分别调研 A、B 再汇总：set_task_criteria -> set_task_plan（A、B 无依赖，汇总依赖 A、B）-> dispatch_parallel_worker（A、B）-> 收到整组结果 -> 完成汇总 -> finish_task。
"""


TEAM_LEADER_TOOLS: List[Dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "set_task_criteria",
            "description": "保存用户任务的整体验收标准。必须在其他 TeamLeader 工具之前调用。",
            "parameters": {
                "type": "object",
                "properties": {
                    "content": {"type": "string", "description": "Markdown 验收标准"}
                },
                "required": ["content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "set_task_plan",
            "description": "在内存中创建或完整替换当前 Plan。Plan 不指定 Worker 类型。",
            "parameters": {
                "type": "object",
                "properties": {
                    "steps": {
                        "type": "array",
                        "description": "一层子任务列表",
                        "items": {
                            "type": "object",
                            "properties": {
                                "id": {"type": "string"},
                                "goal": {"type": "string"},
                                "depends_on": {
                                    "type": "array",
                                    "items": {"type": "string"},
                                },
                                "handoff": {"type": "string"},
                            },
                            "required": ["id", "goal", "depends_on", "handoff"],
                        },
                    }
                },
                "required": ["steps"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "dispatch_worker",
            "description": "只派发一个当前可执行的子任务，适合单个任务或有前后依赖的任务。调用后等待 Worker 返回。",
            "parameters": {
                "type": "object",
                "properties": {
                    "subtask_id": {
                        "type": "string",
                        "description": "当前 Plan 中的子任务 id",
                    },
                    "worker_type": {
                        "type": "string",
                        "description": "任务上下文中列出的可用 Worker 类型",
                    },
                    "request": {
                        "type": "string",
                        "description": "完整的 Worker 执行说明",
                    },
                },
                "required": ["subtask_id", "worker_type", "request"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "dispatch_parallel_worker",
            "description": "同时派发两个或以上互不依赖且当前可执行的子任务，并等待整组 Worker 返回。",
            "parameters": {
                "type": "object",
                "properties": {
                    "tasks": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "subtask_id": {"type": "string"},
                                "worker_type": {"type": "string"},
                                "request": {"type": "string"},
                            },
                            "required": ["subtask_id", "worker_type", "request"],
                        },
                    }
                },
                "required": ["tasks"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "finish_task",
            "description": "写入最终 Markdown 结果并结束 TeamLeader。只有这个工具可以结束任务。",
            "parameters": {
                "type": "object",
                "properties": {
                    "content": {
                        "type": "string",
                        "description": "面向用户的最终 Markdown",
                    }
                },
                "required": ["content"],
            },
        },
    },
]


CLI_WORKER_TOOLS: List[Dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "dispatch_cli_worker",
            "description": "启动一个新的 Codex CLI Worker 会话，完成指定子任务并返回交接结果。",
            "parameters": {
                "type": "object",
                "properties": {
                    "subtask_id": {
                        "type": "string",
                        "description": "当前 Plan 中的子任务 id",
                    },
                    "request": {
                        "type": "string",
                        "description": "给 Codex CLI Worker 的完整执行说明",
                    },
                    "acceptance_criteria": {
                        "type": "string",
                        "description": "该子任务必须满足的验收标准",
                    },
                },
                "required": ["subtask_id", "request", "acceptance_criteria"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "resume_cli_worker",
            "description": "使用已有 Codex session 继续 CLI Worker，适合失败修复或用户反馈后的增量修改。",
            "parameters": {
                "type": "object",
                "properties": {
                    "subtask_id": {
                        "type": "string",
                        "description": "已有 CLI Worker 对应的 Plan 子任务 id",
                    },
                    "session_id": {
                        "type": "string",
                        "description": "session.json 中保存的 Codex thread_id",
                    },
                    "follow_up_request": {
                        "type": "string",
                        "description": "需要 Codex 在原会话中继续执行的反馈或修改要求",
                    },
                },
                "required": ["subtask_id", "session_id", "follow_up_request"],
            },
        },
    },
]


class ToolInputError(ValueError):
    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind
        self.message = message


@dataclass
class PlanStep:
    step_id: str
    goal: str
    depends_on: List[str]
    handoff: str
    status: str = "pending"
    worker_type: Optional[str] = None


class PlanState:
    """In-memory task plan and the small amount of state derived from dispatch results."""

    def __init__(self):
        self.steps: Dict[str, PlanStep] = {}

    def replace(self, raw_steps: Any) -> None:
        if not isinstance(raw_steps, list) or not raw_steps:
            raise ToolInputError("parameter_error", "steps must be a non-empty list")

        parsed: Dict[str, PlanStep] = {}
        for raw in raw_steps:
            if not isinstance(raw, dict):
                raise ToolInputError(
                    "parameter_error", "each plan step must be an object"
                )
            step_id = raw.get("id")
            goal = raw.get("goal")
            depends_on = raw.get("depends_on")
            handoff = raw.get("handoff")
            if (
                not isinstance(step_id, str)
                or not step_id
                or step_id in {".", ".."}
                or "/" in step_id
                or "\\" in step_id
            ):
                raise ToolInputError(
                    "parameter_error", "plan step id must be a simple non-empty name"
                )
            if step_id in parsed:
                raise ToolInputError(
                    "parameter_error", f"duplicate plan step id: {step_id}"
                )
            if not isinstance(goal, str) or not goal:
                raise ToolInputError(
                    "parameter_error", f"goal is required for {step_id}"
                )
            if not isinstance(depends_on, list) or not all(
                isinstance(item, str) for item in depends_on
            ):
                raise ToolInputError(
                    "parameter_error", f"depends_on must be a list of ids for {step_id}"
                )
            if not isinstance(handoff, str) or not handoff:
                raise ToolInputError(
                    "parameter_error", f"handoff is required for {step_id}"
                )

            previous = self.steps.get(step_id)
            parsed[step_id] = PlanStep(
                step_id=step_id,
                goal=goal,
                depends_on=list(depends_on),
                handoff=handoff,
                status=previous.status if previous else "pending",
                worker_type=previous.worker_type if previous else None,
            )

        for step in parsed.values():
            for dependency in step.depends_on:
                if dependency not in parsed:
                    raise ToolInputError(
                        "parameter_error",
                        f"unknown dependency {dependency} in {step.step_id}",
                    )
                if dependency == step.step_id:
                    raise ToolInputError(
                        "parameter_error",
                        f"step cannot depend on itself: {step.step_id}",
                    )

        self._ensure_acyclic(parsed)
        self.steps = parsed

    @staticmethod
    def _ensure_acyclic(steps: Mapping[str, PlanStep]) -> None:
        visiting = set()
        visited = set()

        def visit(step_id: str) -> None:
            if step_id in visiting:
                raise ToolInputError(
                    "parameter_error", "plan dependencies contain a cycle"
                )
            if step_id in visited:
                return
            visiting.add(step_id)
            for dependency in steps[step_id].depends_on:
                visit(dependency)
            visiting.remove(step_id)
            visited.add(step_id)

        for step_id in steps:
            visit(step_id)

    def get(self, step_id: str) -> PlanStep:
        step = self.steps.get(step_id)
        if step is None:
            raise ToolInputError(
                "unknown_subtask_id",
                f"subtask_id does not exist in the current Plan: {step_id}",
            )
        return step

    def ensure_ready(self, step_id: str) -> PlanStep:
        step = self.get(step_id)
        if step.status != "pending":
            raise ToolInputError(
                "subtask_not_pending", f"subtask is already {step.status}: {step_id}"
            )
        blocked = [
            dependency
            for dependency in step.depends_on
            if self.steps[dependency].status != "completed"
        ]
        if blocked:
            raise ToolInputError(
                "dependency_not_ready",
                f"subtask {step_id} is blocked by: {', '.join(blocked)}",
            )
        return step

    def ensure_parallel(self, step_ids: Sequence[str]) -> None:
        selected = set(step_ids)
        if len(selected) != len(step_ids):
            raise ToolInputError(
                "parameter_error", "parallel tasks must have distinct subtask_id values"
            )
        for step_id in step_ids:
            step = self.ensure_ready(step_id)
            if any(dependency in selected for dependency in step.depends_on):
                raise ToolInputError(
                    "dependency_not_ready",
                    "parallel tasks must not depend on each other",
                )

    def mark_result(self, result: DispatchResult, worker_type: str) -> None:
        step = self.get(result.worker_name)
        step.status = "completed" if result.success else "failed"
        step.worker_type = worker_type

    def status(self, step_id: str) -> str:
        step = self.get(step_id)
        if step.status != "pending":
            return step.status
        blocked = [
            dependency
            for dependency in step.depends_on
            if self.steps[dependency].status != "completed"
        ]
        if blocked:
            return "blocked_by " + ", ".join(blocked)
        return "ready"

    def snapshot(self) -> str:
        lines = ["[CURRENT_PLAN]"]
        for step in self.steps.values():
            state = self.status(step.step_id)
            if step.worker_type and state in {"completed", "failed"}:
                state = f"{state} via {step.worker_type}"
            lines.append(f"- {step.step_id}: {state}")
        return "\n".join(lines)


class TeamLeader:
    """Run the TeamLeader React loop with native tool calls."""

    def __init__(
        self,
        model_client: Any,
        worker_runners: Optional[Mapping[str, WorkerRunner]] = None,
        max_turns: int = 20,
    ):
        self.model_client = model_client
        self.worker_runners = dict(worker_runners or {})
        self.max_turns = max_turns
        self.plan = PlanState()
        self._criteria_set = False
        self._workspace: Optional[TaskWorkspace] = None
        self._user_request = ""
        self._messages: List[Dict[str, Any]] = []
        self._turn = 0

    def available_tools(self) -> List[Dict[str, Any]]:
        """Return only tools backed by the Worker types configured for this run."""
        tools = list(TEAM_LEADER_TOOLS)
        if "cli" in self.worker_runners:
            tools.extend(CLI_WORKER_TOOLS)
        return tools

    def plan_status(self, step_id: str) -> str:
        return self.plan.status(step_id)

    async def run(self, workspace: TaskWorkspace, user_request: str) -> str:
        if not isinstance(user_request, str) or not user_request:
            raise ValueError("user_request must be provided")

        self.plan = PlanState()
        self._criteria_set = False
        self._workspace = workspace
        self._user_request = user_request
        self._turn = 0
        workspace.write_task(
            f"# User Request\n\n{user_request}\n\n# Inputs\n\n{self._input_listing()}"
        )
        self._messages = [
            {"role": "system", "content": TEAM_LEADER_SYSTEM_PROMPT},
            {"role": "user", "content": self._task_context()},
        ]
        self._trace(
            "task_started",
            user_request=user_request,
            task_dir=str(workspace.task_dir),
            inputs=self._input_listing(),
        )

        try:
            for self._turn in range(1, self.max_turns + 1):
                tools = self.available_tools()
                self._trace(
                    "model_request",
                    model=getattr(self.model_client, "model", None),
                    messages=self._messages,
                    tools=tools,
                )
                try:
                    response = await self.model_client.complete(
                        self._messages, tools
                    )
                except Exception as exc:
                    self._trace(
                        "model_error",
                        error_type=type(exc).__name__,
                        error=str(exc),
                    )
                    raise

                turn = parse_native_response(response)
                self._trace(
                    "model_response",
                    kind=turn.kind,
                    visible_text=turn.visible_text,
                    error=turn.error,
                )
                if turn.kind == "invalid":
                    self._messages.append(
                        {
                            "role": "user",
                            "content": f"[MODEL_OUTPUT_ERROR]\n{turn.error}\n请重新生成一个合法的单工具调用。",
                        }
                    )
                    continue
                if turn.kind == "text":
                    self._messages.append(
                        {"role": "assistant", "content": turn.visible_text or ""}
                    )
                    self._messages.append(
                        {
                            "role": "user",
                            "content": "请继续执行任务。必须调用一个合适的工具；任务完成时调用 finish_task。",
                        }
                    )
                    continue

                tool_call = turn.tool_call
                assert tool_call is not None
                self._trace(
                    "tool_call",
                    call_id=tool_call.call_id,
                    name=tool_call.name,
                    arguments=tool_call.arguments,
                )
                self._messages.append(
                    self._assistant_tool_message(turn.visible_text, tool_call)
                )
                result_text, final_content = await self._execute_tool(tool_call)
                self._trace(
                    "tool_result",
                    name=tool_call.name,
                    observation=result_text,
                    final_content=final_content,
                )
                if final_content is not None:
                    self._trace(
                        "task_finished",
                        result_file="final/result.md",
                        content=final_content,
                    )
                    self._write_trajectory()
                    return final_content
                self._messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": tool_call.call_id,
                        "name": tool_call.name,
                        "content": result_text,
                    }
                )
        except Exception as exc:
            self._trace(
                "task_failed",
                error_type=type(exc).__name__,
                error=str(exc),
            )
            self._write_trajectory()
            raise

        error = RuntimeError("TeamLeader reached max_turns without calling finish_task")
        self._trace("task_failed", error_type=type(error).__name__, error=str(error))
        self._write_trajectory()
        raise error

    def _trace(self, event: str, **details: Any) -> None:
        self._require_ready_workspace()
        self._workspace.append_trace(event, turn=self._turn, **details)

    def _write_trajectory(self) -> None:
        self._require_ready_workspace()
        lines = ["# TeamLeader Trajectory", ""]
        for event in self._workspace.read_trace():
            self._append_trajectory_event(lines, event)
        self._workspace.write_trajectory("\n".join(lines).rstrip() + "\n")

    @staticmethod
    def _append_trajectory_event(lines: List[str], event: Mapping[str, Any]) -> None:
        event_name = event["event"]
        turn = event.get("turn")
        if event_name == "task_started":
            lines.extend(
                [
                    "## Task",
                    "",
                    "### User Request",
                    "",
                    event["user_request"],
                    "",
                ]
            )
            return
        if event_name == "model_request":
            request = {
                "model": event.get("model"),
                "messages": event["messages"],
                "tools": event["tools"],
            }
            lines.extend(
                [
                    f"## Turn {turn} - Model Request",
                    "",
                    "```json",
                    json.dumps(request, ensure_ascii=False, indent=2),
                    "```",
                    "",
                ]
            )
            return
        if event_name == "model_response":
            lines.extend(["### Assistant Response", "", f"kind: {event['kind']}", ""])
            lines.extend(
                [
                    "#### Visible Text",
                    "",
                    event.get("visible_text") or "（无可见文本）",
                    "",
                ]
            )
            if event.get("error"):
                lines.extend(["#### Error", "", event["error"], ""])
            return
        if event_name == "tool_call":
            lines.extend(
                [
                    "### Action",
                    "",
                    f"`{event['name']}`",
                    "",
                    "```json",
                    json.dumps(event["arguments"], ensure_ascii=False, indent=2),
                    "```",
                    "",
                ]
            )
            return
        if event_name == "tool_result":
            lines.extend(["### Observation", "", event["observation"] or "", ""])
            if event.get("final_content") is not None:
                lines.extend(["### Final Content", "", event["final_content"], ""])
            return
        if event_name == "model_error":
            lines.extend(
                [
                    f"## Turn {turn} - Model Error",
                    "",
                    f"{event['error_type']}: {event['error']}",
                    "",
                ]
            )
            return
        if event_name == "task_finished":
            lines.extend(
                [
                    "## Task Finished",
                    "",
                    f"Result file: `{event['result_file']}`",
                    "",
                ]
            )
            return
        if event_name == "task_failed":
            lines.extend(
                [
                    "## Task Failed",
                    "",
                    f"{event['error_type']}: {event['error']}",
                    "",
                ]
            )

    def _task_context(self) -> str:
        assert self._workspace is not None
        worker_types = ", ".join(sorted(self.worker_runners)) or "无"
        return (
            f"用户任务：\n{self._user_request}\n\n"
            f"当前 Task 目录：{self._workspace.task_dir}\n"
            f"用户附件：\n{self._input_listing()}\n\n"
            f"当前可用 Worker 类型：{worker_types}\n"
            "如果没有可用 Worker，请由 TeamLeader 直接完成适合的任务，不要调用不存在的 Worker。\n\n"
            "请先设置验收标准和当前任务 Plan。"
        )

    def _input_listing(self) -> str:
        assert self._workspace is not None
        input_files = sorted(
            path.name for path in self._workspace.inputs_dir.iterdir() if path.is_file()
        )
        return "\n".join(f"- inputs/{name}" for name in input_files) or "- 无附件"

    @staticmethod
    def _assistant_tool_message(
        visible_text: Optional[str], tool_call: ToolCall
    ) -> Dict[str, Any]:
        return {
            "role": "assistant",
            "content": visible_text,
            "tool_calls": [
                {
                    "id": tool_call.call_id,
                    "type": "function",
                    "function": {
                        "name": tool_call.name,
                        "arguments": json.dumps(
                            tool_call.arguments, ensure_ascii=False
                        ),
                    },
                }
            ],
        }

    async def _execute_tool(self, tool_call: ToolCall) -> Tuple[str, Optional[str]]:
        try:
            if tool_call.name == "set_task_criteria":
                return self._set_criteria(tool_call.arguments), None
            if tool_call.name == "set_task_plan":
                return self._set_plan(tool_call.arguments), None
            if tool_call.name == "dispatch_worker":
                return await self._dispatch_one(tool_call.arguments), None
            if tool_call.name == "dispatch_parallel_worker":
                return await self._dispatch_many(tool_call.arguments), None
            if tool_call.name == "dispatch_cli_worker":
                return await self._dispatch_cli(tool_call.arguments), None
            if tool_call.name == "resume_cli_worker":
                return await self._resume_cli(tool_call.arguments), None
            if tool_call.name == "finish_task":
                content = self._finish_task(tool_call.arguments)
                return "", content
            raise ToolInputError(
                "unknown_tool", f"unknown TeamLeader tool: {tool_call.name}"
            )
        except ToolInputError as exc:
            return f"[TOOL_ERROR]\nerror_kind: {exc.kind}\nmessage: {exc.message}", None
        except Exception as exc:
            return (
                f"[TOOL_ERROR]\nerror_kind: tool_execution_error\nmessage: {exc}",
                None,
            )

    def _set_criteria(self, arguments: Mapping[str, Any]) -> str:
        self._require_ready_workspace()
        content = arguments.get("content")
        if not isinstance(content, str) or not content:
            raise ToolInputError(
                "parameter_error", "content must be a non-empty string"
            )
        self._workspace.write_task(
            f"# User Request\n\n{self._user_request}\n\n"
            f"# Inputs\n\n{self._input_listing()}\n\n"
            f"# Acceptance Criteria\n\n{content}"
        )
        self._criteria_set = True
        return "[CRITERIA_SAVED]\nfile: task.md"

    def _set_plan(self, arguments: Mapping[str, Any]) -> str:
        if not self._criteria_set:
            raise ToolInputError(
                "criteria_required", "call set_task_criteria before set_task_plan"
            )
        self.plan.replace(arguments.get("steps"))
        return "[PLAN_UPDATED]\n" + self.plan.snapshot()

    async def _dispatch_one(self, arguments: Mapping[str, Any]) -> str:
        self._require_plan()
        subtask_id = self._string_argument(arguments, "subtask_id")
        worker_type = self._string_argument(arguments, "worker_type")
        request = self._string_argument(arguments, "request")
        step = self.plan.ensure_ready(subtask_id)
        runner = self._runner(worker_type)
        result = await dispatch_worker(self._workspace, step.step_id, request, runner)
        self.plan.mark_result(result, worker_type)
        return self._format_result(result, worker_type) + "\n" + self.plan.snapshot()

    async def _dispatch_many(self, arguments: Mapping[str, Any]) -> str:
        self._require_plan()
        raw_tasks = arguments.get("tasks")
        if not isinstance(raw_tasks, list) or len(raw_tasks) < 2:
            raise ToolInputError(
                "parameter_error", "tasks must contain at least two items"
            )

        tasks: List[WorkerTask] = []
        worker_types: Dict[str, str] = {}
        subtask_ids: List[str] = []
        for raw in raw_tasks:
            if not isinstance(raw, dict):
                raise ToolInputError(
                    "parameter_error", "each parallel task must be an object"
                )
            subtask_id = self._string_argument(raw, "subtask_id")
            worker_type = self._string_argument(raw, "worker_type")
            request = self._string_argument(raw, "request")
            subtask_ids.append(subtask_id)
            worker_types[subtask_id] = worker_type
            tasks.append(
                WorkerTask(
                    worker_name=subtask_id,
                    request=request,
                    runner=self._runner(worker_type),
                )
            )
        self.plan.ensure_parallel(subtask_ids)
        results = await dispatch_parallel_worker(self._workspace, tasks)
        for result in results:
            self.plan.mark_result(result, worker_types[result.worker_name])

        formatted = [
            self._format_result(result, worker_types[result.worker_name])
            for result in results
        ]
        return "\n\n".join(formatted) + "\n" + self.plan.snapshot()

    async def _dispatch_cli(self, arguments: Mapping[str, Any]) -> str:
        self._require_plan()
        subtask_id = self._string_argument(arguments, "subtask_id")
        request = self._string_argument(arguments, "request")
        acceptance = self._string_argument(arguments, "acceptance_criteria")
        step = self.plan.ensure_ready(subtask_id)
        runner = self._runner("cli")
        request_with_criteria = (
            f"{request}\n\n## Acceptance Criteria\n\n{acceptance}"
        )
        result = await dispatch_worker(
            self._workspace,
            step.step_id,
            request_with_criteria,
            runner,
        )
        self.plan.mark_result(result, "cli")
        return self._format_result(result, "cli") + "\n" + self.plan.snapshot()

    async def _resume_cli(self, arguments: Mapping[str, Any]) -> str:
        self._require_plan()
        subtask_id = self._string_argument(arguments, "subtask_id")
        session_id = self._string_argument(arguments, "session_id")
        follow_up = self._string_argument(arguments, "follow_up_request")
        step = self.plan.get(subtask_id)
        runner = self._runner("cli")
        resume = getattr(runner, "resume", None)
        if resume is None:
            raise ToolInputError(
                "worker_not_resumable", "configured CLI Worker cannot resume a session"
            )

        worker_dir = self._workspace.worker_dir(step.step_id)
        result_path = self._workspace.result_path(step.step_id)
        try:
            await resume(worker_dir, session_id, follow_up)
        except Exception as exc:
            result = DispatchResult(
                worker_name=step.step_id,
                result_path=result_path,
                error_kind="worker_error",
                error=str(exc),
            )
        else:
            if not result_path.is_file():
                result = DispatchResult(
                    worker_name=step.step_id,
                    result_path=result_path,
                    error_kind="result_missing",
                    error="Worker finished without creating result.md",
                )
            else:
                content = result_path.read_text(encoding="utf-8")
                status = "completed"
                for line in content.splitlines():
                    if line.startswith("Status:"):
                        status = line.partition(":")[2].strip().lower()
                        break
                result = DispatchResult(
                    worker_name=step.step_id,
                    result_path=result_path,
                    result_content=content,
                    task_status=status,
                )

        self.plan.mark_result(result, "cli")
        return self._format_result(result, "cli") + "\n" + self.plan.snapshot()

    def _finish_task(self, arguments: Mapping[str, Any]) -> str:
        self._require_plan()
        content = arguments.get("content")
        if not isinstance(content, str) or not content:
            raise ToolInputError(
                "parameter_error", "content must be a non-empty string"
            )
        self._workspace.write_final_result(content)
        return content

    def _require_plan(self) -> None:
        self._require_ready_workspace()
        if not self._criteria_set:
            raise ToolInputError(
                "criteria_required", "call set_task_criteria before other tools"
            )
        if not self.plan.steps:
            raise ToolInputError(
                "plan_required", "call set_task_plan before dispatching or finishing"
            )

    def _require_ready_workspace(self) -> None:
        if self._workspace is None:
            raise ToolInputError("runtime_error", "TeamLeader is not running a task")

    def _runner(self, worker_type: str) -> WorkerRunner:
        runner = self.worker_runners.get(worker_type)
        if runner is None:
            raise ToolInputError(
                "unknown_worker_type", f"unknown worker_type: {worker_type}"
            )
        return runner

    @staticmethod
    def _string_argument(arguments: Mapping[str, Any], name: str) -> str:
        value = arguments.get(name)
        if not isinstance(value, str) or not value:
            raise ToolInputError(
                "parameter_error", f"{name} must be a non-empty string"
            )
        return value

    @staticmethod
    def _format_result(result: DispatchResult, worker_type: str) -> str:
        result_file = f"workers/{result.worker_name}/result.md"
        if result.success:
            return (
                "[WORKER_COMPLETED]\n"
                f"subtask_id: {result.worker_name}\n"
                f"worker_type: {worker_type}\n"
                f"result_file: {result_file}\n\n"
                f"{result.result_content or ''}"
            )
        if result.error_kind is None:
            return (
                "[WORKER_FAILED]\n"
                f"subtask_id: {result.worker_name}\n"
                f"worker_type: {worker_type}\n"
                f"result_file: {result_file}\n\n"
                f"{result.result_content or ''}"
            )
        return (
            "[WORKER_ERROR]\n"
            f"subtask_id: {result.worker_name}\n"
            f"worker_type: {worker_type}\n"
            f"error_kind: {result.error_kind}\n"
            f"message: {result.error or ''}"
        )
