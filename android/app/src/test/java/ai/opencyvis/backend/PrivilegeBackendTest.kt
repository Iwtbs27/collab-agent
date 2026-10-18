package ai.opencyvis.backend

import org.junit.Test
import org.junit.Assert.*

class PrivilegeBackendTest {
    @Test
    fun `capabilities reports backend name`() {
        val caps = BackendCapabilities(
            name = "test",
            canInjectInput = true,
            canCaptureScreen = true,
            canCaptureSecure = false,
            canCreateVirtualDisplay = true,
        )
        assertEquals("test", caps.name)
        assertFalse(caps.canCaptureSecure)
        assertTrue(caps.canInjectInput)
    }

    @Test
    fun `system backend has SYSTEM capabilities`() {
        val backend = SystemBackend()
        assertEquals("system", backend.capabilities.name)
        assertTrue(backend.capabilities.canCaptureSecure)
        assertTrue(backend.capabilities.canInjectInput)
        assertTrue(backend.capabilities.canCaptureScreen)
        assertTrue(backend.capabilities.canCreateVirtualDisplay)
    }

    @Test
    fun `NONE capabilities has nothing`() {
        val caps = BackendCapabilities.NONE
        assertEquals("none", caps.name)
        assertFalse(caps.canCaptureSecure)
        assertFalse(caps.canInjectInput)
    }

    @Test
    fun `fromProbeMask decodes bitmask correctly`() {
        val caps = BackendCapabilities.fromProbeMask(
            BackendCapabilities.CAP_INJECT_INPUT or BackendCapabilities.CAP_CAPTURE_SCREEN or BackendCapabilities.CAP_CREATE_VD,
            "shizuku"
        )
        assertEquals("shizuku", caps.name)
        assertTrue(caps.canInjectInput)
        assertTrue(caps.canCaptureScreen)
        assertFalse(caps.canCaptureSecure)
        assertTrue(caps.canCreateVirtualDisplay)
    }

    @Test
    fun `fromProbeMask with zero mask gives nothing`() {
        val caps = BackendCapabilities.fromProbeMask(0, "empty")
        assertFalse(caps.canInjectInput)
        assertFalse(caps.canCaptureScreen)
        assertFalse(caps.canCaptureSecure)
        assertFalse(caps.canCreateVirtualDisplay)
    }

    @Test
    fun `fromProbeMask with full mask gives everything`() {
        val caps = BackendCapabilities.fromProbeMask(0xF, "full")
        assertTrue(caps.canInjectInput)
        assertTrue(caps.canCaptureScreen)
        assertTrue(caps.canCaptureSecure)
        assertTrue(caps.canCreateVirtualDisplay)
    }
}
