package com.huanli233.hibari.wear

import android.content.Context
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.LayoutDirection
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.unit.isUnspecified
import com.huanli233.hibari.wear.view.WearInsetLayoutView
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Per-edge pixel insets. Upstream keeps these as four independent `PaddingValues` accessors. */
data class WearEdgeInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    companion object {
        val NONE = WearEdgeInsets(0, 0, 0, 0)
    }
}

/**
 * Which edges of a [WearInsetLayoutView] child are pushed inside the inset box.
 *
 * The bit values are the ones `androidx.wear.widget.BoxInsetLayout.LayoutParams` ships with
 * (`BOX_NONE = 0`, `BOX_LEFT = 1`, `BOX_TOP = 2`, `BOX_RIGHT = 4`, `BOX_BOTTOM = 8`,
 * `BOX_ALL = 15`, read from `BoxInsetLayout$LayoutParams` in
 * `androidx.wear:wear:1.4.0`), so `BoxedEdges` in `Scaffold.kt` re-points at these with no change
 * of value and no change at any call site. LEFT/RIGHT are absolute edges, not start/end — again
 * matching the widget being replaced; it is safe here only because Compose's horizontal inset is
 * symmetric ([WearInsets.contentPaddingValues] uses `PaddingValues(horizontal = …)`).
 */
object WearBoxedEdges {
    const val NONE = 0
    const val LEFT = 1
    const val TOP = 2
    const val RIGHT = 4
    const val BOTTOM = 8
    const val ALL = LEFT or TOP or RIGHT or BOTTOM
}

/**
 * The inset maths Wear **Compose** computes, ported 1:1, plus the two geometric models it is being
 * swapped out for, kept here so the difference is pinned by tests instead of by memory.
 *
 * ## What Wear Compose actually does — verified, not assumed
 *
 * Wear Compose has **no safe-area container and no inscribed rect.** The premise this port was
 * dispatched with ("Compose derives the safe area as the axis-aligned rect inscribed in the
 * screen's circle") is **refuted** by the reference tree: there is no `BoxInsetLayout`, no
 * `boxedEdges`, no largest-rect-in-a-circle and no `isScreenRound` branch anywhere in the scaffold
 * path of `androidx/wear/compose`. What exists instead is per-edge padding as a *percentage of the
 * screen's dp*, applied whether or not the screen is round:
 *
 *  - `material3/Padding.kt:28` `internal object PaddingDefaults`
 *  - `material3/Padding.kt:34` `verticalContentPaddingPercentage = 10f`
 *  - `material3/Padding.kt:41-44` `verticalContentPadding() = ceilDp(screenHeightDp.dp * 10 / 100)`
 *  - `material3/Padding.kt:50` `horizontalContentPaddingPercentage = 5.2f`
 *  - `material3/Padding.kt:57-60` `horizontalContentPadding(p = 5.2f) = ceilDp(screenWidthDp.dp * p / 100)`
 *  - `material3/Padding.kt:75` `ceilDp(dp) = Dp(ceil(dp.value))` — the ceil happens in **dp space**
 *  - `material3/ScreenScaffold.kt:786-792` the scaffold default is exactly
 *    `PaddingValues(horizontal = horizontalContentPadding(), vertical = verticalContentPadding())`
 *  - `material3/AppScaffold.kt:71-82` `AppScaffold` itself is a plain
 *    `Box(Modifier.fillMaxSize().background(containerColor))` — **zero insets**, and no per-child
 *    opt-in exists at all.
 *
 * Screen-shape helpers upstream does have, for reference: `materialcore/Resources.kt:40-43`
 * `isRoundDevice()` (`Configuration.isScreenRound`, unguarded there), `:67` `screenHeightDp()`,
 * `:76` `screenWidthDp()`, `:88` `LARGE_SCREEN_WIDTH_DP = 225` — all already ported into
 * [WearScreen], which this object deliberately does not depend on except for roundness in
 * [inscribedRectInsetsPx].
 *
 * Round-screen geometry does exist upstream, but only *inside components that hug the curve*, and
 * always as a chord solve for that component's own height, never as a container inset:
 * `material3/ScrollIndicator.kt:505-513` and `material/PositionIndicator.kt:644-656`
 * (`radius - sqrt(max(0, radius² - (height/2)²))`), `foundation/BasicCurvedText.kt:188-191`
 * (`maxWidth = 2 * sqrt(textHeight * (radius - textHeight/4))`), `material3/EdgeButton.kt:440-449`
 * (`sidePadding`, then `Rect(sidePadding, 0, width - sidePadding, height)`). This module already
 * inlines that chord maths where it belongs — `ScrollIndicator.kt:373` and `LevelIndicator.kt:300`
 * here — so it is deliberately **not** re-provided: a third copy is a third place to keep in step.
 *
 * So [contentInsetsPx] implements Compose's model, and [WearInsetLayoutView] applies it.
 */
