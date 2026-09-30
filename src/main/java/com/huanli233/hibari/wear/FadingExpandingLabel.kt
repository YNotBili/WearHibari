package com.huanli233.hibari.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.huanli233.hibari.animation.Animatable
import com.huanli233.hibari.animation.FiniteAnimationSpec
import com.huanli233.hibari.animation.Spring
import com.huanli233.hibari.animation.spring
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.MutableState
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.bindState
import com.huanli233.hibari.runtime.derivedStateOf
import com.huanli233.hibari.runtime.effects.LaunchedEffect
import com.huanli233.hibari.runtime.locals.LocalDensity
import com.huanli233.hibari.runtime.locals.LocalLayoutDirection
import com.huanli233.hibari.runtime.mutableStateOf
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.text.FontFamily
import com.huanli233.hibari.ui.text.TextAlign
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.text.createTypeface
import com.huanli233.hibari.ui.text.fontVariationSettings
import com.huanli233.hibari.ui.text.toAndroidStyle
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.LayoutDirection
import com.huanli233.hibari.ui.unit.TextUnit
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Ported from androidx.wear.compose.material3.FadingExpandingLabel.
 *
 * Animates a label whose line count changes: the box grows to the new text's height while each newly
 * revealed line fades in through a multiplied alpha (`FadingExpandingLabel.kt:155-191`). Where [Text]
 * ellipsizes, this is the label that expands the Button or Card holding it.
 *
 * What survives, and what had to be re-expressed:
 *  - The fade arithmetic is verbatim, including upstream's rect: `topLeft = Offset(0f, top)` with
 *    `size = Size(width, bottom)` is `top + bottom` tall, so it overlaps the following line instead of
 *    stopping at `bottom` (`:178-179`). Kept as written; the following lines multiply to zero through
 *    their own rects, so the overlap is invisible either way.
 *  - `graphicsLayer { compositingStrategy = Offscreen }` + `drawRect(..., BlendMode.Modulate)` (`:165`,
 *    `:175-180`) becomes `Canvas.saveLayer` + a `DST_IN` xfermode. For a pure white source those are the
 *    same premultiplied product, and unlike `android.graphics.BlendMode.MODULATE` (API 29) `DST_IN` has
 *    existed since API 1 — so this port needs no version gate for the effect it produces.
 *  - `Modifier.height(animatedHeight)` (`:164`) becomes the view's measured height while animating
 *    ([WearFadingExpandingLabelView.revealHeightPx]). Lines the box has not grown to are cut off by the
 *    parent's `clipChildren`, where upstream leaves them drawn but multiplied to transparent: the same
 *    pixels, reached differently.
 *  - `rememberTextMeasurer()` / `TextMeasurer.measure` (`:123-135`) has no Hibari counterpart, so the
 *    measuring engine is a [StaticLayout] built from a [TextPaint] configured exactly as
 *    [com.huanli233.hibari.wear.attributes.textStyle] configures a `TextView` — that agreement is what
 *    makes the animated height and the drawn text line up.
 *  - `onTextLayout` (`:157-160`), how upstream learns the width it was laid out at and re-measures,
 *    becomes [WearFadingExpandingLabelView.onWidthMeasured] writing the same state, so the two-pass
 *    measure — `maxTextWidth` null on the first pass, `Constraints()` there (`:133`), unbounded here —
 *    survives as it does upstream.
 *  - `LocalTextConfiguration` is a known gap, so [textAlign] defaults to null and [maxLines] to
 *    `Int.MAX_VALUE`, the same choices [Text] makes.
 *
 * Not ported:
 *  - `fontStyle` and `textDecoration` (`:91`, `:95`): Hibari's [TextStyle] has neither field and
 *    `Modifier.textStyle` has no way to apply them, so there is nothing to merge them into.
 *  - `minLines` (`:100`) is kept as a parameter and does nothing, because upstream never reads it — it
 *    is absent from the measure call (`:126-134`) and from the `Text` it draws (`:155-191`). Carrying a
 *    dead parameter is the faithful option; making it work would be inventing behaviour.
 *  - `softWrap` reaches the measurement only (`:129`), exactly as upstream, whose drawn `Text` never
 *    receives it either.
 *
 * @param style Defaults to null rather than `currentTextStyle()`: a `@Tunable` default expression is
 *   hoisted into a non-`@Tunable` `$default` method, which cannot read `LocalTextStyle`.
 * @param animationSpec Defaults to null for the same reason; resolves
 *   [FadingExpandingLabelDefaults.animationSpec].
 */
