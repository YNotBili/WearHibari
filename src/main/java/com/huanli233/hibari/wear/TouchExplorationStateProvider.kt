package com.huanli233.hibari.wear

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import com.huanli233.hibari.runtime.ProvidableTunationLocal
import com.huanli233.hibari.runtime.RememberObserver
import com.huanli233.hibari.runtime.State
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentView
import com.huanli233.hibari.runtime.getValue
import com.huanli233.hibari.runtime.mutableStateOf
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.runtime.setValue
import com.huanli233.hibari.runtime.staticTunationLocalOf

/**
 * Ported from `androidx.wear.compose.material3.TouchExplorationStateProvider`
 * (`material3/TouchExplorationStateProvider.kt:37-138`), which is a line-for-line copy of
 * `foundation/TouchExplorationStateProvider.kt:37-138` — the two files differ only in package and in
 * the `material3` header date. This module therefore gets one home: the `material3` spelling, since
 * that is the one every component that reads it imports (`material3/Picker.kt:206`,
 * `material3/PickerGroup.kt:95` and `:161`, `material3/TimePicker.kt:161`,
 * `material3/DatePicker.kt:129`, `foundation/pager/Pager.kt:319`). No alias is declared, and
 * `material/TouchExplorationStateProvider.kt:47`, the public predecessor of both, is not ported.
 *
 * The signal on Views is the same one upstream reads from [AccessibilityManager] — `isEnabled &&
 * isTouchExplorationEnabled` — but three parts of upstream's machinery have no counterpart here, so
 * the factoring moves:
 *  - Upstream's [TouchExplorationStateProvider.touchExplorationState] is `@Composable`, so it can call
 *    `remember` and `LocalLifecycleOwner` from inside the interface member. A `@Tunable` body cannot
 *    sit on a `fun interface` member that a test is meant to implement with a lambda, so the member is
 *    plain and takes a [Context], and the top-level [touchExplorationState] is the `@Tunable` glue that
 *    remembers the state and drives its lifetime.
 *  - That glue is also why [TouchExplorationState] carries `register`/`unregister`: upstream's
 *    `Listener` (`:100-130`) is both the `State` and the thing the composable registers, reached through
 *    a private class. Here the private class is not reachable from the glue, so the two operations it
 *    needs are on the returned type.
 *  - `Lifecycle.Event.ON_RESUME`/`ON_PAUSE` (`:69-73`) become the host view's attach state: this module
 *    cannot assume a `LifecycleOwner` is in scope for a nested tuner, and a detached `HibariView` is the
 *    Views fact that corresponds to "not on screen". `unregister()` on being forgotten is upstream's own
 *    `onDispose` fallback (`:75-79`), kept because a view that is never attached never gets a detach
 *    callback either.
 *
 * Not ported: `LocalLifecycleOwner`/`LifecycleEventObserver` (`:33-35`, `:85-98`) — replaced by
 * [View.addOnAttachStateChangeListener] as above, so there is no observer to add or remove.
 */
fun interface TouchExplorationStateProvider {

    /**
     * Returns the touch exploration service state wrapped in a [State] to allow components to attach the
     * state to itself. This will allow components to react to change in service state, if required.
     *
     * It is strongly discouraged to make logic conditional based on state of accessibility services —
     * the caveat upstream attaches to this type (`:37-41`) carries over verbatim.
     *
     * @param context Used to reach the [AccessibilityManager], where upstream's copy reaches
     *   `LocalContext.current` (`:60`).
     */
    fun touchExplorationState(context: Context): TouchExplorationState
}

/**
 * A [State] whose subscription to the accessibility service is owned by whoever holds it — the
 * minimum the glue in [touchExplorationState] needs from upstream's private
 * `Listener.register()`/`unregister()` (`:118-129`).
 *
 * Deviation: upstream has no such type; its `Listener` is a private class and the composable that
 * builds it calls the two methods directly.
 */
interface TouchExplorationState : State<Boolean> {

    /** Sample the live values and start tracking them. Repeat calls are ignored. */
    fun register()

    /** Stop tracking. Safe to call when not registered, as upstream's comment at `:76-77` requires. */
    fun unregister()
}

/**
 * The default implementation of [TouchExplorationStateProvider] (`:56-131`). It depends on the state of
 * accessibility services to determine the current state of touch exploration services.
 */
