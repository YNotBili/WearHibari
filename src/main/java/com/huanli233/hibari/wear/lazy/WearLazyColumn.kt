package com.huanli233.hibari.wear.lazy

import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.WearableRecyclerView
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.recyclerview.HibariAdapter
import com.huanli233.hibari.recyclerview.LazyListScope
import com.huanli233.hibari.recyclerview.LazyListScopeImpl
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.currentTuner
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.ref
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.WearScreen

/**
 * Scroll position of a [ScalingLazyColumn], mirroring `ScalingLazyListState` in
 * androidx.wear.compose.foundation.lazy.
 *
 * Upstream names its two public members after the item at the **viewport midline**, not the first
 * visible one, and `ScalingLazyColumn` reports `centerItemIndex` from that. A `RecyclerView` can
 * name the item straddling `height / 2` just as directly, so that is what is exposed — inventing a
 * `firstVisibleItemIndex` here would read like LazyColumn and mis-describe what the value is.
 *
 * `centerItemScrollOffset` upstream is the signed distance from the centre item's anchor to the
 * focal line; from Views only an unsigned pixel distance is recoverable without walking decor
 * bounds, so it is reported as a non-negative pixel offset. The suspend `itemScrollScope`,
 * `distanceToIndexSnapshot` and the `Saver` are absent: nothing in Hibari's retune path waits on a
 * list settling, and there is no `Parcelable` state restoration layer to hook a saver into.
 */
class ScalingLazyListState(
    val initialCenterItemIndex: Int = 0,
) {
    internal var recycler: RecyclerView? = null

    /** Held until the view exists, so a position change before attach still applies. */
    private var pendingIndex: Int? = null

    val isScrollInProgress: Boolean
        get() = recycler?.scrollState != RecyclerView.SCROLL_STATE_IDLE

    val canScrollForward: Boolean get() = recycler?.canScrollVertically(1) ?: false
    val canScrollBackward: Boolean get() = recycler?.canScrollVertically(-1) ?: false

    /** The item straddling the viewport midline, matching upstream's `centerItemIndex`. */
    val centerItemIndex: Int
        get() {
            val view = recycler ?: return 0
            val lm = view.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return 0
            val midline = view.height / 2
            val child = view.findChildViewUnder(view.paddingLeft + 1f, (midline + view.paddingTop).toFloat())
                ?: lm.getChildAt(0)
            return child?.let { view.getChildAdapterPosition(it) }?.coerceAtLeast(0)
                ?: lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        }

    /** Signed in upstream; unsigned pixels here, see the class note. */
    val centerItemScrollOffset: Int
        get() {
            val view = recycler ?: return 0
            val midline = view.height / 2
            val child = view.findChildViewUnder(view.paddingLeft + 1f, midline.toFloat()) ?: return 0
            val childCenter = child.top + child.height / 2
            return kotlin.math.abs(childCenter - midline)
        }

    fun scrollToItem(index: Int) {
        pendingIndex = index
        recycler?.scrollToPosition(index)
    }

    fun animateScrollToItem(index: Int) {
        pendingIndex = index
        recycler?.smoothScrollToPosition(index)
    }

    internal fun attach(view: RecyclerView) {
        recycler = view
        pendingIndex?.let { view.scrollToPosition(it) }
        pendingIndex = null
    }
}

/**
 * Ported from androidx.wear.compose.foundation.lazy.ScalingLazyColumn.
 *
 * Backed by `WearableRecyclerView` + `WearableLinearLayoutManager` from `androidx.wear:wear`, which
 * is the closest existing Views equivalent: its `LayoutCallback` is the per-item transform hook, and
 * `setEdgeItemsCenteringEnabled` provides the half-viewport padding the centred arrangement needs.
 *
 * Not ported, with reasons:
 *  - **Snap fling** (`ScalingLazyColumnSnapFlingBehavior`): it settles on a Compose `LazyListAnchor`
 *    computed from measured item offsets mid-fling. A `RecyclerView` fling settles on velocity decay;
 *    approximating the anchor snap means a custom `FlingBehavior`, not an item decoration.
 *  - **`fadingEdgeLength`**: Compose achieves this with a `RenderEffect` blur/dissolve on API 31+, and
 *    the alpha curve I apply is per-item rather than per-pixel, so a long edge fades whole rows in
 *    steps instead of dissolving continuously.
 *  - **`aboveContentPadding`/`belowContentPadding`** as separate knobs: folded into [contentPadding].
 *  - **`autoPreventClickOnScroll`** and the `itemContext`/keyed `contentType` reuse tuning.
 */
