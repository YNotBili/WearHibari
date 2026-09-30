package com.huanli233.hibari.wear.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint.FontMetrics
import android.graphics.text.PositionedGlyphs
import android.os.Build
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.text.TextShaper
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.huanli233.hibari.ui.layout.Alignment
import com.huanli233.hibari.ui.unit.IntSize
import com.huanli233.hibari.ui.unit.LayoutDirection
import com.huanli233.hibari.wear.AnimatedTextFontRegistry
import com.huanli233.hibari.wear.animatedTextLerp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws one string whose variable-font axes and size morph between two configurations.
 *
 * The renderer behind `androidx.wear.compose.material3.AnimatedText` — `AnimatedTextState`
 * (`material3/AnimatedText.kt:424-633`). The string is shaped twice, once with the registry's start
 * paint and once with its end paint, then each glyph is drawn at the position lerped between the two
 * shapes (`AnimatedText.kt:448-496`, `:544-585`), while the font itself interpolates its variation
 * axes and the size interpolates between the two font sizes (`:232-258`). Nothing fades between two
 * different strings: the text is the same at 0f and at 1f.
 *
 * `Canvas.drawGlyphs` and `android.text.TextShaper` are API 31, which is upstream's own
 * `@RequiresApi(31)` gate (`AnimatedText.kt:104`, `:210`), and the shape pass needs
 * `TextRunShaper`/`PositionedGlyphs` to hang a `Font` off a `Typeface` at all — there is no public
 * `Font.Builder(Typeface)` overload. So:
 *  - **API 31+** is the faithful path: per-glyph positions lerped, per-glyph font substitution
 *    (upstream `AnimatedText.kt:471-495`, verbatim including its "if the glyph's own font file differs
 *    from the animated one, draw it with the glyph's font" rule at `:487-491`).
 *  - **API 25-30** is a whole-run path, documented rather than faked: the run is drawn with
 *    `Canvas.drawText` by a paint whose `textSize` lerps and whose `fontVariationSettings` string
 *    lerps per frame (both public since API 26), so the axes and the size *do* animate, but the glyph
 *    positions come from live shaping at the current fraction instead of being interpolated between
 *    the two shapes, and glyphs whose font file differs cannot be substituted individually. Below 26
 *    the axis animation is inert and only the size morphs, because font variation settings need 26.
 *
 * Three upstream details are kept exactly, including where they look wrong:
 *  - The baseline offset and ascent terms go through the `lerp(Int, Int, Float)` overload and are then
 *    divided by 2 and by 4 as *integers* (`AnimatedText.kt:461-467`), so both truncate.
 *  - `startWorkingPaint.textSize` is mutated in the draw pass (`AnimatedText.kt:469`), and one registry
 *    is meant to be shared between several animated labels (`AnimatedText.kt:196-197`); two views
 *    sharing a registry therefore write each other's size, and a re-shape after a draw measures with
 *    the last drawn fraction's size. Upstream has the same hazard. The pre-31 branch re-asserts each
 *    working paint's own size before it measures, because that path has no other way to get a stable
 *    start/end box — that one divergence is deliberate and only exists below 31.
 *  - The glyph loops stop at the shorter of the two runs and the shorter of the two glyph counts.
 *    Upstream only clamps the run count (`minOf(startPositionedGlyphs.size, endPositionedGlyphs.size)`,
 *    `AnimatedText.kt:471-476`) and reads `startGlyphs.glyphCount()` per run; the added per-run clamp
 *    is because a morph can change how many glyphs a run collapses into, and reading past the end run
 *    would throw rather than animate.
 *
 * @see com.huanli233.hibari.wear.AnimatedText
 */
open class WearAnimatedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * The string to animate. Changing it re-shapes both configurations, exactly as
     * `AnimatedTextState.updateText` does (`AnimatedText.kt:438-445`) — including the early return for
     * an unchanged value, which is what keeps the per-frame fraction from rebuilding the shape.
     */
    open var text: CharSequence = ""
        set(value) {
            if (field.contentEquals(value)) return
            field = value
            markContentDirty()
        }

    /** The fonts, sizes and paints the animation travels between. */
    open var fontRegistry: AnimatedTextFontRegistry? = null
        set(value) {
            if (field === value) return
            field = value
            markContentDirty()
        }

    /**
     * Where the lerped box sits inside the view's own box — upstream's `contentAlignment`
     * (`AnimatedText.kt:110`, applied at `:455-460`).
     */
    open var contentAlignment: Alignment = Alignment.Center
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    /**
     * Feeds upstream's `else ->` branch of `getTextDirHeuristic` (`AnimatedText.kt:591-608`), which
     * picks `FIRSTSTRONG_RTL` or `FIRSTSTRONG_LTR`. Upstream first consults `TextStyle.textDirection`;
     * Hibari's `TextStyle` has no such field, so the layout direction is the only input.
     */
    open var layoutDirection: LayoutDirection = LayoutDirection.Ltr
        set(value) {
            if (field == value) return
            field = value
            markContentDirty()
        }

    /**
     * The animation's progress: 0f is the start configuration, 1f the end one, and overshoot outside
     * that range is allowed for spring animations (`AnimatedText.kt:98-99`). Written by
     * `Modifier.bindState` from [com.huanli233.hibari.wear.AnimatedText]'s `progressFraction`, which is
     * why it is not part of the re-tuned spec. Defaults to 1f — a view nobody is animating shows the
     * end configuration, the same thing upstream's reduce-motion branch renders
     * (`AnimatedText.kt:133`).
     */
    open var fraction: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Paint used by the whole-run path below API 31; never handed out to callers. */
    private val morphPaint = TextPaint()

    /** The same `FontMetrics` instance every pass, as upstream (`AnimatedText.kt:620`). */
    private val fontMetrics = FontMetrics()

    private var contentDirty = true

    /** Upstream's `intSize` (`AnimatedText.kt:502`): the box that fits both configurations. */
    private var contentSize = IntSize(0, 0)

    /** Upstream's `isRtl`, read off the text itself, not off the layout direction (`:526`, `:561`). */
    private var isRtl = false

    private var currentText: String = ""

    private var startWidthPx = 0f
    private var endWidthPx = 0f
    private var startHeightPx = 0f
    private var endHeightPx = 0f
    private var startBaselineOffset = 0
    private var endBaselineOffset = 0
    private var startAscentPx = 0
    private var endAscentPx = 0

    /** Positions of the glyphs at the start, lerped against [endPositionedGlyphs] (`:534-538`). */
    private val startPositionedGlyphs = mutableListOf<PositionedGlyphs>()
    private val endPositionedGlyphs = mutableListOf<PositionedGlyphs>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        ensureContent()
        // Upstream forces the Canvas to `state.size` (`AnimatedText.kt:123`); `resolveSize` honours an
        // exact parent constraint instead, which is the Views-side reading of the same intent.
        setMeasuredDimension(
            resolveSize(contentSize.width, widthMeasureSpec),
            resolveSize(contentSize.height, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val registry = fontRegistry ?: return
        ensureContent()
        // `if (size == DpSize.Zero) return` (`AnimatedText.kt:450-452`).
        if (contentSize.width == 0 || contentSize.height == 0) return
        if (currentText.isEmpty()) return

        val widthPx = animatedTextLerp(startWidthPx, endWidthPx, fraction)
        val heightPx = animatedTextLerp(startHeightPx, endHeightPx, fraction)
        val offset = contentAlignment.align(
            IntSize(widthPx.roundToInt(), heightPx.roundToInt()),
            IntSize(width, height),
            if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        )
        canvas.translate(
            if (isRtl) widthPx + offset.x.toFloat() else offset.x.toFloat(),
            offset.y.toFloat() +
                heightPx / 2 +
                animatedTextLerpInt(startBaselineOffset, endBaselineOffset, fraction) / 2 +
                animatedTextLerpInt(startAscentPx, endAscentPx, fraction) / 4,
        )

        // `animatedFontRegistry.startWorkingPaint.textSize = getFontSize(fraction)` (`:469`).
        registry.startWorkingPaint.textSize = registry.getFontSize(fraction)

        if (Build.VERSION.SDK_INT >= 31) drawGlyphsPath(canvas, registry)
        else drawWholeRunPath(canvas, registry, widthPx)
    }

    /** The faithful path: `AnimatedText.kt:468-495`. */
    private fun drawGlyphsPath(canvas: Canvas, registry: AnimatedTextFontRegistry) {
        val currentFont = registry.getFont(currentText, fraction)
        val numGlyphs = minOf(startPositionedGlyphs.size, endPositionedGlyphs.size)
        for (i in 0 until numGlyphs) {
            val startGlyphs = startPositionedGlyphs[i]
            val endGlyphs = endPositionedGlyphs[i]
            val count = minOf(startGlyphs.glyphCount(), endGlyphs.glyphCount())
            for (j in 0 until count) {
                val glyphFont = startGlyphs.getFont(j)
                canvas.drawGlyphs(
                    intArrayOf(startGlyphs.getGlyphId(j)),
                    0,
                    floatArrayOf(
                        animatedTextLerp(startGlyphs.getGlyphX(j), endGlyphs.getGlyphX(j), fraction),
                        animatedTextLerp(startGlyphs.getGlyphY(j), endGlyphs.getGlyphY(j), fraction),
                    ),
                    0,
                    1,
                    if (currentFont.file?.name != glyphFont.file?.name) glyphFont else currentFont,
                    registry.startWorkingPaint,
                )
            }
        }
    }

    /**
     * The below-API-31 path: one `drawText` call with the size and the variation-settings string
     * lerped to the current fraction. See the class note for what this cannot do.
     *
     * The morph paint is a copy taken fresh from the start working paint each frame, so the per-frame
     * `fontVariationSettings` write cannot leave a size or an axis setting on a paint another node
     * shares.
     */
    private fun drawWholeRunPath(canvas: Canvas, registry: AnimatedTextFontRegistry, widthPx: Float) {
        morphPaint.set(registry.startWorkingPaint)
        morphPaint.textSize = registry.getFontSize(fraction)
        morphPaint.setVariationSettingsSafe(registry.variationSettingsAt(fraction))
        val advance = morphPaint.measureText(currentText)
        // The LTR path puts the run's left edge on the translated origin; RTL trails it by the run's own
        // advance, which is what upstream's `widthPx + offset.x` translation at :462 amounts to.
        val x = if (isRtl) widthPx - advance else 0f
        canvas.drawText(currentText, x, 0f, morphPaint)
    }

    /** Re-shapes only when the text, the registry or the direction moved. */
    private fun ensureContent() {
        if (!contentDirty) return
        contentDirty = false
        startPositionedGlyphs.clear()
        endPositionedGlyphs.clear()
        val registry = fontRegistry
        if (registry == null) {
            currentText = ""
            contentSize = IntSize(0, 0)
            return
        }
        currentText = text.toString()
        val width = calculateMaxWidth(registry)
        val height = calculateMaxHeight(registry)
        contentSize = IntSize(width.roundToInt(), height.roundToInt())
    }

    private fun markContentDirty() {
        contentDirty = true
        requestLayout()
        invalidate()
    }

    /**
     * `AnimatedTextState.calculateMaxWidth` (`AnimatedText.kt:551-585`): shape the run with the start
     * paint and again with the end paint, summing each pass's advances, and take the larger. The API
     * 31 shaping is `TextShaper.shapeText`, upstream's own call (`:563-582`); below that
     * `measureText` gives the same advance sum for the single run a label is.
     */
    private fun calculateMaxWidth(registry: AnimatedTextFontRegistry): Float {
        startWidthPx = 0f
        endWidthPx = 0f
        if (currentText.isEmpty()) return 0f

        val textDirHeuristic = getTextDirHeuristic()
        isRtl = textDirHeuristic.isRtl(currentText, 0, currentText.length)

        if (Build.VERSION.SDK_INT >= 31) {
            TextShaper.shapeText(
                currentText,
                0,
                currentText.length,
                textDirHeuristic,
                registry.startWorkingPaint,
            ) { _, _, glyphs, _ ->
                startPositionedGlyphs.add(glyphs)
                startWidthPx += glyphs.advance
            }
            TextShaper.shapeText(
                currentText,
                0,
                currentText.length,
                textDirHeuristic,
                registry.endWorkingPaint,
            ) { _, _, glyphs, _ ->
                endPositionedGlyphs.add(glyphs)
                endWidthPx += glyphs.advance
            }
        } else {
            registry.startWorkingPaint.textSize = registry.startFontSizePxValue
            registry.endWorkingPaint.textSize = registry.endFontSizePxValue
            startWidthPx = registry.startWorkingPaint.measureText(currentText)
            endWidthPx = registry.endWorkingPaint.measureText(currentText)
        }
        return max(startWidthPx, endWidthPx)
    }

    /** `AnimatedTextState.getTextDirHeuristic`, layout-direction branch only (`:591-608`). */
    private fun getTextDirHeuristic(): TextDirectionHeuristic =
        if (layoutDirection == LayoutDirection.Rtl) {
            TextDirectionHeuristics.FIRSTSTRONG_RTL
        } else {
            TextDirectionHeuristics.FIRSTSTRONG_LTR
        }

    /**
     * `AnimatedTextState.calculateMaxHeight` (`AnimatedText.kt:610-632`), including the max(start, end)
     * return value and the three quantities the draw pass reads off it.
     */
    private fun calculateMaxHeight(registry: AnimatedTextFontRegistry): Float {
        if (currentText.isEmpty()) {
            startHeightPx = 0f
            startAscentPx = 0
            startBaselineOffset = 0
            endHeightPx = 0f
            endAscentPx = 0
            endBaselineOffset = 0
            return 0f
        }
        if (Build.VERSION.SDK_INT >= 31) {
            // Upstream resolves both fonts before reading either metric (`AnimatedText.kt:621-622`).
            val startFont = registry.getFont(currentText, 0f)
            val endFont = registry.getFont(currentText, 1f)
            startFont.getMetrics(registry.startWorkingPaint, fontMetrics)
            startHeightPx = fontMetrics.descent - fontMetrics.ascent
            startAscentPx = fontMetrics.ascent.roundToInt()
            startBaselineOffset = -fontMetrics.top.roundToInt()
            endFont.getMetrics(registry.endWorkingPaint, fontMetrics)
            endHeightPx = fontMetrics.descent - fontMetrics.ascent
            endAscentPx = fontMetrics.ascent.roundToInt()
            endBaselineOffset = -fontMetrics.top.roundToInt()
        } else {
            registry.startWorkingPaint.textSize = registry.startFontSizePxValue
            registry.startWorkingPaint.getFontMetrics(fontMetrics)
            startHeightPx = fontMetrics.descent - fontMetrics.ascent
            startAscentPx = fontMetrics.ascent.roundToInt()
            startBaselineOffset = -fontMetrics.top.roundToInt()
            registry.endWorkingPaint.textSize = registry.endFontSizePxValue
            registry.endWorkingPaint.getFontMetrics(fontMetrics)
            endHeightPx = fontMetrics.descent - fontMetrics.ascent
            endAscentPx = fontMetrics.ascent.roundToInt()
            endBaselineOffset = -fontMetrics.top.roundToInt()
        }
        return max(startHeightPx, endHeightPx)
    }
}

/**
 * `androidx.compose.ui.util.lerp(start: Int, stop: Int, fraction: Float)`, which rounds the result back
 * to Int — that is what upstream's baseline/ascent terms pass through before the integer `/2` and `/4`.
 */
private fun animatedTextLerpInt(start: Int, stop: Int, fraction: Float): Int =
    start + ((stop - start) * fraction).roundToInt()

/**
 * `Paint.setFontVariationSettings` is API 26, and a string the font rejects throws rather than
 * returning false on every release, so a rejected lerp leaves the paint's own settings alone.
 */
private fun TextPaint.setVariationSettingsSafe(settings: String) {
    if (Build.VERSION.SDK_INT < 26 || settings.isEmpty()) return
    runCatching { fontVariationSettings = settings }
}
