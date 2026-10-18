package ai.opencyvis.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Animated glow border overlay for VIEW mode.
 * Draws a gradient-stroked border around the screen edge that rotates
 * when the agent is actively operating.
 *
 * Reference: SurfaceCardActivity "effect" view
 * overlaid on the SurfaceView that shows a glowing border during agent operation.
 */
class GlowBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val borderWidth = 4f * resources.displayMetrics.density
    private val glowRadius = 8f * resources.displayMetrics.density
    private val cornerRadius = 0f // full-screen, no rounding needed

    // Gradient colors: blue → purple → cyan → blue (looping)
    private val gradientColors = intArrayOf(
        0xFF4FC3F7.toInt(), // light blue
        0xFF7C4DFF.toInt(), // purple
        0xFF18FFFF.toInt(), // cyan
        0xFF4FC3F7.toInt(), // light blue (loop)
    )

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth + glowRadius
    }

    private var rotationAngle = 0f
    private val borderRect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        // BlurMaskFilter requires software rendering
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun startGlow() {
        visibility = VISIBLE
        if (!animator.isRunning) {
            animator.start()
        }
    }

    fun stopGlow() {
        animator.cancel()
        visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Keep the crisp stroke's outer edge flush with the view bounds.
        // The outer half of the glow may be clipped; the visible border must
        // stay aligned with the physical screen edge.
        val inset = borderWidth / 2
        borderRect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        if (borderRect.isEmpty) return

        // Create a sweep gradient centered on the view, rotated by the animation angle
        val cx = width / 2f
        val cy = height / 2f

        val sweep = SweepGradient(cx, cy, gradientColors, null)
        val matrix = Matrix()
        matrix.setRotate(rotationAngle, cx, cy)
        sweep.setLocalMatrix(matrix)

        // Draw outer glow (blurred, wider stroke)
        glowPaint.shader = sweep
        glowPaint.alpha = 45
        glowPaint.maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRect(borderRect, glowPaint)

        // Draw crisp border on top
        borderPaint.shader = sweep
        borderPaint.alpha = 140
        canvas.drawRect(borderRect, borderPaint)
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
