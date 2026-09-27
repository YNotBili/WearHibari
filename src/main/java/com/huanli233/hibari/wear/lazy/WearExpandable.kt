package com.huanli233.hibari.wear.lazy

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.huanli233.hibari.animation.Animatable
import com.huanli233.hibari.animation.AnimationSpec
import com.huanli233.hibari.animation.AnimationVector1D
import com.huanli233.hibari.animation.TweenSpec
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.recyclerview.LazyListScope
import com.huanli233.hibari.runtime.RememberObserver
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.mutableStateSetOf
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Ported from `androidx/wear/compose/foundation/Expandable.kt` — the state half of it.
 *
 * Upstream's file carries one state object per expandable: `ExpandableState` with a boolean
 * `expanded` whose getter is the *target* of the running animation (`Expandable.kt:230-266`), plus a
 * keyed bag of those states, `ExpandableStateMapping.getOrPutNew` (`Expandable.kt:296-321`). This
 * port collapses both into one key-set shape — `expandedKeys` plus [toggleExpandable] / [collapseAll]
 * / [retainAll] / [isExpanded] — because a lazy list can hold several expandables and the scope
 * extensions below have to name which one they belong to. **Those four names are this port's own
 * API**: `grep -rn "expandedKeys\|toggleExpandable\|collapseAll\|retainAll" androidx/wear/compose`
 * returns zero hits, and `foundation/Expandable.kt` is the only expandable file in the reference
 * tree, so nothing upstream should be read as endorsing them. Consequences of the choice, all of
 * them deliberate:
 *
 *  - `expanded: Boolean` becomes [isExpanded] / [toggleExpandable] taking a key. Its "target, not
 *    current value" semantics are kept literally: upstream reads `Animatable.targetValue == 1f`
 *    (`Expandable.kt:255`), and here the expanded-key set *is* the target — it flips on the click,
 *    while [expandProgress] chases.
 *  - [expandProgress] is keyed, where upstream's was a property of one state.
 *  - `ExpandableStateMapping<T>` and `rememberExpandableStateMapping` are **not ported**: a map from
 *    key to per-key state is exactly what [ExpandableState] now is, so the mapping would be a second
 *    indirection over the same data.
 *  - `rememberExpandableState(initiallyExpanded, expandAnimationSpec, collapseAnimationSpec)` and
 *    `ExpandableState.saver` are **not ported**: the constructor is public in the key-set shape, so
 *    `remember { ExpandableState(...) }` at the call site does the remembering, and Hibari has no
 *    `rememberSaveable`/`Saver` layer to hook a saver into (same omission already documented on
 *    [ScalingLazyListState]).
 *  - `@FrequentlyChangingValue` on the progress getter has no Hibari equivalent and is dropped; it
 *    is a lint annotation, not behaviour.
 *
 * [AnimationSpec] is honoured in full, including the easing curve, because [Animatable] exists in
 * `com.huanli233.hibari.animation` and its `value`/`targetValue` are snapshot state — every frame it
 * writes invalidates whichever tune read it, which is what drives the reveal.
 *
 * The internal [CoroutineScope] is owned here rather than taken from `rememberCoroutineScope()` so
 * the public constructor stays a single-expression call; [onForgotten] cancels it. Driving state from
 * that scope requires the main thread, which is where a click arrives, so [toggleExpandable],
 * [collapseAll] and [retainAll] are main-thread calls, as they are upstream.
 */
