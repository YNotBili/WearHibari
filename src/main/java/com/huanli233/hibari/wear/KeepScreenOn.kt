package com.huanli233.hibari.wear

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import com.huanli233.hibari.runtime.RememberObserver
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.remember

/**
 * Ported from androidx.wear.compose.material3.KeepScreenOn (`material3/KeepScreenOn.kt:27-36`):
 * `window.addFlags(FLAG_KEEP_SCREEN_ON)` while the call site is in the tree, `clearFlags` when it
 * leaves. Both the flag and the add-then-clear pairing are upstream's, unchanged.
 *
 * Hibari has no window local and no activity local, so the window is resolved from
 * [currentContext] — which is `currentTuner.tunation.hostView.context`, i.e. the `Context` the host
 * `HibariView` was constructed with, and not necessarily an `Activity`. [findActivity] is upstream's
 * own walk of the `ContextWrapper` chain (`:38-43`) and is copied verbatim rather than narrowed to a
 * single `as? Activity`, because a themed or context-wrapper host is the normal case and a plain cast
 * would miss it.
 *
 * **When no `Activity` is found this is a no-op.** That is a verified consequence of the host, not a
 * guess: upstream's `window` is equally nullable (`context.findActivity()?.window`, `:31`) and it
 * skips both `addFlags` and `clearFlags` when it is null, so the behaviour on a non-`Activity` host is
 * the behaviour upstream has on that path. A caller that renders into an application-context view
 * tree therefore keeps no screen on, and nothing is left set on dismissal either.
 *
 * Deviations, both of them forced by this module's shape:
 *  - `public` rather than upstream's `internal` (`:28`). Both of upstream's call sites are ported and
 *    do call this — [ConfirmationDialog]'s `confirmationDialogContentWrapper` and
 *    [OpenOnPhoneDialogContent], where the dropped flag used to be recorded as a gap — so `internal`
 *    would now be reachable; it stays `public` because a caller that shows one of these surfaces
 *    through its own window has the same "keep the screen on while this is up" need and has no other
 *    route to the flag in this module.
 *  - The lifecycle rides on a [RememberObserver] handed to `remember` instead of upstream's
 *    `DisposableEffect(Unit)` (`:30-35`). Same add-on-enter / clear-on-leave timing, but Hibari's
 *    `DisposableEffect` (`runtime/effects/DisposableEffect.kt:36-47`) injects a
 *    `DisposableEffectView` node into the layout to get its detach signal, and a window flag does not
 *    deserve a phantom view in the screen's child list.
 *
 * Not ported: nothing — `material3/KeepScreenOn.kt` is 43 lines and all of its logic is above.
 */
@Tunable
fun KeepScreenOn() {
    // Resolved in the tunable body, not inside `remember`: that calculation lambda is
    // `@DisallowTunableCalls`, and `currentContext` is a `@Tunable` getter. One tune's value is kept
    // for the object's lifetime, which is upstream's timing too — it resolves the window once, in the
    // `DisposableEffect(Unit)` body (`:30-31`).
    val window = currentContext.findActivity()?.window
    remember { KeepScreenOnFlag(window) }
}

/** Upstream's private `Context.findActivity()` (`material3/KeepScreenOn.kt:38-43`). */
private fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * The flag, added when this is remembered into the tree and cleared when it leaves — upstream's
 * `DisposableEffect` body (`:30-35`) moved onto [RememberObserver]: `Tuner.cache` fires
 * [RememberObserver.onRemembered] when the value is stored, and both `Tuner.dispose` and the
 * end-of-tune `forgetUntouchedSlots` sweep (Tuner.kt:30-58) fire
 * [RememberObserver.onForgotten] — the latter being what covers a `if (show) { KeepScreenOn() }`
 * branch going false.
 *
 * [window] is captured for the object's lifetime, exactly as upstream captures the resolved window in
 * its `onDispose` closure; keys are absent upstream (`DisposableEffect(Unit)`) and are absent here.
 */
private class KeepScreenOnFlag(private val window: Window?) : RememberObserver {

    override fun onRemembered() {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onForgotten() {
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
