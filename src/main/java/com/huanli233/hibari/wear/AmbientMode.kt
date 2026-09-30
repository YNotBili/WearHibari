package com.huanli233.hibari.wear

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import com.huanli233.hibari.runtime.RememberObserver
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.TunationLocalProvider
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.getValue
import com.huanli233.hibari.runtime.mutableStateOf
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.runtime.setValue
import com.huanli233.hibari.runtime.staticTunationLocalOf

/**
 * Ported from androidx.wear.compose.foundation.AmbientMode (`foundation/AmbientMode.kt:26-64`),
 * which is the type hierarchy only: an abstract base with a private constructor, the [Interactive]
 * object, and [Ambient] carrying the two device capabilities. Both members and both property names
 * are upstream's, and [Ambient.equals]/[Ambient.hashCode] are transcribed (`:48-62`) rather than
 * written as a `data class`: the two-field comparison is the same either way, but a `data class` would
 * also add a `copy()` and a generated `toString()` that upstream does not expose.
 *
 * Hibari is a Views renderer, so the facts behind the type have to come from the platform rather
 * than from a `WatchActivityState`-style composition helper. What this file reads is documented on
 * [AmbientModeHost], and [LocalAmbientMode] is the single place it lands.
 *
 * # Not ported
 *
 *  - `AmbientModeManager`, `LocalAmbientModeManager`, `rememberAmbientModeManager()` and
 *    `AmbientModeHost(showIf, onUnavailable, content)`: the current Wear OS API surface, which the
 *    reference tree predates — `grep -rn "AmbientMode" /home/rj/qmce/app-new/src/main/java` returns
 *    hits in `foundation/AmbientMode.kt` alone, and `Ambient` here is only ever the two-field class
 *    at `:44-47`. [AmbientModeHost] borrows the manager's *role* (observe the device, publish the
 *    mode) under the name the port brief asked for; it has no `showIf`/`onUnavailable` gating,
 *    because there is no upstream declaration of those parameters to copy.
 *  - `AmbientTickEffect` (the once-a-minute ambient refresh the class doc at `:31-33` describes):
 *    that is a *content* concern of whoever draws an ambient screen, not a fact about the mode.
 */
abstract class AmbientMode private constructor() {

    /** Represents the mode when the user is actively interacting with the device. */
    object Interactive : AmbientMode()

    /**
     * Represents that device is in the ambient mode. In this mode, the app is typically updated at
     * infrequent intervals (e.g., once per minute).
     *
     * @property isBurnInProtectionRequired Indicates whether the ambient layout must implement
     *   burn-in protection. When this property is set to true, composables must be shifted around
     *   periodically in ambient mode. To ensure that content isn't shifted off the screen, avoid
     *   placing content within 10 pixels of the edge of the screen and also avoid solid white areas
     *   to prevent pixel burn-in. Both of these requirements only apply in ambient mode, and only
     *   when this property is set to true.
     * @property isLowBitAmbientSupported Specifies whether this device has low-bit ambient mode.
     *   When this property is set to true, the screen supports fewer bits for each color in ambient
     *   mode. In this case, anti-aliasing should be disabled in ambient mode.
     */
    class Ambient(
        val isBurnInProtectionRequired: Boolean,
        val isLowBitAmbientSupported: Boolean,
    ) : AmbientMode() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Ambient

            return isBurnInProtectionRequired == other.isBurnInProtectionRequired &&
                isLowBitAmbientSupported == other.isLowBitAmbientSupported
        }

        override fun hashCode(): Int {
            var result = isBurnInProtectionRequired.hashCode()
            result = 31 * result + isLowBitAmbientSupported.hashCode()
            return result
        }
    }
}

/**
 * The ambient mode in scope.
 *
 * Named as the port brief asks for it; upstream has no such local in this tree (see [AmbientMode]).
 * Seeded [AmbientMode.Interactive], which is the safe answer for a host that never mounts
 * [AmbientModeHost]: the interactive rendering is the one that is correct on a lit screen, and a
 * caller outside a host has no way to learn otherwise.
 */
val LocalAmbientMode = staticTunationLocalOf<AmbientMode> { AmbientMode.Interactive }

/**
 * Read the mode [AmbientModeHost] published, or [AmbientMode.Interactive] when there is no host.
 *
 * camelCase because a value-returning `@Tunable` may not be PascalCase.
 */
@Tunable
fun ambientMode(): AmbientMode = LocalAmbientMode.current

