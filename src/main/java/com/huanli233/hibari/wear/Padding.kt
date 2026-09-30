package com.huanli233.hibari.wear

import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import kotlin.math.ceil

/*
 * Ported from androidx.wear.compose.material3.Padding (`material3/Padding.kt`, whole file, 76 lines)
 * plus the two screen-dp readers it is built on (`materialcore/Resources.kt:67 screenHeightDp()`,
 * `:76 screenWidthDp()`).
 *
 * Everything in the upstream file is `internal` — `PaddingDefaults` (`:28`), `screenHeightFraction`
 * (`:67`), `screenWidthFraction` (`:70`) and the two fraction constants (`:72-73`) — and stays
 * `internal` here rather than being dressed up as public API.
 *
 * The percentage maths is deliberately *not* re-derived: [WearInsets] already carries
 * `PaddingDefaults.verticalContentPaddingPercentage` / `horizontalContentPaddingPercentage` /
 * `edgePadding` and the `ceilDp` formula (see its KDoc, which audits
 * `material3/Padding.kt:34-75` line by line). This file supplies only the composition-reading layer
 * upstream has and [WearScreen] does not: Dp-returning, argument-free accessors, so a component does
 * not have to hand-feed a `Context` or a raw `screenHeightDp` and then re-`ceil` the result itself.
 *
 * Converged in integration (was open when this file landed):
 *  - `IconButtonDefaults.minimumVerticalListContentPadding` and
 *    `TextButtonDefaults.minimumVerticalListContentPadding` inlined `Dp(screenHeightDp * 0.13f)`
 *    without this file's `ceil`, so both sat up to 1.dp below upstream's
 *    `screenHeightFraction(SMALL_VERTICAL_CONTENT_PADDING_FRACTION)`; both now call it.
 *    `CompactButtonDefaults.minimumVerticalListContentPadding` (`CompactButton.kt:233-234`) already
 *    did, and is the reason the note here no longer lists a third copy.
 *  - Left alone on purpose: `AlertDialog.kt:90-104`'s `dialogScreenWidthFraction` /
 *    `dialogScreenHeightFraction` / `dialogHorizontalContentPadding` / `dialogVerticalContentPadding`
 *    are not the same layer — they take a [Context] from their caller instead of reading the ambient,
 *    and every `@Tunable` read here would force the annotation onto ~30 `AlertDialog` call sites that
 *    resolve padding outside a tuner body. The shared maths is one `ceil`, which both already run.
 */

/**
 * `screenHeightDp()` — `materialcore/Resources.kt:67`, which reads `LocalConfiguration`. Here it is
 * `currentContext`'s configuration, so a retune is what re-evaluates it (as in [WearScreen]), and
 * `@Tunable` is what gives the body the tuner [currentContext] needs.
 */
@Tunable
internal fun screenHeightDp(): Int = currentContext.resources.configuration.screenHeightDp

/** `screenWidthDp()` — `materialcore/Resources.kt:76`. */
@Tunable
internal fun screenWidthDp(): Int = currentContext.resources.configuration.screenWidthDp

/**
 * `ceilDp` — `material3/Padding.kt:75`: `with(LocalDensity.current) { Dp(ceil(dp.value)) }`. The
 * density receiver only buys the `Dp` constructor, so the helper is plain arithmetic here. The ceil
 * happens in **dp space**, before any density multiply, which is what makes a fraction of a small
 * Wear screen land on a whole dp rather than a sub-pixel offset.
 */
internal fun ceilDp(dp: Dp): Dp = Dp(ceil(dp.value))

/** `screenHeightFraction` — `material3/Padding.kt:67`. */
@Tunable
internal fun screenHeightFraction(fraction: Float): Dp = ceilDp(screenHeightDp().dp * fraction)

/** `screenWidthFraction` — `material3/Padding.kt:70`. */
@Tunable
internal fun screenWidthFraction(fraction: Float): Dp = ceilDp(screenWidthDp().dp * fraction)

/**
 * `LARGE_VERTICAL_CONTENT_PADDING_FRACTION` — `material3/Padding.kt:72`, 0.23f: the top/bottom
 * content padding for the large variant, read by upstream's `TimeText`/scaffold paths.
 */
internal const val LARGE_VERTICAL_CONTENT_PADDING_FRACTION = 0.23f

/**
 * `SMALL_VERTICAL_CONTENT_PADDING_FRACTION` — `material3/Padding.kt:73`, 0.13f: the minimum vertical
 * list content padding every `*Defaults.minimumVerticalListContentPadding` in this module is meant to
 * be built from.
 */
internal const val SMALL_VERTICAL_CONTENT_PADDING_FRACTION = 0.13f

/**
 * `PaddingDefaults` — `material3/Padding.kt:28-64`, upstream's `internal object` of the same name.
 */
internal object PaddingDefaults {

    /** `PaddingDefaults.verticalContentPaddingPercentage` — `Padding.kt:30-34`, 10f. */
    const val verticalContentPaddingPercentage = WearInsets.VerticalContentPaddingPercentage

    /**
     * `PaddingDefaults.verticalContentPadding()` — `Padding.kt:36-44`:
     * `ceilDp(screenHeightDp.dp * verticalContentPaddingPercentage / 100)`, delegated to
     * [WearInsets.verticalContentPaddingDp] so the formula and the percentage live in one place.
     */
    @Tunable
    fun verticalContentPadding(): Dp =
        WearInsets.verticalContentPaddingDp(screenHeightDp()).dp

    /** `PaddingDefaults.horizontalContentPaddingPercentage` — `Padding.kt:46-50`, 5.2f. */
    const val horizontalContentPaddingPercentage = WearInsets.HorizontalContentPaddingPercentage

    /**
     * `PaddingDefaults.horizontalContentPadding(percentage)` — `Padding.kt:52-60`:
     * `ceilDp(screenWidthDp.dp * percentage / 100)`, with upstream's own default percentage. A caller
     * may pass its own, as `material3/TimePicker.kt:311` does.
     */
    @Tunable
    fun horizontalContentPadding(
        percentage: Float = horizontalContentPaddingPercentage,
    ): Dp = WearInsets.horizontalContentPaddingDp(screenWidthDp(), percentage).dp

    /**
     * `PaddingDefaults.edgePadding` — `Padding.kt:62-63`, a flat 2.dp. Upstream never applies it as a
     * screen inset, only as a component-local minimum ([WearInsets.EdgePaddingDp] records which
     * components); kept here so those ports have the name to point at.
     */
    val edgePadding: Dp = WearInsets.EdgePaddingDp.dp
}
