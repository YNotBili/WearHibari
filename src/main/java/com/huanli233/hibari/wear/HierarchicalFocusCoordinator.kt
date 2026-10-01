package com.huanli233.hibari.wear

import android.annotation.SuppressLint
import android.view.View
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.HibariView
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.runtime.effects.rememberCoroutineScope
import com.huanli233.hibari.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * The bookkeeping half of `androidx.wear.compose.foundation`'s hierarchical focus, ported onto the
 * platform View focus system.
 *
 * Upstream keeps this state in the companion object of its private
 * `HierarchicalFocusCoordinatorModifierNode`, i.e. one process-wide pair of lists. Here it is one
 * coordinator per screen root ([HibariView] when there is one, otherwise the top-most ancestor
 * View), which is what `LocalFocusManager` actually is upstream — focus is per window there, so two
 * mounted [HibariView]s (or an Activity that called [HibariView.setContent])
 * stop fighting over one shared "who is focused" list.
 *
 * There is deliberately **no `LocalHierarchicalFocusCoordinator`**: grep over the reference tree
 * shows upstream has no such local (`HierarchicalFocusCoordinator` is only the deprecated
 * `@Composable` in this file's namesake), and a Hibari `Modifier` factory is a plain function with
 * no tuner in scope, so it could not read a local even if one existed. The coordinator is reached
 * through the View the modifier was applied to instead, via [of].
 *
 * What does **not** work here, relative to upstream, with the reason:
 *
 * 1. `shouldSwallowFocusGroupKeyEvent` / the `onPreviewKeyEvent` half of
 *    `Modifier.hierarchicalFocusGroup` (HierarchicalFocus.kt:79, 120-125) is not ported. That hook
 *    drops `KEYCODE_DPAD_*` events coming from the emulator's "petc" virtual device so an active
 *    focus group is not knocked off by synthetic key storms. Views have no per-subtree key preview:
 *    it needs either `View.dispatchKeyEvent` on an ancestor (this module's nodes are flat Views, so
 *    there is nothing to override without inventing a wrapper view class that would fight
 *    `Modifier.viewClass`) or a host hook. **A caller that hits petc storms must intercept in the
 *    Activity's `onKeyDown`/`dispatchKeyEvent`**; nothing else in this port is affected, because
 *    real dpad/rotary events do reach a focused View inside [HibariView]
 *    — `HibariView` is a plain `FrameLayout` that overrides no `dispatchKeyEvent`,
 *    `dispatchGenericMotionEvent` or `onKeyDown`, and never sets `descendantFocusability`, so the
 *    default `FOCUS_BEFORE_DESCENDANTS` keeps the window's key stream routing to whatever descendant
 *    holds focus.
 * 2. [hierarchicalFocusGroup] / [requestFocusOnHierarchyActive] resolve on the next
 *    `View.post`, not in upstream's `sideEffect` at the end of the same frame's composition, so a
 *    focus change is observable one apply-pass later.
 * 3. Two focus roles on one View collapse into one [HierarchicalFocusNode] (see the table in
 *    HierarchicalFocus.kt); the resolved path differs by that one node, never by which View is
 *    focused.
 * 4. Upstream's deprecated `HierarchicalFocusCoordinator(requiresFocus, content)`
 *    (this file's namesake, HierarchicalFocusCoordinator.kt:41-51) is not ported as a *function*:
 *    the name belongs to the coordinator class above, and a same-named `@Tunable` would shadow its
 *    constructor at call sites. Its one line has a direct equivalent:
 *    `Node(Modifier.hierarchicalFocusGroup(requiresFocus())) { content() }`.
 * 5. When the active path empties, [resolve] releases only the focus a leaving node owns
 *    ([clearFocusLeftOnPath]) rather than calling [clearHierarchicalFocus] unconditionally: upstream's
 *    `FocusManager.clearFocus()` is scoped to the caller's focus scope, and a window has exactly one
 *    focus owner here. Without the guard, `TimePicker`'s confirm button — focused by hand, outside
 *    the hierarchy, at the same moment every picker group goes inactive — would lose the focus a
 *    frame later.
 *
 * @see hierarchicalFocusGroup
 * @see requestFocusOnHierarchyActive
 * @see rememberActiveFocusRequester
 */
class HierarchicalFocusCoordinator internal constructor(val root: View) {

    private val changedNodes = ArrayList<HierarchicalFocusNode>(4)
    private var lastActiveNodePath: MutableList<HierarchicalFocusNode> = mutableListOf()
    private var resolveScheduled = false

    /** Upstream `scheduleUpdateAfterTreeSettles()`: collect, then resolve once. */
    internal fun enqueueChanged(node: HierarchicalFocusNode) {
        changedNodes.add(node)
        if (!resolveScheduled) {
            resolveScheduled = true
            root.post { resolveScheduled = false; resolve() }
        }
    }

    /** Upstream `onDetach()`: a node that leaves the tree silently takes its focus with it. */
    internal fun onNodeDetached(node: HierarchicalFocusNode) {
        if (lastActiveNodePath.remove(node)) {
            node.onFocusChanged?.invoke(false)
        }
    }

    private fun resolve() {
        if (changedNodes.isEmpty()) return

        // Upstream seeds the candidate set with the last active node: without it, adding or removing
        // an outermost group would diff against an empty path and fire the wrong callbacks.
        lastActiveNodePath.lastOrNull()?.let { changedNodes.add(it) }

        val parentActiveNodes =
            changedNodes.filter { it.declared && it.view.isAttachedToWindow && it.parentChainActive() }

        if (parentActiveNodes.isNotEmpty()) {
            val nextActiveNodePath =
                parentActiveNodes.firstOrNull { it.active }?.findActive()?.parentChain()
                    ?: emptyList()

            if (nextActiveNodePath != lastActiveNodePath) {
                var focusSet = false

                for (node in nextActiveNodePath) {
                    if (!lastActiveNodePath.contains(node)) {
                        if (node.focusSite) {
                            if (!focusSet) {
                                val callback = node.onFocusChanged
                                if (callback != null) callback(true) else node.requestHierarchicalFocus()
                                focusSet = true
                            }
                        } else {
                            node.onFocusChanged?.invoke(true)
                        }
                    }
                }

                for (node in lastActiveNodePath) {
                    if (!nextActiveNodePath.contains(node)) {
                        node.onFocusChanged?.invoke(false)
                    }
                }

                if (!focusSet) clearFocusLeftOnPath(lastActiveNodePath, nextActiveNodePath)

                lastActiveNodePath = nextActiveNodePath.toMutableList()
            }
        }
        changedNodes.clear()
    }

    /**
     * The guarded form of upstream's `currentValueOf(LocalFocusManager).clearFocus()` — guarded
     * because Compose's non-forced `clearFocus()` only releases focus sitting in scopes below the
     * caller, which has no Views equivalent (a window has one focus owner, so
     * [clearHierarchicalFocus] drops anything). What it does instead is release focus only while the
     * owner is a view belonging to a node that is *leaving* the active path, which is the case this
     * subsystem is responsible for.
     *
     * That distinction is load-bearing, not cosmetic: `TimePicker`'s last rotation step focuses a
     * node that is deliberately outside the hierarchy (`material3/TimePicker.kt:272`, `:365-369` — a
     * bare `focusRequester` plus `focusable`, never a focus site) at the same moment every picker
     * group in the tree goes inactive. Resolving that batch afterwards, as both upstream's
     * `sideEffect` and the `View.post` here do, would then clear the focus the caller had just taken.
     */
    private fun clearFocusLeftOnPath(
        previousPath: List<HierarchicalFocusNode>,
        nextPath: List<HierarchicalFocusNode>,
    ) {
        val focused = root.findFocus() ?: return
        val leaving = previousPath.filterNot { it in nextPath }
        if (leaving.any { it.view.containsHierarchicalFocusTarget(focused) }) {
            clearHierarchicalFocus()
        }
    }

    /**
     * Upstream `currentValueOf(LocalFocusManager).clearFocus()` — the window-level "nobody in the
     * active path wants focus" case. Unguarded, unlike the call [resolve] makes: this releases
     * whatever the screen holds, so call it only when that is what you mean.
     */
    fun clearHierarchicalFocus() {
        // `View.clearFocus()` on a root drops focus held by any descendant; `root.findFocus()` is
        // only consulted to keep the no-op case from churning the focus state.
        if (root.findFocus() != null) root.clearFocus()
    }

    /**
     * Force a re-resolve, e.g. after a host change (an Activity taking and returning focus) that
     * moved the platform focus away behind this coordinator's back. Cheap: the diff inside
     * [resolve] makes it a no-op when the active path did not change.
     */
    fun invalidate() {
        lastActiveNodePath.lastOrNull()?.let { enqueueChanged(it) }
    }

    companion object {
        /**
         * The coordinator owning [view]'s screen: created on first use and cached as a keyed tag on
         * the top-most ancestor, so every node in one `HibariView` shares one active path.
         *
         * The store is a View tag for the same reason the node store in `HierarchicalFocus.kt` is one,
         * and the key here really is a View: it is the root `of()` just walked to, while the class holds
         * `val root: View` — so the old map's `WeakReference` value existed to stop that map pinning
         * its own key, an edge a tag has no equivalent of. The View <-> coordinator cycle dies with the
         * screen like anything else in it, and the lock the old store took on every `markChanged()` is
         * gone. Two consequences: a coordinator now lives exactly as long as its root View, so a pooled
         * root keeps its (drained, still correct) coordinator; and `of()` is public, so calling it on a
         * detached View leaves that View tagged. Main-thread only, like both callers here
         * ([HierarchicalFocusNode.markChanged] and the detach callback in `HierarchicalFocus.kt`).
         */
        @JvmStatic
        fun of(view: View): HierarchicalFocusCoordinator {
            var root: View = view
            while (true) {
                val parent = root.parent
                if (parent !is View) break
                root = parent
                // Stop at the screen root rather than walking into the whole window decor, so a
                // HibariView nested inside a foreign layout keeps its own focus tree.
                if (parent is HibariView) {
                    root = parent
                    break
                }
            }
            (root.getTag(hierarchicalFocusCoordinatorKey) as? HierarchicalFocusCoordinator)?.let {
                return it
            }
            val created = HierarchicalFocusCoordinator(root)
            root.setTag(hierarchicalFocusCoordinatorKey, created)
            return created
        }
    }
}

/**
 * Tag key for the coordinator of one screen root, from this module's `res/values/ids.xml` —
 * `View.setTag(int, Object)` needs a real resource id, which is settled at
 * `HierarchicalFocus.kt`'s store documentation.
 */
private val hierarchicalFocusCoordinatorKey = R.id.hibari_hierarchical_focus_coordinator

/**
 * A handle to one focusable View, standing in for `androidx.compose.ui.focus.FocusRequester`.
 *
 * Named and shaped the way it is because a top-level `FocusRequester` is off-limits here (it is not
 * this subsystem's upstream name and a sibling route may want it); the two members that matter to
 * callers keep upstream's names. Bind it to a View with [hierarchicalFocusRequester].
 *
 * Unlike a Compose `FocusRequester`, [isFocused] is not a snapshot read — it is the platform's own
 * `View.isFocused`, so it also reports focus the user moved with the dpad. Poll it from a
 * `LaunchedEffect`/retune that already runs when the selection changes, as upstream's `Picker` does
 * with `focusRequester.hasFocus()`.
 */
class HierarchicalFocusRequester {

    private var boundView: WeakReference<View>? = null

    internal fun bind(view: View) {
        boundView = WeakReference(view)
    }

    /**
     * `FocusRequester.hasFocus()`: does the bound View hold platform focus right now? `false` while
     * nothing is bound, since an unbound requester can neither hold nor be given focus.
     */
    fun hasFocus(): Boolean = boundView?.get()?.isFocused == true

    /** [hasFocus] as a property, for callers that read it from a `Modifier`-less site. */
    val isFocused: Boolean
        get() = hasFocus()

    /**
     * `FocusRequester.requestFocus()`: make the bound View the platform's focus owner. Returns what
     * `View.requestFocus()` returned — `false` when the View is not focusable or not attached, where
     * upstream would throw or no-op depending on the state of the focus system.
     */
    fun requestFocus(): Boolean {
        val view = boundView?.get() ?: return false
        return view.grantHierarchicalFocus()
    }
}

internal fun HierarchicalFocusNode.requestHierarchicalFocus(): Boolean {
    // Upstream's node is itself a `FocusRequesterModifierNode`, so `requestFocus()` on a focus site
    // that also carries `Modifier.focusRequester(fr)` goes through `fr`. Same rule here.
    requester?.let { return it.requestFocus() }
    return view.grantHierarchicalFocus()
}

/**
 * The single place this port touches `View.isFocusable`.
 *
 * Upstream can assume the target is focusable because whoever attached the `FocusRequester` also
 * applied `focusable()`/`rotaryScrollable()`. On Views, `View.requestFocus()` returns false unless
 * `isFocusable` is set, and — the part with no Compose counterpart — is refused outright while the
 * device is in touch mode unless `isFocusableInTouchMode` is set. Since a focus site *is* the
 * declaration "give this View focus when my subtree wins", raising both flags here is what
 * reproduces upstream's observable behaviour (tap a picker column, that column then eats the
 * rotary/dpad stream) instead of silently doing nothing. The flags are only ever set to true, never
 * restored, so a caller that wants a View to stay unfocusable must not make it a focus site.
 */
private fun View.grantHierarchicalFocus(): Boolean {
    if (!isFocusable) isFocusable = true
    if (!isFocusableInTouchMode) isFocusableInTouchMode = true
    return requestFocus()
}

/**
 * Whether [other] is this View or sits inside it. The Views stand-in for Compose's "is that node in
 * one of my descendant focus scopes": a `View` subtree is the only scoping this port has, and a
 * focus site may legitimately hand its focus to a view below itself (an
 * [HierarchicalFocusRequester] bound to a child, say).
 */
private fun View.containsHierarchicalFocusTarget(other: View): Boolean {
    var candidate: View? = other
    while (candidate != null) {
        if (candidate === this) return true
        candidate = candidate.parent as? View
    }
    return false
}

/**
 * Upstream's `rememberActiveFocusRequester(): FocusRequester`
 * (HierarchicalFocusCoordinator.kt:30-39), deprecated exactly as upstream deprecates it.
 *
 * It remembers a [HierarchicalFocusRequester] and registers an invisible
 * `Node(Modifier.hierarchicalOnFocusChanged { if (it) requester.requestFocus() })` — upstream's
 * `Box(Modifier.hierarchicalOnFocusChanged { ... })` — so that when the surrounding
 * [hierarchicalFocusGroup] tree resolves in this subtree's favour, the requester's View is handed
 * focus. Bind the returned object to a focusable child with [hierarchicalFocusRequester].
 *
 * Prefer [requestFocusOnHierarchyActive], which needs no requester at all.
 */
@Deprecated(
    "Replaced by Modifier.requestFocusOnHierarchyActive(), use that instead",
    level = DeprecationLevel.WARNING,
)
@SuppressLint("TunableNamingDetector")
@Tunable
fun rememberActiveFocusRequester(): HierarchicalFocusRequester {
    val requester = remember { HierarchicalFocusRequester() }
    Node(Modifier.hierarchicalOnFocusChanged { if (it) requester.requestFocus() })
    return requester
}

/**
 * Upstream's `ActiveFocusListener(onFocusChanged: CoroutineScope.(Boolean) -> Unit)`
 * (HierarchicalFocusCoordinator.kt:53-61), deprecated as upstream deprecates it: an invisible
 * [HierarchicalFocusNode]-bearing node that reports when the active path gains or loses this subtree.
 *
 * The receiver and the launch are upstream's, and both are real here: `rememberCoroutineScope`
 * (`hibari-runtime/.../effects/Effects.kt:59`) is the counterpart of Compose's, so the callback is
 * dispatched into that scope exactly as `:59-60` does — even though the focus change itself already
 * arrives on the main thread from a `View.post`, which is why a caller could get away without the
 * launch, but the scope is what lets a caller cancel or suspend its own handler.
 */
@Deprecated(
    "Replaced by Modifier.requestFocusOnHierarchyActive(), or the new LocalScreenIsActive, use that instead",
    level = DeprecationLevel.WARNING,
)
@Tunable
fun ActiveFocusListener(onFocusChanged: CoroutineScope.(Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    Node(Modifier.hierarchicalOnFocusChanged { scope.launch { onFocusChanged(it) } })
}
