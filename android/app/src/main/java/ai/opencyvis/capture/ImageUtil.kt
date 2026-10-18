package ai.opencyvis.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Bitmap manipulation utilities: resize, rotate, convert to JPEG base64.
 */
object ImageUtil {

    private const val MAX_WIDTH = 768
    private const val JPEG_QUALITY = 85

    /**
     * Resize bitmap so its width does not exceed [maxWidth], preserving aspect ratio.
     */
    fun resizeToMaxWidth(bitmap: Bitmap, maxWidth: Int = MAX_WIDTH): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val scale = maxWidth.toFloat() / bitmap.width
        val newHeight = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
    }

    /**
     * Rotate bitmap by [degrees].
     */
    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Convert RGBA bitmap to RGB by drawing onto an RGB_565 or ARGB_8888 canvas
     * without alpha, then encode as JPEG.
     */
    fun convertToRgb(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.RGB_565) return bitmap
        val rgbBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(rgbBitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return rgbBitmap
    }

    fun toJpegBytes(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    fun toJpegBase64(bitmap: Bitmap, quality: Int = JPEG_QUALITY): String {
        return Base64.encodeToString(toJpegBytes(bitmap, quality), Base64.NO_WRAP)
    }

    fun processScreenshotToBytes(bitmap: Bitmap): ByteArray {
        val resized = resizeToMaxWidth(bitmap)
        val rgb = convertToRgb(resized)
        val bytes = toJpegBytes(rgb)
        if (rgb !== resized) rgb.recycle()
        if (resized !== bitmap) resized.recycle()
        return bytes
    }

    fun processScreenshot(bitmap: Bitmap): String {
        return Base64.encodeToString(processScreenshotToBytes(bitmap), Base64.NO_WRAP)
    }
}
