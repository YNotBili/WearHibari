package com.huanli233.hibari.wear

import android.graphics.fonts.Font
import android.graphics.fonts.FontVariationAxis
import android.graphics.text.TextRunShaper
import android.os.Build
import android.text.TextPaint
import android.util.LruCache
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.bindState
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.derivedStateOf
import com.huanli233.hibari.runtime.locals.LocalDensity
import com.huanli233.hibari.runtime.locals.LocalLayoutDirection
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.layout.Alignment
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.text.fontVariationSettings
import com.huanli233.hibari.ui.text.toAndroidStyle
import com.huanli233.hibari.ui.unit.Density
import com.huanli233.hibari.ui.unit.LayoutDirection
import com.huanli233.hibari.ui.unit.TextUnit
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.view.WearAnimatedTextView
import kotlin.math.floor

/**
 * The stable props [WearAnimatedTextView] needs, as a comparable value so a retune that changes
 * nothing re-shapes nothing. [fontRegistry] is compared by identity, which is upstream's rule too —
 * `remember(fontRegistry, layoutDirection, density)` (`AnimatedText.kt:116`).
 *
 * The animated fraction is deliberately not here: it arrives through `Modifier.bindState`, because
 * putting it in a value that is rebuilt every tune would re-apply this whole spec — and re-shape the
 * text — once per frame. See [AnimatedText].
 */
data class AnimatedTextSpec(
    val text: CharSequence,
    val fontRegistry: AnimatedTextFontRegistry,
    val contentAlignment: Alignment = Alignment.Center,
    val layoutDirection: LayoutDirection = LayoutDirection.Ltr,
)

fun Modifier.animatedText(spec: AnimatedTextSpec): Modifier =
    this.thenViewAttribute<WearAnimatedTextView, AnimatedTextSpec>(uniqueKey, spec) {
        text = it.text
        fontRegistry = it.fontRegistry
        contentAlignment = it.contentAlignment
        layoutDirection = it.layoutDirection
    }

/**
 * Ported from androidx.wear.compose.material3.AnimatedText.
 *
 * This is *not* a cross-fade between two strings: [text] is one string whose **variable-font axes
 * and size morph** from the registry's start configuration to its end configuration as
 * [progressFraction] travels 0f to 1f (`AnimatedText.kt:70-82`). The animation clock is the
 * caller's — upstream takes `progressFraction: () -> Float` and the component never animates itself.
 *
 * Deviations from upstream's shape, one per bullet; the drawing-side ones are expanded in
 * [WearAnimatedTextView]:
 *  - `Canvas(modifier.size(state.size))` becomes a `View` whose `onMeasure` reports the same
 *    max(start, end) box (`AnimatedText.kt:122-123`, `:436`, `:545-548`).
 *  - The reduce-motion branch (`AnimatedText.kt:114`, `:132-133`) **is** ported: upstream samples
 *    `LocalReduceMotion.current` and then picks the drawn fraction with
 *    `if (isReduceMotionEnabled) 1f else progressFraction()`, and that same `if` is what
 *    [derivedStateOf] evaluates here, so under reduce motion [progressFraction] is never called and
 *    the view is handed the end configuration (`view/WearAnimatedTextView.kt:112-125`).
 *    The remaining deviation is *how the setting is known*: Hibari has no reduce-motion
 *    `TunationLocal` — this module's only source is [wearReduceMotionEnabled] in `ReduceMotion.kt`,
 *    a **live** `Settings.Global` read sampled once per tune, deliberately not cached and not
 *    observed (`ReduceMotion.kt:118-131`, with its header at `:64-72` explaining what it does not
 *    reproduce from upstream's `compositionLocalWithComputedDefaultOf`). So a wearer who flips the
 *    setting mid-animation sees the change on this node's *next* tune, not on the next frame;
 *    upstream's `ContentObserver` (`foundation/CompositionLocals.kt:44-52`) is what we cannot match.
 *    Upstream's second `updateText(text)` under reduce motion (`AnimatedText.kt:127-129`) has no
 *    counterpart and needs none: it is there because the Canvas lambda is the only thing that
 *    re-reads the string once nothing animates, whereas [text] here travels into
 *    [AnimatedTextSpec] on every tune and a changed value re-shapes the view
 *    (`view/WearAnimatedTextView.kt:74-79`). Our string cannot go stale, so there is nothing to
 *    refresh.
 *  - `semantics { text = AnnotatedString(text) }` (`AnimatedText.kt:123-125`) is not ported: a
 *    `TextView`-less custom `View` gets no accessibility text node here, the same gap every other
 *    drawn text view in this module has.
 *
 * @param progressFraction Upstream invokes this inside the draw scope (`AnimatedText.kt:133`), so the
 *   Canvas recomposes per frame. Here the lambda is wrapped in [derivedStateOf] and piped into the
 *   view with [bindState], which subscribes to the state directly: the snapshot read stays inside the
 *   derived state's own scope, so an animating fraction drives one view property instead of
 *   re-tuning the whole host, and the draw pass reads the same number upstream would have.
 * @param contentAlignment Defaults to `Alignment.Center`, upstream's default
 *   (`AnimatedText.kt:110`); it is a plain companion value, so it is legal as a `@Tunable` default.
 */
