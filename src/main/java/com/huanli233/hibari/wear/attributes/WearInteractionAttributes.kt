package com.huanli233.hibari.wear.attributes

import android.view.View
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey

/**
 * Clickable + enabled as one attribute, because every Wear container takes both and a disabled
 * container must also drop its click handler rather than keep it and grey out.
 *
 * The [ClickCommand] is the attribute's compared value, so the lambda rides inside it deliberately.
 * `ViewAttribute.equals` (`hibari-ui/.../Attribute.kt:68-78`) compares `key` and `value` only, so
 * keying this on `enabled` alone would let a click whose lambda captures a composition value keep
 * firing the first closure forever: the retune that changed the captured value changes nothing the
 * diff can see. Same reasoning as `SliderButtonCommand` in
 * [com.huanli233.hibari.wear.Slider]. The churn is cheap because every write here is a *set*, not an
 * add — `setOnClickListener` replaces the handler, so a re-apply cannot stack listeners the way an
 * `addOnClickListener` would.
 *
 * `isFocusable` belongs to this contract because upstream's `Modifier.clickable` installs
 * `focusable()` itself: a clickable thing is a d-pad and rotary target by definition. Without it
 * every ported button in this module was reachable by touch only, which is precisely the difference
 * a dial with a crown shows. Touch-mode focusability stays untouched, so focus is entered from a key
 * rather than stolen by a tap.
 */
fun Modifier.clickable(enabled: Boolean, onClick: () -> Unit): Modifier =
    this.thenViewAttribute<View, ClickCommand>(uniqueKey, ClickCommand(enabled, onClick)) { command ->
        val handler = if (command.enabled) {
            View.OnClickListener { command.onClick() }
        } else {
            null
        }
        setOnClickListener(handler)
        isClickable = command.enabled
        isLongClickable = command.enabled
        isFocusable = command.enabled
        isEnabled = command.enabled
    }

/**
 * The click half of the [clickable] attribute: `enabled` plus the handler itself, so an attribute
 * whose captured state moved does not compare equal to the previous round.
 */
private data class ClickCommand(
    val enabled: Boolean,
    val onClick: () -> Unit,
)
