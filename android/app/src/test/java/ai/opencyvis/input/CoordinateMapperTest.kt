package ai.opencyvis.input

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Tests for CoordinateMapper which converts normalized (0-1000) coordinates
 * to actual pixel coordinates, accounting for screen rotation.
 */
class CoordinateMapperTest {

    private lateinit var mockContext: Context
    private lateinit var mockWindowManager: WindowManager
    private lateinit var mockDisplay: Display
    private var testWidth: Int = 0
    private var testHeight: Int = 0
    private var testRotation: Int = Surface.ROTATION_0

    private fun setupDisplay(width: Int, height: Int, rotation: Int = Surface.ROTATION_0) {
        testWidth = width
        testHeight = height
        testRotation = rotation
        mockDisplay = mock {
            on { this.rotation } doReturn rotation
        }
        // Mock getRealSize to set Point values
        whenever(mockDisplay.getRealSize(any())).thenAnswer { invocation ->
            val point = invocation.getArgument<Point>(0)
            point.x = width
            point.y = height
            null
        }

        mockWindowManager = mock {
            on { defaultDisplay } doReturn mockDisplay
        }
        mockContext = mock {
            on { getSystemService(Context.WINDOW_SERVICE) } doReturn mockWindowManager
        }
    }

    private fun mapper(): CoordinateMapper =
        CoordinateMapper(
            mockContext,
            testWidth,
            testHeight,
            rotationToDegrees(testRotation)
        )

    private fun displayBackedMapper(): CoordinateMapper = CoordinateMapper(mockContext)

    private fun rotationToDegrees(rotation: Int): Int =
        when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

    // --- Standard 1080x1920 screen ---

    @Test
    fun `0,0 maps to top-left pixel on 1080x1920`() {
        setupDisplay(1080, 1920)
        val mapper = displayBackedMapper()
        val pixel = mapper.normalizedToPixel(0, 0)
        assertEquals(0, pixel.x)
        assertEquals(0, pixel.y)
    }

