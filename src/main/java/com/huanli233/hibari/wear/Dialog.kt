package com.huanli233.hibari.wear

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.runtime.RememberObserver
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.view.WearSwipeToDismissView

/**
 * Ported from androidx.wear.compose.material3.Dialog (`material3/Dialog.kt:75-208`): the base
 * full-screen surface [AlertDialog], [ConfirmationDialog] and [OpenOnPhoneDialog] sit on.
 *
 * # How it is hosted, and why not on the root view
 *
 * Upstream mounts its content in a real `androidx.compose.ui.window.Dialog`, i.e. a second window.
 * hibari-wear has no `Dialog`/`PopupWindow`/`WindowManager` host, and the two candidate replacements
 * were both checked against this runtime:
 *
 *  - *An overlay `HibariView` added to `currentView.rootView`.* It renders, but it is a second
 *    [com.huanli233.hibari.runtime.Tunation], and `HibariView.runTunable` (HibariView.kt:42-60)
 *    re-provides only `LocalLifecycleOwner`, `LocalContext`, `LocalConfiguration`, `LocalDensity`
 *    and `LocalLayoutDirection`. `LocalColorScheme`/`LocalTypography`/`LocalShapes` and
 *    `LocalContentColor` are provided by [MaterialTheme] as ordinary tunation locals whose stacks
 *    `Tuner.startProviders`/`Tuner.endProviders` push and pop around one tune, so they are empty the
 *    moment the host's tune finishes and a nested tune cannot see them — the overlay would lose the
 *    theme. A nested `Tunation` that shares the parent's `TuneData` (`Tunation.kt:12-19`) does not fix
 *    it either, for the same push/pop reason.
 *  - *In-place, over the caller's own parent*: one tune, one set of locals, and the dialog's subtree
 *    is a `Box` that is `matchParentSize()` and paints `colorScheme.background` — which is literally
 *    upstream's own content box (`Dialog.kt:182-191`, `Box(Modifier.matchParentSize().background(
 *    MaterialTheme.colorScheme.background)) { content() }`).
 *
 * So this port takes the second route. [AlertDialog] hosts its own content through this very
 * [Dialog], as upstream does; [ConfirmationDialog] still self-hosts and says so at
 * ConfirmationDialog.kt:53, and [OpenOnPhoneDialog] at OpenOnPhoneDialog.kt:41, each naming
 * `properties: DialogProperties` and swipe-to-dismiss as window-only gaps — headers that predate
 * this file. What this file recovers is the window-derived behaviour that can in fact be had
 * without a window — see [dialogBackPressHandling] and the swipe box below.
 *
 * Because there is no window, the caller owes two things upstream got for free:
 *  - **Placement on top.** Put [Dialog] last inside the screen root so it draws over its siblings;
 *    nothing here can lift it above a sibling that comes later.
 *  - **Ancestor gesture contention.** If that screen root is a [ScreenScaffold], its
 *    [WearSwipeToDismissView] is the *parent* of the dialog's own box, and a parent's
 *    `onInterceptTouchEvent` runs first: a right-swipe on the dialog can dismiss the screen behind it
 *    instead of the dialog. The Views fix is `requestDisallowInterceptTouchEvent`, which
 *    [WearSwipeToDismissView] lists as an un-ported gap in its own header (line 195) and which this
 *    file cannot patch from outside the view.
 *
 * # Not ported, with reasons
 *
 *  - The entry/exit animation stack (`:86-96`, `:160-174`, `:195-204`, `animateContentAlpha`
 *    `:210-229`, `animateDialogScale` `:231-250`, `DialogVisibility` `:252-255`): content alpha
 *    0→1, dialog scale 1.25→1.0, and the host screen scaled to `BackgroundMinScale = 0.85f`
 *    (`:257-258`) through `LocalScaffoldState.parentScale` (`:93`, `:114-115`, `:207`). There is no
 *    scaffold-state plumbing here to scale, and the specs come from `MaterialTheme.motionScheme`,
 *    which [MaterialTheme] documents as unported. Dropping the animations also drops the reason
 *    `shouldShow = showState || currentState == Display` (`:90`) keeps a hidden dialog composed: with
 *    nothing to animate out, `visible` alone is the right gate, which is what upstream reduces to.
 *  - `swipeToDismissBoxState.offset`-driven background scaling (`:106-129`), which also keys off
 *    `LocalReduceMotion` (`:98`), is not ported. The reduce-motion *source* now exists — this module
 *    reads it through `wearReduceMotionEnabled` in `ReduceMotion.kt` — so what is missing here is the
 *    offset-driven scale itself, not the setting.
 *  - The window configuration block (`:146-158`): `setWindowAnimations(Animation)`,
 *    `setDimAmount(0f)` and `setLayout(MATCH_PARENT, MATCH_PARENT)`. The dim is the one thing whose
 *    *absence* is faithful — upstream sets it to zero and paints `colorScheme.background` itself — and
 *    the full-bleed size is what `matchParentSize()` gives here.
 *  - `LocalScaffoldState`, `parentScale` reset on dispose (`:207`), and focus control: no window, no
 *    scaffold state.
 *  - `properties.usePlatformDefaultWidth` / `securePolicy` (`:136-141` names them): both are window
 *    configuration whose types live in compose-ui, which the reference tree does not carry, so there
 *    is nothing here to copy verbatim and no flag to set on a host window this port does not create.
 *    [DialogProperties] therefore carries only the two fields the reference file shows by name.
 *  - The `qmce_settings`/`fullscreen_dialogs` `SharedPreferences` read at `:99-101` and the branches
 *    gated on it (`:135-144`, `:152-157`, `:165-169`) are a local edit in the reference copy, not
 *    wear Material 3: the dialog is treated as unconditionally full-bleed, which is the branch that
 *    edit defaults to.
 *
 * @param content upstream's slot is a bare `@Composable () -> Unit` inside its own `Box`; the
 *   [BoxScope] receiver is this module's convention (see
 *   [com.huanli233.hibari.wear.IconButton]) and is what lets a child centre itself with
 *   `Modifier.gravity`.
 */