object WearInsets {
    /** `PaddingDefaults.verticalContentPaddingPercentage` — material3/Padding.kt:34. */
    const val VerticalContentPaddingPercentage = 10f

    /** `PaddingDefaults.horizontalContentPaddingPercentage` — material3/Padding.kt:50. */
    const val HorizontalContentPaddingPercentage = 5.2f

    /**
     * `PaddingDefaults.edgePadding` — material3/Padding.kt:63, a flat `2.dp`.
     *
     * Not applied by [contentInsetsPx] and not applied by [WearInsetLayoutView]: upstream uses it
     * only as a component-local minimum (`TimeText.kt:134`, `ScrollIndicator.kt:361`,
     * `PageIndicator.kt:185`, `LevelIndicator.kt:216`,
     * `CircularProgressIndicator.kt:547` `FullScreenPadding`), never as a screen inset.
     *
     * Discrepancy to flag for the orchestrator: `WearScreen.edgePaddingDp` in this module returns
     * `0f` on a square screen under 192.dp tall. No such rule exists anywhere in
     * `androidx/wear/compose` — `edgePadding` is unconditional, and `192.dp` appears in the
     * reference tree exactly once, as `lazy/ResponsiveTransformationSpec.kt:210 SmallScreenSize`.
     * So that branch is not a port, and the numbers here do not inherit it.
     */
    const val EdgePaddingDp = 2f

    /**
     * Fraction of an axis's own length given up on each side by the largest axis-aligned rect with
     * the same aspect ratio inside an ellipse: `(1 - 1/sqrt(2)) / 2 = 0.14644660940672624`.
     *
     * Not used by [contentInsetsPx]; see [inscribedRectInsetsPx].
     */
    const val InscribedAxisInsetFactor = 0.14644660940672624f

    /**
     * `BoxInsetLayout.FACTOR`, verbatim from `androidx.wear:wear:1.4.0` — the same number as
     * [InscribedAxisInsetFactor], truncated to six digits by whoever wrote it. Only used to pin
     * the replaced model in tests; see [legacyBoxInsetPx].
     */
    const val LegacyBoxInsetFactor = 0.146447f

    /** `ceilDp` — material3/Padding.kt:75. Ceils the dp *value*, before any density multiply. */
    fun ceilDp(value: Float): Float = ceil(value.toDouble()).toFloat()

    /**
     * `PaddingDefaults.verticalContentPadding()` — material3/Padding.kt:41-44:
     * `ceilDp(screenHeightDp.dp * 10 / 100)`.
     *
     * Same formula and constant as [WearScreen.verticalContentPaddingDp], which reads a `Context`;
     * this is the parameterless-input version the container and the unit tests use.
     */
    fun verticalContentPaddingDp(screenHeightDp: Int): Float =
        ceilDp(screenHeightDp * VerticalContentPaddingPercentage / 100f)