@Tunable
@Suppress("UNUSED_PARAMETER")
fun FadingExpandingLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle? = null,
    animationSpec: FiniteAnimationSpec<Float>? = null,
) {
    val density = LocalDensity.current
    val spToPx = density.density * density.fontScale
    val layoutDirection = LocalLayoutDirection.current
    val base = style ?: currentTextStyle()
    // `mergedTextStyle` (`:109-121`), minus the two fields Hibari's TextStyle does not carry.
    val merged = base.copy(
        fontSize = fontSize.fadingLabelOr(base.fontSize),
        letterSpacing = letterSpacing.fadingLabelOr(base.letterSpacing),
        lineHeight = lineHeight.fadingLabelOr(base.lineHeight),
        fontWeight = fontWeight ?: base.fontWeight,
        fontFamily = fontFamily ?: base.fontFamily,
    )
    val ambient = LocalContentColor.current
    val resolvedColor = color.takeOrElse { ambient }

    val drawnTextState = remember { mutableStateOf(text) }
    val maxTextWidthState = remember { mutableStateOf<Int?>(null) }
    val showAnimatedHeightState = remember { mutableStateOf(false) }

    // `textMeasureResult` (`:124-135`): remembered on the keys upstream uses plus the two numbers the
    // measurement itself depends on.
    val measuredWidthPx = maxTextWidthState.value
    val measureResult = remember(
        text,
        merged,
        resolvedColor,
        measuredWidthPx,
        softWrap,
        maxLines,
        textAlign,
        layoutDirection,
        spToPx,
    ) {
        fadingLabelMeasure(
            text = text,
            style = merged,
            color = resolvedColor,
            maxWidthPx = measuredWidthPx,
            maxLines = maxLines,
            softWrap = softWrap,
            textAlign = textAlign,
            layoutDirection = layoutDirection,
            density = density.density,
            fontScale = density.fontScale,
        )
    }

    val currentMeasureResultState = remember { mutableStateOf(measureResult) }
    val animatedHeight = remember { Animatable(measureResult.height.toFloat()) }
    val resolvedSpec = animationSpec ?: FadingExpandingLabelDefaults.animationSpec

    LaunchedEffect(measureResult) {
        // Don't animate if text hasn't changed (`:139-145`).
        if (text == drawnTextState.value && !showAnimatedHeightState.value) {
            currentMeasureResultState.value = measureResult
            animatedHeight.snapTo(measureResult.height.toFloat())
            return@LaunchedEffect
        }

        drawnTextState.value = text
        currentMeasureResultState.value = measureResult
        showAnimatedHeightState.value = true
        // Animate to the new text height to reveal it with a fade-in animation (`:150-152`).
        animatedHeight.animateTo(measureResult.height.toFloat(), resolvedSpec)
    }

    // Upstream reads `showAnimatedTextHeight` and `animatedHeight.value` in its modifier chain, so the
    // height animates by recomposition. `bindState` writes the same pair into the view per frame without
    // re-tuning the tree; see [AnimatedText] for the same choice. Both captured instances are
    // keyless-remembered, so the derived state is built once and keeps its subscription — keying it on
    // `animatedHeight` itself would rebuild the derived state on the tune that starts the animation.
    val revealHeightState = remember {
        derivedStateOf {
            if (showAnimatedHeightState.value) animatedHeight.value else Float.NaN
        }
    }

    Node(
        modifier = modifier
            .viewClass(WearFadingExpandingLabelView::class.java)
            .thenViewAttribute<WearFadingExpandingLabelView, StaticLayout>(
                uniqueKey,
                currentMeasureResultState.value,
            ) { layout -> textLayout = layout }
            // The width sink is named, not `it`: the lambda it installs would otherwise capture the
            // applier's own implicit `it`.
            .thenViewAttribute<WearFadingExpandingLabelView, MutableState<Int?>>(
                uniqueKey,
                maxTextWidthState,
            ) { widthSink -> onWidthMeasured = { widthPx -> widthSink.value = widthPx } }
            .bindState(uniqueKey, revealHeightState) { reveal ->
                (this as WearFadingExpandingLabelView).revealHeightPx = reveal
            }
    )
}

/** Contains default values for [FadingExpandingLabel] (`FadingExpandingLabel.kt:194-200`). */
object FadingExpandingLabelDefaults {

    /**
     * Default animation spec for [FadingExpandingLabel]: upstream's
     * `MaterialTheme.motionScheme.slowEffectsSpec<Float>()` (`:199`), which `MotionScheme.standard()`
     * builds as `spring(dampingRatio = EffectsDampingRatio, stiffness = EffectsSlowStiffness)`
     * (`material3/MotionScheme.kt:147-151`) out of `EffectsDampingRatio = Spring.DampingRatioNoBouncy`
     * (`:261`) and `EffectsSlowStiffness = 260f` (`:265`). Hibari's `MotionScheme.standard()` builds
     * the same spring, but reaching it means reading `MaterialTheme.motionScheme`, a `@Tunable`
     * member this plain property cannot evaluate — upstream's `@Composable get()` has no `@Tunable`
     * default-expression counterpart either — so the spec is built here from the same two numbers,
     * sharing the `EffectsSlowStiffness` constant declared in `MotionScheme.kt`.
     */
    val animationSpec: FiniteAnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = EffectsSlowStiffness,
    )
}

