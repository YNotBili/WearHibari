package com.huanli233.hibari.wear.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.text.fontVariationSettings
import com.huanli233.hibari.ui.text.toAndroidStyle
import com.huanli233.hibari.ui.unit.TextUnit
import com.huanli233.hibari.ui.unit.takeOrElse
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where the arc's midpoint sits relative to the sweep. Mirrors `CurvedLayout`'s `anchor`/`AnchorType`
 * (`foundation/CurvedLayout.kt:46-56`) with the same 0 / 0.5 / 1 ratios.
 */
enum class CurvedAnchor(val ratio: Float) {
    Start(0f),
    Center(0.5f),
    End(1f),
}

/**
 * Which line of the text rides the arc the glyphs are drawn on. Upstream's `WarpOffset`
 * (`foundation/CurvedTextStyle.kt:499-569`) selects the line that *keeps its width* while the glyph
 * outlines are warped, which a renderer that cannot warp glyphs has to read as "which line is placed
 * on the arc" instead; see the note on [WearCurvedTextView].
 *
 * The offsets themselves are [determineRadiusOffset], the module's single copy of upstream's
 * `CurvedTextStyle.WarpOffset.determineWarpRadiusOffset` (`CurvedTextStyle.kt:555-569`).
 *
 * Upstream's `WarpOffset` is a value class whose companion (`CurvedTextStyle.kt:529-553`) carries
 * seven offsets, `Unspecified` among them (`:531`, with `isSpecified`/`takeOrElse` at `:512-527`).
 * The six that mean something to a renderer are here as five names, upstream's `None` and `Baseline`
 * collapsed into [None]: upstream's own arithmetic gives `Baseline` 0 (`:557`) and never lets `None`
 * reach that function at all (`BasicCurvedText.kt` gates warping on it, `CurvedTextStyle.kt:567`
 * throws otherwise), so both answer 0 here.
 *
 * There is deliberately no `Unspecified` member: a `when` over this enum is an expression in
 * [determineRadiusOffset] below and again in `CurvedContainer.kt:798-804`, so adding a sixth name
 * would break the second one, which is not this file's to edit. A caller that needs upstream's "fill
 * this in from another style" state — [com.huanli233.hibari.wear.CurvedTextStyle] does — spells it as
 * a nullable `WarpOffset?` instead.
 */
enum class WarpOffset {
    /** No radial adjustment: the baseline rides on the arc. */
    None,

    /** `-ascent / 2` */
    HalfAscent,

    /**
     * `-(ascent + descent) / 2`, the default in Wear Compose (`CurvedTextStyle.kt:542-546`, and
     * `BasicCurvedText.kt:302` for the value an unspecified offset resolves to).
     */
    HalfOpticalHeight,

    /** `-ascent` */
    Ascent,

    /** `-descent`, i.e. below the baseline, as upstream. */
    Descent,
}

/**
 * The one implementation in this module of upstream's
 * `CurvedTextStyle.WarpOffset.determineWarpRadiusOffset(ascent, descent)`
 * (`foundation/CurvedTextStyle.kt:555-569`), which answers "how far does the line that keeps its
 * width sit from the baseline, given this warp choice". [ascent] and [descent] are upstream's two
 * arguments, and a caller supplies them from whichever paint it measured with —
 * `TextPaint.ascent()`/`descent()` in both [WearCurvedTextView] and the budgeting side of
 * `CurvedContainer`.
 *
 * Upstream's table is `Baseline -> 0f`, `HalfAscent -> -ascent / 2`,
 * `HalfOpticalHeight -> -(ascent + descent) / 2`, `Ascent -> -ascent`, `Descent -> -descent`,
 * `else -> throw IllegalArgumentException` (`:556-568`). [None] stands for the two zero-yielding
 * names upstream's renderer can reach — `Baseline` directly, `None` through the gate that skips
 * warping — so it answers 0 rather than throwing, which is what upstream's own pipeline ends up
 * using. There is no arm for upstream's `Unspecified` because this enum has no such member; see the
 * note on [WarpOffset].
 */
fun WarpOffset.determineRadiusOffset(ascent: Float, descent: Float): Float = when (this) {
    WarpOffset.None -> 0f
    WarpOffset.HalfAscent -> -ascent / 2f
    WarpOffset.HalfOpticalHeight -> -(ascent + descent) / 2f
    WarpOffset.Ascent -> -ascent
    WarpOffset.Descent -> -descent
}