    /**
     * `PaddingDefaults.horizontalContentPadding(percentage)` — material3/Padding.kt:57-60:
     * `ceilDp(screenWidthDp.dp * percentage / 100)`, default percentage 5.2.
     *
     * The `percentage` parameter is upstream's, not an addition: `material3/TimePicker.kt:311`
     * calls it with its own value.
     */
    fun horizontalContentPaddingDp(
        screenWidthDp: Int,
        percentage: Float = HorizontalContentPaddingPercentage,
    ): Float = ceilDp(screenWidthDp * percentage / 100f)

    /**
     * `ScreenScaffoldDefaults.contentPadding` — material3/ScreenScaffold.kt:786-792, rebuilt from
     * the screen's dp so it is callable without a composition.
     */
    fun contentPaddingValues(screenWidthDp: Int, screenHeightDp: Int): PaddingValues =
        PaddingValues(
            horizontal = horizontalContentPaddingDp(screenWidthDp).dp,
            vertical = verticalContentPaddingDp(screenHeightDp).dp,
        )

    /**
     * A `PaddingValues` in pixels, the way `Modifier.padding` resolves one: `Dp.toPx()` is
     * `value * density` (`hibari-ui` `unit/Density.kt:33`, itself Compose's `Density.toPx`) and the
     * integer edge is `roundToInt`, the same convention as `Dp.toPx(context)` in `unit/Dp.kt:432`.
     *
     * [layoutDirection] is required by the `PaddingValues` interface rather than by the maths: the
     * scaffold padding is symmetric, so Ltr and Rtl give identical insets.
     */
    fun insetsPx(
        padding: PaddingValues,
        density: Float,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ): WearEdgeInsets = WearEdgeInsets(
        left = padding.calculateLeftPadding(layoutDirection).toPxOrZero(density),
        top = padding.calculateTopPadding().toPxOrZero(density),
        right = padding.calculateRightPadding(layoutDirection).toPxOrZero(density),
        bottom = padding.calculateBottomPadding().toPxOrZero(density),
    )

    /**
     * The inset box of a whole screen, in pixels, from the same inputs Wear Compose reads — the
     * window configuration's `screenWidthDp`/`screenHeightDp` and the density.
     *
     * Deliberately **shape-independent**: nothing in the upstream chain that produces this padding
     * asks whether the screen is round (material3/Padding.kt:41-60, ScreenScaffold.kt:786-792).
     */
    fun contentInsetsPx(
        screenWidthDp: Int,
        screenHeightDp: Int,
        density: Float,
    ): WearEdgeInsets = insetsPx(contentPaddingValues(screenWidthDp, screenHeightDp), density)

    /** [contentInsetsPx] read from a [Context], the `LocalConfiguration` equivalent. */
    fun contentInsetsPx(context: Context): WearEdgeInsets {
        val configuration = context.resources.configuration
        return contentInsetsPx(
            screenWidthDp = configuration.screenWidthDp,
            screenHeightDp = configuration.screenHeightDp,
            density = context.resources.displayMetrics.density,
        )
    }

    /**
     * The per-edge opt-in: the inset survives only on the edges the child boxed itself into,
     * which is the whole of `BoxedEdges` semantics. Pure, no Android types.
     */
    fun boxedInsetsPx(insets: WearEdgeInsets, edges: Int): WearEdgeInsets = WearEdgeInsets(
        left = if (edges and WearBoxedEdges.LEFT != 0) insets.left else 0,
        top = if (edges and WearBoxedEdges.TOP != 0) insets.top else 0,
        right = if (edges and WearBoxedEdges.RIGHT != 0) insets.right else 0,
        bottom = if (edges and WearBoxedEdges.BOTTOM != 0) insets.bottom else 0,
    )