/**
 * Draws a label whose box height animates while its lines fade in.
 *
 * The renderer behind [FadingExpandingLabel]: upstream expresses the same thing as
 * `Modifier.height(...).graphicsLayer { Offscreen }.drawWithContent { ... }` (`:161-183`).
 *
 * It draws a [StaticLayout] handed to it instead of measuring a `CharSequence` itself, because the height
 * the animation targets must come from the same measurement the label is drawn with — upstream has one
 * source of truth too, in `textMeasureResult` (`:124-136`). Being a plain [View] rather than a
 * `TextView` is what allows the height to be forced mid-animation without the view re-laying-out the
 * text and clobbering the reveal, which is upstream's `overflow = TextOverflow.Visible` (`:189`) doing
 * the same job.
 */
class WearFadingExpandingLabelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** The laid-out text, built by the composable. A new instance means a new layout pass. */
    var textLayout: StaticLayout? = null
        set(value) {
            if (field === value) return
            field = value
            requestLayout()
            invalidate()
        }

    /**
     * The box height to reveal to, in px, or `NaN` while the label has never changed text — upstream's
     * `showAnimatedTextHeight` flag and `Modifier.height(animatedHeight)` collapsed into one number
     * (`:107`, `:162-164`).
     */
    var revealHeightPx: Float = Float.NaN
        set(value) {
            // `Float.NaN == Float.NaN` is false, so an unguarded setter would ask for a layout pass on
            // every frame forever once the label has settled.
            if (field == value || (field.isNaN() && value.isNaN())) return
            field = value
            requestLayout()
            invalidate()
        }

    /**
     * Reports the width this view was measured against, which is how the composable re-measures the text
     * the way upstream's `onTextLayout` does (`:157-160`). Called from [onMeasure] only when the number
     * actually moved, so the feedback settles after one extra pass instead of looping.
     */
    var onWidthMeasured: ((Int) -> Unit)? = null

    private var lastReportedWidthPx = -1

    private val fadePaint = Paint().apply {
        // Upstream's `Color(255, 255, 255, alpha)` drawn with `BlendMode.Modulate` (`:176-180`); for a
        // white source DST_IN is the same premultiplied product, without the API 29 gate.
        color = 0xFFFFFFFF.toInt()
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val layout = textLayout
        val naturalHeight = layout?.height ?: 0
        val wantedHeight = if (revealHeightPx.isNaN()) naturalHeight
        else revealHeightPx.roundToInt().coerceAtLeast(0)
        setMeasuredDimension(
            resolveSize(naturalWidthPx(layout), widthMeasureSpec),
            resolveSize(wantedHeight, heightMeasureSpec),
        )

        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val available = MeasureSpec.getSize(widthMeasureSpec)
            if (available != lastReportedWidthPx) {
                lastReportedWidthPx = available
                onWidthMeasured?.invoke(available)
            }
        }
    }

    /** The widest line, which is what `wrapContentWidth` means for a laid-out label. */
    private fun naturalWidthPx(layout: StaticLayout?): Int {
        if (layout == null) return 0
        var widest = 0f
        for (i in 0 until layout.lineCount) widest = max(widest, layout.getLineWidth(i))
        return ceil(widest).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val layout = textLayout ?: return
        val reveal = revealHeightPx
        if (reveal.isNaN()) {
            layout.draw(canvas)
            return
        }

        val paint = fadePaint
        val layerBottom = max(layout.height.toFloat(), reveal)
        val count = canvas.saveLayer(0f, 0f, width.toFloat(), layerBottom, paint)
        layout.draw(canvas)
        for (i in 0 until layout.lineCount) {
            val top = layout.getLineTop(i).toFloat()
            val bottom = layout.getLineBottom(i).toFloat()
            if (reveal < bottom) {
                val alpha = ((reveal - top) / (bottom - top) - 0.5f) * 2
                paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                // `top` to `top + bottom`, because upstream's size term is `bottom`, not
                // `bottom - top` (`:178-179`). See the component note.
                canvas.drawRect(0f, top, width.toFloat(), top + bottom, paint)
            }
        }
        canvas.restoreToCount(count)
    }
}

