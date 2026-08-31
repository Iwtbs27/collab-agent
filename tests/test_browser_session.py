import json
import shutil
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
from unittest.mock import AsyncMock

sys.path.insert(0, str(Path(__file__).parents[1] / "src"))

from omniact_browser.browser.chrome import (
    get_edge_user_data_dir,
    get_last_used_profile,
    list_chromium_profiles,
)
from omniact_browser.browser.profile import BrowserProfile
from omniact_browser.browser.session import BrowserSession
from omniact_browser.browser.watchdogs.local_browser_watchdog import LocalBrowserWatchdog
from omniact_browser.tools.service import Tools


class EdgeProfileTests(unittest.TestCase):
    def test_blank_profile_does_not_allocate_temp_directory_until_launch(self):
        self.assertIsNone(BrowserProfile().user_data_dir)

    def test_edge_user_data_dir_uses_local_app_data(self):
        with patch.dict("os.environ", {"LOCALAPPDATA": r"C:\Users\test\AppData\Local"}):
            path = get_edge_user_data_dir()

        self.assertEqual(
            path,
            Path(r"C:\Users\test\AppData\Local\Microsoft\Edge\User Data"),
        )

    def test_profile_discovery_reads_last_used_edge_profile(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "Local State").write_text(
                json.dumps(
                    {
                        "profile": {
                            "last_used": "Profile 2",
                            "info_cache": {
                                "Default": {"name": "Personal"},
                                "Profile 2": {"name": "Work"},
                            },
                        }
                    }
                ),
                encoding="utf-8",
            )

            self.assertEqual(get_last_used_profile(root), "Profile 2")
            self.assertEqual(
                list_chromium_profiles(root),
                [
                    {"directory": "Default", "name": "Personal"},
                    {"directory": "Profile 2", "name": "Work"},
                ],
            )

    def test_edge_profile_is_copied_to_an_independent_temp_directory(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            profile_dir = root / "Default"
            profile_dir.mkdir()
            (profile_dir / "Cookies").write_text("session data", encoding="utf-8")
            (profile_dir / "Cache").mkdir()
            (profile_dir / "Cache" / "data_0").write_text("cache", encoding="utf-8")
            (root / "Local State").write_text("{}", encoding="utf-8")

            profile = BrowserProfile(
                executable_path=r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                user_data_dir=root,
                profile_directory="Default",
                enable_default_extensions=False,
            )
            copied_root = Path(profile.user_data_dir)
            try:
                self.assertNotEqual(copied_root, root)
                self.assertEqual(
                    (copied_root / "Default" / "Cookies").read_text(encoding="utf-8"),
                    "session data",
                )
                self.assertFalse((copied_root / "Default" / "Cache").exists())
            finally:
                shutil.rmtree(copied_root, ignore_errors=True)

    def test_browser_profile_temp_directory_is_removed_by_watchdog(self):
        with TemporaryDirectory() as temp_dir:
            profile_root = Path(temp_dir) / "browser-use-user-data-dir-worker"
            profile_root.mkdir()
            session = BrowserSession()
            watchdog = LocalBrowserWatchdog(
                event_bus=session.event_bus,
                browser_session=session,
            )

            watchdog._cleanup_temp_dir(profile_root)

            self.assertFalse(profile_root.exists())


class TakeoverToolTests(unittest.TestCase):
    def test_takeover_action_is_registered_and_terminates_the_batch(self):
        tools = Tools()

        action = tools.registry.registry.actions["request_user_takeover"]
        self.assertTrue(action.terminates_sequence)
        schema = action.param_model.model_json_schema()["properties"]
        self.assertEqual(set(schema), {"reason", "instruction"})


class BrowserWindowTests(unittest.IsolatedAsyncioTestCase):
    async def test_window_can_be_minimized_and_shown_for_takeover(self):
        session = BrowserSession()
        session.agent_focus_target_id = "target-1"
        session._cdp_client_root = unittest.mock.Mock()
        session._cdp_client_root.send_raw = AsyncMock(
            side_effect=[{"windowId": 7}, {}, {"windowId": 7}, {}, {}]
        )

        await session.hide_window()
        await session.show_window()

        calls = session._cdp_client_root.send_raw.await_args_list
        self.assertEqual(calls[0].args, ("Browser.getWindowForTarget", {"targetId": "target-1"}))
        self.assertEqual(
            calls[1].args,
            (
                "Browser.setWindowBounds",
                {
                    "windowId": 7,
                    "bounds": {
                        "windowState": "normal",
                        "left": -32000,
                        "top": -32000,
                        "width": 1280,
                        "height": 900,
                    },
                },
            ),
        )
        self.assertEqual(
            calls[3].args,
            (
                "Browser.setWindowBounds",
                {
                    "windowId": 7,
                    "bounds": {
                        "windowState": "normal",
                        "left": 0,
                        "top": 0,
                        "width": 1280,
                        "height": 900,
                    },
                },
            ),
        )
        self.assertEqual(calls[4].args, ("Target.activateTarget", {"targetId": "target-1"}))


if __name__ == "__main__":
    unittest.main()