    /**
     * The true inscribed rect, in pixels — the model the task premise attributed to Compose.
     *
     * For an ellipse with semi-axes `a = width/2`, `b = height/2` the largest axis-aligned rect of
     * the same aspect ratio has half-extents `a/sqrt(2)` and `b/sqrt(2)`, so each side gives up
     * `axis * InscribedAxisInsetFactor` ([InscribedAxisInsetFactor]). A round Wear display is a
     * circle (`width == height`), which collapses both axes to `0.1464466 * D`.
     *
     * **This is not what Wear Compose does** and [WearInsetLayoutView] does not use it; it is here
     * as the geometric answer to the "inscribed rect" requirement, and so the difference against
     * [legacyBoxInsetPx] is testable rather than folklore.
     *
     * Which edges are actually clipped, on a circle: none of them cleanly. The circle is tangent to
     * each side at that side's midpoint only, so a full-width horizontal band is inside the circle
     * at exactly one height and every edge has a clipped portion. The clipped depth depends on how
     * far the element reaches vertically: for a circle of diameter `D` and an element of height `h`
     * centred on the middle, each side must give up `D/2 - sqrt((D/2)² - (h/2)²)` — `0.0101D` at
     * `h = 0.2D`, but `0.1464D` at `h = D`, the inscribed square, which is the worst case any single
     * fixed inset would have to assume. Compose never assumes it: it pads by a fixed percentage of
     * the screen and leaves the chord solve to the components that hug the curve.
     */
    fun inscribedRectInsetsPx(widthPx: Int, heightPx: Int, isRound: Boolean): WearEdgeInsets {
        if (!isRound || widthPx <= 0 || heightPx <= 0) return WearEdgeInsets.NONE
        return WearEdgeInsets(
            left = (InscribedAxisInsetFactor * widthPx).roundToInt().coerceAtLeast(0),
            top = (InscribedAxisInsetFactor * heightPx).roundToInt().coerceAtLeast(0),
            right = (InscribedAxisInsetFactor * widthPx).roundToInt().coerceAtLeast(0),
            bottom = (InscribedAxisInsetFactor * heightPx).roundToInt().coerceAtLeast(0),
        )
    }

    /**
     * [inscribedRectInsetsPx] with the roundness read from [context].
     *
     * The `Configuration.isScreenRound` read is not repeated here: [WearScreen.isRound] owns it and
     * already falls back to `uiMode` + `screenWidthDp == screenHeightDp` below API 23, which is the
     * guard the port contract asks for. Callers that need the geometric box (an `EdgeButton` or a
     * curve-hugging component port) and the unit tests are the intended consumers;
     * [WearInsetLayoutView] is not one of them.
     */
    fun inscribedRectInsetsPx(context: Context, widthPx: Int, heightPx: Int): WearEdgeInsets =
        inscribedRectInsetsPx(widthPx, heightPx, WearScreen.isRound(context))

    /**
     * `BoxInsetLayout.calculateInset` from `androidx.wear:wear:1.4.0` —
     * `(int)(FACTOR * max(min(measuredWidth, screenWidth), min(measuredHeight, screenHeight)))`,
     * i.e. one scalar for all four edges, computed off the **physical display metrics**
     * (`Resources.getSystem().getDisplayMetrics()`, captured in the constructor), truncated toward
     * zero, and applied only when `Configuration.isScreenRound` is true.
     *
     * Kept only so a test can assert how far the replaced model and [contentInsetsPx] disagree
     * (a 392 x 392 px screen at density 2, i.e. 196 x 196 dp: 57 px on every edge, against
     * left 22 / top 40 / right 22 / bottom 40 from Compose's maths). Nothing in this module calls
     * it, and the cast rather than [roundToInt] is part of why the two never agree exactly.
     */
    fun legacyBoxInsetPx(longestSidePx: Int): Int =
        (LegacyBoxInsetFactor * longestSidePx.coerceAtLeast(0)).toInt()

    /**
     * `Unspecified` dp reads as zero padding rather than `NaN * density`, which is what Compose's
     * `Modifier.padding` effectively does by never passing an unspecified edge through.
     */
    private fun Dp.toPxOrZero(density: Float): Int =
        if (isUnspecified) 0 else (value * density).roundToInt()
}
