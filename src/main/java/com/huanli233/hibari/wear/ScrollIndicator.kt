package com.huanli233.hibari.wear

import android.content.Context
import com.huanli233.hibari.animation.AnimationSpec
import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.animation.tween
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.setLuminance
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.lazy.ScalingLazyListState
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.view.WearScrollIndicatorView
import kotlin.math.sqrt

/**
 * Ported from androidx.wear.compose.material3.ScrollIndicatorColors.
 *
 * Upstream's `copy` treats [Color.Unspecified] as "keep mine", which is why this stays a plain class
 * rather than a data class.
 */
class ScrollIndicatorColors(public val indicatorColor: Color, public val trackColor: Color) {

    fun copy(
        indicatorColor: Color = this.indicatorColor,
        trackColor: Color = this.trackColor,
    ): ScrollIndicatorColors = ScrollIndicatorColors(
        indicatorColor = indicatorColor.takeOrElse { this.indicatorColor },
        trackColor = trackColor.takeOrElse { this.trackColor },
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is ScrollIndicatorColors) return false
        if (indicatorColor != other.indicatorColor) return false
        if (trackColor != other.trackColor) return false
        return true
    }

    override fun hashCode(): Int {
        var result = indicatorColor.hashCode()
        result = 31 * result + trackColor.hashCode()
        return result
    }
}

/**
 * The pair of numbers upstream's internal `IndicatorState` reduces every scrollable container to:
 * where in the track the thumb sits, and how much of the track it covers.
 *
 * Hibari has no observable scroll state to adapt, so [ScrollIndicator] takes these as data. The two
 * factories below are the ported adapters, one per upstream state type that survives here.
 */
data class ScrollIndicatorFractions(val positionFraction: Float, val sizeFraction: Float) {

    companion object {

        /**
         * Upstream `ScrollStateAdapter`, for a container that reports a raw pixel offset — a
         * `ScrollView` or `NestedScrollView` whose listener the caller already has. [containerHeightPx]
         * is the height upstream measures off the indicator's own box, which is
         * `ScrollIndicatorDefaults.IndicatorHeight` plus its width.
         */
        fun forScrollState(
            scrollValue: Int,
            maxValue: Int,
            containerHeightPx: Int,
        ): ScrollIndicatorFractions {
            val position = if (maxValue == 0) 0f else scrollValue.toFloat() / maxValue
            val containerHeight = containerHeightPx.toFloat()
            val size = if (containerHeight + maxValue == 0.0f) {
                ScrollIndicatorDefaults.MaxSizeFraction
            } else {
                (containerHeight / (containerHeight + maxValue)).coerceIn(
                    ScrollIndicatorDefaults.MinSizeFraction,
                    ScrollIndicatorDefaults.MaxSizeFraction,
                )
            }
            return ScrollIndicatorFractions(position, size)
        }

        /**
         * Upstream `ScalingLazyColumnStateAdapter`, over what [ScalingLazyListState] actually
         * publishes today. Read this next to the class KDoc on [ScrollIndicator], which lists the
         * accessors that are still missing; this is not the full port and does not pretend to be.
         *
         * Upstream's arithmetic is kept verbatim — a decimal first and last visible index, then
         * `dFirst / (dFirst + total - dLast)` for the position and `(dLast - dFirst) / total` for the
         * size, clamped to `[MinSizeFraction, MaxSizeFraction]` — with the two viewport edge indices
         * rebuilt around [centerItemIndex] instead of read off `layoutInfo`, so they describe a
         * [visibleItemCount]-wide window sitting on the focal item. That is exact at both ends of the
         * list, and in the middle it is upstream's own formula fed with a centred window rather than
         * a measured one.
         *
         * @param visibleItemCount how many items the viewport shows; nothing in Hibari can answer
         *   that yet, so `null` degrades the window to the single centred item, which makes the
         *   position `centerItemIndex / (itemCount - 1)` and the thumb upstream's minimum span.
         */
        fun forScalingLazyColumn(
            centerItemIndex: Int,
            itemCount: Int,
            visibleItemCount: Int?,
            canScrollForward: Boolean,
            canScrollBackward: Boolean,
        ): ScrollIndicatorFractions {
            if (itemCount <= 0) return ScrollIndicatorFractions(0f, 0f)
            // The only scrollability signal the state exposes. A list that scrolls neither way holds
            // all of its content, which is what upstream's own arithmetic yields for that case.
            if (!canScrollForward && !canScrollBackward) {
                return ScrollIndicatorFractions(0f, ScrollIndicatorDefaults.MaxSizeFraction)
            }

            val span = (visibleItemCount ?: 1).coerceAtLeast(1).toFloat()
            val half = span / 2f
            val total = itemCount.toFloat()
            // Item boundaries in index space: with `centerItemIndex` on the focal line, the viewport
            // runs from `c + 0.5 - span/2` to `c + 0.5 + span/2`, which is exactly a `span`-item
            // difference, the quantity upstream's sizeFraction divides by the total.
            val decimalFirstItemIndex = (centerItemIndex + 0.5f - half).coerceIn(0f, total)
            val decimalLastItemIndex = (centerItemIndex + 0.5f + half).coerceIn(0f, total)
            val distanceFromEnd = total - decimalLastItemIndex
            val position = if (decimalFirstItemIndex + distanceFromEnd == 0f) {
                0f
            } else {
                decimalFirstItemIndex / (decimalFirstItemIndex + distanceFromEnd)
            }
            val size = ((decimalLastItemIndex - decimalFirstItemIndex) / total).coerceIn(
                ScrollIndicatorDefaults.MinSizeFraction,
                ScrollIndicatorDefaults.MaxSizeFraction,
            )
            return ScrollIndicatorFractions(position, size)
        }
    }
}

