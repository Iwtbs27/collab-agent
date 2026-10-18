package ai.opencyvis.input

import android.content.Context
import android.graphics.Point
import android.view.Surface
import android.view.WindowManager

/**
 * Maps normalized coordinates (0-1000) to actual pixel coordinates,
 * accounting for screen rotation.
 */
class CoordinateMapper(
    context: Context,
    private val fixedSize: Point? = null,
    private val fixedRotationDegrees: Int? = null,
    private val fixedWidth: Int? = null,
    private val fixedHeight: Int? = null
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    constructor(
        context: Context,
        fixedWidth: Int,
        fixedHeight: Int,
        fixedRotationDegrees: Int = 0
    ) : this(
        context = context,
        fixedSize = null,
        fixedRotationDegrees = fixedRotationDegrees,
        fixedWidth = fixedWidth,
        fixedHeight = fixedHeight
    )

    /**
     * Get the current display size in pixels (accounting for rotation).
     * If a fixedSize is set (e.g. for virtual display), returns that instead.
     */
    fun getDisplaySize(): Point {
        if (fixedWidth != null && fixedHeight != null) {
            return Point().apply {
                x = fixedWidth
                y = fixedHeight
            }
        }
        if (fixedSize != null) return fixedSize
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        return size
    }

    /**
     * Get the current display rotation in degrees.
     * Virtual displays don't rotate, so returns 0 if fixedSize is set.
     */
    fun getRotationDegrees(): Int {
        if (fixedRotationDegrees != null) return fixedRotationDegrees
        if (fixedSize != null) return 0
        val display = windowManager.defaultDisplay
        return when (display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * Convert normalized coordinates (0-1000) to pixel coordinates.
     * Handles screen rotation so (0,0) is always the visual top-left.
     */
    fun normalizedToPixel(nx: Int, ny: Int): Point {
        val (width, height) = getDisplayDimensions()
        val rotation = getRotationDegrees()

        // In natural orientation, width and height are as-is
        val pixelX: Int
        val pixelY: Int

        when (rotation) {
            0 -> {
                // Normal portrait
                pixelX = normalizedAxisToPixel(nx, width)
                pixelY = normalizedAxisToPixel(ny, height)
            }
            90 -> {
                // Landscape, rotated left
                pixelX = normalizedAxisToPixel(ny, width)
                pixelY = normalizedAxisToPixel(1000 - nx, height)
            }
            180 -> {
                // Upside down
                pixelX = normalizedAxisToPixel(1000 - nx, width)
                pixelY = normalizedAxisToPixel(1000 - ny, height)
            }
            270 -> {
                // Landscape, rotated right
                pixelX = normalizedAxisToPixel(1000 - ny, width)
                pixelY = normalizedAxisToPixel(nx, height)
            }
            else -> {
                pixelX = normalizedAxisToPixel(nx, width)
                pixelY = normalizedAxisToPixel(ny, height)
            }
        }

        return Point().apply {
            x = pixelX
            y = pixelY
        }
    }

    /**
     * Convert pixel coordinates to normalized (0-1000).
     */
    fun pixelToNormalized(px: Int, py: Int): Point {
        val (width, height) = getDisplayDimensions()
        val nx = pixelAxisToNormalized(px, width)
        val ny = pixelAxisToNormalized(py, height)
        return Point().apply {
            x = nx
            y = ny
        }
    }

    private fun getDisplayDimensions(): Pair<Int, Int> {
        if (fixedWidth != null && fixedHeight != null) return fixedWidth to fixedHeight
        val size = getDisplaySize()
        return size.x to size.y
    }

    private fun normalizedAxisToPixel(value: Int, dimension: Int): Int {
        if (dimension <= 1) return 0
        val clamped = value.coerceIn(0, 1000)
        return ((clamped / 1000.0) * dimension).toInt().coerceIn(0, dimension - 1)
    }

    private fun pixelAxisToNormalized(value: Int, dimension: Int): Int {
        if (dimension <= 0) return 0
        return ((value / dimension.toDouble()) * 1000).toInt().coerceIn(0, 1000)
    }
}
