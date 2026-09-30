package com.huanli233.hibari.wear

import android.view.View
import android.view.ViewGroup
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Ported from `androidx.wear.compose.foundation.HierarchicalFocus`.
 *
 * Upstream keeps the focus tree in `Modifier.Node`s that are `TraversableNode`s, and resolves it in
 * a `sideEffect` after the composition settles. Both halves survive here, but the substrate changes:
 *
 * | upstream | this port |
 * |---|---|
 * | `HierarchicalFocusCoordinatorModifierNode` (one per modifier element, in a `TraversableNode` tree) | [HierarchicalFocusNode], one per **View**, found through the real View parent/child chain |
 * | `Modifier.hierarchicalFocusGroup(active)` | [hierarchicalFocusGroup] — same name, same meaning |
 * | `Modifier.requestFocusOnHierarchyActive()` | [requestFocusOnHierarchyActive] |
 * | `Modifier.hierarchicalOnFocusChanged(cb)` (back-compat only) | [hierarchicalOnFocusChanged] |
 * | `FocusRequesterModifierNode.requestFocus()` / `LocalFocusManager.clearFocus()` | `View.requestFocus()` / `rootView.clearFocus()` — the platform focus system, no host hook needed |
 * | `sideEffect { ... }` batch at the end of the composition pass | `View.post { ... }` batch on the next frame |
 * | the "petc" `onPreviewKeyEvent` swallow (upstream HierarchicalFocus.kt:79, 120-125) | **not ported**, see [HierarchicalFocusCoordinator] KDoc |
 *
 * The three upstream element factories all build the *same* element type, and upstream can stack
 * `hierarchicalFocusGroup(active).requestFocusOnHierarchyActive()` on one layout node (that is
 * exactly what `PickerGroup.PickerGroupItem` does for a column nobody handed a requester). Here a
 * View carries at most one [HierarchicalFocusNode], so the elements merge into it through separate
 * slots ([HierarchicalFocusNode.groupActive] / [HierarchicalFocusNode.focusSite] /
 * [HierarchicalFocusNode.onFocusChanged]). The resolved behaviour is identical because the two
 * upstream nodes on one layout node always sit at the same depth of the traversal tree: the group
 * decides whether the subtree is live, and the focus site is what gets focus.
 *
 * That equality has one consequence for who may write [HierarchicalFocusNode.groupActive].
 * Upstream's site elements carry an `active = true` of their own (`:110`, `:133`), because there each
 * factory builds its own node; the two nodes stay independent, and the group's `active = selected`
 * still gates the subtree. Merged into one node, a site writing `active = true` would overwrite the
 * group's `false` and every inactive column would claim to be live. It does not need to:
 * [HierarchicalFocusNode.active] already answers `true` when no group named the View, so
 * `groupActive` is written *only* by [hierarchicalFocusGroup], which is upstream's `active = true` by
 * default rather than by assignment.
 */

/**
 * Marks a subtree of the focus tree as live or dead, mirroring
 * `Modifier.hierarchicalFocusGroup(active)`.
 *
 * Nest these to form a focus tree with an implicit root; among siblings only one should pass
 * `active = true`. When the active path changes, the topmost `requestFocusOnHierarchyActive()`
 * whose every ancestor group is active takes real View focus, and everything that fell off the path
 * loses it.
 *
 * @param active `true` when this sub tree may hold focus. A pager passes `currentPage == page`.
 */
fun Modifier.hierarchicalFocusGroup(active: Boolean): Modifier =
    this.thenViewAttribute<View, Boolean>(uniqueKey, active) {
        val node = hierarchicalFocusNode()
        node.declared = true
        node.groupActive = it
    }

/**
 * The focus site of [hierarchicalFocusGroup]: when the enclosing groups resolve in this node's
 * favour, the View this modifier is applied to is handed actual focus, mirroring
 * `Modifier.requestFocusOnHierarchyActive()`.
 *
 * Upstream requires the element to be before the focusable one in the chain and relies on
 * `Modifier.focusRequester`/`focusable` making the target focusable. On Views the gate is
 * `View.isFocusable`, so this also raises `isFocusable` and `isFocusableInTouchMode` on the host
 * view — see [requestHierarchicalFocus], which documents why that is the faithful mapping rather
 * than a convenience.
 *
 * Two of these should not be siblings; wrap each in its own [hierarchicalFocusGroup] and mark at
 * most one active, same as upstream.
 *
 * Deliberately does not touch [HierarchicalFocusNode.groupActive], although upstream's element does
 * carry `active = true`: see the note at the top of this file. Stacking the two here therefore
 * behaves exactly like upstream's two nodes at one depth — the group gates the subtree, this marks
 * what gets focus.
 */
