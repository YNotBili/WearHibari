package com.huanli233.hibari.wear

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import kotlin.math.sqrt

/**
 * Ported from the `LocalConfiguration`-based helpers in androidx.wear.compose.materialcore.Resources
 * and material3.Padding.
 *
 * Upstream reads these through `LocalConfiguration`, which recomposes on a configuration change.
 * Here they take a [Context] and are read at apply time, so a retune is what re-evaluates them.
 */
object WearScreen {
    /** `LARGE_SCREEN_WIDTH_DP` from materialcore.Resources. */
    const val LargeScreenWidthDp = 225

    fun isSmallScreen(context: Context): Boolean =
        context.resources.configuration.screenWidthDp < LargeScreenWidthDp

    fun isLargeScreen(context: Context): Boolean = !isSmallScreen(context)

    fun isRound(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 23) {
        context.resources.configuration.isScreenRound
    } else {
        val cfg = context.resources.configuration
        (cfg.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_WATCH &&
            cfg.screenWidthDp == cfg.screenHeightDp
    }

    /**
     * `PaddingDefaults.edgePadding` — a flat `2.dp` (material3/Padding.kt:63), exposed as
     * [WearInsets.EdgePaddingDp]. There is deliberately no screen-shaped variant: the branch that
     * used to fall to 0.dp on a square screen under 192.dp tall appears nowhere in
     * `androidx/wear/compose`, where `edgePadding` is unconditional. `192.dp` exists upstream only as
     * `lazy/ResponsiveTransformationSpec.kt:210 SmallScreenSize`, a scroll-transform threshold, not a
     * padding rule.
     */

    /** `PaddingDefaults.verticalContentPadding()`: 10% of screen height, ceil'd to whole dp. */
    fun verticalContentPaddingDp(context: Context): Float =
        WearInsets.verticalContentPaddingDp(context.resources.configuration.screenHeightDp)

    /** `PaddingDefaults.horizontalContentPadding()`: 5.2% of screen width. */
    fun horizontalContentPaddingDp(context: Context): Float =
        WearInsets.horizontalContentPaddingDp(context.resources.configuration.screenWidthDp)
}

/**
 * Geometry of the display as seen by a measuring view. The round-screen helpers in Wear Compose
 * (`CurvedLayout`, `EdgeButton`, `ScrollIndicator`, `Vignette`) all reduce to these three numbers.
 */
data class ScreenShape(
    val width: Int,
    val height: Int,
    val isRound: Boolean,
) {
    val centerX: Float get() = width / 2f
    val centerY: Float get() = height / 2f

    /** 0 for a square screen: callers branch on this rather than treating it as a degenerate circle. */
    val radius: Float get() = if (isRound) minOf(width, height) / 2f else 0f

    /**
     * Half the horizontal inset that keeps a centred rectangle inside the circle: the inscribed
     * square reaches `r / sqrt(2)`, so each side gives up `r * (1 - 1/sqrt(2))`.
     */
    val boxedHorizontalInset: Int
        get() = if (isRound) (radius * (1f - 1f / sqrt(2f))).toInt().coerceAtLeast(0) else 0

    /** The x range of the circle at height [y]; the full width when the screen is square. */
    fun horizontalBoundsAt(y: Float): ClosedFloatingPointRange<Float> {
        if (!isRound || radius <= 0f) return 0f..width.toFloat()
        val dy = (y - centerY).coerceIn(-radius, radius)
        val half = sqrt((radius * radius - dy * dy).coerceAtLeast(0f))
        return (centerX - half)..(centerX + half)
    }

    fun contains(x: Float, y: Float): Boolean {
        if (!isRound) return x >= 0f && x <= width && y >= 0f && y <= height
        val dx = x - centerX
        val dy = y - centerY
        return dx * dx + dy * dy <= radius * radius
    }

    companion object {
        fun of(view: android.view.View): ScreenShape =
            ScreenShape(
                width = (if (view.measuredWidth > 0) view.measuredWidth else view.width).coerceAtLeast(0),
                height = (if (view.measuredHeight > 0) view.measuredHeight else view.height).coerceAtLeast(0),
                isRound = WearScreen.isRound(view.context),
            )
    }
}
