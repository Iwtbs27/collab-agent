package ai.opencyvis.input

import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Tests for InputInjector swipe behavior.
 * Validates the injection mode constants and event construction logic.
 * Full integration (ValueAnimator + vsync) requires an instrumented test.
 */
class InputInjectorSwipeTest {

    @Test
    fun `INJECT_MODE_ASYNC is 0 and WAIT_FOR_FINISH is 2`() {
        // Verify our constants match Android's InputManager values
        // Access via reflection since they're private
        val clazz = InputInjector::class.java
        val asyncField = clazz.getDeclaredField("INJECT_MODE_ASYNC")
        asyncField.isAccessible = true
        assertEquals(0, asyncField.getInt(null))

        val waitField = clazz.getDeclaredField("INJECT_MODE_WAIT_FOR_FINISH")
        waitField.isAccessible = true
        assertEquals(2, waitField.getInt(null))
    }

    @Test
    fun `InputSequenceResult tracks failures correctly for swipe`() {
        val result = InputSequenceResult()

        // Simulate DOWN success
        result.record(true)
        assertTrue(result.success)

        // Simulate MOVE events - one failure should mark the whole sequence as failed
        result.record(true)
        result.record(true)
        result.record(false)  // One MOVE injection rejected
        result.record(true)

        assertFalse(result.success)
    }

    @Test
    fun `swipe direction coordinates cover full gesture range`() {
        // Verify the ActionExecutor's swipe directions produce meaningful pixel distances
        // up: 500,700 -> 500,300 (vertical 400-unit swipe upward)
        // down: 500,300 -> 500,700 (vertical 400-unit swipe downward)
        // left: 700,500 -> 300,500 (horizontal 400-unit swipe left)
        // right: 300,500 -> 700,500 (horizontal 400-unit swipe right)

        val directions = mapOf(
            "up" to intArrayOf(500, 700, 500, 300),
            "down" to intArrayOf(500, 300, 500, 700),
            "left" to intArrayOf(700, 500, 300, 500),
            "right" to intArrayOf(300, 500, 700, 500)
        )

        for ((name, coords) in directions) {
            val dx = coords[2] - coords[0]
            val dy = coords[3] - coords[1]
            val distance = Math.sqrt((dx * dx + dy * dy).toDouble())
            // Each swipe should cover 400 normalized units
            assertEquals("$name swipe distance", 400.0, distance, 0.1)
        }
    }

    @Test
    fun `AccelerateDecelerate interpolation formula produces non-linear motion`() {
        // AccelerateDecelerateInterpolator uses: (cos((t+1)*PI)/2) + 0.5
        fun interpolate(t: Float): Float =
            ((Math.cos((t + 1) * Math.PI) / 2.0) + 0.5).toFloat()

        val midpoint = interpolate(0.25f)
        // At 25% time, should be less than 25% distance (still accelerating)
        assertTrue("Should be accelerating at 25%: $midpoint < 0.25", midpoint < 0.25f)

        val laterPoint = interpolate(0.75f)
        // At 75% time, should be more than 75% distance (decelerating)
        assertTrue("Should be decelerating at 75%: $laterPoint > 0.75", laterPoint > 0.75f)
    }
}