/**
 * Draws text along a circular arc, for round Wear screens.
 *
 * Ported from `BasicCurvedText` + the renderer selection in `WarpedCurvedTextRenderer`. It draws like
 * upstream's **fallback** renderer (`AndroidCurvedTextRenderer`, `BasicCurvedText.kt:561-588`,
 * `Canvas.drawTextOnPath`) rather than the API 34+ `WarpedCurvedTextRenderer`, which warps each
 * glyph's *outline* by walking `PathIterator` — and `PathIterator` is `Path_iterator`, API 34 only
 * (`WarpedCurvedTextRenderer.kt:49`). Two deviations follow from that, and both are this view's:
 *  - Glyphs are rigid, so nothing fans radially: the whole label keeps the width it measures at the
 *    arc it is drawn on.
 *  - Upstream warps the outlines around a fixed baseline, which stays at
 *    `parentOuterRadius - baseLinePosition` (`BasicCurvedText.kt:218`) while the *warp* line moves to
 *    `baselineRadius + warpRadiusOffset` (`WarpedCurvedTextRenderer.kt:117`). Here the arc carries the
 *    baseline, so a non-zero [warpOffset] shifts the whole text box off the sector upstream would put
 *    it on — outward for clockwise text, inward for counter-clockwise — by [warpOffset]'s radius
 *    offset, up to a quarter em. The annulus drawn for [backgroundColor] keeps upstream's footprint,
 *    so it frames the glyphs only when [warpOffset] is [WarpOffset.None]. Passing
 *    [WarpOffset.None] reproduces upstream's radial placement exactly.
 *
 * Upstream's own fallback keeps the baseline at `baselineRadius` and budgets the sweep at the warp
 * radius (`BasicCurvedText.kt:222-229`, drawn at `:575-582`), which draws less than the whole label
 * because `drawTextOnPath` clips at the end of the path; this view does not, and that is where the
 * two differ.
 *
 * Angle convention is copied from `CurvedLayout.offsetFromDistanceAndAngle`
 * (`foundation/CurvedLayout.kt:449-450`): canvas coordinates with y down, so 0 rad is 3 o'clock and
 * increasing angle travels visually clockwise; 270 deg is the top.
 */