    @Test
    fun `1000,1000 maps to bottom-right pixel on 1080x1920`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(1000, 1000)
        // 1000 * 1080 / 1000 = 1080, coerced to 1079
        assertEquals(1079, pixel.x)
        assertEquals(1919, pixel.y)
    }

    @Test
    fun `500,500 maps to center on 1080x1920`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(500, 500)
        assertEquals(540, pixel.x)
        assertEquals(960, pixel.y)
    }

    @Test
    fun `250,750 maps correctly on 1080x1920`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(250, 750)
        assertEquals(270, pixel.x)  // 250 * 1080 / 1000 = 270
        assertEquals(1440, pixel.y) // 750 * 1920 / 1000 = 1440
    }

    // --- 1080x2400 screen (modern tall display) ---

    @Test
    fun `0,0 maps to top-left on 1080x2400`() {
        setupDisplay(1080, 2400)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(0, 0)
        assertEquals(0, pixel.x)
        assertEquals(0, pixel.y)
    }

    @Test
    fun `1000,1000 maps to bottom-right on 1080x2400`() {
        setupDisplay(1080, 2400)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(1000, 1000)
        assertEquals(1079, pixel.x)
        assertEquals(2399, pixel.y)
    }

    @Test
    fun `500,500 maps to center on 1080x2400`() {
        setupDisplay(1080, 2400)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(500, 500)
        assertEquals(540, pixel.x)
        assertEquals(1200, pixel.y) // 500 * 2400 / 1000 = 1200
    }

    // --- Different screen sizes ---

    @Test
    fun `works with 720x1280 screen`() {
        setupDisplay(720, 1280)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(500, 500)
        assertEquals(360, pixel.x)
        assertEquals(640, pixel.y)
    }

    @Test
    fun `works with 1440x2560 screen`() {
        setupDisplay(1440, 2560)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(500, 500)
        assertEquals(720, pixel.x)
        assertEquals(1280, pixel.y)
    }

    // --- Rotation tests ---

    @Test
    fun `rotation 0 maps correctly`() {
        setupDisplay(1080, 1920, Surface.ROTATION_0)
        val mapper = mapper()
        assertEquals(0, mapper.getRotationDegrees())
        val pixel = mapper.normalizedToPixel(500, 500)
        assertEquals(540, pixel.x)
        assertEquals(960, pixel.y)
    }

    @Test
    fun `rotation 90 maps correctly`() {
        // In landscape, getRealSize returns rotated dimensions
        setupDisplay(1920, 1080, Surface.ROTATION_90)
        val mapper = mapper()
        assertEquals(90, mapper.getRotationDegrees())
        val pixel = mapper.normalizedToPixel(500, 500)
        assertEquals(960, pixel.x)
        assertEquals(540, pixel.y)
    }

    @Test
    fun `rotation 180 maps correctly`() {
        setupDisplay(1080, 1920, Surface.ROTATION_180)
        val mapper = mapper()
        assertEquals(180, mapper.getRotationDegrees())
        val pixel = mapper.normalizedToPixel(500, 500)
        assertEquals(540, pixel.x)
        assertEquals(960, pixel.y)
    }

    @Test
    fun `rotation 270 maps correctly`() {
        setupDisplay(1920, 1080, Surface.ROTATION_270)
        val mapper = mapper()
        assertEquals(270, mapper.getRotationDegrees())
        val pixel = mapper.normalizedToPixel(500, 500)
        assertEquals(960, pixel.x)
        assertEquals(540, pixel.y)
    }

    // --- Reverse mapping ---

    @Test
    fun `pixelToNormalized reverses normalizedToPixel for center`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val normalized = mapper.pixelToNormalized(540, 960)
        assertEquals(500, normalized.x)
        assertEquals(500, normalized.y)
    }

    @Test
    fun `pixelToNormalized for origin`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val normalized = mapper.pixelToNormalized(0, 0)
        assertEquals(0, normalized.x)
        assertEquals(0, normalized.y)
    }

    @Test
    fun `pixelToNormalized clamps to 1000`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val normalized = mapper.pixelToNormalized(1080, 1920)
        assertEquals(1000, normalized.x)
        assertEquals(1000, normalized.y)
    }

    // --- Edge cases ---

    @Test
    fun `getDisplaySize returns correct dimensions`() {
        setupDisplay(1080, 1920)
        val mapper = displayBackedMapper()
        val size = mapper.getDisplaySize()
        assertEquals(1080, size.x)
        assertEquals(1920, size.y)
    }

    @Test
    fun `negative normalized values are clamped to 0`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        // Integer math: -10 * 1080 / 1000 = -10 (approximately), coerced to 0
        val pixel = mapper.normalizedToPixel(-10, -10)
        assertEquals(0, pixel.x)
        assertEquals(0, pixel.y)
    }

    // --- Corner coordinates on fixed-size mapper ---

    @Test
    fun `0,0 maps to top-left pixel with fixed-size mapper on 1080x1920`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(0, 0)
        assertEquals(0, pixel.x)
        assertEquals(0, pixel.y)
    }

    @Test
    fun `0,0 maps to top-left on 1440x2560`() {
        setupDisplay(1440, 2560)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(0, 0)
        assertEquals(0, pixel.x)
        assertEquals(0, pixel.y)
    }

    @Test
    fun `1000,1000 maps to bottom-right on 1440x2560`() {
        setupDisplay(1440, 2560)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(1000, 1000)
        assertEquals(1439, pixel.x)
        assertEquals(2559, pixel.y)
    }

    @Test
    fun `0,0 maps to top-left on 720x1280`() {
        setupDisplay(720, 1280)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(0, 0)
        assertEquals(0, pixel.x)
        assertEquals(0, pixel.y)
    }

    @Test
    fun `1000,1000 maps to bottom-right on 720x1280`() {
        setupDisplay(720, 1280)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(1000, 1000)
        assertEquals(719, pixel.x)
        assertEquals(1279, pixel.y)
    }

    @Test
    fun `500,500 maps to center on 1440x2560`() {
        setupDisplay(1440, 2560)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(500, 500)
        assertEquals(720, pixel.x)
        assertEquals(1280, pixel.y)
    }

    @Test
    fun `500,500 maps to center on 720x1280`() {
        setupDisplay(720, 1280)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(500, 500)
        assertEquals(360, pixel.x)
        assertEquals(640, pixel.y)
    }

    // --- Out-of-range clamping ---

    @Test
    fun `values above 1000 are clamped to max pixel`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(1100, 1200)
        assertEquals(1079, pixel.x)
        assertEquals(1919, pixel.y)
    }

    @Test
    fun `large negative values are clamped to 0`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(-50, -100)
        assertEquals(0, pixel.x)
        assertEquals(0, pixel.y)
    }

    @Test
    fun `mixed out-of-range values clamp independently`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(-50, 1100)
        assertEquals(0, pixel.x)
        assertEquals(1919, pixel.y)
    }

    @Test
    fun `out-of-range values with rotation 90 are clamped`() {
        setupDisplay(1920, 1080, Surface.ROTATION_90)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(1500, -200)
        // After clamping: nx=1000, ny=0
        // rotation 90: pixelX = normalizedAxisToPixel(ny=0, 1920) = 0
        //              pixelY = normalizedAxisToPixel(1000-nx=0, 1080) = 0
        assertEquals(0, pixel.x)
        assertEquals(0, pixel.y)
    }

    // --- Round-trip tests: normalized -> pixel -> normalized ---

    @Test
    fun `round-trip for center 500,500 on 1080x1920`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(500, 500)
        val back = mapper.pixelToNormalized(pixel.x, pixel.y)
        assertEquals(500, back.x)
        assertEquals(500, back.y)
    }

    @Test
    fun `round-trip for origin 0,0 on 1080x1920`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(0, 0)
        val back = mapper.pixelToNormalized(pixel.x, pixel.y)
        assertEquals(0, back.x)
        assertEquals(0, back.y)
    }

    @Test
    fun `round-trip for 250,750 on 1080x1920`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(250, 750)
        val back = mapper.pixelToNormalized(pixel.x, pixel.y)
        assertEquals(250, back.x)
        assertEquals(750, back.y)
    }

    @Test
    fun `round-trip for 100,900 on 1440x2560`() {
        setupDisplay(1440, 2560)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(100, 900)
        val back = mapper.pixelToNormalized(pixel.x, pixel.y)
        // Allow +/-1 tolerance due to integer truncation
        assertTrue("Expected ~100 but got ${back.x}", Math.abs(back.x - 100) <= 1)
        assertTrue("Expected ~900 but got ${back.y}", Math.abs(back.y - 900) <= 1)
    }

    @Test
    fun `round-trip for 333,667 on 720x1280`() {
        setupDisplay(720, 1280)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(333, 667)
        val back = mapper.pixelToNormalized(pixel.x, pixel.y)
        // Allow +/-2 tolerance for smaller screens
        assertTrue("Expected ~333 but got ${back.x}", Math.abs(back.x - 333) <= 2)
        assertTrue("Expected ~667 but got ${back.y}", Math.abs(back.y - 667) <= 2)
    }

    // --- Very small screen (100x100) ---

    @Test
    fun `0,0 maps to top-left on 100x100`() {
        setupDisplay(100, 100)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(0, 0)
        assertEquals(0, pixel.x)
        assertEquals(0, pixel.y)
    }

    @Test
    fun `1000,1000 maps to bottom-right on 100x100`() {
        setupDisplay(100, 100)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(1000, 1000)
        assertEquals(99, pixel.x)
        assertEquals(99, pixel.y)
    }

    @Test
    fun `500,500 maps to center on 100x100`() {
        setupDisplay(100, 100)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(500, 500)
        assertEquals(50, pixel.x)
        assertEquals(50, pixel.y)
    }

    @Test
    fun `round-trip on 100x100 for center`() {
        setupDisplay(100, 100)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(500, 500)
        val back = mapper.pixelToNormalized(pixel.x, pixel.y)
        assertEquals(500, back.x)
        assertEquals(500, back.y)
    }

    @Test
    fun `small increments are distinguishable on 100x100`() {
        setupDisplay(100, 100)
        val mapper = mapper()
        // 100 pixels means each pixel = 10 normalized units
        val p0 = mapper.normalizedToPixel(0, 0)
        val p10 = mapper.normalizedToPixel(10, 10)
        val p20 = mapper.normalizedToPixel(20, 20)
        assertEquals(0, p0.x)
        assertEquals(1, p10.x)
        assertEquals(2, p20.x)
    }

    // --- pixelToNormalized additional cases ---

    @Test
    fun `pixelToNormalized at last pixel maps to 1000 or close on 1080x1920`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val normalized = mapper.pixelToNormalized(1079, 1919)
        // 1079/1080 * 1000 = 999.07 -> 999
        assertTrue("Expected close to 1000 but got ${normalized.x}", normalized.x >= 998)
        assertTrue("Expected close to 1000 but got ${normalized.y}", normalized.y >= 998)
    }

    @Test
    fun `pixelToNormalized on 1440x2560`() {
        setupDisplay(1440, 2560)
        val mapper = mapper()
        val normalized = mapper.pixelToNormalized(720, 1280)
        assertEquals(500, normalized.x)
        assertEquals(500, normalized.y)
    }

    @Test
    fun `pixelToNormalized on 720x1280`() {
        setupDisplay(720, 1280)
        val mapper = mapper()
        val normalized = mapper.pixelToNormalized(360, 640)
        assertEquals(500, normalized.x)
        assertEquals(500, normalized.y)
    }

    @Test
    fun `pixelToNormalized on 100x100`() {
        setupDisplay(100, 100)
        val mapper = mapper()
        val normalized = mapper.pixelToNormalized(50, 50)
        assertEquals(500, normalized.x)
        assertEquals(500, normalized.y)
    }

    // --- Rotation with corner coordinates ---

    @Test
    fun `rotation 90 top-left 0,0`() {
        setupDisplay(1920, 1080, Surface.ROTATION_90)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(0, 0)
        // rotation 90: pixelX = ny*W/1000 = 0, pixelY = (1000-nx)*H/1000 = 1080 -> 1079
        assertEquals(0, pixel.x)
        assertEquals(1079, pixel.y)
    }

    @Test
    fun `rotation 90 bottom-right 1000,1000`() {
        setupDisplay(1920, 1080, Surface.ROTATION_90)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(1000, 1000)
        // rotation 90: pixelX = ny*W/1000 = 1920 -> 1919, pixelY = (1000-nx)*H/1000 = 0
        assertEquals(1919, pixel.x)
        assertEquals(0, pixel.y)
    }

    @Test
    fun `rotation 180 top-left 0,0`() {
        setupDisplay(1080, 1920, Surface.ROTATION_180)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(0, 0)
        // rotation 180: pixelX = (1000-0)*1080/1000 = 1080 -> 1079
        //               pixelY = (1000-0)*1920/1000 = 1920 -> 1919
        assertEquals(1079, pixel.x)
        assertEquals(1919, pixel.y)
    }

    @Test
    fun `rotation 180 bottom-right 1000,1000`() {
        setupDisplay(1080, 1920, Surface.ROTATION_180)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(1000, 1000)
        // rotation 180: pixelX = (1000-1000)*1080/1000 = 0
        //               pixelY = (1000-1000)*1920/1000 = 0
        assertEquals(0, pixel.x)
        assertEquals(0, pixel.y)
    }

    @Test
    fun `rotation 270 top-left 0,0`() {
        setupDisplay(1920, 1080, Surface.ROTATION_270)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(0, 0)
        // rotation 270: pixelX = (1000-ny)*W/1000 = 1920 -> 1919
        //               pixelY = nx*H/1000 = 0
        assertEquals(1919, pixel.x)
        assertEquals(0, pixel.y)
    }

    @Test
    fun `rotation 270 bottom-right 1000,1000`() {
        setupDisplay(1920, 1080, Surface.ROTATION_270)
        val mapper = mapper()
        val pixel = mapper.normalizedToPixel(1000, 1000)
        // rotation 270: pixelX = (1000-1000)*W/1000 = 0
        //               pixelY = 1000*H/1000 = 1080 -> 1079
        assertEquals(0, pixel.x)
        assertEquals(1079, pixel.y)
    }

    // --- getDisplaySize with fixed dimensions ---

    @Test
    fun `getDisplaySize returns fixedWidth and fixedHeight`() {
        setupDisplay(1080, 1920)
        val mapper = mapper()
        val size = mapper.getDisplaySize()
        assertEquals(1080, size.x)
        assertEquals(1920, size.y)
    }

    @Test
    fun `getDisplaySize returns fixedSize for 100x100`() {
        setupDisplay(100, 100)
        val mapper = mapper()
        val size = mapper.getDisplaySize()
        assertEquals(100, size.x)
        assertEquals(100, size.y)
    }
}
