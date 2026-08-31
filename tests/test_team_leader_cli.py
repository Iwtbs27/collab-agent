import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from collab_agent.team_leader import TeamLeader
from collab_agent.workspace import TaskWorkspace


def tool_response(name, arguments, call_id="call-1"):
    return {
        "choices": [
            {
                "message": {
                    "content": None,
                    "tool_calls": [
                        {
                            "id": call_id,
                            "type": "function",
                            "function": {
                                "name": name,
                                "arguments": json.dumps(arguments, ensure_ascii=False),
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


class FakeCLIWorker:
    def __init__(self, first_status="completed"):
        self.first_status = first_status
        self.resume_calls = []

    async def __call__(self, worker_dir):
        (worker_dir / "session.json").write_text(
            json.dumps({"session_id": "thread-cli"}), encoding="utf-8"
        )
        (worker_dir / "result.md").write_text(
            f"Status: {self.first_status}\n\nCLI result\n\nSession ID：thread-cli",
            encoding="utf-8",
        )

    async def resume(self, worker_dir, session_id, follow_up_request):
        self.resume_calls.append((session_id, follow_up_request))
        (worker_dir / "result.md").write_text(
            "Status: completed\n\nCLI result after feedback", encoding="utf-8"
        )


class TeamLeaderCLITests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp_dir = TemporaryDirectory()
        self.workspace = TaskWorkspace.create(self.temp_dir.name, "task-001")

    def tearDown(self):
        self.temp_dir.cleanup()

    async def test_dispatch_cli_worker_is_exposed_and_writes_request(self):
        cli = FakeCLIWorker()
        model = FakeModel(
            [
                tool_response("set_task_criteria", {"content": "完成 CLI 任务"}),
                tool_response(
                    "set_task_plan",
                    {
                        "steps": [
                            {
                                "id": "code",
                                "goal": "完成代码任务",
                                "depends_on": [],
                                "handoff": "交接代码结果",
                            }
                        ]
                    },
                ),
                tool_response(
                    "dispatch_cli_worker",
                    {
                        "subtask_id": "code",
                        "request": "实现功能并运行测试",
                        "acceptance_criteria": "测试全部通过",
                    },
                ),
                tool_response("finish_task", {"content": "已完成 CLI 任务"}),
            ]
        )

        result = await TeamLeader(model, worker_runners={"cli": cli}).run(
            self.workspace, "请完成代码任务。"
        )

        self.assertEqual(result, "已完成 CLI 任务")
        names = {
            tool["function"]["name"]
            for tool in model.requests[0][1]
        }
        self.assertIn("dispatch_cli_worker", names)
        self.assertIn("resume_cli_worker", names)
        request = self.workspace.request_path("code").read_text(encoding="utf-8")
        self.assertIn("实现功能并运行测试", request)
        self.assertIn("测试全部通过", request)
        dispatch_observation = [
            message["content"]
            for message in model.requests[3][0]
            if message["role"] == "tool"
        ][-1]
        self.assertIn("Session ID：thread-cli", dispatch_observation)

    async def test_resume_cli_worker_uses_existing_session(self):
        cli = FakeCLIWorker(first_status="failed")
        model = FakeModel(
            [
                tool_response("set_task_criteria", {"content": "完成并修复 CLI 任务"}),
                tool_response(
                    "set_task_plan",
                    {
                        "steps": [
                            {
                                "id": "code",
                                "goal": "完成代码任务",
                                "depends_on": [],
                                "handoff": "交接代码结果",
                            }
                        ]
                    },
                ),
                tool_response(
                    "dispatch_cli_worker",
                    {
                        "subtask_id": "code",
                        "request": "实现功能",
                        "acceptance_criteria": "测试通过",
                    },
                ),
                tool_response(
                    "resume_cli_worker",
                    {
                        "subtask_id": "code",
                        "session_id": "thread-cli",
                        "follow_up_request": "请修复测试失败并重新运行测试",
                    },
                ),
                tool_response("finish_task", {"content": "修复完成"}),
            ]
        )

        await TeamLeader(model, worker_runners={"cli": cli}).run(
            self.workspace, "请完成代码任务。"
        )

        self.assertEqual(
            cli.resume_calls,
            [("thread-cli", "请修复测试失败并重新运行测试")],
        )
        self.assertEqual(self.workspace.read_result("code").splitlines()[0], "Status: completed")


if __name__ == "__main__":
    unittest.main()