@Tunable
fun AnimatedText(
    text: String,
    fontRegistry: AnimatedTextFontRegistry,
    progressFraction: () -> Float,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
) {
    val reduceMotion = wearReduceMotionEnabled(currentContext)
    // Upstream chooses the fraction inside the draw scope — `if (isReduceMotionEnabled) 1f else
    // progressFraction()` (`material3/AnimatedText.kt:133`) — after sampling `LocalReduceMotion` at
    // `:114`. The same choice is made here, and when the setting is on the derived state reads nothing
    // outside its own constant, so a caller's animation cannot pull the text back into motion.
    val fractionState = remember(progressFraction, reduceMotion) {
        derivedStateOf { if (reduceMotion) 1f else progressFraction() }
    }
    val spec = AnimatedTextSpec(
        text = text,
        fontRegistry = fontRegistry,
        contentAlignment = contentAlignment,
        layoutDirection = LocalLayoutDirection.current,
    )
    Node(
        modifier = modifier
            .viewClass(WearAnimatedTextView::class.java)
            .animatedText(spec)
            .bindState(uniqueKey, fractionState) {
                (this as WearAnimatedTextView).fraction = it
            }
    )
}

/**
 * Generates an [AnimatedTextFontRegistry] to use within composition.
 *
 * Start and end of the animation are when the animatable is at 0f and 1f, respectively. The API
 * supports overshooting, so a generated font can be extrapolated outside
 * [startFontVariationSettings] and [endFontVariationSettings] — the caller must make sure the font
 * supports the settings throughout the animation (`AnimatedText.kt:142-145`).
 *
 * Upstream's `FontVariation.Settings` (`AnimatedText.kt:156-157`) is a Compose text type with no
 * Hibari analogue; the variation axes travel as a raw `fontVariationSettings` string instead — the
 * same currency this module already uses for tracking a weight axis
 * (`FontWeight.fontVariationSettings(widthAxis)`, applied in
 * [WearCurvedTextView.configurePaint]` style) — parsed by
 * [FontVariationAxis.fromFontVariationSettings] at [AnimatedTextFontRegistry] construction.
 *
 * @param style Defaults to null rather than `currentTextStyle()` because a `@Tunable` default
 *   expression is hoisted into a non-`@Tunable` `$default` method, which cannot read
 *   `LocalTextStyle`; the resolution happens in the body.
 * @param startFontSize / @param endFontSize Default to [TextUnit.Unspecified] for the same reason —
 *   upstream's default is `textStyle.fontSize` (`AnimatedText.kt:159-160`), which needs the resolved
 *   style. Unspecified reads the style's own size.
 * @param color Upstream folds `textStyle.color` into the content colour
 *   (`AnimatedText.kt:165`); Hibari's [TextStyle] carries no colour, so this is
 *   `color` then [LocalContentColor], and a still-unspecified result falls back to white in the
 *   registry, which is what [com.huanli233.hibari.wear.view.WearCurvedTextView] does for the same
 *   reason.
 */
