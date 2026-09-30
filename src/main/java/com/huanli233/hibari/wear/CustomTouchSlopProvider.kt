package com.huanli233.hibari.wear

import android.content.Context
import android.view.ViewConfiguration
import com.huanli233.hibari.runtime.ProvidableTunationLocal
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.TunationLocalProvider
import com.huanli233.hibari.runtime.staticTunationLocalOf

/**
 * The px touch slop in force for this subtree, or `null` for the platform value. Hibari's stand-in for
 * `LocalViewConfiguration.touchSlop`, which upstream overwrites in
 * [CustomTouchSlopProvider] (`foundation/CustomTouchSlopProvider.kt:26-40`).
 */
val LocalTouchSlop: ProvidableTunationLocal<Float?> = staticTunationLocalOf { null }

/**
 * Ported from `androidx.wear.compose.foundation.CustomTouchSlopProvider`
 * (`foundation/CustomTouchSlopProvider.kt:24-40`).
 *
 * Upstream's two spellings — the `internal` one in `foundation` and the
 * `@RestrictTo(LIBRARY_GROUP) public` one in `materialcore/CustomTouchSlopProvider.kt:26-40` — are
 * byte-for-byte the same function over the same `LocalViewConfiguration`; this module's components
 * import the `foundation` one (`foundation/pager/Pager.kt:56`, `material/SwipeToReveal.kt:92`), so
 * this file is the single home and no alias is needed.
 *
 * What it does upstream: `LocalViewConfiguration provides CustomTouchSlop(newTouchSlop, current)`, a
 * `ViewConfiguration` delegate that overrides only `touchSlop`
 * (`foundation/CustomTouchSlopProvider.kt:34-40`). Hibari has no `ViewConfiguration` abstraction — the
 * platform one is reachable from anywhere and carries no per-subtree state — so the override is
 * carried as [LocalTouchSlop], a px slop that [currentTouchSlop] resolves. Everything else
 * `ViewConfiguration` reports (long press timeouts, the fling thresholds) is read straight from the
 * platform by both, exactly as upstream's delegate falls through for those members.
 *
 * Deviations, both forced by the runtime rather than chosen:
 *  - Public, where upstream's `foundation` copy is `internal`: its callers are other files of this one
 *    module that are being written concurrently, and `internal` would still compile but would hide the
 *    override from a host app that owns its own gesture code, which is what `materialcore`'s copy
 *    exists for upstream.
 *  - The override carries `Float?` rather than a whole configuration object, because the only member
 *    upstream ever replaces is `touchSlop`.
 *  - [content] is `@Tunable`, as every content slot in this module is.
 *
 * Wrap [content] so that everything inside it drags with [newTouchSlop] instead of the platform slop.
 *
 * Upstream's uses are always a multiplier around the current value — `originalTouchSlop * 1.10f` for
 * the pager (`foundation/pager/Pager.kt:126`, `:415`), `touchSlop * 1.20f` for the reveal
 * (`material3/SwipeToReveal.kt:318`, `:1924`) — and, in the pager, a second pass that restores the
 * original inside each page (`foundation/pager/Pager.kt:182`). Both numbers are published by the
 * consuming components, not here.
 *
 * @param newTouchSlop The slop in px. Upstream's parameter is `Float` and is likewise px: Compose's
 *   default `ViewConfiguration.touchSlop` is `ViewConfiguration.getScaledTouchSlop()` as a float, which
 *   is what [currentTouchSlop] falls back to.
 */
@Tunable
fun CustomTouchSlopProvider(newTouchSlop: Float, content: @Tunable () -> Unit) {
    TunationLocalProvider(LocalTouchSlop provides newTouchSlop, content = content)
}

/**
 * The touch slop a gesture in this subtree should use, in px: [LocalTouchSlop] when a
 * [CustomTouchSlopProvider] wraps the call, otherwise the platform's
 * `ViewConfiguration.getScaledTouchSlop()`.
 *
 * This is the read side of the port, and the one a Views component needs because a `View` has no
 * ambient to consult at gesture time: call this in a `@Tunable` body and hand the float to the view
 * with the component's other tuned numbers. `view/WearSliderView.kt:321-324` and
 * `view/WearSwipeToDismissView.kt:589` currently read `ViewConfiguration` inside the view, which is
 * the same value as this function returns outside any provider.
 */
@Tunable
fun currentTouchSlop(context: Context): Float =
    LocalTouchSlop.current ?: ViewConfiguration.get(context).scaledTouchSlop.toFloat()