class ExpandableState(
    initiallyExpandedKeys: Set<Any> = emptySet(),
    private val expandAnimationSpec: AnimationSpec<Float> = ExpandableItemsDefaults.expandAnimationSpec,
    private val collapseAnimationSpec: AnimationSpec<Float> = ExpandableItemsDefaults.collapseAnimationSpec,
) : RememberObserver {

    private val _expandedKeys = mutableStateSetOf<Any>()

    /**
     * One [Animatable] per key the user has ever toggled, holding the 0f (collapsed) to 1f (expanded)
     * progress. This is a plain map, not snapshot state: entries are only ever *added* together with a
     * mutation of [_expandedKeys], and every reader of [expandProgress] reads that set first, so a new
     * entry can never appear without its readers already being invalidated.
     */
    private val revealAnimations = mutableMapOf<Any, Animatable<Float, AnimationVector1D>>()

    private val animationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    init {
        _expandedKeys.addAll(initiallyExpandedKeys)
    }

    /** The keys currently targeted as expanded. Upstream's equivalent is `expanded` on one state. */
    val expandedKeys: Set<Any> get() = _expandedKeys.toSet()

    /**
     * Represents the current state of the component for [key], true meaning the extra information is
     * showing. Mid-animation this reflects only the *target* of that animation, exactly as upstream's
     * `targetValue == 1f` does.
     */
    fun isExpanded(key: Any): Boolean = key in _expandedKeys

    /**
     * Progress from 0f (collapsed) to 1f (expanded) for [key]. Mid-animation it is the value the
     * reveal is currently at; at rest it is 0f or 1f.
     */
    fun expandProgress(key: Any): Float {
        val expanded = key in _expandedKeys
        val animation = revealAnimations[key] ?: return if (expanded) 1f else 0f
        return animation.value
    }

    /**
     * Upstream's per-child reveal ramp, from `expandableItems`:
     * `(state.expandProgress * count - animationStart).coerceIn(0f, 1f)` with
     * `animationStart = count - 1 - itemIndex`. Hoisted here so a child's own tune can re-read it
     * every frame instead of inheriting the value captured when it was inserted.
     */
    fun childRevealProgress(key: Any, count: Int, index: Int): Float =
        (expandProgress(key) * count - (count - 1 - index)).coerceIn(0f, 1f)

    /** Show the extra information for [key] if hidden, hide it if shown. */
    fun toggleExpandable(key: Any) {
        if (!_expandedKeys.remove(key)) {
            _expandedKeys.add(key)
        }
        animateExpansion(key)
    }

    /** Collapse every key, animating each one out. */
    fun collapseAll() {
        val expanded = _expandedKeys.toList()
        if (expanded.isEmpty()) return
        _expandedKeys.clear()
        expanded.forEach { animateExpansion(it) }
    }

    /**
     * Keep only the keys still present in [keys]; everything else collapses (with its exit animation).
     * Call it from a list whose items come and go so stale keys cannot accumulate in the expanded set.
     */
    fun retainAll(keys: Set<Any>) {
        val dropped = _expandedKeys.filterNot { it in keys }
        if (dropped.isEmpty()) return
        _expandedKeys.removeAll(dropped)
        dropped.forEach { animateExpansion(it) }
    }

    private fun animateExpansion(key: Any) {
        val expanded = key in _expandedKeys
        val target = if (expanded) 1f else 0f
        // A key that never animated starts at the *other* rail, so the first toggle still animates.
        val animation = revealAnimations.getOrPut(key) { Animatable(if (expanded) 0f else 1f) }
        animationScope.launch {
            animation.animateTo(
                targetValue = target,
                animationSpec = if (expanded) expandAnimationSpec else collapseAnimationSpec,
            )
        }
    }

    override fun onRemembered() {
        // Nothing to acquire: the state is valid from construction, remembered or not.
    }

    override fun onForgotten() {
        animationScope.cancel()
    }
}

/** Contains the default values used by Expandable components. */
object ExpandableItemsDefaults {
    /**
     * Default animation used to show extra information. Upstream `TweenSpec(1000)`
     * (`Expandable.kt:326`), easing left to the spec default on both sides — Compose's `TweenSpec`
     * defaults to `FastOutSlowInEasing`, as does Hibari's
     * (`com.huanli233.hibari.animation` `AnimationSpec.kt:78`), so the curves match.
     */
    val expandAnimationSpec: AnimationSpec<Float> = TweenSpec(1000)

    /** Default animation used to hide extra information. Upstream `TweenSpec(1000)` (`Expandable.kt:329`). */
    val collapseAnimationSpec: AnimationSpec<Float> = TweenSpec(1000)
}