class DefaultTouchExplorationStateProvider : TouchExplorationStateProvider {

    override fun touchExplorationState(context: Context): TouchExplorationState =
        Listener(context)

    private class Listener(context: Context) :
        AccessibilityManager.AccessibilityStateChangeListener,
        AccessibilityManager.TouchExplorationStateChangeListener,
        TouchExplorationState {

        // Upstream casts unconditionally (`:61-63`); a context with no accessibility service is a
        // headless test, and there the honest answer is "off" rather than a crash.
        private val accessibilityManager: AccessibilityManager? =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

        private var accessibilityEnabled by mutableStateOf(accessibilityManager?.isEnabled == true)
        private var touchExplorationEnabled by
            mutableStateOf(accessibilityManager?.isTouchExplorationEnabled == true)
        private var registered = false

        override val value: Boolean
            get() = accessibilityEnabled && touchExplorationEnabled

        override fun onAccessibilityStateChanged(it: Boolean) {
            accessibilityEnabled = it
        }

        override fun onTouchExplorationStateChanged(it: Boolean) {
            touchExplorationEnabled = it
        }

        override fun register() {
            val manager = accessibilityManager ?: return
            if (registered) return
            accessibilityEnabled = manager.isEnabled
            touchExplorationEnabled = manager.isTouchExplorationEnabled

            // Both `add*` calls are guarded by `registered` rather than repeated: an
            // AccessibilityManager callback arrives on the main thread, the same thread that re-tunes,
            // so a re-registration on every tune would otherwise pile up duplicate listeners.
            manager.addTouchExplorationStateChangeListener(this)
            manager.addAccessibilityStateChangeListener(this)
            registered = true
        }

        override fun unregister() {
            val manager = accessibilityManager ?: return
            manager.removeTouchExplorationStateChangeListener(this)
            manager.removeAccessibilityStateChangeListener(this)
            registered = false
        }
    }
}

/** Tunation local to provide a means to override [TouchExplorationStateProvider] during testing. */
val LocalTouchExplorationStateProvider: ProvidableTunationLocal<TouchExplorationStateProvider> =
    staticTunationLocalOf {
        DefaultTouchExplorationStateProvider()
    }

/**
 * `LocalTouchExplorationStateProvider.current.touchExplorationState()` as a component reads it
 * (`material3/TimePicker.kt:161`, `material3/Picker.kt:206`): the live state, remembered per
 * [context] and per provider, subscribed for as long as the node that asked for it is alive.
 *
 * Neither parameter may default to the ambient it belongs to — a `@Tunable` default expression is
 * hoisted into a non-`@Tunable` `$default` that cannot read `currentContext` or a tunation local — so
 * [context] is required, and `null` [provider] means
 * [LocalTouchExplorationStateProvider]'s current value.
 *
 * @param provider A provider is matched to the state it produced: changing it re-remembers, which
 *   unregisters the old listener and registers the new one.
 */
@Tunable
fun touchExplorationState(
    context: Context,
    provider: TouchExplorationStateProvider? = null,
): State<Boolean> {
    val resolvedProvider = provider ?: LocalTouchExplorationStateProvider.current
    val host = currentView
    return remember(context, resolvedProvider) {
        TouchExplorationSubscription(resolvedProvider.touchExplorationState(context), host)
    }
}

/**
 * Keeps the state upstream's `ObserveState` (`:67-80`) keeps alive: registered while the host view is
 * attached, removed with the view's detach and again when the remembered entry is dropped, and never
 * registered twice. Delegating [State] means a consumer reads the listener's own value, so no snapshot
 * of the boolean is cached here.
 */
private class TouchExplorationSubscription(
    private val state: TouchExplorationState,
    private val host: View,
) : State<Boolean>, RememberObserver {

    override val value: Boolean get() = state.value

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            state.register()
        }

        override fun onViewDetachedFromWindow(view: View) {
            state.unregister()
        }
    }

    init {
        host.addOnAttachStateChangeListener(attachListener)
    }

    override fun onRemembered() {
        if (host.isAttachedToWindow) state.register()
    }

    override fun onForgotten() {
        host.removeOnAttachStateChangeListener(attachListener)
        state.unregister()
    }
}
