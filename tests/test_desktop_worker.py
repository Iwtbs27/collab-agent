import asyncio
import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from collab_agent.desktop_worker import DesktopWorker
from collab_agent.team_leader import TeamLeader
from collab_agent.workspace import TaskWorkspace


class FakeState:
    def __init__(self, status="FINISH"):
        self.status = status

    async def handle(self, agent, context):
        agent.status = self.status

    def next_agent(self, agent):
        return agent

    def next_state(self, agent):
        return self


class FakeAgent:
    def __init__(self, status="FINISH"):
        self.status = "CONTINUE"
        self.state = FakeState(status)

    async def handle(self, context):
        await self.state.handle(self, context)

    def set_state(self, state):
        if type(state).__name__ == "ContinueAppAgentState":
            self.state = FakeState("FINISH")
        else:
            self.state = state


class DesktopWorkerTests(unittest.IsolatedAsyncioTestCase):
    def make_worker_dir(self, temp_dir):
        worker_dir = Path(temp_dir) / "workers" / "desktop-task"
        (worker_dir / "artifacts").mkdir(parents=True)
        (worker_dir / "request.md").write_text(
            "打开记事本并输入测试内容", encoding="utf-8"
        )
        return worker_dir

    async def test_run_writes_handoff_and_trajectory(self):
        with TemporaryDirectory() as temp_dir:
            worker_dir = self.make_worker_dir(temp_dir)
            contexts = []

            def dispatcher_factory(session, manager):
                contexts.append(session.context)
                return object()

            worker = DesktopWorker(
                host_agent_factory=lambda request: FakeAgent(),
                dispatcher_factory=dispatcher_factory,
            )
            with patch(
                "collab_agent.desktop_worker._is_terminal_host_state",
                return_value=True,
            ):
                await worker(worker_dir)

            result = (worker_dir / "result.md").read_text(encoding="utf-8")
            self.assertIn("Status: completed", result)
            self.assertTrue((worker_dir / "trace.jsonl").is_file())
            trajectory = (worker_dir / "trajectory.md").read_text(encoding="utf-8")
            self.assertIn("打开记事本并输入测试内容", trajectory)
            events = [
                json.loads(line)
                for line in (worker_dir / "trace.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            self.assertEqual(events[-1]["event"], "worker_finished")
            from omniact_desktop.module.context import ContextNames

            logger = contexts[0].get(ContextNames.LOGGER)
            logger.write('{"status":"logged"}')
            last_event = json.loads(
                (worker_dir / "trace.jsonl")
                .read_text(encoding="utf-8")
                .splitlines()[-1]
            )
            self.assertEqual(last_event["event"], "processor_context")

    def test_ui_control_receiver_is_registered_without_office_factories(self):
        from omniact_desktop.automator.puppeteer import ReceiverManager

        self.assertIn("UIControl", ReceiverManager._receiver_factory_registry)

    def test_unfocused_keyboard_input_preserves_spaces(self):
        from omniact_desktop.automator.ui_control.controller import ControlReceiver

        application = MagicMock()
        receiver = ControlReceiver(control=None, application=application)

        keys = "COLLAB AGENT DESKTOP CHECK{ENTER}Status: DRAFT"
        receiver.keyboard_input({"keys": keys, "control_focus": False})

        application.type_keys.assert_called_once_with(
            keys=keys,
            with_spaces=True,
        )

    async def test_takeover_resume_unblocks_waiting_worker(self):
        with TemporaryDirectory() as temp_dir:
            worker_dir = self.make_worker_dir(temp_dir)
            worker = DesktopWorker(
                host_agent_factory=lambda request: FakeAgent(status="PENDING"),
                dispatcher_factory=lambda session, manager: object(),
                max_steps=3,
            )
            with patch(
                "collab_agent.desktop_worker._is_terminal_host_state",
                return_value=True,
            ):
                task = asyncio.create_task(worker(worker_dir))
                for _ in range(20):
                    if worker.waiting_requests:
                        break
                    await asyncio.sleep(0.01)
                self.assertIn("desktop-task", worker.waiting_requests)
                await worker.takeover("desktop-task")
                await worker.resume("desktop-task")
                await task
            self.assertIn(
                "Status: completed",
                (worker_dir / "result.md").read_text(encoding="utf-8"),
            )

    async def test_cancel_wakes_waiting_worker(self):
        with TemporaryDirectory() as temp_dir:
            worker_dir = self.make_worker_dir(temp_dir)
            worker = DesktopWorker(
                host_agent_factory=lambda request: FakeAgent(status="PENDING"),
                dispatcher_factory=lambda session, manager: object(),
            )
            task = asyncio.create_task(worker(worker_dir))
            for _ in range(20):
                if worker.waiting_requests:
                    break
                await asyncio.sleep(0.01)
            await worker.cancel("desktop-task")
            await task
            self.assertIn(
                "Status: cancelled",
                (worker_dir / "result.md").read_text(encoding="utf-8"),
            )

    async def test_missing_agent_status_fails_without_empty_turns(self):
        with TemporaryDirectory() as temp_dir:
            worker_dir = self.make_worker_dir(temp_dir)
            worker = DesktopWorker(
                host_agent_factory=lambda request: FakeAgent(status=None),
                dispatcher_factory=lambda session, manager: object(),
                max_steps=5,
            )
            await worker(worker_dir)

            result = (worker_dir / "result.md").read_text(encoding="utf-8")
            self.assertIn("Status: failed", result)
            events = [
                json.loads(line)
                for line in (worker_dir / "trace.jsonl")
                .read_text(encoding="utf-8")
                .splitlines()
            ]
            self.assertEqual(
                len([event for event in events if event["event"] == "agent_turn"]),
                1,
            )

    async def test_team_leader_dispatches_desktop_runner(self):
        with TemporaryDirectory() as temp_dir:
            workspace = TaskWorkspace.create(temp_dir, "task-001")
            calls = []

            async def desktop_runner(worker_dir):
                calls.append(worker_dir.name)
                (worker_dir / "result.md").write_text(
                    "Status: completed\n\nDesktop handoff", encoding="utf-8"
                )

            def response(name, arguments):
                return {
                    "choices": [
                        {
                            "message": {
                                "content": None,
                                "tool_calls": [
                                    {
                                        "id": f"call-{name}",
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

            class Model:
                def __init__(self):
                    self.responses = [
                        response("set_task_criteria", {"content": "完成桌面任务"}),
                        response(
                            "set_task_plan",
                            {
                                "steps": [
                                    {
                                        "id": "desktop-step",
                                        "goal": "执行桌面操作",
                                        "depends_on": [],
                                        "handoff": "返回桌面操作结果",
                                    }
                                ]
                            },
                        ),
                        response(
                            "dispatch_worker",
                            {
                                "subtask_id": "desktop-step",
                                "worker_type": "desktop",
                                "request": "执行桌面操作",
                            },
                        ),
                        response("finish_task", {"content": "桌面任务已完成"}),
                    ]

                async def complete(self, messages, tools):
                    return self.responses.pop(0)

            result = await TeamLeader(
                Model(), worker_runners={"desktop": desktop_runner}
            ).run(workspace, "完成桌面任务")

            self.assertEqual(result, "桌面任务已完成")
            self.assertEqual(calls, ["desktop-step"])

    def test_desktop_openai_client_uses_project_user_agent(self):
        from omniact_desktop.llm.openai import OpenAIService

        with patch("omniact_desktop.llm.openai.OpenAI") as openai_client:
            OpenAIService.get_openai_client(
                "openai",
                "https://example.test/v1",
                1,
                10,
                "test-key",
            )

        self.assertEqual(
            openai_client.call_args.kwargs["default_headers"],
            {"User-Agent": "collab-agent"},
        )

    def test_desktop_openai_service_does_not_send_a_startup_probe(self):
        from omniact_desktop.llm import AgentType
        from omniact_desktop.llm.openai import OpenAIService

        client = MagicMock()
        config = {
            AgentType.HOST: {
                "API_TYPE": "openai",
                "API_BASE": "https://example.test/v1",
                "API_KEY": "test-key",
                "API_MODEL": "test-model",
            },
            "MAX_RETRY": 1,
            "TIMEOUT": 10,
            "PRICES": {},
        }
        with patch.object(OpenAIService, "get_openai_client", return_value=client):
            OpenAIService(config, AgentType.HOST)

        client.beta.chat.completions.parse.assert_not_called()


if __name__ == "__main__":
    unittest.main()