/**
 * Adds a series of items, expanded/collapsed according to the [state] entry for [key], with the
 * reverse-staggered reveal of upstream's `ScalingLazyListScope.expandableItems`.
 *
 * Upstream's parameter list is `(state, count, key, itemContent)`, where `key` builds the per-child
 * list key. Here `key` names the *expandable* — the key-set [ExpandableState] cannot be addressed
 * without one — and the child key factory becomes [childKey], keeping upstream's `null` meaning
 * "position is the key".
 *
 * What is ported 1:1 is the emission rule and the ramp. A child is emitted only while its
 * `animationProgress > 0`, and because `expandProgress` is snapshot state the list scope re-tunes on
 * every animation frame, so children enter last-first and leave first-last: item `count - 1 - i`
 * animates over the `i`-th `duration / count` slice of the 1000 ms tween.
 *
 * What differs is where the *partial height* lives. Upstream crops each row inside its own `Layout`
 * (`shownHeight = height * animationProgress`, child placed at `height * (animationProgress - 1)`),
 * and so do the rows here — through [WearExpandableRevealView]. The crop value is read *inside* the
 * item body, not captured from the list scope, because `HibariAdapter` diffs `LazyListItem` on key
 * alone: an already-present row is never rebound, so only a read inside the row's own tunation keeps
 * it animating. On top of the crop, every enter/leave is also a diffutil insert/remove, which
 * `ListAdapter` + `DefaultItemAnimator` do animate (cross-fade plus translate) — the stagger is real,
 * not faked, and it is the adapter that carries a row in when the list scope first emits it.
 *
 * The cost of the faithful route is stated plainly: while an expandable is animating, the list scope
 * runs per frame and `submitList` is called per frame. `AsyncListDiffer` drops the diffs it has
 * already been superseded on, so the frames where the emitted set is unchanged produce no
 * `notify*` and no rebind; this is the same shape as upstream's per-frame recomposition.
 *
 * Caveat inherited from `LazyListItem.equals` ignoring `content`: a row already in the list keeps the
 * content lambda it was first inserted with, so a `childKey` that stays constant while the captured
 * data behind [itemContent] changes will not re-render. Give children keys that identify their data.
 *
 * @param state the [ExpandableState] holding the expansion of these items.
 * @param key the key whose expanded/hidden state drives the series.
 * @param count the number of items.
 * @param childKey a factory of stable and unique keys for each child; `null` means position is the key.
 * @param itemContent the content of a single item, in a [BoxScope] as upstream's is.
 */
fun LazyListScope.expandableItems(
    state: ExpandableState,
    key: Any,
    count: Int,
    childKey: ((index: Int) -> Any)? = null,
    itemContent: @Tunable BoxScope.(index: Int) -> Unit,
) {
    val expandProgress = state.expandProgress(key)
    repeat(count) { itemIndex ->
        // Animations for each item start in inverse order, the first item animates last.
        val animationStart = count - 1 - itemIndex
        val animationProgress = (expandProgress * count - animationStart).coerceIn(0f, 1f)
        if (animationProgress > 0) {
            item(key = childKey?.invoke(itemIndex)) {
                Node(
                    modifier = Modifier
                        .viewClass(WearExpandableRevealView::class.java)
                        .expandableRevealAttrs(state.childRevealProgress(key, count, itemIndex)),
                ) {
                    Box { itemContent(itemIndex) }
                }
            }
        }
    }
}

/**
 * Adds a single item supporting two levels of information, according to the [state] entry for [key].
 *
 * Upstream's `expandableItem(state, key, content)` measures `content(false)` and `content(true)` in
 * one `Layout`, lerps the item's width and height between them and cross-fades the two by `alpha`.
 * That whole shape is reproduced by [WearExpandableCrossfadeView], including the horizontal centring
 * and `clipToBounds`, and the same `Box` wrapping of each variant — so [content] really is invoked
 * twice per tune, once with `false` and once with `true`, and selects its own variant from the
 * argument rather than from a live "is expanded" flag.
 *
 * The one deviation: upstream's `key` defaults to `null`. A key-set [ExpandableState] cannot address
 * an entry by position, because position moves when any expandable above it is inserted or removed,
 * so [key] is required.
 *
 * @param state the [ExpandableState] connected to this item.
 * @param key the item's list key *and* the key of its expansion in [state]: one name serving both
 *   roles, which follows from the key-set state described in this file's header. Upstream's `key`
 *   (`Expandable.kt:160`) is only the list key, because its state object belongs to one item.
 * @param content the content displayed by the item, called for the collapsed and the expanded variant.
 */
