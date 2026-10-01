package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.bindState
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.view.WearPageIndicatorView
import kotlin.math.min

/**
 * Everything [WearPageIndicatorView] needs to draw one frame. Immutable so a retune that changes
 * nothing leaves the view alone, mirroring `CurvedTextSpec` in this module.
 *
 * Three of these fields — [currentPage], [currentPageOffsetFraction] and, from the frame channel,
 * the [pageCount]/[pagesOnScreen] pair derived from `PagerState.pageCount` — are what
 * [PageIndicatorImpl] overwrites on the view outside any tune, the way upstream reads its state
 * inside the draw lambda (`material3/PageIndicator.kt:219`) and re-remembers `pagesOnScreen` on
 * `pageCount` (`:187-197`). Everything else is tune-time geometry and colour, `:201-207`.
 * [boxSize] is the layout size, which upstream derives from the same numbers the view does; it
 * follows the tune, not the frame.
 */
data class PageIndicatorSpec(
    val pageCount: Int,
    val pagesOnScreen: Int,
    val currentPage: Int,
    val currentPageOffsetFraction: Float,
    val selectedColor: Color,
    val unselectedColor: Color,
    val backgroundColor: Color,
    val indicatorSize: Dp,
    val spacing: Dp,
    val backgroundRadius: Dp,
    val edgePadding: Dp,
    val screenWidth: Dp,
    val isHorizontal: Boolean,
    val boxSize: DpSize,
)

/** Pushes a [PageIndicatorSpec] onto the drawing view. */
fun Modifier.pageIndicatorSpec(spec: PageIndicatorSpec): Modifier =
    this.thenViewAttribute<WearPageIndicatorView, PageIndicatorSpec>(uniqueKey, spec) { this.spec = it }

/**
 * Ported from androidx.wear.compose.material3.HorizontalPageIndicator (`material3/PageIndicator.kt:79-97`).
 *
 * Upstream's one public shape is `(pagerState, modifier, selectedColor, unselectedColor,
 * backgroundColor)`, and that is what is published here. The `(pageCount, currentPage,
 * currentPageOffsetFraction)` form this port used to carry existed because "Hibari has no pager state
 * yet" — a claim [PagerState] refutes (`Pager.kt:223-518`): it publishes [PagerState.pageCount]
 * (`Pager.kt:236`), [PagerState.currentPage] (`:314`) and [PagerState.currentPageOffsetFraction]
 * (`:320`), and [rememberPagerState] creates one (`Pager.kt:195-204`). Place it at the bottom centre
 * of the screen, as upstream's doc requires (`:65-68`).
 *
 * @param pagerState State of the [HorizontalPager] used to control this indicator
 *   (`material3/PageIndicator.kt:73`). [PagerState.currentPage] is a snapshot read (`Pager.kt:313-314`,
 *   upstream's own page-granular split), so the tune that sizes the box also tracks the page — one
 *   retune per page crossed, which is coarser than upstream's per-frame draw read but never a
 *   per-frame retune; [PagerState.currentPageOffsetFraction] is never read in the tune (a read of it
 *   subscribes the host subtree to every frame of the drag, `Pager.kt:253-261`) and
 *   [PagerState.pageCount] is a plain provider call (`Pager.kt:233-236`) whose value the frame copy
 *   also carries, so an observable `pageCount` behind the provider is not silently frozen into the
 *   geometry. [PageIndicatorImpl] keeps the live numbers on the view through `Modifier.bindState`.
 * @param selectedColor Defaults to `null` and resolves in the body:
 *   [PageIndicatorDefaults.selectedColor] reads `MaterialTheme`, and a `@Tunable` default expression
 *   is hoisted into a non-`@Tunable` `$default` method that cannot. Upstream's default is the
 *   `@Composable val` at `material3/PageIndicator.kt:149-150`.
 * @param unselectedColor Defaults to `null` for the same reason; resolves
 *   [PageIndicatorDefaults.unselectedColor] (`:156-159`).
 * @param backgroundColor Defaults to `null` for the same reason; resolves
 *   [PageIndicatorDefaults.backgroundColor] (`:165-168`).
 */
