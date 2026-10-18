package ai.opencyvis.backend

import org.junit.Test
import org.junit.Assert.*

class BackendDetectorTest {
    @Test
    fun `system uid detected correctly`() {
        assertTrue(BackendDetector.isSystemUid(1000))
        assertFalse(BackendDetector.isSystemUid(2000))
        assertFalse(BackendDetector.isSystemUid(10142))
        assertFalse(BackendDetector.isSystemUid(0))
    }
}