fun Modifier.requestFocusOnHierarchyActive(): Modifier =
    this.thenViewAttribute<View, Boolean>(uniqueKey, true) {
        val node = hierarchicalFocusNode()
        node.declared = true
        node.focusSite = true
    }

/**
 * Back-compat element that replaces the real focus request with a callback — upstream's internal
 * `Modifier.hierarchicalOnFocusChanged(onFocusChanged)`, which is what
 * [rememberActiveFocusRequester] and `ActiveFocusListener` are built on.
 *
 * Like upstream, when a node has both a callback and `activeFocus`, the callback wins
 * (`node.onFocusChanged?.invoke(true) ?: node.requestFocus()`), so a caller that wants the platform
 * to move focus must not pass a callback.
 *
 * [HierarchicalFocusNode.groupActive] is left alone for the same reason as
 * [requestFocusOnHierarchyActive].
 */
internal fun Modifier.hierarchicalOnFocusChanged(onFocusChanged: (Boolean) -> Unit): Modifier =
    this.thenViewAttribute<View, (Boolean) -> Unit>(uniqueKey, onFocusChanged) {
        val node = hierarchicalFocusNode()
        node.declared = true
        node.onFocusChanged = it
        node.focusSite = true
    }

/**
 * Bind a [HierarchicalFocusRequester] to the View this is applied to — the port of
 * `androidx.compose.ui.focus.focusRequester(fr)`, which is how a `Picker` exposes its scrollable to
 * [rememberActiveFocusRequester]. Naming deviates because a bare `FocusRequester` top-level name is
 * taken by Compose in every consumer's head and would collide with sibling routes here.
 *
 * Once bound, [HierarchicalFocusRequester.isFocused] answers the platform's own `View.isFocused`, so
 * it also tracks focus the user moved by hand. Like upstream's `Modifier.focusRequester`, this
 * element is *not* a tree node: it neither opens nor closes a focus group, so applying it leaves the
 * resolved active path untouched. It is also not a focus site, and upstream's `PickerGroupItem`
 * keeps the two apart — with a caller-supplied requester it emits `Modifier.focusRequester(it)` and
 * leaves `requestFocusOnHierarchyActive()` off, so the hierarchy never auto-focuses that node and the
 * caller drives it; with none it emits the focus site instead. Where both *are* on one View,
 * [HierarchicalFocusNode.requestHierarchicalFocus] routes the hierarchy's request through the
 * requester.
 */
fun Modifier.hierarchicalFocusRequester(requester: HierarchicalFocusRequester): Modifier =
    this.thenViewAttribute<View, HierarchicalFocusRequester>(uniqueKey, requester) {
        val node = hierarchicalFocusNode()
        node.requester = it
        it.bind(view = node.view)
    }

/**
 * One entry of the focus tree, keyed by the [View] it was applied to.
 *
 * Hibari applies a `Modifier.Element` with a value-diff, so these setters are the "update" half of
 * upstream's `ModifierNodeElement.update(node)`: assigning an unchanged value is a no-op and
 * schedules nothing, which matters because a lambda-valued element ([hierarchicalOnFocusChanged])
 * never compares equal and is therefore re-applied on every re-tune.
 *
 * Keying the node by View is safe because the patcher pairs a slot with its own view
 * (`Patcher.kt:136`, `findViewByKey(newNode.key, position)`) and re-applies the whole modifier list
 * when it has to build a fresh one, so a node's `active` cannot be left over from another slot the
 * way it could in a plain adapter-backed recycling group.
 */
internal class HierarchicalFocusNode(val view: View) {

    /** `hierarchicalFocusGroup(active)`; `null` = that element is not on this View. */
    var groupActive: Boolean? = null
        set(value) {
            if (field != value) {
                field = value
                markChanged()
            }
        }