open class WearCurvedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // `also` with an explicit receiver: the class has its own `style` property, which `apply`'s
    // implicit receiver would not shield.
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.style = Paint.Style.FILL
        it.flags = it.flags or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPath = Path()
    private val backgroundPath = Path()
    private val arcBounds = RectF()

    private var geometryDirty = true
    private var cachedDensity = 0f
    private var cachedFitted = ""
    private var canDraw = false

    open var text: CharSequence = ""
        set(value) {
            if (field != value) {
                field = value
                markGeometryDirty()
            }
        }

    open var style: TextStyle = TextStyle()
        set(value) {
            field = value
            markGeometryDirty()
        }

    open var textColor: Color = Color.Unspecified
        set(value) {
            field = value
            markGeometryDirty()
        }

    /**
     * An annulus sector behind the glyphs, spanning the text box upstream budgets for it —
     * [warpOffset] moves the glyphs but not that footprint, so the two only line up for
     * [WarpOffset.None].
     */
    open var backgroundColor: Color = Color.Transparent
        set(value) {
            field = value
            markGeometryDirty()
        }

    /** 270f puts the text at 12 o'clock, matching `CurvedLayout`'s default anchor. */
    open var anchorDegrees: Float = TopAnchor
        set(value) {
            field = value
            markGeometryDirty()
        }

    open var anchorType: CurvedAnchor = CurvedAnchor.Center
        set(value) {
            field = value
            markGeometryDirty()
        }

    /** Clockwise lays text across the top of the dial; counter-clockwise across the bottom. */
    open var clockwise: Boolean = true
        set(value) {
            field = value
            markGeometryDirty()
        }

    /**
     * Upstream's `CurvedTextDefaults.ScrollableContentMaxSweepAngle` (70 deg,
     * `material3/CurvedText.kt:148-165`), which upstream applies as
     * `CurvedModifier.sizeIn(maxSweepDegrees = ...)` on the child (`:107-126`) and this port passes
     * straight to the child. The same file recommends 120 deg where nothing scrolls.
     */
    open var maxSweepDegrees: Float = DefaultMaxSweepDegrees
        set(value) {
            field = value
            markGeometryDirty()
        }

    open var minSweepDegrees: Float = 0f
        set(value) {
            field = value
            markGeometryDirty()
        }

    open var warpOffset: WarpOffset = WarpOffset.HalfOpticalHeight
        set(value) {
            field = value
            markGeometryDirty()
        }

    /** Clockwise tracking. Used for both directions unless [letterSpacingCounterClockwise] is set. */
    open var letterSpacing: TextUnit = TextUnit.Unspecified
        set(value) {
            field = value
            markGeometryDirty()
        }

    /**
     * Counter-clockwise tracking. Wear's type scale gives this a larger value than the clockwise
     * one (0.6 sp vs 1.4 sp on `arcMedium`) because glyphs fan inward as they travel the other way.
     */
    open var letterSpacingCounterClockwise: TextUnit = TextUnit.Unspecified
        set(value) {
            field = value
            markGeometryDirty()
        }

    /** Radial padding from the screen edge, in px. `ArcPaddingValues.outer`. */
    open var outerPaddingPx: Float = 0f
        set(value) {
            field = value
            markGeometryDirty()
        }

    open var overflow: CurvedTextOverflow = CurvedTextOverflow.Clip
        set(value) {
            field = value
            markGeometryDirty()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(size, resolveSize(size, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        markGeometryDirty()
    }

    /** Every input that can move a glyph, change its tracking, or resize the annulus. */
    private fun markGeometryDirty() {
        geometryDirty = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        if (geometryDirty || density != cachedDensity) rebuildGeometry(density)
        if (!canDraw) return

        if (backgroundColor.alpha > 0f) {
            backgroundPaint.color = backgroundColor.toArgb()
            canvas.drawPath(backgroundPath, backgroundPaint)
        }
        // drawTextOnPath walks the path in its own direction, so counter-clockwise text needs the
        // arc traced backwards from the far end or it renders upside down along the bottom.
        canvas.drawTextOnPath(cachedFitted, textPath, 0f, 0f, textPaint)
    }

    /**
     * Resolves the paint, the fitted string and the arc geometry once per change rather than once
     * per frame: `configurePaint` re-resolves the typeface from a variation-settings string and
     * [fitToSweep] may build a `StaticLayout`, both of which dominated scroll frames when they ran
     * inside `onDraw`.
     */
    private fun rebuildGeometry(density: Float) {
        geometryDirty = false
        cachedDensity = density
        canDraw = false
        if (text.isEmpty()) return
        configurePaint(density)

        val fm = textPaint.fontMetrics
        val glyphHeight = fm.descent - fm.ascent
        val lineHeightPx = lineHeightPx(density)
        // Extra leading is split across the two halves so the box, not the baseline, is centred.
        val diff = if (lineHeightPx > 0f) lineHeightPx - glyphHeight else 0f
        val actualAscent = -fm.ascent + diff / 2f
        val actualDescent = fm.descent + diff / 2f
        val textHeight = actualAscent + actualDescent

        val outerRadius = min(width, height) / 2f - outerPaddingPx
        if (outerRadius <= 0f) return
        val baselinePosition = if (clockwise) actualAscent else actualDescent
        val baselineRadius = outerRadius - baselinePosition
        val radialOffset = warpRadiusOffset()
        val measureRadius =
            if (clockwise) baselineRadius + radialOffset else baselineRadius - radialOffset
        if (measureRadius <= 0f) return

        val fitted = fitToSweep(measureRadius)
        // Upstream's single 360 deg cap on a drawn sweep: `BasicCurvedText.kt:433-434`.
        val sweepRadians = (textPaint.measureText(fitted) / measureRadius)
            .coerceIn(minSweepDegrees.toRadians(), maxSweepDegrees.toRadians())
            .coerceAtMost(360f.toRadians())
        if (sweepRadians <= 0f) return

        val startRadians = anchorDegrees.toRadians() -
            (if (clockwise) anchorType.ratio else 1f - anchorType.ratio) * sweepRadians

        val outerDeg = Math.toDegrees(startRadians.toDouble()).toFloat()
        val sweepDeg = Math.toDegrees(sweepRadians.toDouble()).toFloat()
        // Annulus sector: ride the outer arc out, come back along the inner one, close. Upstream's
        // footprint (`BasicCurvedText.kt:440-459`), which the warped glyphs fill exactly; rigid
        // glyphs on a [warpOffset] arc sit off it. See the class note.
        backgroundPath.reset()
        backgroundPath.arcTo(arcRect(outerRadius), outerDeg, sweepDeg, true)
        backgroundPath.arcTo(arcRect((outerRadius - textHeight).coerceAtLeast(0f)),
            outerDeg + sweepDeg, -sweepDeg, false)
        backgroundPath.close()

        textPath.reset()
        if (clockwise) {
            textPath.addArc(arcRect(measureRadius), outerDeg, sweepDeg)
        } else {
            textPath.addArc(arcRect(measureRadius), outerDeg + sweepDeg, -sweepDeg)
        }

        cachedFitted = fitted
        canDraw = true
    }

    private fun configurePaint(density: Float) {
        // sp is scaled by density alone and em is not handled: upstream's `fontSize.toPx()` also
        // carries `Density.fontScale` (`BasicCurvedText.kt:165`), but no other hibari text surface
        // does either — `Modifier.textSize` applies a TextUnit's raw value — so this matches the
        // module rather than upstream. Not ported, deliberately.
        if (style.fontSize.isSp) textPaint.textSize = style.fontSize.value * density
        textPaint.color = (if (textColor.isSpecified) textColor else Color.White).toArgb()

        // Upstream's CurvedTextStyle(TextStyle) folds a text style's tracking into both its own
        // variants (`CurvedTextStyle.kt:243-255`), so a style that carries tracking has to be
        // honoured here even when neither tracking property was set on this view.
        val chosen = if (!clockwise && letterSpacingCounterClockwise != TextUnit.Unspecified) {
            letterSpacingCounterClockwise
        } else {
            letterSpacing.takeOrElse { style.letterSpacing }
        }
        // Upstream keeps tracking in em; a sp tracking is normalised against the rendered size.
        textPaint.letterSpacing = if (chosen == TextUnit.Unspecified) 0f else when {
            chosen.isEm -> chosen.value
            chosen.isSp && textPaint.textSize > 0f -> chosen.value * density / textPaint.textSize
            else -> 0f
        }
        if (style.fontFeatureSettings != null) {
            if (android.os.Build.VERSION.SDK_INT >= 26) textPaint.fontFeatureSettings = style.fontFeatureSettings
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            textPaint.fontVariationSettings =
                style.fontWeight.fontVariationSettings(style.widthAxis)
        }
        style.fontFamily.typeface(style.fontWeight.toAndroidStyle())?.let { textPaint.typeface = it }
    }

    private fun lineHeightPx(density: Float): Float =
        if (style.lineHeight.isSp) style.lineHeight.value * density else -1f

    /**
     * The radial shift this view applies for its [warpOffset].
     *
     * The arithmetic is [WarpOffset.determineRadiusOffset], the module's only copy of it; what is
     * left here is only the platform gate. Zero below API 29: upstream wraps the whole warping block
     * in `SDK_INT >= Q` and leaves the effective offset at `WarpOffset.None` underneath it
     * (`foundation/BasicCurvedText.kt:299-303`). The budget side in `CurvedTextChild.initializeMeasure`
     * carries the same gate — the two must agree or the arc a card budgets stops matching the arc
     * drawn.
     */
    private fun warpRadiusOffset(): Float =
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) 0f
        else warpOffset.determineRadiusOffset(textPaint.ascent(), textPaint.descent())

    /**
     * Truncate to the arc budget: `maxSweepDegrees * measureRadius` is the usable arc length in px.
     * Past it, Ellipsis runs `TextUtils.ellipsize` and Clip reads `getLineEnd(0)` off a one-line
     * `StaticLayout` — both are upstream's `CurvedTextDelegate.ellipsize` (`BasicCurvedText.kt:512-536`),
     * which is handed an already-rounded budget by its call site in `doDraw`
     * (`BasicCurvedText.kt:504`, `(parentSweepRadians * measureRadius).roundToInt()`), and this view
     * rounds the same way. The budget this view is given comes from
     * the container's own sweep, so inside a [com.huanli233.hibari.wear.CurvedLayout] the label is
     * clipped to the sector it was allocated rather than to its `CurvedTextSpec.maxSweepDegrees`.
     */
    private fun fitToSweep(measureRadius: Float): String {
        val budget = (maxSweepDegrees.toRadians() * measureRadius).roundToInt()
        val full = text.toString()
        if (overflow == CurvedTextOverflow.Visible || textPaint.measureText(full) <= budget + 0.001f) {
            return full
        }
        return when (overflow) {
            CurvedTextOverflow.Ellipsis -> TextUtils.ellipsize(
                text,
                textPaint,
                budget.toFloat(),
                TextUtils.TruncateAt.END,
            ).toString()

            else -> {
                val layout = StaticLayout.Builder.obtain(full, 0, full.length, textPaint, budget)
                    .setEllipsize(null)
                    .setMaxLines(1)
                    .build()
                layout.text.substring(0, layout.getLineEnd(0))
            }
        }
    }

    private fun arcRect(radius: Float) = arcBounds.apply {
        set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
    }

    private val centerX: Float get() = width / 2f
    private val centerY: Float get() = height / 2f

    companion object {
        const val TopAnchor = 270f
        const val DefaultMaxSweepDegrees = 70f
    }
}

enum class CurvedTextOverflow { Clip, Ellipsis, Visible }

private fun Float.toRadians(): Float = this * (Math.PI.toFloat() / 180f)
