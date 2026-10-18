package ai.opencyvis.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffVisualDetectorTest {

    @Test
    fun `small visual changes do not count as high confidence transition`() {
        val detector = HandoffVisualDetector()
        val baseline = fingerprint(value = 30)
        val current = fingerprint(value = 34)

        assertFalse(detector.isHighConfidenceTransition(baseline, current))
    }

    @Test
    fun `large visual changes count as high confidence transition`() {
        val detector = HandoffVisualDetector()
        val baseline = fingerprint(value = 25)
        val current = fingerprint(value = 220)

        assertTrue(detector.isHighConfidenceTransition(baseline, current))
    }

    private fun fingerprint(value: Int): HandoffVisualDetector.Fingerprint =
        HandoffVisualDetector.Fingerprint(
            width = 160,
            height = 320,
            values = IntArray(16 * 16) { value }
        )
}
