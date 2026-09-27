package com.huanli233.hibari.wear.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.huanli233.hibari.wear.ScreenShape
import com.huanli233.hibari.wear.Vignette

/**
 * The two vignette gradients of androidx.wear.compose.material.Vignette, drawn instead of shipped.
 *
 * Upstream renders `circular_vignette_top.png` / `circular_vignette_bottom.png` (384x384) and
 * `rectangular_vignette_top.png` / `rectangular_vignette_bottom.png` (360x12) with
 * `ContentScale.FillWidth`, so both variants are sized off the view width. Every number below is
 * sampled out of those four PNGs (pure black, alpha only) rather than invented:
 *
 *  - rectangular: a vertical gradient, 12 samples over a strip `width * 12 / 360` tall. The rows
 *    measure horizontally uniform to within 2/255, so a plain linear gradient is exact.
 *  - circular: a band that follows the screen circle. Along the radial normal it reaches 16 px in a
 *    192 px radius (one twelfth of the radius) and peaks at alpha 229; outside the circle the asset
 *    is empty, so the band is clipped to it.
 *  - circular, again: the band is *not* rotationally symmetric. Sampled along the arc, its strength
 *    falls off as the surface curves away from vertical (229 at the apex, ~38 halfway to the side,
 *    ~1 at the horizontal extremes). A radial gradient cannot express that, hence the
 *    `PorterDuff.DST_IN` pass that multiplies the band by the measured vertical profile. Without it
 *    the sides of the watch would be far darker than upstream.
 *
 * Deviations: the round peak is 229/255 while the rectangular peak is 255/255 - that is what the
 * assets hold - and the circular modulation runs from `centerY` to the top and bottom edges, which
 * matches the asset's normalised radius only for the square viewport a round watch reports.
 *
 * Created by [Vignette]; nothing here is public API beyond the two properties it exposes.
 */
class WearVignetteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Corresponds to upstream drawing the `...VignetteTop` image. */
    var drawTop: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Corresponds to upstream drawing the `...VignetteBottom` image. */
    var drawBottom: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val modulatePaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val bounds = RectF()
    private val layerPaint = Paint()

    private var circlePath: Path? = null
    private var circularBandShader: RadialGradient? = null
    private var topArcShader: LinearGradient? = null
    private var bottomArcShader: LinearGradient? = null
    private var topStripShader: LinearGradient? = null
    private var bottomStripShader: LinearGradient? = null
    private var stripHeight = 0f
    private var roundScreen = false
    private var builtWidth = -1
    private var builtHeight = -1

    init {
        // Upstream passes `contentDescription = null`, i.e. decorative and hidden from a11y.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildGradients()
    }

    private fun buildGradients() {
        builtWidth = width
        builtHeight = height
        circlePath = null
        circularBandShader = null
        topArcShader = null
        bottomArcShader = null
        topStripShader = null
        bottomStripShader = null
        roundScreen = false
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val shape = ScreenShape.of(this)
        if (shape.isRound && shape.radius > 0f) {
            roundScreen = true
            val radius = shape.radius
            circularBandShader = RadialGradient(
                shape.centerX, shape.centerY, radius,
                circularBandColors, circularBandPositions, Shader.TileMode.CLAMP,
            )
            // Both modulations start at the centre, where the asset is transparent, and run out to
            // the edge; CLAMP keeps the far half of the view at zero, which is what splits the
            // top band from the bottom one.
            topArcShader = LinearGradient(
                shape.centerX, shape.centerY, shape.centerX, 0f,
                arcColors, arcPositions, Shader.TileMode.CLAMP,
            )
            bottomArcShader = LinearGradient(
                shape.centerX, shape.centerY, shape.centerX, h,
                arcColors, arcPositions, Shader.TileMode.CLAMP,
            )
            circlePath = Path().apply {
                addCircle(shape.centerX, shape.centerY, radius, Path.Direction.CW)
            }
        } else {
            stripHeight = w * RectangularAspect
            topStripShader = LinearGradient(
                0f, 0f, 0f, stripHeight, rectangularColors, rectangularPositions, Shader.TileMode.CLAMP,
            )
            bottomStripShader = LinearGradient(
                0f, h, 0f, h - stripHeight, rectangularColors, rectangularPositions, Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (builtWidth != width || builtHeight != height) buildGradients()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (roundScreen) {
            val band = circularBandShader ?: return
            val circle = circlePath ?: return
            val topArc = topArcShader ?: return
            val bottomArc = bottomArcShader ?: return
            if (drawTop) drawCircularBand(canvas, circle, band, topArc, w, h)
            if (drawBottom) drawCircularBand(canvas, circle, band, bottomArc, w, h)
        } else {
            if (drawTop) {
                bandPaint.shader = topStripShader ?: return
                canvas.drawRect(0f, 0f, w, stripHeight, bandPaint)
            }
            if (drawBottom) {
                bandPaint.shader = bottomStripShader ?: return
                canvas.drawRect(0f, h - stripHeight, w, h, bandPaint)
            }
        }
    }

    /** The radial band, dimmed by the measured arc profile; upstream gets both from one bitmap. */
    private fun drawCircularBand(
        canvas: Canvas,
        circle: Path,
        band: RadialGradient,
        arc: LinearGradient,
        w: Float,
        h: Float,
    ) {
        bandPaint.shader = band
        modulatePaint.shader = arc
        bounds.set(0f, 0f, w, h)
        val layer = canvas.saveLayer(bounds, layerPaint)
        val clipped = canvas.save()
        canvas.clipPath(circle)
        canvas.drawRect(bounds, bandPaint)
        canvas.restoreToCount(clipped)
        canvas.drawRect(bounds, modulatePaint)
        canvas.restoreToCount(layer)
    }

    companion object {
        /** Aspect of the 360x12 rectangular asset: what `ContentScale.FillWidth` makes the height. */
        private const val RectangularAspect = 12f / 360f

        /** Alpha of `rectangular_vignette_top.png`, sampled at the middle of each row, outside in. */
        private val RectangularAlpha =
            intArrayOf(255, 254, 243, 217, 191, 166, 141, 114, 90, 64, 38, 13)

        /**
         * Alpha of `circular_vignette_top.png` sampled down the vertical centre line from the apex,
         * i.e. along the radial normal, one entry per pixel of depth: 0 by the 16th pixel.
         */
        private val CircularBandAlpha =
            intArrayOf(229, 216, 201, 183, 163, 140, 116, 93, 73, 54, 39, 26, 17, 9, 4, 1, 0)

        /** That band's depth as a fraction of the 192 px radius it sits inside. */
        private const val CircularBandFraction = 16f / 192f

        /**
         * Alpha along the circular asset's arc against `u = (centerY - y) / radius`, sampled every
         * 0.05 from the horizontal extremes (`u = 0`) to the apex (`u = 1`).
         */
        private val CircularArcAlpha = floatArrayOf(
            0f, 0.004f, 0.005f, 0.009f, 0.018f, 0.029f, 0.044f, 0.063f, 0.09f, 0.13f, 0.168f,
            0.23f, 0.284f, 0.351f, 0.438f, 0.516f, 0.607f, 0.701f, 0.8f, 0.846f, 1f,
        )

        // The asset rows are sampled at their centres, hence the half-row offsets: outside the first
        // and last sample CLAMP holds their value, exactly as the bitmap's edge pixels do upstream.
        private val rectangularPositions = FloatArray(RectangularAlpha.size) { i ->
            (i + 0.5f) / RectangularAlpha.size
        }
        private val rectangularColors = IntArray(RectangularAlpha.size) { i ->
            RectangularAlpha[i] shl 24
        }

        // RadialGradient stops ascend outwards, so the depth table is walked back to front.
        private val circularBandPositions = FloatArray(CircularBandAlpha.size) { i ->
            1f - CircularBandFraction * (1f - i / (CircularBandAlpha.size - 1f))
        }
        private val circularBandColors = IntArray(CircularBandAlpha.size) { i ->
            CircularBandAlpha[CircularBandAlpha.size - 1 - i] shl 24
        }

        private val arcPositions = FloatArray(CircularArcAlpha.size) { i ->
            i / (CircularArcAlpha.size - 1f)
        }
        private val arcColors = IntArray(CircularArcAlpha.size) { i ->
            (CircularArcAlpha[i] * 255f + 0.5f).toInt() shl 24
        }
    }
}