fun LazyListScope.expandableItem(
    state: ExpandableState,
    key: Any,
    content: @Tunable (expanded: Boolean) -> Unit,
) {
    item(key = key) {
        Node(
            modifier = Modifier
                .viewClass(WearExpandableCrossfadeView::class.java)
                .expandableCrossfadeAttrs(state.expandProgress(key)),
        ) {
            Box { content(false) }
            Box { content(true) }
        }
    }
}

/**
 * Adds the item that controls the expandable items sharing [key]; it animates out as they animate in.
 *
 * Upstream expresses this as `expandableItemImpl(invertProgress = true, content = { if (it) content() })`
 * (`Expandable.kt:183`): the collapsed variant is an empty `Box`, the expanded variant is [content],
 * and the progress feeding the crossfade is `1 - expandProgress` (`Expandable.kt:199`). That is what
 * is emitted here, empty `Box` included, so the row collapses to zero height and fades rather than
 * disappearing in one step.
 *
 * Because the row is never removed from the list — it only shrinks to 0x0 — this needs no diffutil
 * change; the height change comes from the row's own `requestLayout`. The fully hidden variant is set
 * `GONE` so it cannot swallow taps on the row (see [WearExpandableCrossfadeView]).
 *
 * Upstream's `key: Any? = null` is required here, for the reason given on [expandableItem].
 *
 * @param state the [ExpandableState] this button drives.
 * @param key the item's list key and the key it toggles.
 * @param content the content displayed, usually a chip or a header row.
 */
fun LazyListScope.expandableButton(
    state: ExpandableState,
    key: Any,
    content: @Tunable () -> Unit,
) {
    item(key = key) {
        Node(
            modifier = Modifier
                .viewClass(WearExpandableCrossfadeView::class.java)
                // Upstream `expandableButton`: progress = 1f - state.expandProgress.
                .expandableCrossfadeAttrs(1f - state.expandProgress(key)),
        ) {
            Box { }
            Box { content() }
        }
    }
}

/** Push upstream's per-child reveal ramp onto a [WearExpandableRevealView]. */
private fun Modifier.expandableRevealAttrs(progress: Float): Modifier =
    thenViewAttribute<WearExpandableRevealView, Float>(uniqueKey, progress) {
        revealProgress = it
    }

/** Push upstream's crossfade ramp onto a [WearExpandableCrossfadeView]. */
private fun Modifier.expandableCrossfadeAttrs(progress: Float): Modifier =
    thenViewAttribute<WearExpandableCrossfadeView, Float>(uniqueKey, progress) {
        crossfadeProgress = it
    }

/** `lerp(from, to, fraction)` for the pixel dimensions upstream lerps. */
private fun expandableLerp(from: Int, to: Int, fraction: Float): Int =
    (from + (to - from) * fraction).roundToInt()

/**
 * The `Layout` of upstream's `expandableItems` as a View: it reports a height of
 * `childHeight * progress` and places its child at `childHeight * (progress - 1)`, so the child's
 * bottom edge stays pinned to the bottom of the cropped box and the row reveals from the bottom up.
 * `Modifier.clipToBounds()` becomes `clipChildren`, which a `ViewGroup` has on by default and which
 * is asserted here rather than relied upon.
 *
 * A [revealProgress] of 1f is a plain pass-through wrapper. The item is only emitted above 0f, but 0f
 * is reachable during a frame, and a zero-height box is exactly what upstream shows then.
 */
