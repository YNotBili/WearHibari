package com.huanli233.hibari.wear

import android.view.View
import com.huanli233.hibari.ui.unit.Offset

/**
 * Ported from `androidx.wear.compose.foundation.GestureInclusion` (`foundation/GestureInclusion.kt:22-35`).
 *
 * This is the type's single home in this module, mirroring upstream: `material3` does not re-declare
 * it — `material3/SwipeToReveal.kt:114` imports the `foundation` type, as does
 * `foundation/pager/Pager.kt:58`. So there is nothing to alias here; the two material3-side consumers
 * (`SwipeToRevealDefaults.gestureInclusion`, `PagerDefaults.gestureInclusion`) are ported in their own
 * files against this interface.
 *
 * Signature deviation: upstream's predicate is
 * `ignoreGestureStart(offset: Offset, layoutCoordinates: LayoutCoordinates)`, and `LayoutCoordinates`
 * has no counterpart anywhere in Hibari (`androidx.compose.ui.layout` is not a dependency). The second
 * parameter becomes the [View] the gesture landed in, which is the object the two operations upstream
 * calls are performed on: [gestureInclusionScreenOffset] is `layoutCoordinates.localToScreen(offset)`
 * and [gestureInclusionRootWidthPx] is `layoutCoordinates.findRootCoordinates().size.width`. Upstream's
 * two implementations read exactly those two facts
 * (`material3/SwipeToReveal.kt:1619-1624`, `foundation/pager/Pager.kt:324-338`), so passing the view
 * rather than a pre-resolved pair keeps the same split of responsibility — the inclusion decides, the
 * caller supplies raw geometry.
 *
 * [offset] is view-local, in px, matching upstream's `firstDown.position` relative to the node that
 * owns the gesture.
 *
 * Not ported: upstream's `@Stable` annotations on the implementations
 * (`material3/SwipeToReveal.kt:1614`, `:1645`) — those are Compose-compiler stability hints with no
 * meaning for a Views attribute, and the implementations themselves live in the consumer files, where
 * they are private.
 */
interface GestureInclusion {

    /**
     * Determines whether a gesture starting at the given offset will be handled by this component.
     *
     * @param offset The offset of the gesture within the component's layout, in px.
     * @param view The view whose bounds the [offset] is relative to; upstream's `layoutCoordinates`.
     * @return `true` if the gesture should be ignored by this component, `false` otherwise.
     */
    fun ignoreGestureStart(offset: Offset, view: View): Boolean
}

/**
 * `LayoutCoordinates.localToScreen(offset)` (`GestureInclusion.kt:34` as used by
 * `material3/SwipeToReveal.kt:1620`): the view's own screen position added to a point expressed in
 * its coordinate space.
 */
fun gestureInclusionScreenOffset(offset: Offset, view: View): Offset {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return Offset(offset.x + location[0], offset.y + location[1])
}

/**
 * `layoutCoordinates.findRootCoordinates().size.width` (`material3/SwipeToReveal.kt:1621`): the width
 * of the whole layout, in px. [View.getRootView] is the root this module's nodes live under, and
 * before the first layout pass the size has to come from `measuredWidth` because `width` is still 0.
 */
fun gestureInclusionRootWidthPx(view: View): Int {
    val root = view.rootView
    return if (root.width > 0) root.width else root.measuredWidth
}
