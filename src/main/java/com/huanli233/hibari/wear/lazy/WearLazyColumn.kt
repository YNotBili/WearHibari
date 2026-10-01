package com.huanli233.hibari.wear.lazy

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.WearableRecyclerView
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.recyclerview.HibariAdapter
import com.huanli233.hibari.recyclerview.LazyListItem
import com.huanli233.hibari.recyclerview.LazyListScope
import com.huanli233.hibari.recyclerview.LazyListScopeImpl
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.currentTuner
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.ref
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.StablePaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
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
 * bounds, so it is reported as a non-negative pixel offset. The suspend `itemScrollScope` and
 * `distanceToIndexSnapshot` are not built here: nothing in Hibari's retune path waits on a list
 * settling. Nor is the `Saver` — Hibari declares no `Saver` API in any module, which is a gap in
 * this port rather than a wall: `View.onSaveInstanceState` plus the stable ids `Renderer
 * .generateViewId` registers for named views would carry the centre item across a process death.
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
    // The scope is collected again every tune, never cached: an item body is a `@Tunable` lambda that
    // captures this tune's values, so a row the differ does rebind has to be handed the new closure.
    // Reusing one `LazyListScopeImpl` keeps only the per-item model; what is skipped is the submit,
    // which is where the expensive part lives — see [WearListSubmittedItems].
    val scope = remember { LazyListScopeImpl() }
    val submitted = remember { WearListSubmittedItems() }
    scope.reset()
    scope.apply(content)
    submitted.take(scope.items)?.let { adapter.submitList(it) }

    // Rebuilt per tune so a params change reaches the live layout manager; the manager itself is
    // remembered through the view, not the modifier, because RecyclerView keeps its own reference.
    val callback = remember { WearListTransformLayoutCallback(scalingParams) }
    callback.params = scalingParams

    Node(
        modifier = modifier
            .viewClass(WearableRecyclerView::class.java)
            .padding(contentPadding)
            // `Modifier.ref` runs only when the view is created (`Renderer.kt:163`), so the four
            // parameters upstream re-reads on every recomposition (`foundation/lazy/ScalingLazyColumn
            // .kt:634-704`) cannot ride on it. `contentPadding` already reaches the view through
            // `padding`; the three below are moved here from the old `ref` block, in this order:
            // the manager has to exist before reverse layout and edge centring can be written to it,
            // and centring has to be re-applied after `padding` has replaced the padding it offsets
            // itself from. The adapter, the circular-gesture flag and the state attach stay in `ref`
            // because those genuinely happen once per view.
            .wearListScrollableManager(callback, userScrollEnabled)
            .wearListReverseLayout(reverseLayout)
            .wearListEdgeCentering(centerVertically, contentPadding)
            .ref { view ->
                if (view !is WearableRecyclerView) return@ref
                view.adapter = adapter
                view.isCircularScrollingGestureEnabled = false
                state.attach(view)
            }
    )
}

/**
 * `userScrollEnabled`, as a value-compared attribute rather than a creation-time constructor argument.
 *
 * The flag reaches scrolling through `LayoutManager.canScrollVertically()`, which `RecyclerView`
 * re-reads on every touch, nested scroll, fling and `computeVerticalScroll*` call (verified against
 * `androidx.recyclerview` 1.3.1: `RecyclerView.scrollBy`, `nestedScrollByInternal`,
 * `onInterceptTouchEvent`, `onTouchEvent`, `fling`), and [WearScrollableLinearLayoutManager] answers
 * it from [WearScrollableLinearLayoutManager.userScrollEnabled], a `var` (`lazy/WearListTransform.kt`),
 * so a change is one write and takes effect on the next gesture without disturbing the rows or an
 * in-flight fling.
 *
 * A manager that does not exist yet is the only case that swaps it. `setLayoutManager` drops every
 * attached child and clears the recycler (`RecyclerView.setLayoutManager`), so the outgoing anchor is
 * carried over with the platform's own save/restore pair and the outgoing `reverseLayout` is copied
 * rather than assumed. That swap happens once per view, not once per flip.
 */