@Tunable
fun rememberAnimatedTextFontRegistry(
    startFontVariationSettings: String,
    endFontVariationSettings: String,
    style: TextStyle? = null,
    startFontSize: TextUnit = TextUnit.Unspecified,
    endFontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
): AnimatedTextFontRegistry {
    val provided = LocalDensity.current
    val densityValue = provided.density
    val fontScaleValue = provided.fontScale
    val resolvedStyle = style ?: currentTextStyle()
    val ambient = LocalContentColor.current
    val resolvedColor = color.takeOrElse { ambient }
    return remember(
        resolvedStyle,
        startFontVariationSettings,
        endFontVariationSettings,
        startFontSize,
        endFontSize,
        resolvedColor,
        densityValue,
        fontScaleValue,
    ) {
        AnimatedTextFontRegistry(
            textStyle = resolvedStyle,
            startFontVariationSettings = startFontVariationSettings,
            endFontVariationSettings = endFontVariationSettings,
            startFontSize = startFontSize,
            endFontSize = endFontSize,
            density = Density(densityValue, fontScaleValue),
            contentColor = resolvedColor,
        )
    }
}

/**
 * Generates fonts to be used by [AnimatedText] throughout the animation.
 *
 * Ported from `AnimatedTextFontRegistry` (`AnimatedText.kt:210-399`). Reusable between multiple
 * [AnimatedText] nodes to save memory (`AnimatedText.kt:196-197`).
 *
 * Two upstream constructor parameters are gone, both for the same reason — Hibari resolves a typeface
 * without a context:
 *  - `fontFamilyResolver: FontFamily.Resolver` (`AnimatedText.kt:218`, used `:288-299`): upstream
 *    needs it to turn a `FontFamily` + weight + style + synthesis into a `Typeface`.
 *    [com.huanli233.hibari.ui.text.FontFamily.typeface] does that directly, so the registry just
 *    calls it.
 *  - `density: Density` is kept as a [com.huanli233.hibari.ui.unit.Density], because Hibari's
 *    `Density` carries the same `TextUnit.toPx()` upstream calls at `:222-223`.
 *
 * Upstream's `textStyle.textDirection` (`AnimatedText.kt:226`) is not carried: Hibari's [TextStyle]
 * has no text-direction field, so the drawing side falls back on the layout direction alone.
 *
 * @param startFontVariationSettings / @param endFontVariationSettings A `fontVariationSettings`
 *   string, e.g. `"'wght' 400,'wdth' 100"`. Parsed on API 26+; below that no axis is animated and
 *   only the font size morphs, because `FontVariationAxis.fromFontVariationSettings` is API 26.
 */
