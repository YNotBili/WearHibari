package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.view.WearVignetteView

/** Possible combinations for vignette state. */
@JvmInline
value class VignettePosition constructor(private val key: Int) {

    internal fun drawTop(): Boolean {
        return when (key) {
            1 -> false
            else -> true
        }
    }

    internal fun drawBottom(): Boolean {
        return when (key) {
            0 -> false
            else -> true
        }
    }

    public companion object {
        /** Only the top part of the vignette is displayed. */
        val Top: VignettePosition = VignettePosition(0)

        /** Only the bottom part of the vignette is displayed. */
        val Bottom: VignettePosition = VignettePosition(1)

        /** Both the top and bottom of the vignette is displayed. */
        val TopAndBottom: VignettePosition = VignettePosition(2)
    }

    override fun toString(): String {
        return when (key) {
            0 -> "VignetteValue.Top"
            1 -> "VignetteValue.Bottom"
            else -> "VignetteValue.Both"
        }
    }
}

/**
 * Vignette is whole screen decoration used to blur the top and bottom of the edges of a wearable
 * screen when scrolling content is displayed. The vignette is split between a top and bottom image
 * which can be displayed independently depending on the use case.
 *
 * The vignette is designed to be used as an overlay, typically in the `Scaffold`: put it last
 * inside [AppScaffold] (or a [ScreenScaffold]'s box) so it sits on top of the scrolling content,
 * which it does not swallow because the node is not clickable.
 *
 * ## How this differs from upstream, and why
 *
 * Upstream `androidx.wear.compose.material.Vignette` is a `Box(Modifier.fillMaxSize())` holding one
 * or two `Image`s drawn from `ImageResources.{Circular,Rectangular}Vignette{Top,Bottom}` with
 * `ContentScale.FillWidth` and aligned to `TopCenter` / `BottomCenter`. There are **no colour stops
 * and no gradient in that file**: the falloff lives entirely in the shipped bitmaps
 * (`circular_vignette_*.png` 384x384, `rectangular_vignette_*.png` 360x12). Those resources cannot
 * travel with this port, so [WearVignetteView] reconstructs them as gradients whose every stop is
 * sampled from those exact PNGs; see the tables there.
 *
 * Geometry preserved: the round variant scales with the view width (a 1:1 image, so a full-screen
 * square) and the band hugs the screen circle; the rectangular variant scales with the view width
 * too, which is what makes its strip `width / 30` tall rather than a fixed dp.
 *
 * Deviations, all of them cosmetic:
 *  - Upstream overlays up to two bitmaps over the whole `fillMaxSize` box; this is one node that
 *    draws both bands, so a `TopAndBottom` vignette costs one view instead of two.
 *  - Upstream's `Box` sizes itself to the parent's constraints; the equivalent here is
 *    `matchParentSize()`, so a `Vignette` outside a full-size parent paints its gradients to that
 *    smaller box rather than overflowing it.
 *  - `isRoundDevice()` upstream reads `LocalConfiguration`, which recomposes on change. Here the
 *    view asks `WearScreen.isRound(context)` when it is sized, so a round/square switch needs a
 *    layout pass, not a retune.
 *
 * @param vignettePosition whether to draw top and/or bottom images for this Vignette
 * @param modifier optional Modifier for the root of the Vignette
 */
@Tunable
fun Vignette(vignettePosition: VignettePosition, modifier: Modifier = Modifier) {
    Node(
        modifier = modifier
            .matchParentSize()
            .viewClass(WearVignetteView::class.java)
            .thenViewAttribute<WearVignetteView, Boolean>(uniqueKey, vignettePosition.drawTop()) {
                drawTop = it
            }
            .thenViewAttribute<WearVignetteView, Boolean>(uniqueKey, vignettePosition.drawBottom()) {
                drawBottom = it
            },
    )
}