/** The drawing inputs for one frame of [WearScrollIndicatorView]; immutable so diffing is free. */
data class ScrollIndicatorSpec(
    val positionFraction: Float,
    val sizeFraction: Float,
    val indicatorColor: Color,
    val trackColor: Color,
    val indicatorHeight: Dp,
    val indicatorWidth: Dp,
    val gapHeight: Dp,
    val paddingHorizontal: Dp,
    val screenWidth: Dp,
    val reverseDirection: Boolean,
    val positionAnimationSpec: AnimationSpec<Float>,
    val boxSize: DpSize,
)

/** Pushes a [ScrollIndicatorSpec] onto the drawing view. */
fun Modifier.scrollIndicatorSpec(spec: ScrollIndicatorSpec): Modifier =
    this.thenViewAttribute<WearScrollIndicatorView, ScrollIndicatorSpec>(uniqueKey, spec) { this.spec = it }

/**
 * Ported from androidx.wear.compose.material3.ScrollIndicator.
 *
 * Place it at the centre end of the screen, as upstream's docs require: it draws on the circle the
 * bezel describes, so it reads as a curve on a round screen and as a bar on a square one.
 *
 * It animates: a change to either fraction is tweened by [positionAnimationSpec] inside
 * [WearScrollIndicatorView] with a `ValueAnimator`, the precedent this module already set in
 * `ContainerDrawable`. Upstream runs the same two transitions through `Animatable` and a
 * `snapshotFlow`, and adds `jiggleAmount` to the position target while overscrolling; overscroll does
 * not exist here, and [positionFraction] and [sizeFraction] ride along on the same animator rather
 * than two, which is indistinguishable for the default spec because upstream animates both with the
 * same one.
 *
 * @param positionFraction where in the track the thumb sits, `0f` at the start, `1f` at the end.
 * @param sizeFraction how much of the track the thumb covers; `1f` is the whole of it.
 * @param colors Defaults to `null` and resolves in the body: `ScrollIndicatorDefaults.colors()` reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot.
 * @param reverseDirection flips the direction the position runs in. Upstream defaults this from
 *   `state.layoutInfo.reverseLayout`, which [ScalingLazyListState] does not expose, so it defaults to
 *   `false` here.
 */
@Tunable
fun ScrollIndicator(
    positionFraction: Float,
    sizeFraction: Float,
    modifier: Modifier = Modifier,
    colors: ScrollIndicatorColors? = null,
    reverseDirection: Boolean = false,
    positionAnimationSpec: AnimationSpec<Float> = ScrollIndicatorDefaults.PositionAnimationSpec,
) {
    val resolved = colors ?: ScrollIndicatorDefaults.colors()
    ScrollIndicatorImpl(
        fractions = ScrollIndicatorFractions(positionFraction, sizeFraction),
        modifier = modifier,
        colors = resolved,
        reverseDirection = reverseDirection,
        positionAnimationSpec = positionAnimationSpec,
    )
}