@Tunable
fun ScalingLazyColumn(
    modifier: Modifier = Modifier,
    state: ScalingLazyListState? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp),
    centerVertically: Boolean = true,
    scalingParams: ListTransformParams = WearListTransformDefaults.ScalingLazy,
    reverseLayout: Boolean = false,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    // `remember` cannot live in the default: the hoisted `$default` gets a copied tuner parameter but
    // nothing threads it into the nested call, so `currentTuner` there is the intrinsic that throws.
    val resolvedState = state ?: remember { ScalingLazyListState() }
    WearLazyColumn(
        modifier = modifier,
        state = resolvedState,
        contentPadding = contentPadding,
        centerVertically = centerVertically,
        scalingParams = scalingParams,
        reverseLayout = reverseLayout,
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}

/**
 * Ported from androidx.wear.compose.foundation.lazy.TransformingLazyColumn.
 *
 * This is the weaker of the two ports. Upstream's transform lives in `material3/lazy` as
 * `TransformationSpec` / `ResponsiveTransformationSpec` (there is no `ListTransformationSpec` in
 * this tree) and it shrinks the item's **layout height** during measure, so siblings close the gap
 * and the list reflows continuously around the focal line; it also keeps separate container and
 * content alpha, and re-measures in both scroll directions. `WearableLinearLayoutManager` gives one
 * measure pass and positions children by untransformed bounds, so that reflow is not expressible —
 * what is delivered here is ScalingLazyColumn's curve applied to a fixed-pitch list. See
 * [WearListTransformLayoutCallback].
 */
@Tunable
fun TransformingLazyColumn(
    modifier: Modifier = Modifier,
    state: ScalingLazyListState? = null,
    contentPadding: PaddingValues = PaddingValues(),
    transformParams: ListTransformParams = WearListTransformDefaults.ScalingLazy,
    reverseLayout: Boolean = false,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val resolvedState = state ?: remember { ScalingLazyListState() }
    WearLazyColumn(
        modifier = modifier,
        state = resolvedState,
        contentPadding = contentPadding,
        centerVertically = false,
        scalingParams = transformParams,
        reverseLayout = reverseLayout,
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}

@Tunable
internal fun WearLazyColumn(
    modifier: Modifier,
    state: ScalingLazyListState,
    contentPadding: PaddingValues,
    centerVertically: Boolean,
    scalingParams: ListTransformParams,
    reverseLayout: Boolean,
    userScrollEnabled: Boolean,
    content: LazyListScope.() -> Unit,
) {
    val parentTunation = currentTuner.tunation
    val adapter = remember { HibariAdapter(parentTunation) }
    val scope = LazyListScopeImpl().apply(content)
    adapter.submitList(scope.items)

    // Rebuilt per tune so a params change reaches the live layout manager; the manager itself is
    // remembered through the view, not the modifier, because RecyclerView keeps its own reference.
    val callback = remember { WearListTransformLayoutCallback(scalingParams) }
    callback.params = scalingParams

    Node(
        modifier = modifier
            .viewClass(WearableRecyclerView::class.java)
            .padding(contentPadding)
            .ref { view ->
                if (view !is WearableRecyclerView) return@ref
                view.layoutManager = WearScrollableLinearLayoutManager(
                    view.context, callback, userScrollEnabled
                ).apply { this.reverseLayout = reverseLayout }
                view.adapter = adapter
                view.setEdgeItemsCenteringEnabled(centerVertically)
                view.isCircularScrollingGestureEnabled = false
                state.attach(view)
            }
    )
}
