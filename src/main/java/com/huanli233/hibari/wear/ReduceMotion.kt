/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huanli233.hibari.wear

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Ported from two upstream files, and now the module's only copy of either:
 *  - `androidx.wear.compose.foundation.LocalReduceMotion`'s backing read
 *    (`foundation/CompositionLocals.kt:88-99`) as [wearReduceMotionEnabled].
 *  - `androidx.wear.compose.material3.animatedDelay` (`material3/AnimationSpecUtils.kt:203-208`) as
 *    [wearAnimatedDelay].
 *
 * ## What upstream's reduce-motion source actually is
 *
 * It is a CompositionLocal with a *computed default*, not a parameter anything threads through:
 * `public val LocalReduceMotion: ProvidableCompositionLocal<Boolean> =
 * compositionLocalWithComputedDefaultOf { ... }` (`foundation/CompositionLocals.kt:39-55`). Proved by
 * its call sites rather than assumed — every one of them reads the local itself and passes the value
 * into [wearAnimatedDelay]'s `reduceMotionEnabled` parameter, at
 * `material3/ConfirmationDialog.kt:273` then `:278`, `:560`/`:562`, `:618`/`:621`, `:644`/`:647`,
 * `:920`/`:923`, `:975`/`:978`, `:1000`/`:1003`, `:1029`/`:1032`, and
 * `material3/OpenOnPhoneDialog.kt:200` then `:204`, `:320` then `:323`. Nothing else in the reference
 * tree feeds that argument, and no file other than `foundation/CompositionLocals.kt` reads the
 * `reduce_motion` global at all (`Settings.` searched across `androidx/wear/compose`: the only
 * reduce-motion hits are `:50`, `:90`, `:98` in that file).
 *
 * Two things worth stating precisely, because they change what a faithful port owes:
 *  - The local is **never provided** anywhere in this tree — searched for a provider of it across all
 *    of `androidx/wear/compose`: zero hits. So every consumer gets the computed default, i.e. the
 *    cached system-settings read below, and there is no theme-layer override to reproduce.
 *  - The plumbing around it is absent from this fork in a way that does not matter: the file is clean
 *    of the fork's own `rj.qmce.lite.AppConfig` / `qmce_settings` backdoors (searched: zero hits in
 *    `foundation/CompositionLocals.kt`), so the settings read is genuine upstream behaviour. And
 *    `WearThreadRestriction` — the androidx.wear helper such reads normally go through — appears
 *    **nowhere** in this reference tree (zero hits), so it is not a source this port can be measured
 *    against.
 *
 * ## What is reproduced, and what is not
 *
 * Reproduced exactly: the key (`Settings.Global`, name `reduce_motion`, which the upstream comment at
 * `:97` identifies as framework `Settings.Global.Wearable#REDUCE_MOTION`), the default
 * (`REDUCE_MOTION_DEFAULT = 0`, `:99`), the `== 1` predicate (`:90`), the resolver taken from the
 * **application** context (`:42`), and the `catch` — upstream catches only [SecurityException], logs a
 * warning and reports `false` (`:91-94`).
 *
 * Not reproduced, deliberately:
 *  - Upstream's **caching and observation**: `cachedReducedMotion: MutableState<Boolean?>` (`:103`)
 *    filled once, plus a `ContentObserver` on `Settings.Global.getUriFor(REDUCE_MOTION)` registered
 *    with `notifyForDescendants = false` (`:44-52`) that refreshes it. [wearReduceMotionEnabled] is a
 *    live read, which is what all the copies it replaced were; upgrading it to an observed
 *    `TunationLocal` is a behaviour change (a toggle would take effect mid-tune) and is out of scope
 *    here. `AmbientModeHost` (`AmbientMode.kt:137-144`) proves the observed shape is achievable in
 *    this framework — it registers a `DisplayManager.DisplayListener` inside a `remember`ed source and
 *    publishes through `TunationLocalProvider` — so this is a choice, not a limitation.
 *  - Upstream's `Log.w` uses tag `TAG = "CompositionLocals"` (`:92`, `:101`), i.e. the file's own name;
 *    the tag here follows that convention rather than being invented at the call site.
 *
 * Naming deviation, per `WEAR_PORT_CONTRACT.md` rule 2: upstream's helpers are called `animatedDelay`
 * and (in `CompositionLocals.kt`) `getReducedMotionSettingValue`, both generic at package scope. This
 * module's whole `com.huanli233.hibari.wear` package is one compilation unit shared by every ported
 * component, so both names here carry the `wear` prefix, the way `pagerReduceMotionEnabled` and
 * `dialogAnimatedDelay` already did in the files these replace.
 *
 * Folded in from these five private samplers, all identical in key, default, predicate and caught
 * exception — they disagreed only about going through `applicationContext`, and about what to name the
 * key:
 *  - `Placeholder.kt`'s `placeholderReduceMotion` (was `:648-652`; inline key literal, no
 *    `applicationContext`)
 *  - `PagerScaffold.kt`'s `pagerReduceMotionEnabled` (was `:340-344`, const `ReduceMotionSetting` at
 *    `:346`; no `applicationContext`)
 *  - `view/WearPickerViews.kt`'s `isReduceMotionEnabled` (was `:343-347`, a *member* function reading
 *    the View's own `context`, with const `SETTING_REDUCE_MOTION` at `:1516`; no `applicationContext`)
 *  - `OpenOnPhoneDialog.kt`'s `openOnPhoneReduceMotionEnabled` (was `:446-454`, const
 *    `OpenOnPhoneReduceMotionSetting` at `:457`; **did** go through `applicationContext`, which is why
 *    this function does too)
 *  - `ConfirmationDialog.kt`'s `dialogAnimatedDelay` (was `:580-582`), which was not a sampler but
 *    [wearAnimatedDelay] with the reduce-motion branch deleted, so it delayed unconditionally.
 *
 * All five are gone from those files, and both `animatedDelay` consumers now call this pair — named by
 * function, since the line numbers this bullet used to carry went stale with every edit to those two
 * files: `ConfirmationDialogContent`, `SuccessConfirmationDialogContent`,
 * `FailureConfirmationDialogContent`, `confirmationDialogContentWrapper` and
 * `confirmationDialogIconContainer` in `ConfirmationDialog.kt`, and `OpenOnPhoneDialogContent` in its
 * own file.
 */