/**
 * Publish the device's live [AmbientMode] into [LocalAmbientMode] for [content].
 *
 * What it reads, and how far that can be trusted:
 *  - **Interactive vs ambient** comes from `Display.STATE_DOZE` / `Display.STATE_DOZE_SUSPEND` on the
 *    default display (`DisplayManager.getDisplay(DEFAULT_DISPLAY).state`), re-read through a
 *    [DisplayManager.DisplayListener]. That is the platform channel the Wear ambient support uses:
 *    a watch in ambient mode leaves the panel powered but dozing, which is exactly what those two
 *    states mean, and `onDisplayChanged` is what fires when a display enters or leaves them.
 *    `Display.STATE_OFF` deliberately stays [AmbientMode.Interactive] rather than being called
 *    ambient: with the panel off the host activity is stopped anyway, and claiming ambient there
 *    would draw a burn-in-shifted frame nobody can see.
 *  - **[AmbientMode.Ambient.isLowBitAmbientSupported] /
 *    [AmbientMode.Ambient.isBurnInProtectionRequired]** come from the
 *    two `Settings.Global` flags the Wear OS "always on" guidance names
 *    (`low_bit_ambient_available`, `burn_in_protection_available`). Caveat recorded here on purpose:
 *    those two key strings are *not* verifiable from anything checked into this repo — neither
 *    `androidx/wear/compose` nor `androidx.wear:wear:1.4.0` in the local cache carries them (that
 *    artifact reaches the same two facts through the Play Services wearable compat library, as
 *    `com.google.android.wearable.compat.extra.LOWBIT_AMBIENT` and
 *    `...BURN_IN_PROTECTION`, which needs a `GoogleApiClient` this module does not have). So on a
 *    device line that does not publish those globals, `Settings.Global.getInt` answers with its
 *    default of `0` and both flags report **false** — an [AmbientMode.Ambient] that says "no low-bit,
 *    no burn-in shift". That is the documented fallback, not a measured capability: a caller that
 *    must not burn in should treat false as unknown and shift anyway.
 *
 * Everything is read on the main looper through a [Handler] of one, and the listener is dropped when
 * the remembered source is forgotten — i.e. when this host leaves the tree or [currentContext]
 * changes, since the source is `remember`ed on it.
 *
 * Not ported: the interactive-ambient *offload* half (`isAmbientOffloadEnabled`), which needs
 * `AmbientOffloadState`/`ComplicationsWatchFaceService` plumbing from Play Services that this module
 * does not depend on.
 */
@Tunable
fun AmbientModeHost(content: @Tunable () -> Unit) {
    val context = currentContext
    val source = remember(context) { AmbientModeSource(context) }
    TunationLocalProvider(
        LocalAmbientMode provides source.mode,
        content = content,
    )
}

/**
 * The display listener and the two cached capability flags behind [AmbientModeHost].
 *
 * The mode is a `mutableStateOf` so that a display-state change reads as a state write and retunes
 * whoever read [ambientMode], which is the only way a Views host can match Compose's recomposition.
 */
private class AmbientModeSource(context: Context) : RememberObserver {

    private val appContext = context.applicationContext

    private val displayManager =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private val handler = Handler(Looper.getMainLooper())

    private var modeValue by mutableStateOf<AmbientMode>(readMode())

    /** Written only from [readMode]; read during a tune, where the snapshot system records it. */
    val mode: AmbientMode get() = modeValue

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refresh()
        }
    }

    override fun onRemembered() {
        refresh()
        displayManager?.registerDisplayListener(listener, handler)
    }

    override fun onForgotten() {
        displayManager?.unregisterDisplayListener(listener)
    }

    private fun refresh() {
        modeValue = readMode()
    }

    private fun readMode(): AmbientMode {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return AmbientMode.Interactive
        return when (display.state) {
            Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> AmbientMode.Ambient(
                isBurnInProtectionRequired = globalFlag(BurnInProtectionFlag),
                isLowBitAmbientSupported = globalFlag(LowBitAmbientFlag),
            )
            else -> AmbientMode.Interactive
        }
    }

    /**
     * An absent key reads as `0`, which is the documented fallback above. The [runCatching] is only
     * there so a provider that refuses a global read cannot take a watch UI down with it; it answers
     * the same false.
     */
    private fun globalFlag(key: String): Boolean = runCatching {
        Settings.Global.getInt(appContext.contentResolver, key, 0) == 1
    }.getOrDefault(false)

    private companion object {
        const val LowBitAmbientFlag = "low_bit_ambient_available"
        const val BurnInProtectionFlag = "burn_in_protection_available"
    }
}
