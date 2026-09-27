package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.runtime.Tunable
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
 * [boxSize] is the layout size, which upstream derives from the same three numbers the view does.
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
 * Ported from androidx.wear.compose.material3.HorizontalPageIndicator.
 *
 * Upstream takes a `PagerState` and reads exactly three numbers off it — `pageCount`, `currentPage`
 * and `currentPageOffsetFraction` — so those are the parameters here: Hibari has no pager state yet
 * (`com.huanli233.hibari.recyclerview.Pager` wraps a `ViewPager2` but exposes no position). Feed the
 * live values from whatever owns the pager, per frame while the user drags: like upstream this
 * component holds no animation of its own, it is a pure function of the offset.
 *
 * Place it at the bottom centre of the screen, as upstream's docs require.
 *
 * @param selectedColor Defaults to `null` and resolves in the body:
 *   [PageIndicatorDefaults.selectedColor] reads `MaterialTheme`, and a `@Tunable` default expression
 *   is hoisted into a non-`@Tunable` `$default` method that cannot.
 * @param unselectedColor Defaults to `null` for the same reason; resolves
 *   [PageIndicatorDefaults.unselectedColor].
 * @param backgroundColor Defaults to `null` for the same reason; resolves
 *   [PageIndicatorDefaults.backgroundColor].
 */
@Tunable
fun HorizontalPageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
    currentPageOffsetFraction: Float = 0f,
    selectedColor: Color? = null,
    unselectedColor: Color? = null,
    backgroundColor: Color? = null,
) {
    val resolvedSelected = selectedColor ?: PageIndicatorDefaults.selectedColor()
    val resolvedUnselected = unselectedColor ?: PageIndicatorDefaults.unselectedColor()
    val resolvedBackground = backgroundColor ?: PageIndicatorDefaults.backgroundColor()
    PageIndicatorImpl(
        pageCount = pageCount,
        currentPage = currentPage,
        modifier = modifier,
        currentPageOffsetFraction = currentPageOffsetFraction,
        selectedColor = resolvedSelected,
        unselectedColor = resolvedUnselected,
        backgroundColor = resolvedBackground,
        isHorizontal = true,
    )
}

/**
 * Ported from androidx.wear.compose.material3.VerticalPageIndicator.
 *
 * Same state-shape deviation as [HorizontalPageIndicator]. Upstream puts this one at the centre end
 * of the screen, right in LTR and left in RTL; the view reads the layout direction itself, so the
 * mirroring is intact.
 *
 * @param selectedColor `null` resolves [PageIndicatorDefaults.selectedColor], as above.
 * @param unselectedColor `null` resolves [PageIndicatorDefaults.unselectedColor], as above.
 * @param backgroundColor `null` resolves [PageIndicatorDefaults.backgroundColor], as above.
 */
@Tunable
fun VerticalPageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
    currentPageOffsetFraction: Float = 0f,
    selectedColor: Color? = null,
    unselectedColor: Color? = null,
    backgroundColor: Color? = null,
) {
    val resolvedSelected = selectedColor ?: PageIndicatorDefaults.selectedColor()
    val resolvedUnselected = unselectedColor ?: PageIndicatorDefaults.unselectedColor()
    val resolvedBackground = backgroundColor ?: PageIndicatorDefaults.backgroundColor()
    PageIndicatorImpl(
        pageCount = pageCount,
        currentPage = currentPage,
        modifier = modifier,
        currentPageOffsetFraction = currentPageOffsetFraction,
        selectedColor = resolvedSelected,
        unselectedColor = resolvedUnselected,
        backgroundColor = resolvedBackground,
        isHorizontal = false,
    )
}

@Tunable
internal fun PageIndicatorImpl(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier,
    currentPageOffsetFraction: Float,
    selectedColor: Color,
    unselectedColor: Color,
    backgroundColor: Color,
    isHorizontal: Boolean,
) {
    // Upstream trusts `PagerState.pageCount` to be non-negative; a caller with no pager wired up yet
    // can hand us 0, and a negative layout size would be read as MATCH_PARENT/WRAP_CONTENT.
    val pagesOnScreen = min(MaxNumberOfIndicators, pageCount.coerceAtLeast(0))
    val spacerSize = PageIndicatorItemSize + PageIndicatorSpacing

    val horizontalWidth = spacerSize * pagesOnScreen
    val horizontalHeight = PageIndicatorItemSize * 2
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

    Node(
        modifier = modifier
            .viewClass(WearPageIndicatorView::class.java)
            .size(boxSize)
            .pageIndicatorSpec(
                PageIndicatorSpec(
                    pageCount = pageCount,
                    pagesOnScreen = pagesOnScreen,
                    currentPage = currentPage,
                    currentPageOffsetFraction = currentPageOffsetFraction,
                    selectedColor = selectedColor,
                    unselectedColor = unselectedColor,
                    backgroundColor = backgroundColor,
                    indicatorSize = PageIndicatorItemSize,
                    spacing = PageIndicatorSpacing,
                    backgroundRadius = PageIndicatorBackgroundRadius,
                    edgePadding = edgePadding,
                    screenWidth = screenWidth,
                    isHorizontal = isHorizontal,
                    boxSize = boxSize,
                )
            ),
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
