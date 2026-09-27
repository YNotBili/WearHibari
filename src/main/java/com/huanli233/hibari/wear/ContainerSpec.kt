package com.huanli233.hibari.wear

import com.huanli233.hibari.ui.geometry.CircleShape
import com.huanli233.hibari.ui.geometry.RectangleShape
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp

/** A stroke width paired with the colour to stroke it in. */
data class BorderStroke(
    val width: Dp,
    val color: Color,
)

/**
 * Everything a Material 3 container paints, as an immutable value.
 *
 * Hibari diffs attribute values with `equals`, so the container is described by a data class rather
 * than handed to the view as a live [android.graphics.drawable.Drawable]: a retune that changes
 * nothing leaves the attribute equal and the background untouched.
 *
 * Upstream resolves the pressed/disabled/checked variants inside each `*Colors` class from
 * `interactionSource` and passes the painter one [Color]. Here they travel together and the
 * [ContainerDrawable] picks per view state, so the rendered pixels match while the resolve site
 * differs.
 */
data class ContainerSpec(
    val shape: Shape = RectangleShape,
    val containerColor: Color,
    val border: BorderStroke? = null,
    val pressedContainerColor: Color? = null,
    val pressedBorder: BorderStroke? = null,
    val checkedContainerColor: Color? = null,
    val checkedBorder: BorderStroke? = null,
    val disabledContainerColor: Color? = null,
    val disabledBorder: BorderStroke? = null,
) {
    /** True when nothing should be painted, matching Compose's `ColorPainter(Color.Transparent)` skip. */
    val isEmpty: Boolean
        get() = containerColor.alpha == 0f &&
            (border == null || border.width <= 0.dp || border.color.alpha == 0f)
}

val TransparentContainer = ContainerSpec(shape = CircleShape, containerColor = Color.Transparent)