class WearExpandableRevealView(
    context: Context,
    attrs: AttributeSet?,
) : FrameLayout(context, attrs) {

    /** 0f = cropped away, 1f = fully shown. */
    var revealProgress: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field != v) {
                field = v
                // The cropped height is a measured dimension, so this is a layout change, not a redraw.
                requestLayout()
                invalidate()
            }
        }

    init {
        clipChildren = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = slotAt(0)
        if (child == null) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
            return
        }
        // Upstream measures the child against the incoming constraints, i.e. uncropped, and crops only
        // the size it reports.
        measureChild(child, widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(
            child.measuredWidth,
            (child.measuredHeight * revealProgress).roundToInt(),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = slotAt(0) ?: return
        val y = (child.measuredHeight * (revealProgress - 1f)).roundToInt()
        child.layout(0, y, child.measuredWidth, y + child.measuredHeight)
    }

    private fun slotAt(index: Int): View? =
        if (childCount > index) getChildAt(index) else null
}

/**
 * The `Layout` of upstream's `expandableItemImpl` as a View: child 0 is the collapsed variant, child 1
 * the expanded one, both measured against the incoming constraints; the reported size is the lerp of
 * the two by [crossfadeProgress], each variant is kept horizontally centred in that width, and the
 * crossfade rides on their alpha.
 *
 * Upstream skips *placing* a variant whose alpha is 0. A `ViewGroup` has no such notion, so the fully
 * transparent variant is set `GONE` instead, which also keeps an invisible variant — the button that
 * has animated out, say — from receiving the taps on its slot. Both slots are still measured, so the
 * lerp has its two endpoints either way.
 *
 * Deviation: upstream also flips the stacking order mid-fade, `zIndex = 1 - progress` for the
 * collapsed variant and `zIndex = progress` for the expanded one (`Expandable.kt:211-218`), so below
 * half progress the collapsing row is composited on top of the arriving one. Here the draw order is
 * fixed — child 1 last, hence on top — because reordering children would move the slot indices the
 * `GONE` swap above depends on. Only the blend order of two half-alpha overlapping rows is affected,
 * and only mid-transition.
 */
class WearExpandableCrossfadeView(
    context: Context,
    attrs: AttributeSet?,
) : FrameLayout(context, attrs) {

    /** 0f = only the first child, 1f = only the second. */
    var crossfadeProgress: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field != v) {
                field = v
                requestLayout()
                invalidate()
            }
        }

    init {
        clipChildren = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val collapsed = slotAt(0)
        val expanded = slotAt(1)
        if (collapsed != null) measureChild(collapsed, widthMeasureSpec, heightMeasureSpec)
        if (expanded != null) measureChild(expanded, widthMeasureSpec, heightMeasureSpec)

        val fraction = crossfadeProgress
        val collapsedWidth = collapsed?.measuredWidth ?: 0
        val collapsedHeight = collapsed?.measuredHeight ?: 0
        val expandedWidth = expanded?.measuredWidth ?: 0
        val expandedHeight = expanded?.measuredHeight ?: 0

        val width = expandableLerp(collapsedWidth, expandedWidth, fraction)
        val height = expandableLerp(collapsedHeight, expandedHeight, fraction)

        // Upstream's `placeWithLayer(alpha = progress)`, plus the GONE swap its "only place a variant
        // whose alpha is non-zero" branch achieves; without it an invisible variant would still
        // hit-test, which matters for the button that has animated out to zero height.
        collapsed?.alpha = 1f - fraction
        collapsed?.visibility = if (fraction >= 1f) GONE else VISIBLE
        expanded?.alpha = fraction
        expanded?.visibility = if (fraction <= 0f) GONE else VISIBLE

        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = measuredWidth
        slotAt(0)?.let { collapsed ->
            val offset = (width - collapsed.measuredWidth) / 2
            collapsed.layout(offset, 0, offset + collapsed.measuredWidth, collapsed.measuredHeight)
        }
        slotAt(1)?.let { expanded ->
            val offset = (width - expanded.measuredWidth) / 2
            expanded.layout(offset, 0, offset + expanded.measuredWidth, expanded.measuredHeight)
        }
    }

    private fun slotAt(index: Int): View? =
        if (childCount > index) getChildAt(index) else null
}