/**
 * Measures the label the way [WearFadingExpandingLabelView] draws it (`:123-135`), where
 * `Constraints(maxWidth = it) ?: Constraints()` (`:133`) is an unbounded [StaticLayout] width. Unbounded
 * is [FadingLabelUnboundedWidthPx] rather than `Int.MAX_VALUE`, because `StaticLayout` scales its width
 * internally and a full-range int overflows.
 */
private fun fadingLabelMeasure(
    text: String,
    style: TextStyle,
    color: Color,
    maxWidthPx: Int?,
    maxLines: Int,
    softWrap: Boolean,
    textAlign: TextAlign?,
    layoutDirection: LayoutDirection,
    density: Float,
    fontScale: Float,
): StaticLayout {
    val paint = fadingLabelTextPaint(style, color, density, fontScale)
    val width = if (!softWrap || maxWidthPx == null) FadingLabelUnboundedWidthPx else maxWidthPx
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(fadingLabelAlignment(textAlign, layoutDirection))
        .setLineSpacing(fadingLabelLineSpacingExtra(style, density, fontScale), 1f)
        .setIncludePad(true)
        .apply { if (maxLines < Int.MAX_VALUE) setMaxLines(maxLines) }
        .build()
}

/**
 * The [TextPaint] [com.huanli233.hibari.wear.attributes.textStyle] would hand a `TextView` for the same
 * [style] (`attributes/ContainerAttributes.kt:49-76`), including its sp-to-px (`textSize` takes sp and
 * scales by `density * fontScale`) and its tracking-as-em. The two formulas have to agree or the
 * animated box and the drawn text disagree by a pixel on every line.
 */
private fun fadingLabelTextPaint(
    style: TextStyle,
    color: Color,
    density: Float,
    fontScale: Float,
): TextPaint {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val sizeSp = if (style.fontSize.isSp) style.fontSize.value else null
    sizeSp?.let { paint.textSize = it * density * fontScale }

    val tracking = style.letterSpacing
    if (tracking != TextUnit.Unspecified && sizeSp != null && sizeSp > 0f) {
        paint.letterSpacing = tracking.value / sizeSp
    }

    style.fontFamily.typeface(style.fontWeight.toAndroidStyle())?.let { paint.typeface = it }
    if (Build.VERSION.SDK_INT >= 28) {
        paint.typeface = style.fontWeight.createTypeface(paint.typeface ?: Typeface.DEFAULT)
    }
    if (Build.VERSION.SDK_INT >= 26) {
        style.fontWeight.fontVariationSettings(style.widthAxis)?.let { settings ->
            if (settings.isNotEmpty()) runCatching { paint.fontVariationSettings = settings }
        }
        style.fontFeatureSettings?.let { features ->
            runCatching { paint.fontFeatureSettings = features }
        }
    }
    if (color.isSpecified) paint.color = color.toArgb()
    return paint
}

/** `Modifier.textStyle`'s line spacing (`ContainerAttributes.kt:59-64`), as a `StaticLayout` extra. */
private fun fadingLabelLineSpacingExtra(
    style: TextStyle,
    density: Float,
    fontScale: Float,
): Float {
    val sizeSp = if (style.fontSize.isSp) style.fontSize.value else return 0f
    if (!style.lineHeight.isSp || sizeSp <= 0f) return 0f
    return ((style.lineHeight.value - sizeSp) * density * fontScale).coerceAtLeast(0f)
}

/**
 * [TextAlign] onto `StaticLayout`'s alignment. `ALIGN_OPPOSITE` flips with the paragraph direction, so
 * an `End`/`Right` label on an LTR screen is right-aligned and one on an RTL screen is left-aligned —
 * upstream hands the same job to `TextAlign` on the drawn `Text` (`:190`). A null alignment with an RTL
 * layout direction follows the screen, which is what upstream's `LocalTextConfiguration` default does.
 */
private fun fadingLabelAlignment(
    textAlign: TextAlign?,
    layoutDirection: LayoutDirection,
): Layout.Alignment = when {
    textAlign == TextAlign.Center -> Layout.Alignment.ALIGN_CENTER
    textAlign == TextAlign.Right || textAlign == TextAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
    layoutDirection == LayoutDirection.Rtl &&
        (textAlign == null || textAlign == TextAlign.Start) -> Layout.Alignment.ALIGN_OPPOSITE
    else -> Layout.Alignment.ALIGN_NORMAL
}

/** Upstream's `Constraints()` on the first pass (`:133`), halved so `StaticLayout` cannot overflow. */
private const val FadingLabelUnboundedWidthPx = Int.MAX_VALUE / 2

private fun TextUnit.fadingLabelOr(other: TextUnit): TextUnit =
    if (this != TextUnit.Unspecified) this else other

private fun Float.ceilToInt(): Int = kotlin.math.ceil(this).toInt()
