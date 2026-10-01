package com.huanli233.hibari.wear.attributes

import android.view.View
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.R

/**
 * Clickable + enabled as one attribute, because every Wear container takes both and a disabled
 * container must also drop its click handler rather than keep it and grey out.
 *
 * The [ClickCommand] is the attribute's compared value, so the lambda rides inside it deliberately.
 * `ViewAttribute.equals` (`hibari-ui/.../Attribute.kt:68-78`) compares `key` and `value` only, never
 * the applier, so keying this on `enabled` alone would let a click whose lambda captures a
 * composition value keep firing the first closure forever: the retune that changed the captured value
 * changes nothing the diff can see. Same reasoning as `SliderButtonCommand` in
 * [com.huanli233.hibari.wear.Slider].
 *
 * What the command drives is the *contents* of one listener, never the listener itself. A lambda is
 * a fresh object on every tune, so the previous shape — build a `View.OnClickListener` and install it
 * — allocated and re-installed a handler per tune per button, and `setOnClickListener` also re-runs
 * its own `if (!isClickable()) setClickable(true)` on every one of those calls. [WearClickHandler] is
 * created once per View and kept for that View's lifetime, and the applier only writes the new command
 * into it, installing the same instance (or `null` while disabled, as upstream's disabled container
 * drops the handler). The applier still runs on every tune, which is the point: it is what keeps the
 * captured state fresh.
 *
 * `isFocusable` belongs to this contract because upstream's `Modifier.clickable` installs
 * `focusable()` itself: a clickable thing is a d-pad and rotary target by definition. Without it
 * every ported button in this module was reachable by touch only, which is precisely the difference
 * a dial with a crown shows. Touch-mode focusability stays untouched, so focus is entered from a key
 * rather than stolen by a tap.
 */
fun Modifier.clickable(enabled: Boolean, onClick: () -> Unit): Modifier =
    this.thenViewAttribute<View, ClickCommand>(uniqueKey, ClickCommand(enabled, onClick)) { command ->
        val handler = wearClickHandler()
        handler.command = command
        setOnClickListener(if (command.enabled) handler else null)
        isClickable = command.enabled
        isLongClickable = command.enabled
        isFocusable = command.enabled
        isEnabled = command.enabled
    }

/**
 * The one click listener a view ever gets from [clickable]: it dispatches through [command], which
 * the attribute replaces on each tune that changes the lambda or `enabled`.
 *
 * Held in the view's tag rather than rebuilt per tune. The tag key is a real resource id
 * (`R.id.hibari_wear_click_handler`, `res/values/ids.xml`) because `View.setTag(int, Object)` requires
 * an application-specific one — the requirement `HierarchicalFocus.kt:266-269` records, and the reason
 * this file cannot use `View.generateViewId()` the way `Placeholder.kt:643` does.
 */
private class WearClickHandler : View.OnClickListener {
    var command: ClickCommand? = null

    override fun onClick(view: View) {
        command?.onClick?.invoke()
    }
}

private fun View.wearClickHandler(): WearClickHandler =
    (getTag(R.id.hibari_wear_click_handler) as? WearClickHandler) ?: WearClickHandler().also {
        setTag(R.id.hibari_wear_click_handler, it)
    }

/**
 * The click half of the [clickable] attribute: `enabled` plus the handler itself, so an attribute
 * whose captured state moved does not compare equal to the previous round.
 */
private data class ClickCommand(
    val enabled: Boolean,
    val onClick: () -> Unit,
)
