package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.RectangleShape
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.wear.attributes.container

/**
 * Ported from androidx.wear.compose.material3.surface (`material3/Surface.kt:57-85`), the internal
 * modifier every wear Material 3 container funnels through: draw a background, clip it to a shape,
 * stroke a border around that shape.
 *
 * **What upstream actually declares there is a `Modifier`, not a component** — verified: `grep -rn
 * "fun Surface\\("` over `/home/rj/qmce/app-new/src/main/java/androidx/wear/compose` returns no
 * hits, and `Surface.kt` holds only `Modifier.surface(transformation, painter, shape, border)`
 * (`:57-85`), `Modifier.paintBackground` (`:96-100`) and the private `PainterElement`/`PainterNode`
 * behind it (`:102-181`). A public `Surface` composable exists in *phone* Material 3, which the
 * reference tree does not carry. [Surface] below is therefore the component wrapper the port brief
 * asks for, and its parameter list is that phone-Material3 order (`modifier, shape, color,
 * contentColor, border, content`), while its *behaviour* is wear's modifier: the flat path at
 * `:80-85`, which is `border(shape).clip(shape).paintBackground(painter, Crop)`.
 *
 * That path is precisely what [ContainerSpec] already is in this module — [ContainerDrawable]'s own
 * doc calls itself "what Compose's `Modifier.surface()` reduces to on its non-transformation path" —
 * so [surfaceContainer] is the modifier port and this file adds no drawing of its own.
 *
 * # Not honoured, with reasons
 *
 *  - `transformation: SurfaceTransformation?` (`:59`, `:64-79`): the whole `graphicsLayer` branch
 *    that morphs the container and the content separately. `material3/SurfaceTransformation.kt` is a
 *    painter-plus-two-`graphicsLayer`-hook interface with no Views equivalent here, and no wear
 *    component in this module reaches it.
 *  - `painter: Painter` (`:60`): any painter — an image, a brush, a layer list. [ContainerSpec] takes
 *    flat [Color]s only, so [Surface] can draw only what `ColorPainter(color)` draws upstream.
 *    `Modifier.paintBackground`'s alignment/`ContentScale.Crop` arithmetic (`:96-181`, the
 *    intrinsic-size → scale-factor → align → translate chain) exists to place a *sized* painter, and
 *    a flat colour has no intrinsic size, so none of it is ported.
 *  - `.clip(shape)` (`:83`): Compose clips the *content* to the shape. A `View` background does not
 *    clip its children, and this repo has no `clipToOutline`/`ViewOutlineProvider` attribute to
 *    borrow, so children of a rounded [Surface] can overflow the rounded corner. The fill and the
 *    border are rounded; only the clipping of what is inside is lost.
 *  - Shadow and tonal elevation: neither exists in the file this ports. There is no `shadowElevation`
 *    parameter to drop — wear's `surface` modifier draws fill, border and clip only, and a watch
 *    screen is round, so the phone-Material3 elevation/`Arrangement` stack never appears here.
 *    `Modifier.elevation(Float)` exists in `foundation.attributes` if a caller wants a real Android
 *    shadow, and [Surface] deliberately does not expose it: an unwired parameter is worse than none.
 *  - `interactionSource`: not a parameter of upstream's modifier either. The pressed state reaches
 *    the container through [ContainerSpec.pressedContainerColor], which [ContainerDrawable]
 *    resolves from the view's own drawable state. A *checked* container is not a drawable state
 *    here: no view in this module is `Checkable`, so `state_checked` never arrives, and the toggle
 *    rows answer `checked` in composition the way upstream's `AnimatedToggleRoundedCornerShape`
 *    does ([ContainerSpec] carries the full reasoning).
 *
 * @param shape [RectangleShape], upstream's own default for the shape argument (`:61`).
 * @param color has no default because upstream's `painter` argument has none either (`:60`) and this
 *   module's wear `ColorScheme` has no plain `surface` role to borrow — the closest three are
 *   `surfaceContainerLow`, `surfaceContainer` and `surfaceContainerHigh` (ColorScheme.kt:30-32),
 *   which is what each component picks for itself.
 * @param contentColor Defaults to `null` and resolves in the body to `contentColorFor(color)`,
 *   because that read reaches [MaterialTheme] and a `@Tunable` default expression is hoisted into a
 *   non-`@Tunable` `$default` that cannot. Deriving the content colour is the one place this port goes
 *   beyond upstream's modifier, which paints and never provides a colour: it is phone-Material3's
 *   `contentColor = contentColorFor(color)` default, and without it a [Text] inside a rounded surface
 *   keeps whatever ambient colour was already in scope. Pass a colour to override;
 *   [contentColorFor] itself falls back to the ambient [LocalContentColor] when [color] is not a
 *   known container role, so nothing is forced on the caller either.
 */
@Tunable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color,
    contentColor: Color? = null,
    border: BorderStroke? = null,
    content: @Tunable BoxScope.() -> Unit,
) {
    val scope = content
    val resolvedContent = contentColor ?: contentColorFor(color)
    Box(
        modifier = modifier.surfaceContainer(shape, color, border),
    ) {
        provideContentColor(resolvedContent) { scope() }
    }
}

/**
 * The port of `Modifier.surface(null, ColorPainter(color), shape, border)` on the flat path
 * (`material3/Surface.kt:80-85`).
 *
 * `private` where upstream is `internal` (`:57-58`) because nothing else in this module calls it;
 * [Surface] is its only route in, exactly as upstream's `*Impl` containers are the only routes in
 * there.
 */
private fun Modifier.surfaceContainer(
    shape: Shape,
    color: Color,
    border: BorderStroke?,
): Modifier = this.container(
    ContainerSpec(
        shape = shape,
        containerColor = color,
        border = border,
    ),
)