/**
 * Upstream's `LocalReduceMotion.current` — the Wear OS accessibility toggle that turns off animations
 * and screen movements (`foundation/CompositionLocals.kt:36-38`), read live off
 * `Settings.Global`.
 *
 * Takes [context] as an argument instead of reading `currentContext` because the compiler rejects a
 * `@Tunable` invocation inside a `try` block (`TunableCallChecker.kt:155-165`), which is also why the
 * three landed copies took it this way. Outside a tune pass — the picker views, for instance — the
 * caller's own `context` is the right argument.
 *
 * @return true when the wearer has reduce motion on, and also `false` when the platform refuses the
 *   read, which is upstream's fallback (`:91-94`).
 */
internal fun wearReduceMotionEnabled(context: Context): Boolean = try {
    Settings.Global.getInt(
        context.applicationContext.contentResolver,
        WEAR_REDUCE_MOTION_SETTING,
        WEAR_REDUCE_MOTION_DEFAULT,
    ) == 1
} catch (e: SecurityException) {
    Log.w(
        WEAR_REDUCE_MOTION_LOG_TAG,
        "Failed to fetch reduce motion setting, using value: false",
        e,
    )
    false
}

/**
 * Delay to be used for animations. Will enable delay only when ReducedMotion is disabled.
 *
 * `material3/AnimationSpecUtils.kt:203-208`, same arithmetic and same branch meaning: the duration is
 * either waited out in full or skipped completely — there is no shortened delay. Upstream's own KDoc is
 * the one-line summary above.
 *
 * The condition stays a parameter rather than a call to [wearReduceMotionEnabled] because that is
 * upstream's signature (`:204`) and it is what lets a caller sample the local once for the whole
 * animation, exactly as `material3/ConfirmationDialog.kt:560` then `:562` does.
 *
 * @param duration how long to wait, in ms.
 * @param reduceMotionEnabled the caller's `LocalReduceMotion`, i.e.
 *   [wearReduceMotionEnabled].
 */
internal suspend fun wearAnimatedDelay(duration: Long, reduceMotionEnabled: Boolean) {
    if (!reduceMotionEnabled) {
        delay(duration)
    }
}

/** `CompositionLocals.REDUCE_MOTION` (`:98`), framework `Settings.Global.Wearable#REDUCE_MOTION`. */
private const val WEAR_REDUCE_MOTION_SETTING = "reduce_motion"

/** `CompositionLocals.REDUCE_MOTION_DEFAULT` (`:99`). */
private const val WEAR_REDUCE_MOTION_DEFAULT = 0

/** Stands in for `CompositionLocals.TAG` (`:101`), which is the file's own name. */
private const val WEAR_REDUCE_MOTION_LOG_TAG = "ReduceMotion"
