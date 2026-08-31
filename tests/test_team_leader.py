import asyncio
import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
from unittest.mock import AsyncMock

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from collab_agent.model_client import parse_native_response
from collab_agent.model_client import OpenAIModelClient
from collab_agent.team_leader import TeamLeader
from collab_agent.workspace import TaskWorkspace


def tool_response(name, arguments, call_id="call-1", content=None):
    return {
        "choices": [
            {
                "message": {
                    "content": content,
                    "tool_calls": [
                        {
                            "id": call_id,
                            "type": "function",
                            "function": {
                                "name": name,
                                "arguments": json.dumps(arguments),
                            },
                        }
                    ],
                }
            }
        ]
    }


class FakeModel:
    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []

    async def complete(self, messages, tools):
        self.requests.append((messages, tools))
        return self.responses.pop(0)


class TeamLeaderTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp_dir = TemporaryDirectory()
        self.workspace = TaskWorkspace.create(self.temp_dir.name, "task-001")

    def tearDown(self):
        self.temp_dir.cleanup()

    async def test_direct_task_sets_criteria_and_plan_before_finishing(self):
        input_file = Path(self.temp_dir.name) / "notes.txt"
        input_file.write_text("context", encoding="utf-8")
        self.workspace.copy_input(input_file)
        model = FakeModel(
            [
                tool_response(
                    "set_task_criteria",
                    {"content": "最终回答应为简洁的 Markdown。"},
                ),
                tool_response(
                    "set_task_plan",
                    {
                        "steps": [
                            {
                                "id": "answer",
                                "goal": "直接生成最终回答",
                                "depends_on": [],
                                "handoff": "交付给用户",
                            }
                        ]
                    },
                ),
                tool_response(
                    "finish_task",
                    {"content": "# 完成\n\n这是最终答案。"},
                ),
            ]
        )
        leader = TeamLeader(model)

        result = await leader.run(self.workspace, "请直接回答这个问题。")

        self.assertEqual(result, "# 完成\n\n这是最终答案。")
        self.assertEqual(
            self.workspace.final_result_path.read_text(encoding="utf-8"),
            result,
        )
        task_content = self.workspace.task_path.read_text(encoding="utf-8")
        self.assertIn("请直接回答这个问题。", task_content)
        self.assertIn("inputs/notes.txt", task_content)
        self.assertIn("最终回答应为简洁的 Markdown。", task_content)
        self.assertEqual(len(model.requests), 3)
        self.assertEqual(
            {tool["function"]["name"] for tool in model.requests[0][1]},
            {
                "set_task_criteria",
                "set_task_plan",
                "dispatch_worker",
                "dispatch_parallel_worker",
                "finish_task",
            },
        )

    async def test_run_writes_complete_trace_and_readable_trajectory(self):
        model = FakeModel(
            [
                tool_response(
                    "set_task_criteria",
                    {"content": "最终回答必须准确。"},
                    content="我先明确验收标准。",
                ),
                tool_response(
                    "set_task_plan",
                    {
                        "steps": [
                            {
                                "id": "answer",
                                "goal": "直接生成答案",
                                "depends_on": [],
                                "handoff": "交付给用户",
                            }
                        ]
                    },
                    content="接着建立一个直接回答的计划。",
                ),
                tool_response(
                    "finish_task",
                    {"content": "这是最终答案。"},
                    content="验收完成，交付结果。",
                ),
            ]
        )

        result = await TeamLeader(model).run(self.workspace, "请回答一个简单问题。")

        events = [
            json.loads(line)
            for line in self.workspace.trace_path.read_text(
                encoding="utf-8"
            ).splitlines()
        ]
        self.assertEqual(
            [event["event"] for event in events],
            [
                "task_started",
                "model_request",
                "model_response",
                "tool_call",
                "tool_result",
                "model_request",
                "model_response",
                "tool_call",
                "tool_result",
                "model_request",
                "model_response",
                "tool_call",
                "tool_result",
                "task_finished",
            ],
        )
        self.assertEqual(events[2]["visible_text"], "我先明确验收标准。")
        self.assertEqual(events[3]["name"], "set_task_criteria")
        self.assertIn("CRITERIA_SAVED", events[4]["observation"])
        self.assertIn("tool", events[5]["messages"][-1]["role"])
        self.assertEqual(events[-1]["result_file"], "final/result.md")
        self.assertEqual(events[-1]["content"], result)

        trajectory = self.workspace.trajectory_path.read_text(encoding="utf-8")
        self.assertIn("# TeamLeader Trajectory", trajectory)
        self.assertIn("我先明确验收标准。", trajectory)
        self.assertIn("set_task_plan", trajectory)
        self.assertIn("这是最终答案。", trajectory)

    async def test_model_error_writes_failed_trace_and_trajectory(self):
        class FailingModel:
            async def complete(self, messages, tools):
                raise RuntimeError("model unavailable")

        with self.assertRaisesRegex(RuntimeError, "model unavailable"):
            await TeamLeader(FailingModel()).run(self.workspace, "请完成任务。")

        events = [
            json.loads(line)
            for line in self.workspace.trace_path.read_text(
                encoding="utf-8"
            ).splitlines()
        ]
        self.assertEqual(
            [event["event"] for event in events],
            ["task_started", "model_request", "model_error", "task_failed"],
        )
        trajectory = self.workspace.trajectory_path.read_text(encoding="utf-8")
        self.assertIn("model unavailable", trajectory)

    async def test_single_worker_result_updates_plan_automatically(self):
        async def browser_runner(worker_dir):
            self.assertEqual(
                (worker_dir / "request.md").read_text(encoding="utf-8"),
                "调研 A 公司并交接结论。",
            )
            (worker_dir / "result.md").write_text("A 公司调研结果。", encoding="utf-8")

        model = FakeModel(
            [
                tool_response("set_task_criteria", {"content": "包含 A 公司结论。"}),
                tool_response(
                    "set_task_plan",
                    {
                        "steps": [
                            {
                                "id": "research_a",
                                "goal": "调研 A 公司",
                                "depends_on": [],
                                "handoff": "写入 result.md",
                            }
                        ]
                    },
                ),
                tool_response(
                    "dispatch_worker",
                    {
                        "subtask_id": "research_a",
                        "worker_type": "browser",
                        "request": "调研 A 公司并交接结论。",
                    },
                ),
                tool_response("finish_task", {"content": "已完成 A 公司调研。"}),
            ]
        )
        leader = TeamLeader(model, worker_runners={"browser": browser_runner})

        result = await leader.run(self.workspace, "调研 A 公司。")

        self.assertEqual(result, "已完成 A 公司调研。")
        self.assertEqual(leader.plan_status("research_a"), "completed")
        tool_messages = [
            message
            for request in model.requests
            for message in request[0]
            if message.get("role") == "tool"
        ]
        self.assertTrue(
            any(
                "research_a: completed" in message["content"]
                for message in tool_messages
            )
        )

    async def test_parallel_workers_update_each_plan_step(self):
        async def runner(worker_dir):
            (worker_dir / "result.md").write_text(
                f"{worker_dir.name} result", encoding="utf-8"
            )

        model = FakeModel(
            [
                tool_response("set_task_criteria", {"content": "完成 A、B 调研。"}),
                tool_response(
                    "set_task_plan",
                    {
                        "steps": [
                            {
                                "id": "a",
                                "goal": "调研 A",
                                "depends_on": [],
                                "handoff": "交接 A",
                            },
                            {
                                "id": "b",
                                "goal": "调研 B",
                                "depends_on": [],
                                "handoff": "交接 B",
                            },
                            {
                                "id": "summary",
                                "goal": "汇总 A、B",
                                "depends_on": ["a", "b"],
                                "handoff": "最终交付",
                            },
                        ]
                    },
                ),
                tool_response(
                    "dispatch_parallel_worker",
                    {
                        "tasks": [
                            {
                                "subtask_id": "a",
                                "worker_type": "browser",
                                "request": "调研 A",
                            },
                            {
                                "subtask_id": "b",
                                "worker_type": "browser",
                                "request": "调研 B",
                            },
                        ]
                    },
                ),
                tool_response("finish_task", {"content": "A、B 已汇总。"}),
            ]
        )
        leader = TeamLeader(model, worker_runners={"browser": runner})

        await leader.run(self.workspace, "并行调研 A 和 B。")

        self.assertEqual(leader.plan_status("a"), "completed")
        self.assertEqual(leader.plan_status("b"), "completed")
        self.assertEqual(leader.plan_status("summary"), "ready")

    async def test_complete_cross_worker_chain_uses_parallel_results(self):
        active_browser_workers = 0
        max_active_browser_workers = 0
        browser_started = asyncio.Event()

        async def browser_runner(worker_dir):
            nonlocal active_browser_workers, max_active_browser_workers
            active_browser_workers += 1
            max_active_browser_workers = max(
                max_active_browser_workers, active_browser_workers
            )
            browser_started.set()
            await asyncio.sleep(0.02)
            (worker_dir / "result.md").write_text(
                f"Status: completed\n\n{worker_dir.name} research result.",
                encoding="utf-8",
            )
            active_browser_workers -= 1

        async def cli_runner(worker_dir):
            await browser_started.wait()
            task_dir = worker_dir.parents[1]
            source_paths = (
                task_dir / "workers" / "research-browser-use" / "result.md",
                task_dir / "workers" / "research-ufo2" / "result.md",
            )
            source_text = "\n".join(
                path.read_text(encoding="utf-8") for path in source_paths
            )
            self.assertIn("research-browser-use research result.", source_text)
            self.assertIn("research-ufo2 research result.", source_text)
            artifact = worker_dir / "artifacts" / "comparison.md"
            artifact.parent.mkdir(parents=True, exist_ok=True)
            artifact.write_text(
                "# Browser Agent Comparison\n\n" + source_text,
                encoding="utf-8",
            )
            (worker_dir / "result.md").write_text(
                "Status: completed\n\n"
                "Read both Browser Worker result.md files and wrote "
                "artifacts/comparison.md.",
                encoding="utf-8",
            )

        model = FakeModel(
            [
                tool_response(
                    "set_task_criteria",
                    {"content": "比较报告必须引用两个调研结果并生成 Markdown。"},
                ),
                tool_response(
                    "set_task_plan",
                    {
                        "steps": [
                            {
                                "id": "research-browser-use",
                                "goal": "调研 Browser Use",
                                "depends_on": [],
                                "handoff": "交接 Browser Use 的调研结果",
                            },
                            {
                                "id": "research-ufo2",
                                "goal": "调研 UFO2",
                                "depends_on": [],
                                "handoff": "交接 UFO2 的调研结果",
                            },
                            {
                                "id": "write-comparison",
                                "goal": "读取两个调研结果并生成比较报告",
                                "depends_on": [
                                    "research-browser-use",
                                    "research-ufo2",
                                ],
                                "handoff": "交接 comparison.md",
                            },
                        ]
                    },
                ),
                tool_response(
                    "dispatch_parallel_worker",
                    {
                        "tasks": [
                            {
                                "subtask_id": "research-browser-use",
                                "worker_type": "browser",
                                "request": "调研 Browser Use 并写入 result.md。",
                            },
                            {
                                "subtask_id": "research-ufo2",
                                "worker_type": "browser",
                                "request": "调研 UFO2 并写入 result.md。",
                            },
                        ]
                    },
                ),
                tool_response(
                    "dispatch_cli_worker",
                    {
                        "subtask_id": "write-comparison",
                        "request": (
                            "读取 workers/research-browser-use/result.md 和 "
                            "workers/research-ufo2/result.md，生成比较报告。"
                        ),
                        "acceptance_criteria": "comparison.md 必须引用两个 result.md。",
                    },
                ),
                tool_response(
                    "finish_task",
                    {
                        "content": (
                            "# 完整协作结果\n\n"
                            "两个 Browser Worker 已并行完成，CLI Worker 已读取并生成 "
                            "workers/write-comparison/artifacts/comparison.md。"
                        )
                    },
                ),
            ]
        )
        leader = TeamLeader(
            model,
            worker_runners={"browser": browser_runner, "cli": cli_runner},
        )

        result = await leader.run(
            self.workspace,
            "调研 Browser Use 和 UFO2，并生成比较报告。",
        )

        self.assertIn("comparison.md", result)
        self.assertEqual(max_active_browser_workers, 2)
        self.assertEqual(leader.plan_status("research-browser-use"), "completed")
        self.assertEqual(leader.plan_status("research-ufo2"), "completed")
        self.assertEqual(leader.plan_status("write-comparison"), "completed")
        comparison_path = (
            self.workspace.task_dir
            / "workers"
            / "write-comparison"
            / "artifacts"
            / "comparison.md"
        )
        comparison = comparison_path.read_text(encoding="utf-8")
        self.assertIn("research-browser-use research result.", comparison)
        self.assertIn("research-ufo2 research result.", comparison)
        self.assertEqual(
            self.workspace.final_result_path.read_text(encoding="utf-8"),
            result,
        )
        events = self.workspace.read_trace()
        self.assertEqual(events[-1]["event"], "task_finished")
        self.assertIn(
            "dispatch_parallel_worker",
            [event.get("name") for event in events if event["event"] == "tool_call"],
        )
        self.assertIn(
            "dispatch_cli_worker",
            [event.get("name") for event in events if event["event"] == "tool_call"],
        )

    async def test_dependency_block_is_reported_before_worker_runs(self):
        runner_called = False

        async def runner(worker_dir):
            nonlocal runner_called
            runner_called = True
            (worker_dir / "result.md").write_text("unexpected", encoding="utf-8")

        model = FakeModel(
            [
                tool_response("set_task_criteria", {"content": "完成汇总。"}),
                tool_response(
                    "set_task_plan",
                    {
                        "steps": [
                            {
                                "id": "source",
                                "goal": "准备资料",
                                "depends_on": [],
                                "handoff": "交接资料",
                            },
                            {
                                "id": "summary",
                                "goal": "汇总资料",
                                "depends_on": ["source"],
                                "handoff": "交付汇总",
                            },
                        ]
                    },
                ),
                tool_response(
                    "dispatch_worker",
                    {
                        "subtask_id": "summary",
                        "worker_type": "browser",
                        "request": "直接汇总。",
                    },
                ),
                tool_response("finish_task", {"content": "暂时无法汇总。"}),
            ]
        )
        leader = TeamLeader(model, worker_runners={"browser": runner})

        await leader.run(self.workspace, "汇总资料。")

        self.assertFalse(runner_called)
        tool_messages = [
            message
            for request in model.requests
            for message in request[0]
            if message.get("role") == "tool"
        ]
        self.assertTrue(
            any(
                "dependency_not_ready" in message["content"]
                for message in tool_messages
            )
        )

    def test_native_parser_accepts_one_tool_call(self):
        turn = parse_native_response(
            tool_response("finish_task", {"content": "done"}, content="简短说明")
        )

        self.assertEqual(turn.kind, "tool_call")
        self.assertEqual(turn.visible_text, "简短说明")
        self.assertEqual(turn.tool_name, "finish_task")
        self.assertEqual(turn.arguments, {"content": "done"})

    def test_native_parser_rejects_multiple_tool_calls(self):
        response = tool_response("finish_task", {"content": "done"})
        response["choices"][0]["message"]["tool_calls"].append(
            response["choices"][0]["message"]["tool_calls"][0]
        )

        turn = parse_native_response(response)

        self.assertEqual(turn.kind, "invalid")
        self.assertIn("one tool call", turn.error)

    def test_native_parser_rejects_invalid_json_arguments(self):
        response = tool_response("finish_task", {"content": "done"})
        response["choices"][0]["message"]["tool_calls"][0]["function"]["arguments"] = (
            "{broken"
        )

        turn = parse_native_response(response)

        self.assertEqual(turn.kind, "invalid")
        self.assertIn("valid JSON", turn.error)

    async def test_plan_replacement_keeps_completed_step_status(self):
        async def runner(worker_dir):
            (worker_dir / "result.md").write_text("done", encoding="utf-8")

        model = FakeModel(
            [
                tool_response("set_task_criteria", {"content": "完成任务。"}),
                tool_response(
                    "set_task_plan",
                    {
                        "steps": [
                            {
                                "id": "a",
                                "goal": "执行 A",
                                "depends_on": [],
                                "handoff": "交接 A",
                            }
                        ]
                    },
                ),
                tool_response(
                    "dispatch_worker",
                    {"subtask_id": "a", "worker_type": "browser", "request": "执行 A"},
                ),
                tool_response(
                    "set_task_plan",
                    {
                        "steps": [
                            {
                                "id": "a",
                                "goal": "执行 A",
                                "depends_on": [],
                                "handoff": "交接 A",
                            },
                            {
                                "id": "b",
                                "goal": "执行 B",
                                "depends_on": ["a"],
                                "handoff": "交接 B",
                            },
                        ]
                    },
                ),
                tool_response("finish_task", {"content": "完成。"}),
            ]
        )
        leader = TeamLeader(model, worker_runners={"browser": runner})

        await leader.run(self.workspace, "执行 A 后扩展计划。")

        self.assertEqual(leader.plan_status("a"), "completed")
        self.assertEqual(leader.plan_status("b"), "ready")

    def test_openai_client_uses_explicit_model_configuration(self):
        with patch("openai.AsyncOpenAI") as async_client:
            client = OpenAIModelClient(
                api_key="test-key",
                base_url="https://proxy.example/v1",
                model="test-model",
            )

        async_client.assert_called_once_with(
            api_key="test-key",
            base_url="https://proxy.example/v1",
            default_headers={"User-Agent": "collab-agent"},
        )
        self.assertEqual(client.model, "test-model")

    async def test_openai_client_closes_async_sdk_client(self):
        client = object.__new__(OpenAIModelClient)
        client.client = unittest.mock.Mock()
        client.client.close = AsyncMock()

        await client.close()

        client.client.close.assert_awaited_once_with()

    async def test_openai_client_sends_tools_with_each_request(self):
        client = object.__new__(OpenAIModelClient)
        client.model = "test-model"
        client.client = unittest.mock.Mock()
        client.client.chat.completions.create = AsyncMock(return_value={"choices": []})
        messages = [{"role": "user", "content": "test"}]
        tools = [{"type": "function", "function": {"name": "finish_task"}}]

        await client.complete(messages, tools)

        client.client.chat.completions.create.assert_awaited_once_with(
            model="test-model",
            messages=messages,
            tools=tools,
        )


if __name__ == "__main__":
    unittest.main()
