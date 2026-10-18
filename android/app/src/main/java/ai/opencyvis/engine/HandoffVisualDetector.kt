package ai.opencyvis.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Low-information visual change detector for sensitive handoff periods.
 *
 * The detector never retains the original bitmap. It immediately converts the
 * sampled frame into a small grayscale grid and compares only that coarse data.
 */
class HandoffVisualDetector(
    private val gridSize: Int = 16,
    private val topCropFraction: Float = 0.12f,
    private val bottomCropFraction: Float = 0.35f
) {
    data class Fingerprint(
        val width: Int,
        val height: Int,
        val values: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Fingerprint) return false
            return width == other.width &&
                    height == other.height &&
                    values.contentEquals(other.values)
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + values.contentHashCode()
            return result
        }
    }

    fun fingerprint(bitmap: Bitmap): Fingerprint? {
        if (bitmap.width < gridSize || bitmap.height < gridSize) return null

        val cropTop = (bitmap.height * topCropFraction).toInt().coerceAtLeast(0)
        val cropBottom = (bitmap.height * (1f - bottomCropFraction)).toInt()
            .coerceAtMost(bitmap.height)
        val sampleHeight = cropBottom - cropTop
        if (sampleHeight < gridSize) return null

        val values = IntArray(gridSize * gridSize)
        for (gy in 0 until gridSize) {
            val y0 = cropTop + (gy * sampleHeight) / gridSize
            val y1 = cropTop + ((gy + 1) * sampleHeight) / gridSize
            for (gx in 0 until gridSize) {
                val x0 = (gx * bitmap.width) / gridSize
                val x1 = ((gx + 1) * bitmap.width) / gridSize
                values[gy * gridSize + gx] = averageGray(bitmap, x0, y0, x1, y1)
            }
        }
        return Fingerprint(bitmap.width, bitmap.height, values)
    }

    fun diffRatio(a: Fingerprint, b: Fingerprint): Float {
        if (a.width != b.width || a.height != b.height || a.values.size != b.values.size) {
            return 1f
        }
        var changed = 0
        for (i in a.values.indices) {
            if (abs(a.values[i] - b.values[i]) >= CELL_DIFF_THRESHOLD) changed++
        }
        return changed.toFloat() / a.values.size.toFloat()
    }

    fun isHighConfidenceTransition(baseline: Fingerprint, current: Fingerprint): Boolean {
        return diffRatio(baseline, current) >= HIGH_CONFIDENCE_DIFF_RATIO
    }

    private fun averageGray(bitmap: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var sum = 0L
        var count = 0
        val safeX1 = x1.coerceAtLeast(x0 + 1).coerceAtMost(bitmap.width)
        val safeY1 = y1.coerceAtLeast(y0 + 1).coerceAtMost(bitmap.height)
        for (y in y0 until safeY1) {
            for (x in x0 until safeX1) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                sum += (r * 299 + g * 587 + b * 114) / 1000
                count++
            }
        }
        return if (count == 0) 0 else (sum / count).toInt()
    }

    private companion object {
        private const val CELL_DIFF_THRESHOLD = 32
        private const val HIGH_CONFIDENCE_DIFF_RATIO = 0.38f
    }
}