private fun Modifier.wearListScrollableManager(
    callback: WearListTransformLayoutCallback,
    userScrollEnabled: Boolean,
): Modifier = thenViewAttribute<WearableRecyclerView, Boolean>(uniqueKey, userScrollEnabled) { enabled ->
    val live = layoutManager as? WearScrollableLinearLayoutManager
    if (live != null) {
        live.userScrollEnabled = enabled
    } else {
        // First application only: there is no manager to write to yet, and the outgoing one is the
        // framework default this view was built with, whose anchor and direction have to survive the
        // swap (`setLayoutManager` recycles every child, so the position is restored explicitly).
        val outgoing = layoutManager as? LinearLayoutManager
        val anchor = outgoing?.onSaveInstanceState()
        layoutManager = WearScrollableLinearLayoutManager(context, callback, enabled).apply {
            if (outgoing != null) reverseLayout = outgoing.reverseLayout
            if (anchor != null) onRestoreInstanceState(anchor)
        }
    }
}

/**
 * `reverseLayout` through `LinearLayoutManager.setReverseLayout`, which is the platform's own live
 * path for it: it ignores a no-op write and calls `requestLayout()` when the value moved, so the list
 * re-lays-out instead of keeping a value that only means something to the next constructor.
 */
private fun Modifier.wearListReverseLayout(reverseLayout: Boolean): Modifier =
    thenViewAttribute<WearableRecyclerView, Boolean>(uniqueKey, reverseLayout) { reversed ->
        (layoutManager as? LinearLayoutManager)?.reverseLayout = reversed
    }

/**
 * `centerVertically`, i.e. `WearableRecyclerView.setEdgeItemsCenteringEnabled`, which is what produces
 * the half-viewport padding the centred arrangement needs.
 *
 * The compared value carries the padding on purpose. `setupCenteredPadding` (`androidx.wear:wear`
 * 1.4.0) reads the view's *current* top and bottom padding, stores them as the pair to restore and
 * overwrites both with `viewportHeight / 2 - firstChildHeight / 2`, so a `contentPadding` change
 * writes plain padding over the centring offset and the list silently stops centring. Re-applying
 * centring after every padding change is what keeps the two knobs reconcilable the way they are in
 * Compose, where one measure pass reads both.
 *
 * The padding enters as [StablePaddingValues] — a data class — because a caller may hand a
 * `PaddingValues` implementation that compares by identity, which would otherwise make every tune
 * look like a padding change.
 */
private fun Modifier.wearListEdgeCentering(
    centerVertically: Boolean,
    contentPadding: PaddingValues,
): Modifier = thenViewAttribute<WearableRecyclerView, WearListCentering>(
    uniqueKey,
    WearListCentering(centerVertically, StablePaddingValues.fromPaddingValues(contentPadding)),
) { centering ->
    setEdgeItemsCenteringEnabled(centering.centerVertically)
}

private data class WearListCentering(
    val centerVertically: Boolean,
    val contentPadding: StablePaddingValues,
)

/**
 * Skips `submitList` for an item list that compares equal to the one already in place.
 *
 * `ListAdapter.submitList` hands the list to `AsyncListDiffer`, which runs a full `DiffUtil` pass on a
 * background thread for any list that is not the *same object* as the current one (`androidx
 * .recyclerview` 1.3.1, `AsyncListDiffer.submitList` — its only early-out is a reference comparison).
 * For a 40-row list whose colour changed that is a Myers walk, its id maps and a thread hand-off for
 * a diff that reports nothing: `HibariAdapter.ItemCallback` compares a row by key, content type and
 * data, so an equal list produces an empty update and no row rebinds either way.
 *
 * The comparison here is that same predicate, one row at a time, so nothing is considered unchanged
 * here that the differ would still have rebound. It mirrors `SubmittedItems`
 * (`hibari-recyclerview` `LazyList.kt:107-119`), which is `internal` to that module and so not
 * reachable from this one.
 *
 * The submitted list is a copy, not the collected one: that is what keeps the mutable scope above
 * reusable, and handing `AsyncListDiffer` the same instance twice would hit its reference early-out
 * and skip a diff the changed contents needed.
 */
private class WearListSubmittedItems {

    private var last: List<LazyListItem> = emptyList()

    /** Returns the list to submit, or null when it compares equal to the one already submitted. */
    fun take(next: List<LazyListItem>): List<LazyListItem>? {
        if (next.size == last.size && next.indices.all { last[it] == next[it] }) return null
        val snapshot = next.toList()
        last = snapshot
        return snapshot
    }
}