    /** `requestFocusOnHierarchyActive()` / `hierarchicalOnFocusChanged()`. */
    var focusSite: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                markChanged()
            }
        }

    var onFocusChanged: ((Boolean) -> Unit)? = null

    var requester: HierarchicalFocusRequester? = null

    /** True once [hierarchicalFocusGroup] or [requestFocusOnHierarchyActive] named this View. */
    var declared: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                markChanged()
            }
        }

    /** Upstream `node.active`: a bare focus site is active, a group is whatever it was told. */
    val active: Boolean
        get() = groupActive ?: true

    /**
     * Upstream's `scheduleUpdateAfterTreeSettles()` entry point.
     *
     * Deliberately dropped while the View is outside the tree: the renderer applies attributes to a
     * freshly built View *before* it is added to its parent, so resolving a lookup through
     * `view.parent` that early would hand the node a coordinator rooted at itself and let an
     * inactive group request focus for its own View. [HierarchicalFocusCoordinator.of] walks the
     * parent chain, so the node reports to the screen's coordinator only once it is in one — which
     * is what the attach listener below does. Losing the report is harmless: the coordinator reads
     * this node's *current* slots when it resolves, not a snapshot.
     */
    internal fun markChanged() {
        if (!view.isAttachedToWindow) return
        HierarchicalFocusCoordinator.of(view).enqueueChanged(this)
    }

    /**
     * Nearest ancestor View that owns a *declared* node — upstream's `findNearestAncestor()`, which
     * only ever answers a node carrying the traversal key. A View that merely had a
     * [hierarchicalFocusRequester] bound to it is skipped, exactly as a bare
     * `Modifier.focusRequester` creates no `TraversableNode` upstream.
     */
    fun parentNode(): HierarchicalFocusNode? {
        var parent = view.parent
        while (parent is View) {
            parent.hierarchicalFocusNodeOrNull()?.takeIf { it.declared }?.let { return it }
            parent = parent.parent
        }
        return null
    }

    /** Upstream `parentChainActive()`: every ancestor group active, not counting this node. */
    fun parentChainActive(): Boolean {
        var node = parentNode()
        while (node != null) {
            if (!node.active) return false
            node = node.parentNode()
        }
        return true
    }

    /** Upstream `parentChain()`: root-first list ending with this node. */
    fun parentChain(): List<HierarchicalFocusNode> {
        val path = ArrayList<HierarchicalFocusNode>(4)
        var node: HierarchicalFocusNode? = this
        while (node != null) {
            path.add(node)
            node = node.parentNode()
        }
        path.reverse()
        return path
    }

    /**
     * Upstream `findActive()`: descend into the first live child subtree and stop; a childless node
     * answers itself; every child inactive answers `null`.
     */
    fun findActive(): HierarchicalFocusNode? {
        val children = childNodes()
        if (children.isEmpty()) return this
        for (child in children) {
            if (child.active && child.view.isAttachedToWindow) {
                return child.findActive()
            }
        }
        return null
    }

    /**
     * The nearest descendant nodes, in depth-first View order — what upstream's
     * `traverseDescendants` yields with `SkipSubtreeAndContinueTraversal` on inactive groups.
     */
    fun childNodes(): List<HierarchicalFocusNode> {
        val out = ArrayList<HierarchicalFocusNode>(2)
        view.collectHierarchicalFocusDescendants(out)
        return out
    }
}

/**
 * Per-View node store.
 *
 * `View.setTag(int, Object)` is not usable here: it rejects any key whose package id is below 2, so
 * it needs a real resource id, and this module owns no `res/` (and may not add one). The store is
 * therefore a weak map, with the node itself held through a `WeakReference` so a recycled-but-dropped
 * View can never be pinned by a value that points back at it.
 */
private val HierarchicalFocusNodes =
    Collections.synchronizedMap(WeakHashMap<View, WeakReference<HierarchicalFocusNode>>())

internal fun View.hierarchicalFocusNode(): HierarchicalFocusNode {
    synchronized(HierarchicalFocusNodes) {
        HierarchicalFocusNodes[this]?.get()?.let { return it }
    }
    val created = HierarchicalFocusNode(this)
    // Upstream's onAttach/onDetach overrides: a node entering the tree re-enters the candidate set,
    // and one leaving it takes its share of the active path with it. Added once, for the lifetime of
    // the View, because this map hands back the same node for the same View.
    addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                created.markChanged()
            }

            override fun onViewDetachedFromWindow(view: View) {
                // The parent chain is still linked at this point, so the root — and therefore the
                // same coordinator this node reported changes to — is still reachable.
                HierarchicalFocusCoordinator.of(view).onNodeDetached(created)
            }
        }
    )
    HierarchicalFocusNodes[this] = WeakReference(created)
    return created
}

internal fun View.hierarchicalFocusNodeOrNull(): HierarchicalFocusNode? =
    HierarchicalFocusNodes[this]?.get()

private fun View.collectHierarchicalFocusDescendants(out: MutableList<HierarchicalFocusNode>) {
    if (this !is ViewGroup) return
    for (i in 0 until childCount) {
        val child = getChildAt(i) ?: continue
        val node = child.hierarchicalFocusNodeOrNull()
        if (node != null && node.declared) {
            out.add(node)
        } else {
            child.collectHierarchicalFocusDescendants(out)
        }
    }
}