@Tunable
fun Dialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: @Tunable BoxScope.() -> Unit,
) {
    if (visible) {
        val scope = content
        val dismissState = rememberSwipeToDismissBoxState()
        val scrimColor = MaterialTheme.colorScheme.background
        val surface = dialogSurface()
        val dismiss: (() -> Unit)? = onDismissRequest
        dialogBackPressHandling(properties.dismissOnBackPress, onDismissRequest)
        Box(
            modifier = modifier
                .matchParentSize()
                .viewClass(WearSwipeToDismissView::class.java)
                .thenViewAttribute<WearSwipeToDismissView, SwipeToDismissBoxState>(
                    uniqueKey,
                    dismissState,
                ) { state = it }
                .thenViewAttribute<WearSwipeToDismissView, Color>(uniqueKey, scrimColor) {
                    backgroundScrimColor = it
                }
                .thenViewAttribute<WearSwipeToDismissView, Color>(uniqueKey, scrimColor) {
                    contentScrimColor = it
                }
                .thenViewAttribute<WearSwipeToDismissView, (() -> Unit)?>(uniqueKey, dismiss) {
                    onDismissed = it
                },
        ) {
            Box(modifier = Modifier.matchParentSize().container(surface)) {
                scope()
            }
        }
    }
}

/**
 * The two `DialogProperties` fields the reference file names (`material3/Dialog.kt:137-138`), with
 * their defaults.
 *
 * Reduced port, deliberately: [dismissOnBackPress] is honoured through
 * [dialogBackPressHandling] because that has a window-free equivalent, while [dismissOnClickOutside]
 * is carried and *inert* — with no window there is no scrim, and upstream's own full-bleed branch
 * sizes the dialog to `MATCH_PARENT`×`MATCH_PARENT` (`:152-157`), which leaves no outside to click
 * either. Keeping the parameter keeps the call sites shaped like upstream's; `securePolicy` and
 * `usePlatformDefaultWidth` are absent because their types are not in this repo — see [Dialog].
 */
data class DialogProperties(
    val dismissOnBackPress: Boolean = true,
    val dismissOnClickOutside: Boolean = true,
)

/**
 * Back handling without a window: while the dialog is composed, a back press on the host activity is
 * taken by an [OnBackPressedCallback] and routed to [onDismissRequest], which is what
 * `DialogProperties(dismissOnBackPress = true)` buys upstream (`material3/Dialog.kt:137`).
 *
 * It works only where the host `Context` chain reaches an [OnBackPressedDispatcherOwner] — a
 * `ComponentActivity`, which is what an `AppCompatActivity` is. [findDispatcher] walks the
 * `ContextWrapper` chain the same way upstream's `KeepScreenOn` walks it for an `Activity`
 * (`material3/KeepScreenOn.kt:38-43`), so a themed or wrapped context still resolves. When nothing on
 * the chain owns a dispatcher there is no back handler, exactly as [KeepScreenOn] is a no-op on such a
 * host; the caller's own dismissal route (a button, or a swipe) still works.
 *
 * Gated by [DialogProperties.dismissOnBackPress] only, as upstream gates the window's back handling
 * by that flag alone.
 */
@Tunable
private fun dialogBackPressHandling(dismissOnBackPress: Boolean, onDismissRequest: () -> Unit) {
    val dispatcher = currentContext.findDispatcher()
    if (dispatcher != null) {
        val handler = remember(dispatcher) { DialogBackPressHandler(dispatcher) }
        handler.onBack = onDismissRequest
        handler.isEnabled = dismissOnBackPress
    }
}

/**
 * Registered when remembered, removed when the `visible` branch stops rendering (the runtime forgets
 * an unread `remember` slot at the end of a tune — `forgetUntouchedSlots`, Tuner.kt:30-58), so no
 * invisible dialog keeps swallowing back presses.
 */
private class DialogBackPressHandler(
    private val dispatcher: OnBackPressedDispatcher,
) : RememberObserver {

    /** Rewritten on every tune, because [onDismissRequest] is a lambda and never compares equal. */
    var onBack: (() -> Unit)? = null

    var isEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            callback.isEnabled = value
        }

    private val callback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            onBack?.invoke()
        }
    }

    override fun onRemembered() {
        dispatcher.addCallback(callback)
    }

    override fun onForgotten() {
        callback.remove()
    }
}

/** Upstream's `Context.findActivity()` shape (`KeepScreenOn.kt:38-43`), for a dispatcher instead. */
private fun Context.findDispatcher(): OnBackPressedDispatcher? =
    when {
        this is OnBackPressedDispatcherOwner -> onBackPressedDispatcher
        this is ContextWrapper -> baseContext.findDispatcher()
        else -> null
    }
