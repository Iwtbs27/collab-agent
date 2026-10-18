package ai.opencyvis.overlay

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.opencyvis.engine.AgentState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests for the new OverlayWindow lifecycle contract.
 *
 * The bug fixes hinge on three invariants being true on a real
 * `WindowManager`:
 *
 *   1. `prepare()` does NOT attach anything to the WindowManager.
 *      (Bug 1: cold-start ball that was caused by the old
 *      `show(); hide()` pattern leaving a brief / stale attachment.)
 *
 *   2. While detached, calling `updateState(WaitingForUser)` or
 *      `setExpanded(true)` only flips the cached flag — it does NOT
 *      surface the pill. (Bug 2 secondary: ask_user popping over
 *      the chat foreground.)
 *
 *   3. `attach()` / `detach()` are idempotent and survive rapid
 *      foreground/background flips without throwing or leaking views.
 *      (Gemini's review note about Activity-transition flicker.)
 *
 * NOTE: requires `SYSTEM_ALERT_WINDOW` permission. The system app build
 * holds that permission via `privapp-permissions-opencyvis.xml`, so this
 * test class assumes the app under test is the OpenCyvis privileged build
 * (the same precondition as `TakeoverOverlayUiTest`).
 */
@RunWith(AndroidJUnit4::class)
class OverlayWindowAttachInstrumentedTest {

    private lateinit var overlay: OverlayWindow
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        mainHandler.post {
            try { block() } catch (t: Throwable) { error = t }
            latch.countDown()
        }
        assertTrue("main-thread block timed out", latch.await(5, TimeUnit.SECONDS))
        error?.let { throw it }
    }

    @Before
    fun setUp() {
        runOnMain {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            overlay = OverlayWindow(ctx)
        }
    }

    @After
    fun tearDown() {
        runOnMain { overlay.dismiss() }
    }

    @Test
    fun prepare_does_not_attach_to_window_manager() {
        runOnMain {
            overlay.prepare()
            assertTrue("prepare() should mark prepared", overlay.isPreparedForTest())
            assertFalse(
                "prepare() must NOT attach — Bug 1 regression check",
                overlay.isAttachedForTest()
            )
            assertEquals(
                "prepare() must not call WindowManager.addView",
                0, overlay.attachCount
            )
        }
    }

    @Test
    fun updateState_waiting_while_detached_does_not_surface_pill() {
        runOnMain {
            overlay.prepare()
            // Simulate: user is in foreground (so OverlayService never called
            // attach). Agent transitions to WaitingForUser. The OLD code
            // called `windowManager.addView(pillView)` from inside
            // updateState() and the pill popped over the chat. The NEW
            // contract: updateState only flips cached flags.
            overlay.updateState(AgentState.WaitingForUser("ok?", 0))
            assertFalse(
                "WaitingForUser while detached must not attach — Bug 2 sub-fix",
                overlay.isAttachedForTest()
            )
            assertTrue(
                "WaitingForUser should still pre-select the expanded pill, " +
                        "so a future attach() surfaces it instead of the chat-head",
                overlay.isExpandedForTest()
            )
            assertEquals(0, overlay.attachCount)
        }
    }

    @Test
    fun attach_is_idempotent() {
        runOnMain {
            overlay.prepare()
            overlay.attach()
            overlay.attach()
            overlay.attach()
            assertTrue(overlay.isAttachedForTest())
            assertEquals(
                "attach() must be idempotent — only one addView for repeated calls",
                1, overlay.attachCount
            )
        }
    }

    @Test
    fun detach_is_idempotent() {
        runOnMain {
            overlay.prepare()
            overlay.detach()
            overlay.detach()
            assertFalse(overlay.isAttachedForTest())
            assertEquals(
                "detach() while already detached should not increment counter",
                0, overlay.detachCount
            )

            overlay.attach()
            overlay.detach()
            overlay.detach()
            assertFalse(overlay.isAttachedForTest())
            assertEquals(
                "detach() must be idempotent — only one removeView per attached cycle",
                1, overlay.detachCount
            )
        }
    }

    @Test
    fun attach_then_detach_survives_rapid_flips() {
        // Mimics rapid Activity onStop -> onStart flicker that gemini-cli
        // flagged: the overlay must not throw or leak views under bursty
        // visibility changes. We assert the *invariants* that matter:
        //   - no exception leaks out of the loop
        //   - after the loop, internal state is back to "detached"
        // The exact attach/detach counts are NOT asserted because
        // `View.isAttachedToWindow` may not flip back synchronously after
        // `removeViewImmediate` in instrumentation, which is fine — the
        // important thing is no crash and clean final state.
        runOnMain {
            overlay.prepare()
            repeat(20) {
                overlay.attach()
                overlay.detach()
            }
            assertFalse(
                "After paired attach/detach, final state must be detached",
                overlay.isAttachedForTest()
            )
        }
    }

    @Test
    fun setExpanded_while_detached_only_flips_flag() {
        runOnMain {
            overlay.prepare()
            assertFalse("Should start minimized", overlay.isExpandedForTest())
            overlay.setExpanded(true)
            assertTrue(
                "setExpanded should flip flag even when detached",
                overlay.isExpandedForTest()
            )
            assertEquals(
                "setExpanded must NOT addView while detached",
                0, overlay.attachCount
            )
            assertFalse(overlay.isAttachedForTest())
        }
    }

    @Test
    fun attach_after_waiting_state_surfaces_pill_not_chat_head() {
        runOnMain {
            overlay.prepare()
            overlay.updateState(AgentState.WaitingForUser("ok?", 0))
            // OverlayService now decides we should be visible (e.g. user
            // pressed Home). attach() must surface the pill (since the
            // pending question is more urgent than the chat-head form).
            overlay.attach()
            assertTrue(overlay.isAttachedForTest())
            assertTrue(
                "attach() after WaitingForUser should surface the expanded pill",
                overlay.isExpandedForTest()
            )
        }
    }
}
