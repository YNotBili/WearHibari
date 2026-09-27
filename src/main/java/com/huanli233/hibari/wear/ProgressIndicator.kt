package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.attributes.progressIndicatorAttrs
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.view.WearCircularProgressView
import com.huanli233.hibari.wear.view.WearLinearProgressView

/**
 * Ported from androidx.wear.compose.material3.ProgressIndicatorColors. Upstream hands `Brush`es
 * around; every brush here is a solid colour, so [Color] is enough.
 */
data class ProgressIndicatorColors(
    val indicatorColor: Color,
    val trackColor: Color,
    val disabledIndicatorColor: Color,
    val disabledTrackColor: Color,
)

/**
 * Ported from androidx.wear.compose.material3.CircularProgressIndicator.
 *
 * `allowProgressOverflow` and its overflow-track brush are dropped: the overflow branch only
 * differs by which brush fills the remaining sweep.
 *
 * @param colors Defaults to `null` and resolves in the body: `ProgressIndicatorDefaults.colors()`
 *   reads `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable`
 *   `$default` method that cannot.
 */
@Tunable
fun CircularProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ProgressIndicatorColors? = null,
    strokeWidth: Dp = ProgressIndicatorDefaults.LargeStrokeWidth,
    startAngle: Float = WearCircularProgressView.StartAngle,
    endAngle: Float = startAngle,
) {
    val resolved = colors ?: ProgressIndicatorDefaults.colors()
    Node(
        modifier = modifier.viewClass(WearCircularProgressView::class.java).progressIndicatorAttrs(
            spec = ProgressSpec(
                progress = progress,
                indeterminate = false,
                enabled = enabled,
                colors = resolved,
                strokeWidth = strokeWidth,
                startAngle = startAngle,
                endAngle = endAngle,
            ),
        )
    )
}

@Tunable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ProgressIndicatorColors? = null,
    strokeWidth: Dp = ProgressIndicatorDefaults.IndeterminateStrokeWidth,
) {
    val resolved = colors ?: ProgressIndicatorDefaults.colors()
    Node(
        modifier = modifier.viewClass(WearCircularProgressView::class.java).progressIndicatorAttrs(
            spec = ProgressSpec(
                progress = 0f,
                indeterminate = true,
                enabled = enabled,
                colors = resolved,
                strokeWidth = strokeWidth,
                startAngle = WearCircularProgressView.StartAngle,
                endAngle = WearCircularProgressView.StartAngle,
            ),
        )
    )
}

/** Ported from androidx.wear.compose.material3.LinearProgressIndicator. */
@Tunable
fun LinearProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ProgressIndicatorColors? = null,
    strokeWidth: Dp = ProgressIndicatorDefaults.LargeStrokeWidth,
) {
    val resolved = colors ?: ProgressIndicatorDefaults.colors()
    Node(
        modifier = modifier.viewClass(WearLinearProgressView::class.java).progressIndicatorAttrs(
            spec = ProgressSpec(
                progress = progress,
                indeterminate = false,
                enabled = enabled,
                colors = resolved,
                strokeWidth = strokeWidth,
                startAngle = WearCircularProgressView.StartAngle,
                endAngle = WearCircularProgressView.StartAngle,
            ),
        )
    )
}

data class ProgressSpec(
    val progress: Float,
    val indeterminate: Boolean,
    val enabled: Boolean,
    val colors: ProgressIndicatorColors,
    val strokeWidth: Dp,
    val startAngle: Float,
    val endAngle: Float,
)

object ProgressIndicatorDefaults {
    /** Upstream picks 8.dp/5.dp on small screens; that needs `isSmallScreen()`, not ported. */
    val LargeStrokeWidth: Dp = 12.dp
    val SmallStrokeWidth: Dp = 8.dp
    val IndeterminateStrokeWidth: Dp = 3.dp

    @Tunable
    fun colors(): ProgressIndicatorColors = MaterialTheme.colorScheme.defaultProgressIndicatorColors

    @Tunable
    fun colors(
        indicatorColor: Color = Color.Unspecified,
        trackColor: Color = Color.Unspecified,
        disabledIndicatorColor: Color = Color.Unspecified,
        disabledTrackColor: Color = Color.Unspecified,
    ): ProgressIndicatorColors {
        val defaults = MaterialTheme.colorScheme.defaultProgressIndicatorColors
        return defaults.copy(
            indicatorColor = indicatorColor.takeIfSpecified(defaults.indicatorColor),
            trackColor = trackColor.takeIfSpecified(defaults.trackColor),
            disabledIndicatorColor = disabledIndicatorColor.takeIfSpecified(defaults.disabledIndicatorColor),
            disabledTrackColor = disabledTrackColor.takeIfSpecified(defaults.disabledTrackColor),
        )
    }
}

internal val ColorScheme.defaultProgressIndicatorColors: ProgressIndicatorColors
    @Tunable get() = ProgressIndicatorColors(
        indicatorColor = ColorSchemeKeyTokens.Primary.resolve(this),
        trackColor = ColorSchemeKeyTokens.SurfaceContainer.resolve(this),
        disabledIndicatorColor = ColorSchemeKeyTokens.OnSurface.resolve(this)
            .toDisabledColor(ColorScheme.DisabledContentAlpha),
        disabledTrackColor = ColorSchemeKeyTokens.OnSurface.resolve(this)
            .toDisabledColor(ColorScheme.DisabledContainerAlpha),
    )

private fun Color.takeIfSpecified(fallback: Color): Color =
    if (isSpecified) this else fallback