class AnimatedTextFontRegistry(
    private val textStyle: TextStyle,
    private val startFontVariationSettings: String,
    private val endFontVariationSettings: String,
    private val startFontSize: TextUnit,
    private val endFontSize: TextUnit,
    private val density: Density,
    private val contentColor: Color = Color.Unspecified,
    cacheSize: Int = AnimatedTextDefaults.CacheSize,
) {
    private val startFontSizePx = animatedTextFontPx(startFontSize, textStyle, density)
    private val endFontSizePx = animatedTextFontPx(endFontSize, textStyle, density)

    private val startAxes: Array<FontVariationAxis> = parseAnimatedTextAxes(startFontVariationSettings)
    private val endAxes: Array<FontVariationAxis> = parseAnimatedTextAxes(endFontVariationSettings)

    /**
     * Returns the font at a certain [fraction] of the animation. [text] is required to extract the
     * initial font to draw the animation with (`AnimatedText.kt:228-231`).
     *
     * API 31 only, which is upstream's own gate (`AnimatedText.kt:210`, `@RequiresApi(31)`): building
     * a `Font` off a `Typeface` needs the shaped glyph run's font, and there is no public
     * `Font.Builder(Typeface)` overload.
     */
    internal fun getFont(text: String, fraction: Float): Font {
        val snappedFraction =
            floor(fraction / AnimatedTextDefaults.FractionStep) * AnimatedTextDefaults.FractionStep
        return when (fraction) {
            0f -> getStartFont(text)
            1f -> getEndFont(text)
            else -> {
                val cached = fontCache[fraction]
                val font = cached
                    ?: Font.Builder(getStartFont(text))
                        .setFontVariationSettings(
                            lerpFontVariationSettings(
                                startAxes,
                                endAxes,
                                snappedFraction,
                            )
                        )
                        .build()
                fontCache.put(fraction, font)
                font
            }
        }
    }

    /** Returns the font size at a certain point in the animation, in px. */
    internal fun getFontSize(fraction: Float): Float =
        animatedTextLerp(startFontSizePx, endFontSizePx, fraction)

    /**
     * The variation settings for the current fraction, as the string `TextPaint` accepts.
     *
     * Not an upstream member: it is what the below-API-31 draw path animates, since that path cannot
     * build a [Font] and has to drive the axes through `Paint.setFontVariationSettings` (API 26)
     * instead.
     */
    internal fun variationSettingsAt(fraction: Float): String =
        if (Build.VERSION.SDK_INT < 26 || startAxes.isEmpty()) {
            textStyle.fontWeight.fontVariationSettings(textStyle.widthAxis) ?: ""
        } else {
            FontVariationAxis.toFontVariationSettings(
                lerpFontVariationSettings(startAxes, endAxes, fraction)
            )
        }

    /** Font cache for animation steps, between the start font at 0f and the end font at 1f. */
    private var fontCache = LruCache<Float, Font>(cacheSize)

    /**
     * Array to store the current font variation axes, empty at the start. This helps reduce
     * allocations during the draw phase (`AnimatedText.kt:263-269`).
     */
    private var currentAxes = Array(startAxes.size) { FontVariationAxis("null", 0f) }

    private var startFont: Font? = null
    private var endFont: Font? = null

    /** TextPaint used when drawing onto the canvas, initially set to the start font. */
    internal val startWorkingPaint: TextPaint

    /** TextPaint that reflects how the end font should look. */
    internal val endWorkingPaint: TextPaint

    /** The size the start configuration is drawn at, in px (`AnimatedText.kt:222`). */
    internal val startFontSizePxValue: Float get() = startFontSizePx

    /** The size the end configuration is drawn at, in px (`AnimatedText.kt:223`). */
    internal val endFontSizePxValue: Float get() = endFontSizePx

    /**
     * The style the text is drawn with: the typeface is read off it by both working paints, and the
     * below-API-31 path reads its feature settings off it too.
     */
    internal val style: TextStyle get() = textStyle

    init {
        startWorkingPaint = generateStartWorkingPaint()
        endWorkingPaint = generateEndWorkingPaint(startWorkingPaint)
    }

    /** `AnimatedTextFontRegistry.generateStartWorkingPaint` (`AnimatedText.kt:288-309`). */
    private fun generateStartWorkingPaint(): TextPaint {
        val paint = TextPaint()
        paint.typeface = textStyle.fontFamily.typeface(textStyle.fontWeight.toAndroidStyle())
        paint.color = (if (contentColor.isSpecified) contentColor else Color.White).toArgb()
        paint.textSize = startFontSizePx
        paint.setFontVariationSettingsSafe(
            FontVariationAxis.toFontVariationSettings(
                lerpFontVariationSettings(startAxes, endAxes, 0f)
            )
        )
        paint.setFontFeatureSettingsSafe(textStyle.fontFeatureSettings)
        return paint
    }

    /** `AnimatedTextFontRegistry.generateEndWorkingPaint` (`AnimatedText.kt:311-320`). */
    private fun generateEndWorkingPaint(startWorkingPaint: TextPaint): TextPaint {
        val paint = TextPaint(startWorkingPaint)
        paint.textSize = endFontSizePx
        paint.setFontVariationSettingsSafe(
            FontVariationAxis.toFontVariationSettings(
                lerpFontVariationSettings(startAxes, endAxes, 1f)
            )
        )
        return paint
    }

    /**
     * `AnimatedTextFontRegistry.lerpFontVariationSettings` (`AnimatedText.kt:322-350`), including its
     * reuse of [currentAxes] and its "an axis missing from the end settings keeps its start value"
     * rule.
     *
     * Upstream asks each `FontVariation.Setting` for `toVariationValue(density)` because Compose's
     * settings carry em-relative entries (`OpticalSize`) that need converting; a raw
     * `fontVariationSettings` string is already in variation units, so the values lerp directly and
     * [density] plays no part here.
     */
    private fun lerpFontVariationSettings(
        start: Array<FontVariationAxis>,
        end: Array<FontVariationAxis>,
        fraction: Float,
    ): Array<FontVariationAxis> {
        start.indices.forEach { startIndex ->
            var endSetting = start[startIndex]
            for (endIndex in end.indices) {
                if (end[endIndex].tag == start[startIndex].tag) {
                    endSetting = end[endIndex]
                    break
                }
            }
            currentAxes[startIndex] = FontVariationAxis(
                start[startIndex].tag,
                animatedTextLerp(
                    start[startIndex].styleValue,
                    endSetting.styleValue,
                    fraction,
                ),
            )
        }
        return currentAxes
    }

    /** `AnimatedTextFontRegistry.getEndFont` (`AnimatedText.kt:352-367`). */
    private fun getEndFont(text: String): Font {
        endFont?.let { return it }
        val font = Font.Builder(getStartFont(text))
            .setFontVariationSettings(lerpFontVariationSettings(startAxes, endAxes, 1f))
            .build()
        endFont = font
        return font
    }

    /**
     * `AnimatedTextFontRegistry.getStartFont` (`AnimatedText.kt:369-398`).
     *
     * Upstream shapes the run purely to get a [Font] to hang variation settings off — `Font` has no
     * public `Typeface` constructor — with the comment "Maybe we can find another way without running
     * shapeTextRun?" (`AnimatedText.kt:373-374`). No cheaper public route exists here either, so the
     * shaping call is kept verbatim.
     */
    private fun getStartFont(text: String): Font {
        startFont?.let { return it }
        val glyphs = TextRunShaper.shapeTextRun(
            text,
            0,
            text.length,
            0,
            text.length,
            0f,
            0f,
            false, // Correct layout direction isn't needed for generating the font
            startWorkingPaint,
        )
        val font = Font.Builder(glyphs.getFont(0))
            .setFontVariationSettings(lerpFontVariationSettings(startAxes, endAxes, 0f))
            .build()
        startFont = font
        return font
    }
}

