package ai.opencyvis.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log

/**
 * Small perceptual fingerprint for comparing screenshots across adjacent steps.
 *
 * This is intentionally coarse: cursor blink, tiny loading animations, and minor
 * compression artifacts should not make two screens look meaningfully different.
 */
data class ScreenFingerprint(val averageHash: Long) {

    fun isSimilarTo(other: ScreenFingerprint, maxHammingDistance: Int = DEFAULT_MAX_DISTANCE): Boolean {
        return java.lang.Long.bitCount(averageHash xor other.averageHash) <= maxHammingDistance
    }

    companion object {
        private const val TAG = "ScreenFingerprint"
        private const val HASH_SIZE = 8
        private const val DEFAULT_MAX_DISTANCE = 8

        fun fromBase64(screenshotBase64: String): ScreenFingerprint? {
            return try {
                val bytes = Base64.decode(screenshotBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val fingerprint = fromBitmap(bitmap)
                bitmap.recycle()
                fingerprint
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute screen fingerprint", e)
                null
            }
        }

        fun fromBitmap(bitmap: Bitmap): ScreenFingerprint {
            val scaled = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
            val luminance = IntArray(HASH_SIZE * HASH_SIZE)
            var sum = 0

            for (y in 0 until HASH_SIZE) {
                for (x in 0 until HASH_SIZE) {
                    val pixel = scaled.getPixel(x, y)
                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff
                    val gray = (r * 299 + g * 587 + b * 114) / 1000
                    val index = y * HASH_SIZE + x
                    luminance[index] = gray
                    sum += gray
                }
            }

            if (scaled !== bitmap) {
                scaled.recycle()
            }

            val average = sum / luminance.size
            var bits = 0L
            for (i in luminance.indices) {
                if (luminance[i] >= average) {
                    bits = bits or (1L shl i)
                }
            }
            return ScreenFingerprint(bits)
        }
    }
}
