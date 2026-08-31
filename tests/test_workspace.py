import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from collab_agent.workspace import TaskWorkspace


class TaskWorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_create_task_layout_and_task_file(self):
        workspace = TaskWorkspace.create(self.root, "task-001")

        self.assertTrue(workspace.task_dir.is_dir())
        self.assertTrue(workspace.inputs_dir.is_dir())
        self.assertTrue(workspace.workers_dir.is_dir())
        self.assertTrue(workspace.final_dir.is_dir())
        self.assertTrue(workspace.task_path.is_file())
        self.assertTrue(workspace.trace_path.is_file())

        workspace.write_task("# User Request\nResearch two companies.")

        self.assertEqual(
            workspace.task_path.read_text(encoding="utf-8"),
            "# User Request\nResearch two companies.",
        )

    def test_append_trace_writes_json_lines(self):
        workspace = TaskWorkspace.create(self.root, "task-001")

        workspace.append_trace("task_started", turn=0, user_request="开始任务")
        workspace.append_trace("tool_result", turn=1, observation="执行完成")

        events = [
            json.loads(line)
            for line in workspace.trace_path.read_text(encoding="utf-8").splitlines()
        ]
        self.assertEqual(
            [event["event"] for event in events],
            [
                "task_started",
                "tool_result",
            ],
        )
        self.assertEqual(events[0]["user_request"], "开始任务")
        self.assertEqual(events[1]["observation"], "执行完成")
        self.assertIn("timestamp", events[0])

    def test_copy_input_file_into_task(self):
        source = self.root / "research-template.md"
        source.write_text("# Template", encoding="utf-8")
        workspace = TaskWorkspace.create(self.root / "tasks", "task-001")

        copied = workspace.copy_input(source)

        self.assertEqual(copied, workspace.inputs_dir / source.name)
        self.assertEqual(copied.read_text(encoding="utf-8"), "# Template")

    def test_worker_request_result_and_artifacts_are_isolated(self):
        workspace = TaskWorkspace.create(self.root, "task-001")

        company_a = workspace.worker_dir("browser-company-a")
        company_b = workspace.worker_dir("browser-company-b")
        workspace.write_request("browser-company-a", "Research company A.")
        workspace.write_request("browser-company-b", "Research company B.")

        (company_a / "result.md").write_text(
            "status: succeeded\n\nCompany A notes.", encoding="utf-8"
        )
        (company_b / "result.md").write_text(
            "status: partial\n\nCompany B notes.", encoding="utf-8"
        )

        self.assertNotEqual(company_a, company_b)
        self.assertTrue((company_a / "artifacts").is_dir())
        self.assertTrue((company_b / "artifacts").is_dir())
        request_path = workspace.request_path("browser-company-a")
        self.assertEqual(
            request_path.read_text(encoding="utf-8"), "Research company A."
        )
        self.assertEqual(
            workspace.read_result("browser-company-a"),
            "status: succeeded\n\nCompany A notes.",
        )
        self.assertEqual(
            workspace.read_result("browser-company-b"),
            "status: partial\n\nCompany B notes.",
        )

    def test_write_final_result(self):
        workspace = TaskWorkspace.create(self.root, "task-001")

        workspace.write_final_result("status: succeeded\n\nAll work is complete.")

        self.assertEqual(
            workspace.final_result_path.read_text(encoding="utf-8"),
            "status: succeeded\n\nAll work is complete.",
        )

    def test_reject_duplicate_task_and_input(self):
        workspace = TaskWorkspace.create(self.root, "task-001")
        with self.assertRaises(FileExistsError):
            TaskWorkspace.create(self.root, "task-001")

        source = self.root / "notes.md"
        source.write_text("notes", encoding="utf-8")
        workspace.copy_input(source)
        with self.assertRaises(FileExistsError):
            workspace.copy_input(source)

    def test_reject_invalid_worker_name_and_missing_result(self):
        workspace = TaskWorkspace.create(self.root, "task-001")

        with self.assertRaises(ValueError):
            workspace.worker_dir("../outside")
        with self.assertRaises(FileNotFoundError):
            workspace.read_result("not-created")


if __name__ == "__main__":
    unittest.main()