/**
 * The [ScalingLazyListState] overload of [ScrollIndicator], upstream's `ScrollIndicator(state = ...)`.
 *
 * **What it can and cannot read.** Upstream drives this off `state.layoutInfo`: the ordered visible
 * item list with each item's index, size and viewport-centred offset, `totalItemsCount`,
 * `viewportSize.height`, `anchorType`, the four content/auto-centring paddings, and `reverseLayout`.
 * Hibari's [ScalingLazyListState] publishes `centerItemIndex`, `centerItemScrollOffset`,
 * `isScrollInProgress`, `canScrollForward` and `canScrollBackward` — and none of the rest. Rather
 * than invent the missing numbers or reach past the state's public surface into its recycler,
 * [itemCount] and [visibleItemCount] are parameters, and [ScrollIndicatorFractions.forScalingLazyColumn]
 * rebuilds the two decimal edge indices around `centerItemIndex`:
 *  - the position is exact at both ends of the list, and in the middle it is upstream's formula fed
 *    with a window centred on the focal item rather than one measured at the viewport edges;
 *  - with [visibleItemCount] left `null` the window is one item wide, so the thumb takes upstream's
 *    minimum span and the position reads `centerItemIndex / (itemCount - 1)`;
 *  - and before the list view exists both `canScroll*` read `false`, which the "content fits" branch
 *    reports as a full-length thumb at the top.
 *
 * For the record, the accessors [ScalingLazyListState] would need to make this a full port:
 * `totalItemsCount`, `viewportSize`, the ordered `visibleItemsInfo` (index, size and viewport-centred
 * offset per item), `anchorType`, `reverseLayout`, and the four `beforeContentPadding` /
 * `afterContentPadding` / `beforeAutoCenteringPadding` / `afterAutoCenteringPadding` paddings that
 * upstream adds onto the first and last item's size.
 *
 * The indicator also only moves when something re-tunes it: `ScalingLazyListState` is not observable
 * in Hibari, so unlike upstream's `snapshotFlow` this does not follow a fling on its own. Feed it
 * from a `RecyclerView.OnScrollListener` that writes into state the tune reads.
 *
 * @param state the list this indicator describes.
 * @param itemCount `layoutInfo.totalItemsCount`, which the state does not expose.
 * @param visibleItemCount how many items the viewport shows at once; see
 *   [ScrollIndicatorFractions.forScalingLazyColumn].
 * @param reverseDirection upstream infers this from `layoutInfo.reverseLayout`, which is not on the
 *   state at all, so it has to be passed.
 * @param colors `null` resolves [ScrollIndicatorDefaults.colors] in the body, as in the fraction
 *   overload above.
 */
@Tunable
fun ScrollIndicator(
    state: ScalingLazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
    visibleItemCount: Int? = null,
    colors: ScrollIndicatorColors? = null,
    reverseDirection: Boolean = false,
    positionAnimationSpec: AnimationSpec<Float> = ScrollIndicatorDefaults.PositionAnimationSpec,
) {
    val resolved = colors ?: ScrollIndicatorDefaults.colors()
    ScrollIndicatorImpl(
        fractions = ScrollIndicatorFractions.forScalingLazyColumn(
            centerItemIndex = state.centerItemIndex,
            itemCount = itemCount,
            visibleItemCount = visibleItemCount,
            canScrollForward = state.canScrollForward,
            canScrollBackward = state.canScrollBackward,
        ),
        modifier = modifier,
        colors = resolved,
        reverseDirection = reverseDirection,
        positionAnimationSpec = positionAnimationSpec,
    )
}

/**
 * Upstream's internal `IndicatorImpl`, minus the `rsbSide` knob: which side of a round screen the
 * indicator belongs on is read off the view's layout direction at draw time, the same way upstream
 * reads `LocalLayoutDirection`.
 */
