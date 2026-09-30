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
 * Upstream resolves the *enabled* pair inside each `*Colors` class and passes the painter one [Color]
 * (`material3/IconButton.kt:886`, `colors.containerColor(enabled)`); its press feedback is a separate
 * ripple state layer over the *content* (`material3/Ripple.kt:297-303`), which this module has not
 * ported. [pressedContainerColor]/[pressedBorder] therefore stand in for that ripple by tinting the
 * container, and [ContainerDrawable] swaps the variants on view state. Read that header before calling
 * this upstream's mechanism — the rendered result is close, the route is ours.
 *
 * The *checked* variants are deliberately absent. No view in `hibari-wear` implements `Checkable`
 * (grep over the module: no `Checkable`, no `isChecked`), and `onStateChange` reads only
 * `state_pressed` and `state_enabled`, so `android.R.attr.state_checked` has nothing to arrive from.
 * Two consequences to keep in mind: `Modifier.container` is declared on [android.view.View], so if
 * this drawable is ever hung off one of `hibari-material`'s five checkable hosts
 * (`CheckBox`/`RadioButton`/`Switch`/`CheckedTextView`/`chip.Chip`), a checked state would arrive and
 * be silently unstyled; and the reasoning above is scoped to this module, not to the repo. Toggle
 * buttons answer `checked` where upstream answers it too, in composition: they pick the resting shape
 * and colours at the call site
 * (`IconToggleButton.kt:89`, `TextToggleButton.kt`), which is upstream's
 * `AnimatedToggleRoundedCornerShape` route (`material3/AnimatedToggleRoundedCornerShape.kt`) rather
 * than a second mechanism of ours.
 */
data class ContainerSpec(
    val shape: Shape = RectangleShape,
    val containerColor: Color,
    val border: BorderStroke? = null,
    val pressedContainerColor: Color? = null,
    val pressedBorder: BorderStroke? = null,
    val disabledContainerColor: Color? = null,
    val disabledBorder: BorderStroke? = null,
    /**
     * The outline to morph toward while pressed, for the components whose press feedback is a shape
     * rather than a colour: upstream's icon buttons round from [shape] to
     * `IconButtonDefaults.pressedShape` through `animateButtonShape`
     * (`material3/IconButton.kt:392-402`). Interpolated corner by corner, so a
     * [com.huanli233.hibari.ui.geometry.RoundedCornerShape] morphs to a
     * [com.huanli233.hibari.ui.geometry.CornerBasedShape] correctly in both directions.
     */
    val pressedShape: Shape? = null,
) {
    /** True when nothing should be painted, matching Compose's `ColorPainter(Color.Transparent)` skip. */
    val isEmpty: Boolean
        get() = containerColor.alpha == 0f &&
            (border == null || border.width <= 0.dp || border.color.alpha == 0f)
}

val TransparentContainer = ContainerSpec(shape = CircleShape, containerColor = Color.Transparent)