@Tunable
fun HorizontalPageIndicator(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    selectedColor: Color? = null,
    unselectedColor: Color? = null,
    backgroundColor: Color? = null,
) {
    val resolvedSelected = selectedColor ?: PageIndicatorDefaults.selectedColor()
    val resolvedUnselected = unselectedColor ?: PageIndicatorDefaults.unselectedColor()
    val resolvedBackground = backgroundColor ?: PageIndicatorDefaults.backgroundColor()
    PageIndicatorImpl(
        pagerState = pagerState,
        selectedColor = resolvedSelected,
        unselectedColor = resolvedUnselected,
        backgroundColor = resolvedBackground,
        modifier = modifier,
        indicatorSize = PageIndicatorItemSize,
        spacing = PageIndicatorSpacing,
        isHorizontal = true,
    )
}

/**
 * Ported from androidx.wear.compose.material3.VerticalPageIndicator (`material3/PageIndicator.kt:122-140`).
 *
 * Same signature and same state handling as [HorizontalPageIndicator]; upstream puts this one at the
 * centre end of the screen, right in LTR and left in RTL (`:105-109`), and the view reads the layout
 * direction itself, so the mirroring is intact.
 *
 * @param pagerState State of the [VerticalPager] used to control this indicator
 *   (`material3/PageIndicator.kt:116`); see [HorizontalPageIndicator] for how its numbers reach the
 *   drawing pass.
 * @param selectedColor `null` resolves [PageIndicatorDefaults.selectedColor], as above.
 * @param unselectedColor `null` resolves [PageIndicatorDefaults.unselectedColor], as above.
 * @param backgroundColor `null` resolves [PageIndicatorDefaults.backgroundColor], as above.
 */
@Tunable
fun VerticalPageIndicator(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    selectedColor: Color? = null,
    unselectedColor: Color? = null,
    backgroundColor: Color? = null,
) {
    val resolvedSelected = selectedColor ?: PageIndicatorDefaults.selectedColor()
    val resolvedUnselected = unselectedColor ?: PageIndicatorDefaults.unselectedColor()
    val resolvedBackground = backgroundColor ?: PageIndicatorDefaults.backgroundColor()
    PageIndicatorImpl(
        pagerState = pagerState,
        selectedColor = resolvedSelected,
        unselectedColor = resolvedUnselected,
        backgroundColor = resolvedBackground,
        modifier = modifier,
        indicatorSize = PageIndicatorItemSize,
        spacing = PageIndicatorSpacing,
        isHorizontal = false,
    )
}

/**
 * `PageIndicatorImpl` (`material3/PageIndicator.kt:171-211` and its draw lambda): the same parameter
 * list, in upstream's order, with `state` named [pagerState] because `state` means
 * [com.huanli233.hibari.runtime.State] in this module.
 *
 * The layout maths is upstream's (`:199-207`), including the fact that it reads its `indicatorSize`
 * and `spacing` parameters rather than the file-level constants — the two public entry points pass
 * those same constants (`:93-94`, `:136-137`), and a caller that resolves a colour through
 * [PageIndicatorDefaults] gets the same geometry.
 */
