package ai.opencyvis.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputSequenceResultTest {

    @Test
    fun `sequence stays successful when all injections succeed`() {
        val result = InputSequenceResult()

        result.record(true)
        result.record(true)
        result.record(true)

        assertTrue(result.success)
    }

    @Test
    fun `sequence fails when any injection is rejected`() {
        val result = InputSequenceResult()

        result.record(true)
        result.record(false)
        result.record(true)

        assertFalse(result.success)
    }
}
