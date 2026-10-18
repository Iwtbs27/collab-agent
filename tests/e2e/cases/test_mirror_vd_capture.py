"""E2E test: Mirror VD ensures fresh screenshots during VIEW mode (ADB backend).

Verifies that when the user opens ViewActivity (VIEW mode), the agent can still
capture fresh screenshots via the mirror VD's ImageReader, instead of getting
stale cached frames from the main VD's ImageReader (which stops receiving frames
when its surface is switched to SurfaceView).

Only meaningful on standard flavor (ADB/RemoteBackend). On system flavor,
the VD is local and captureViaImageReader handles the surface swap locally.
"""

from tests.e2e.framework import AgentTestCase
from tests.e2e.assertions_vd import (
    LogcatContains,
    LogcatNotContains,
    VirtualDisplayCreated,
    MinSteps,
)


class TestMirrorVdCreation(AgentTestCase):
    """Verify mirror VD is created when main VD is created (ADB backend)."""

    instruction = "打开设置"
    timeout = 30
    max_steps = 3

    mock_responses = [
        {"tool_call": {"thought": "I'll open Settings", "action_type": "open_app", "app_name": "设置"}},
        {"tool_call": {"thought": "Settings is open", "action_type": "finish", "summary": "已打开设置"}},
    ]

    assertions = [
        VirtualDisplayCreated(),
        LogcatContains("Mirror VD created for display", description="Mirror VD created"),
        LogcatNotContains("Mirror VD: static createVirtualDisplay not available",
                          description="Mirror API available"),
        LogcatNotContains("Mirror VD creation failed",
                          description="Mirror creation did not fail"),
    ]


class TestMirrorVdCaptureInViewMode(AgentTestCase):
    """Verify agent captures fresh frames via mirror VD while user is in VIEW mode.

    Flow:
    1. Agent starts, VD + mirror VD created
    2. Agent takes 1 step (screenshot via mirror ImageReader)
    3. Trigger view mode via dumpsys (simulates user opening ViewActivity)
    4. Agent takes another step — should still get a valid screenshot
    5. Check that capture came from mirror ImageReader, not stale cache
    """

    instruction = "打开设置然后截图"
    timeout = 45
    max_steps = 5

    mock_responses = [
        {"tool_call": {"thought": "Opening settings", "action_type": "open_app", "app_name": "设置"}},
        {"tool_call": {"thought": "I see settings, let me tap", "action_type": "tap", "x": 540, "y": 960}},
        {"tool_call": {"thought": "Done", "action_type": "finish", "summary": "完成"}},
    ]

    # After step 1 completes, trigger VIEW mode so the VD surface switches
    # to SurfaceView. The agent should still capture via mirror VD on step 2.
    trigger_commands = [
        {
            "wait_for": "Step 1:",
            "command": "debug view",
            "delay": 0.5,
        },
    ]

    assertions = [
        VirtualDisplayCreated(),
        LogcatContains("Mirror VD created for display", description="Mirror VD created"),
        MinSteps(min_steps=2),
        # The key assertion: agent captured via mirror ImageReader during VIEW mode.
        # If mirror works, captureFromImageReader returns data from mirrorImageReader.
        # If mirror fails, it would return stale cachedVdFrame or null.
        LogcatNotContains("captureScreen: ImageReader returned null, falling back to CaptureOps",
                          description="No ImageReader null fallback (mirror provided frames)"),
    ]


class TestMirrorVdCleanup(AgentTestCase):
    """Verify mirror VD is properly released when main VD is released."""

    instruction = "打开设置"
    timeout = 25
    max_steps = 2

    mock_responses = [
        {"tool_call": {"thought": "Done", "action_type": "finish", "summary": "完成"}},
    ]

    assertions = [
        LogcatContains("Mirror VD created for display", description="Mirror VD created"),
        LogcatContains("VD released", description="VD + Mirror released"),
    ]