@Tunable
internal fun PageIndicatorImpl(
    pagerState: PagerState,
    selectedColor: Color,
    unselectedColor: Color,
    backgroundColor: Color,
    modifier: Modifier,
    indicatorSize: Dp,
    spacing: Dp,
    isHorizontal: Boolean,
) {
    // Upstream trusts `PagerState.pageCount` to be non-negative; a pager that has not been placed yet
    // can hand us 0, and a negative layout size would be read as MATCH_PARENT/WRAP_CONTENT.
    val pageCount = pagerState.pageCount.coerceAtLeast(0)
    val pagesOnScreen = min(MaxNumberOfIndicators, pageCount)
    val spacerSize = indicatorSize + spacing

    val horizontalWidth = spacerSize * pagesOnScreen
    val horizontalHeight = indicatorSize * 2
    val boundsSize = DpSize(
        width = if (isHorizontal) horizontalWidth else horizontalHeight,
        height = if (isHorizontal) horizontalHeight else horizontalWidth,
    )

    val screenWidth = currentContext.resources.configuration.screenWidthDp.dp
    val edgePadding = PageIndicatorDefaults.EdgePadding
    // Upstream's `padding(edgePadding).size(boundsSize)` insets a canvas inside a bigger box. A view
    // can only clip its own padding away, which would cut the dots off, so the box grows by the same
    // amount instead: with symmetric padding the drawing centre is the same point either way.
    val boxSize = DpSize(
        width = boundsSize.width + edgePadding * 2,
        height = boundsSize.height + edgePadding * 2,
    )

    // Upstream reads all three live numbers in its draw lambda (`:219`; the `currentPage` here is
    // that read moved to page-granular tune, `Pager.kt:313-314`). The offset starts at zero in the
    // spec and the `bindState` channel below replaces the page pair — and a `pageCount` the tune
    // could not know about — per frame; `StateBinding`'s subscription emits its first value as soon
    // as the view is attached, so the seed is gone before the indicator draws.
    val baseSpec = PageIndicatorSpec(
        pageCount = pageCount,
        pagesOnScreen = pagesOnScreen,
        currentPage = pagerState.currentPage,
        currentPageOffsetFraction = 0f,
        selectedColor = selectedColor,
        unselectedColor = unselectedColor,
        backgroundColor = backgroundColor,
        indicatorSize = indicatorSize,
        spacing = spacing,
        backgroundRadius = PageIndicatorBackgroundRadius,
        edgePadding = edgePadding,
        screenWidth = screenWidth,
        isHorizontal = isHorizontal,
        boxSize = boxSize,
    )
    Node(
        modifier = modifier
            .viewClass(WearPageIndicatorView::class.java)
            .size(boxSize)
            .pageIndicatorSpec(baseSpec)
            .bindState(uniqueKey, pagerState.scrollValues) { values ->
                val view = this as WearPageIndicatorView
                val current = view.spec
                if (current != null) {
                    // The page pair moves every frame; `pageCount` is re-called here rather than in
                    // the tune because it *could* hide an observable collection behind the provider
                    // (`Pager.kt:233-236` is a plain invoke) and a frozen copy would then mis-draw
                    // forever. The `if (next != current)` guard below is what makes that safe: a
                    // per-frame write of the same numbers does nothing, so nothing re-tunes.
                    // `boxSize` is the one piece of geometry that stays with the tune — a
                    // pageCount that moves without a retune changes the dots, not the box.
                    val framePageCount = pagerState.pageCount.coerceAtLeast(0)
                    val next = current.copy(
                        pageCount = framePageCount,
                        pagesOnScreen = min(MaxNumberOfIndicators, framePageCount),
                        currentPage = values.currentPage,
                        currentPageOffsetFraction = values.currentPageOffsetFraction,
                    )
                    if (next != current) view.spec = next
                }
            },
    )
}

/** Contains the default values used by [HorizontalPageIndicator] and [VerticalPageIndicator]. */
object PageIndicatorDefaults {

    /** `PaddingDefaults.edgePadding`: the gap between the indicator and the screen edge. */
    val EdgePadding: Dp = 2.dp

    /**
     * The recommended colour for the selected indicator item.
     *
     * Upstream exposes this as a `@Composable val`; a function is the shape a `@Tunable` default
     * argument can actually call, and it is what `TimeTextDefaults.backgroundColor()` does.
     */
    @Tunable
    fun selectedColor(): Color = ColorSchemeKeyTokens.OnBackground.resolve(MaterialTheme.colorScheme)

    /** The recommended colour for an unselected indicator item: `onBackground` at 30%. */
    @Tunable
    fun unselectedColor(): Color =
        ColorSchemeKeyTokens.OnBackground.resolve(MaterialTheme.colorScheme).copy(alpha = 0.3f)

    /** The recommended colour for the indicator background: `background` at 85%. */
    @Tunable
    fun backgroundColor(): Color =
        ColorSchemeKeyTokens.Background.resolve(MaterialTheme.colorScheme).copy(alpha = 0.85f)
}

/** The default size of an indicator dot. */
internal val PageIndicatorItemSize = 6.dp

/** The default spacing between indicator dots. */
internal val PageIndicatorSpacing = 4.dp

/** Half the stroke width of the arc behind the dots, upstream's `BackgroundRadius`. */
internal val PageIndicatorBackgroundRadius = 3.dp

/** Pages above this are represented by shrinking dots at either end rather than more dots. */
private const val MaxNumberOfIndicators = 6