/** Defaults for [AnimatedText] (`AnimatedText.kt:401-414`). */
object AnimatedTextDefaults {
    /** Default font cache size to be used in [AnimatedTextFontRegistry]. */
    val CacheSize: Int = 5

    /**
     * Default step size used to snap progress fractions: a fraction is rounded *down* to a multiple
     * of this to raise the cache hit rate. 0.016f divides a 1 second animation into 60 steps
     * (`AnimatedText.kt:407-413`).
     */
    internal val FractionStep = 0.016f
}

/**
 * The font size in px, upstream's `with(density) { fontSize.toPx() }` (`AnimatedText.kt:222-223`).
 *
 * Hibari's `Density.TextUnit.toPx()` is Sp-only (`FontScaling` checks the unit), and a
 * [TextStyle.fontWeight] driven label is always sized in sp in this module, so an em-relative or an
 * unspecified size falls back to the platform default text size scaled by density — the same thing a
 * `TextView` with no size does.
 */
private fun animatedTextFontPx(size: TextUnit, style: TextStyle, density: Density): Float {
    val chosen = if (size != TextUnit.Unspecified) size else style.fontSize
    return if (chosen.isSp) with(density) { chosen.toPx() }
    else DEFAULT_FONT_SIZE_PX * density.density
}

private const val DEFAULT_FONT_SIZE_PX = 14f

/** `FontVariationAxis.fromFontVariationSettings` is API 26 and rejects an empty string. */
private fun parseAnimatedTextAxes(settings: String): Array<FontVariationAxis> =
    if (Build.VERSION.SDK_INT < 26 || settings.isEmpty()) emptyArray()
    else runCatching { FontVariationAxis.fromFontVariationSettings(settings) as Array<FontVariationAxis>? }
        .getOrNull() ?: emptyArray()

/**
 * `Paint.setFontVariationSettings` is API 26, and the platform throws on a malformed string rather
 * than returning false on every device, so a failure leaves the paint's own settings alone.
 */
private fun TextPaint.setFontVariationSettingsSafe(settings: String) {
    if (Build.VERSION.SDK_INT < 26 || settings.isEmpty()) return
    runCatching { fontVariationSettings = settings }
}

/**
 * `Paint.setFontFeatureSettings` is API 26, and a malformed string throws rather than returning
 * false, so a failure leaves the paint's own settings alone.
 */
private fun TextPaint.setFontFeatureSettingsSafe(settings: String?) {
    if (settings == null || settings.isEmpty() || Build.VERSION.SDK_INT < 26) return
    runCatching { fontFeatureSettings = settings }
}

/** `androidx.compose.ui.util.lerp(start, stop, fraction)` for Float. */
internal fun animatedTextLerp(start: Float, stop: Float, fraction: Float): Float =
    (1 - fraction) * start + fraction * stop
