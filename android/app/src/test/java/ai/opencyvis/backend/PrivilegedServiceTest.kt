package ai.opencyvis.backend

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedServiceTest {

    @Test
    fun `pngToJpeg produces valid JPEG for valid PNG input`() {
        val png = createPng(4, 4)
        val jpeg = PrivilegedService.pngToJpeg(png, maxWidth = 540, quality = 85)
        assertNotNull("pngToJpeg should return non-null for valid PNG", jpeg)
        assertTrue("JPEG output should have content", jpeg!!.size > 2)
        // Verify JPEG magic bytes (SOI marker: FF D8)
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
    }

    @Test
    fun `pngToJpeg scales down when image exceeds maxWidth`() {
        val png = createPng(100, 50) // 100x50 image
        val jpeg = PrivilegedService.pngToJpeg(png, maxWidth = 50, quality = 85)
        assertNotNull(jpeg)
        // Decode the JPEG to verify it was scaled
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg!!.size)
        assertNotNull(decoded)
        assertEquals(50, decoded!!.width)
        assertEquals(25, decoded.height)
        decoded.recycle()
    }

    @Test
    fun `pngToJpeg with zero maxWidth does not scale`() {
        val png = createPng(10, 10)
        val jpeg = PrivilegedService.pngToJpeg(png, maxWidth = 0, quality = 85)
        assertNotNull(jpeg)
        assertEquals(0xFF.toByte(), jpeg!![0])
        assertEquals(0xD8.toByte(), jpeg[1])
        // Decode and verify dimensions are preserved
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertNotNull(decoded)
        assertEquals(10, decoded!!.width)
        assertEquals(10, decoded.height)
        decoded.recycle()
    }

    @Test
    fun `pngToJpeg does not scale when image is smaller than maxWidth`() {
        val png = createPng(20, 30)
        val jpeg = PrivilegedService.pngToJpeg(png, maxWidth = 540, quality = 85)
        assertNotNull(jpeg)
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg!!.size)
        assertNotNull(decoded)
        assertEquals(20, decoded!!.width)
        assertEquals(30, decoded.height)
        decoded.recycle()
    }

    @Test
    fun `pngToJpeg preserves aspect ratio when scaling`() {
        val png = createPng(200, 100) // 2:1 aspect ratio
        val jpeg = PrivilegedService.pngToJpeg(png, maxWidth = 100, quality = 85)
        assertNotNull(jpeg)
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg!!.size)
        assertNotNull(decoded)
        assertEquals(100, decoded!!.width)
        assertEquals(50, decoded.height) // Maintains 2:1 aspect ratio
        decoded.recycle()
    }

    /** Create a valid PNG byte array by encoding a real Bitmap. */
    private fun createPng(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