@Tunable
internal fun ScrollIndicatorImpl(
    fractions: ScrollIndicatorFractions,
    modifier: Modifier,
    colors: ScrollIndicatorColors,
    reverseDirection: Boolean,
    positionAnimationSpec: AnimationSpec<Float>,
) {
    val context = currentContext
    val screenWidth = context.resources.configuration.screenWidthDp.dp
    val indicatorHeight = ScrollIndicatorDefaults.IndicatorHeight
    val indicatorWidth = ScrollIndicatorDefaults.indicatorWidth(context)
    val paddingHorizontal = ScrollIndicatorDefaults.EdgePadding
    val boxSize = ScrollIndicatorDefaults.indicatorBounds(
        screenWidth = screenWidth,
        indicatorHeight = indicatorHeight,
        indicatorWidth = indicatorWidth,
        paddingHorizontal = paddingHorizontal,
    )

    Node(
        modifier = modifier
            .viewClass(WearScrollIndicatorView::class.java)
            .size(boxSize)
            .scrollIndicatorSpec(
                ScrollIndicatorSpec(
                    positionFraction = fractions.positionFraction,
                    sizeFraction = fractions.sizeFraction,
                    indicatorColor = colors.indicatorColor,
                    trackColor = colors.trackColor,
                    indicatorHeight = indicatorHeight,
                    indicatorWidth = indicatorWidth,
                    gapHeight = ScrollIndicatorDefaults.GapHeight,
                    paddingHorizontal = paddingHorizontal,
                    screenWidth = screenWidth,
                    reverseDirection = reverseDirection,
                    positionAnimationSpec = positionAnimationSpec,
                    boxSize = boxSize,
                )
            ),
    )
}

/** Contains the default values used for [ScrollIndicator]. */
object ScrollIndicatorDefaults {

    @Tunable
    fun colors(): ScrollIndicatorColors = MaterialTheme.colorScheme.defaultScrollIndicatorColors

    @Tunable
    fun colors(
        indicatorColor: Color = Color.Unspecified,
        trackColor: Color = Color.Unspecified,
    ): ScrollIndicatorColors =
        MaterialTheme.colorScheme.defaultScrollIndicatorColors.copy(
            indicatorColor = indicatorColor,
            trackColor = trackColor,
        )

    /**
     * [AnimationSpec] for the position animation; pass `snap()` to disable it, as upstream says.
     *
     * The type is Hibari's own `AnimationSpec`, and [WearScrollIndicatorView] reads the duration and
     * easing back out of it for its `ValueAnimator`. A spec that is not a `TweenSpec` has no duration
     * to schedule against, so it snaps.
     */
    val PositionAnimationSpec: AnimationSpec<Float> = tween<Float>(
        durationMillis = 500,
        easing = CubicBezierEasing(0f, 0f, 0f, 1f),
    )

    /** The smallest the thumb ever gets, upstream's `minSizeFraction`. */
    const val MinSizeFraction: Float = 0.3f

    /** The largest the thumb ever gets — and what a list that fits on screen is drawn as. */
    const val MaxSizeFraction: Float = 0.7f

    /** Upstream's `indicatorHeight`. */
    val IndicatorHeight: Dp = 50.dp

    /** The gap the thumb and the two track segments are separated by. */
    val GapHeight: Dp = 3.dp

    /** `PaddingDefaults.edgePadding`, the distance from the screen edge to the arc's centre line. */
    val EdgePadding: Dp = 2.dp

    /**
     * Upstream's `indicatorWidth`: 6.dp on a large screen, 5.dp on a small one. A function of
     * [Context] here because that is what `isLargeScreen()` becomes without a composition ([WearScreen]).
     */
    fun indicatorWidth(context: Context): Dp =
        if (WearScreen.isLargeScreen(context)) 6.dp else 5.dp

    /**
     * Upstream's `size` lambda: the strip the curved indicator is drawn into. Its width is how far
     * the arc of `indicatorHeight` bulges in from the screen edge, so the thumb's own stroke stays
     * inside the box.
     */
    fun indicatorBounds(
        screenWidth: Dp,
        indicatorHeight: Dp,
        indicatorWidth: Dp,
        paddingHorizontal: Dp,
    ): DpSize {
        val radius = screenWidth.value / 2 - paddingHorizontal.value - indicatorWidth.value / 2
        val projection = radius * radius - (indicatorHeight.value / 2) * (indicatorHeight.value / 2)
        // Floored because upstream evaluates this while the container is still measured as 0 wide,
        // which would put a negative number under the square root.
        val width = radius - sqrt(projection.coerceAtLeast(0f)) + paddingHorizontal.value + indicatorWidth.value
        val height = indicatorHeight.value + indicatorWidth.value
        return DpSize(width.dp, height.dp)
    }
}

/** Upstream's `defaultScrollIndicatorColors`; the luminance shifts are the ones in that file. */
internal val ColorScheme.defaultScrollIndicatorColors: ScrollIndicatorColors
    @Tunable get() = ScrollIndicatorColors(
        indicatorColor = ColorSchemeKeyTokens.OnBackground.resolve(this).setLuminance(80f),
        trackColor = ColorSchemeKeyTokens.OnBackground.resolve(this).setLuminance(20f),
    )
