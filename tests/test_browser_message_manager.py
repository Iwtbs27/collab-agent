import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from omniact_browser.agent.message_manager.service import MessageManager
from omniact_browser.agent.views import ActionResult, AgentStepInfo, MessageManagerState
from omniact_browser.filesystem.file_system import FileSystem
from omniact_browser.llm.messages import SystemMessage


class BrowserMessageManagerTests(unittest.TestCase):
    def test_validation_error_is_kept_in_next_step_history(self):
        with TemporaryDirectory() as temp_dir:
            manager = MessageManager(
                task="完成浏览器任务",
                system_message=SystemMessage(content="你是 Browser Agent"),
                file_system=FileSystem(temp_dir),
                state=MessageManagerState(),
            )

            manager.prepare_step_state(
                browser_state_summary=None,
                result=[
                    ActionResult(
                        error=(
                            "Browser decision arguments are invalid.\n"
                            "- action: Field required"
                        )
                    )
                ],
                step_info=AgentStepInfo(step_number=1, max_steps=10),
            )

            history = manager.agent_history_description

        self.assertIn("Browser decision arguments are invalid", history)
        self.assertIn("action: Field required", history)
        self.assertNotIn("Agent failed to output in the right format", history)


if __name__ == "__main__":
    unittest.main()
