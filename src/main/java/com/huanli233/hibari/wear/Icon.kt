package com.huanli233.hibari.wear

import android.widget.ImageView
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.wear.attributes.contentDescription
import com.huanli233.hibari.wear.attributes.image
import com.huanli233.hibari.wear.attributes.imageTint
import com.huanli233.hibari.wear.attributes.scaleType

/**
 * Ported from androidx.wear.compose.material3.Icon.
 *
 * Compose tints through the painter, so a programmatically built `ImageVector` works there; the
 * Views side can only tint what the framework hands back, so resource ids and drawables work and
 * vectors do not.
 *
 * @param tint Defaults to null and resolves to the ambient content colour in the body. Upstream's
 *   default is `contentColorFor(...)` — a composable read — and a `@Tunable` default expression is
 *   hoisted into a non-`@Tunable` `$default` that cannot read locals, so putting that call in the
 *   signature would hand every untinted icon an unresolved colour instead of the ambient one.
 */
@Tunable
fun Icon(
    image: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val resolvedTint = tint ?: contentColorFor(Color.Transparent)
    Node(
        modifier = modifier
            .viewClass(ImageView::class.java)
            .image(image)
            .contentDescription(contentDescription)
            .scaleType(ImageView.ScaleType.FIT_CENTER)
            // Emitted unconditionally; see [imageTint] for why a tint that drops out of the chain is
            // a view rebuild rather than a clear.
            .imageTint(resolvedTint)
    )
}

object IconDefaults {
    val ExtraSmallSize: Dp = 12.dp
    val SmallSize: Dp = 16.dp
    val DefaultSize: Dp = 26.dp
    val LargeSize: Dp = 32.dp
    val ExtraLargeSize: Dp = 36.dp
}

/**
 * Fixed-square icon slot, matching the `size(iconSize)` upstream wraps `Icon` in.
 *
 * @param tint Left null so [Icon] resolves it from the ambient content colour; a `@Tunable` default
 *   cannot call `contentColorFor`, whose read of `LocalContentColor` does not resolve in `$default`.
 */
@Tunable
fun FixedSizeIcon(
    image: Any?,
    contentDescription: String?,
    iconSize: Dp = IconDefaults.DefaultSize,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    Icon(
        image = image,
        contentDescription = contentDescription,
        modifier = modifier.size(DpSize(iconSize, iconSize)),
        tint = tint,
    )
}
