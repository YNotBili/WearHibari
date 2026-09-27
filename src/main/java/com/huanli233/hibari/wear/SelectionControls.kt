package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.onClick
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.tokens.CheckboxButtonTokens
import com.huanli233.hibari.wear.tokens.RadioButtonTokens
import com.huanli233.hibari.wear.tokens.SwitchButtonTokens
import com.huanli233.hibari.wear.view.WearCheckboxView
import com.huanli233.hibari.wear.view.WearRadioView
import com.huanli233.hibari.wear.view.WearSelectionView
import com.huanli233.hibari.wear.view.WearSwitchView

/**
 * The bare selection controls: the tappable glyph on its own, with no label slots.
 *
 * **androidx.wear.compose.material3 has no bare `Checkbox`, `RadioButton` or `Switch` to port.** Its
 * selection controls are the three labelled rows `CheckboxButton` / `RadioButton` / `SwitchButton`,
 * ported in the files of those names, which own the upstream `CheckboxButtonDefaults`,
 * `RadioButtonDefaults` and `SwitchButtonDefaults` objects and the upstream `CheckboxButtonColors`,
 * `RadioButtonColors` and `SwitchButtonColors` types.
 *
 * A bare control does exist upstream, just never as material3 API: `materialcore/SelectionControls.kt`
 * publishes `Checkbox` / `Switch` / `RadioButton`, `material/ToggleControl.kt` wraps them with
 * `CheckboxDefaults` and friends, and material3 keeps its own copies out of the public surface —
 * `private fun Checkbox` (`material3/CheckboxButton.kt:1516`), `private fun Switch`
 * (`material3/SwitchButton.kt:1874`) and an `internal fun RadioControl`
 * (`material3/RadioButton.kt:1459`) that only forwards to `materialcore.RadioButton`.
 *
 * So this trio is our own extraction of that bare shape onto the shared `view/Wear*View` drawings, and
 * no name here even matches the v1 `CheckboxColors` / `SwitchColors` / `RadioButtonColors` interfaces
 * of `material/ToggleControl.kt:199, 221, 243`: these are data classes with fewer roles, resolved from
 * material3 tokens. This module's primary port target is material3, so its names are reserved for the
 * rows and everything here that would otherwise have taken one carries `Bare`:
 * [BareCheckboxColors], [BareRadioButtonColors], [BareSwitchButtonColors] and the three matching
 * `Defaults` objects. They stayed public rather than internal because `wear-sample` — a separate
 * Gradle module, so `internal` is out of its reach — composes all three; the rows, which never read
 * them, are what the renames freed.
 *
 * Only the names of [RadioButton] and `SwitchButton` still double, each overloading its own row at a
 * shorter arity — `label` has no default upstream or here, so no call is ambiguous — and [Checkbox] is
 * the one that stands alone. Folding or dropping this trio in favour of the rows is open work.
 *
 * The `interactionSource` parameter is absent throughout; the views animate their own progress on
 * state change instead.
 */

/** [checked] is applied as an animated 0..1 progress, matching `updateTransition(...).animateFloat`. */
private fun Modifier.selectionControl(
    checked: Boolean,
    color: Color,
): Modifier = this.thenViewAttribute<WearSelectionView, Pair<Boolean, Color>>(uniqueKey, checked to color) {
    controlColor = it.second
    animateProgressTo(if (it.first) 1f else 0f)
}

private fun Modifier.switchColors(
    trackColor: Color,
    trackBorderColor: Color,
    thumbColor: Color,
    tickColor: Color,
): Modifier = this.thenViewAttribute<WearSwitchView, ColorQuartet>(
    uniqueKey,
    ColorQuartet(trackColor, trackBorderColor, thumbColor, tickColor),
) {
    this.trackColor = it.track
    this.trackBorderColor = it.trackBorder
    this.thumbColor = it.thumb
    this.tickColor = it.tick
}

/** Four colours, keyed as one attribute so a switch's retune diffs atomically. */
private data class ColorQuartet(
    val track: Color,
    val trackBorder: Color,
    val thumb: Color,
    val tick: Color,
)

/**
 * @param colors Defaults to `null` and resolves in the body: [BareCheckboxDefaults.colors] reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot.
 */
@Tunable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: BareCheckboxColors? = null,
) {
    val resolved = colors ?: BareCheckboxDefaults.colors()
    Node(
        modifier = modifier
            .viewClass(WearCheckboxView::class.java)
            .selectionControl(checked, resolved.controlColor(checked, enabled))
            .run {
                if (onCheckedChange == null || !enabled) this
                else onClick { onCheckedChange(!checked) }
            }
    )
}

/**
 * @param colors `null` resolves [BareRadioButtonDefaults.colors]; see [Checkbox] for why.
 */
@Tunable
fun RadioButton(
    selected: Boolean,
    onSelect: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: BareRadioButtonColors? = null,
) {
    val resolved = colors ?: BareRadioButtonDefaults.colors()
    Node(
        modifier = modifier
            .viewClass(WearRadioView::class.java)
            .selectionControl(selected, resolved.controlColor(selected, enabled))
            .run {
                if (onSelect == null || !enabled) this
                else onClick { onSelect(true) }
            }
    )
}

/**
 * @param colors `null` resolves [BareSwitchButtonDefaults.colors]; see [Checkbox] for why.
 */
@Tunable
fun SwitchButton(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: BareSwitchButtonColors? = null,
) {
    val resolved = colors ?: BareSwitchButtonDefaults.colors()
    Node(
        modifier = modifier
            .viewClass(WearSwitchView::class.java)
            .switchColors(
                trackColor = resolved.trackColor(checked, enabled),
                trackBorderColor = resolved.trackBorderColor(checked, enabled),
                thumbColor = resolved.thumbColor(checked, enabled),
                tickColor = resolved.tickColor(checked, enabled),
            )
            .selectionControl(checked, resolved.tickColor(checked, enabled))
            .run {
                if (onCheckedChange == null || !enabled) this
                else onClick { onCheckedChange(!checked) }
            }
    )
}

/** Colour set for the bare [Checkbox]; material3's `CheckboxButtonColors` belongs to the row. */
data class BareCheckboxColors(
    val checkedBoxColor: Color,
    val checkedCheckmarkColor: Color,
    val uncheckedBoxColor: Color,
    val disabledCheckedBoxColor: Color,
    val disabledUncheckedBoxColor: Color,
) {
    /**
     * The outline and the fill share one colour in upstream's `drawBox`, and which colour depends on
     * the state: an unchecked box is the outline token, not a faded primary.
     */
    fun controlColor(checked: Boolean, enabled: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedBoxColor else disabledUncheckedBoxColor
        checked -> checkedBoxColor
        else -> uncheckedBoxColor
    }
}

/** Colour set for the bare [RadioButton]; material3's `RadioButtonColors` belongs to the row. */
data class BareRadioButtonColors(
    val selectedControlColor: Color,
    val unselectedControlColor: Color,
    val disabledSelectedControlColor: Color,
) {
    fun controlColor(selected: Boolean, enabled: Boolean): Color = when {
        !enabled -> disabledSelectedControlColor
        selected -> selectedControlColor
        else -> unselectedControlColor
    }
}

/** Colour set for the bare [SwitchButton]; material3's `SwitchButtonColors` belongs to the row. */
data class BareSwitchButtonColors(
    val checkedTrackColor: Color,
    val checkedTrackBorderColor: Color,
    val uncheckedTrackColor: Color,
    val uncheckedTrackBorderColor: Color,
    val checkedThumbColor: Color,
    val uncheckedThumbColor: Color,
    val checkedThumbIconColor: Color,
    val disabledCheckedThumbIconColor: Color,
    val disabledCheckedTrackColor: Color,
) {
    fun trackColor(checked: Boolean, enabled: Boolean): Color = when {
        !enabled -> disabledCheckedTrackColor
        checked -> checkedTrackColor
        else -> uncheckedTrackColor
    }

    fun trackBorderColor(checked: Boolean, enabled: Boolean): Color =
        if (!enabled) disabledCheckedTrackColor
        else if (checked) checkedTrackBorderColor else uncheckedTrackBorderColor

    fun thumbColor(checked: Boolean, enabled: Boolean): Color =
        if (checked) checkedThumbColor else uncheckedThumbColor

    fun tickColor(checked: Boolean, enabled: Boolean): Color =
        if (enabled) checkedThumbIconColor else disabledCheckedThumbIconColor
}

/** Defaults for the bare [Checkbox], named `Bare` to leave upstream's to the row in `CheckboxButton.kt`. */
object BareCheckboxDefaults {
    @Tunable
    fun colors(): BareCheckboxColors = with(MaterialTheme.colorScheme) {
        BareCheckboxColors(
            checkedBoxColor = CheckboxButtonTokens.CheckedBoxColor.resolve(this),
            checkedCheckmarkColor = CheckboxButtonTokens.CheckedCheckmarkColor.resolve(this),
            uncheckedBoxColor = CheckboxButtonTokens.UncheckedBoxColor.resolve(this),
            disabledCheckedBoxColor = CheckboxButtonTokens.DisabledCheckedBoxColor.resolve(this)
                .toDisabledColor(CheckboxButtonTokens.DisabledCheckedBoxOpacity),
            disabledUncheckedBoxColor = CheckboxButtonTokens.DisabledUncheckedBoxColor.resolve(this)
                .toDisabledColor(CheckboxButtonTokens.DisabledUncheckedBoxOpacity),
        )
    }
}

/** Defaults for the bare [RadioButton], named `Bare` to leave upstream's to the row in `RadioButton.kt`. */
object BareRadioButtonDefaults {
    @Tunable
    fun colors(): BareRadioButtonColors = with(MaterialTheme.colorScheme) {
        BareRadioButtonColors(
            selectedControlColor = RadioButtonTokens.SelectedControlColor.resolve(this),
            unselectedControlColor = RadioButtonTokens.UnselectedControlColor.resolve(this),
            disabledSelectedControlColor = RadioButtonTokens.DisabledSelectedControlColor.resolve(this)
                .toDisabledColor(RadioButtonTokens.DisabledSelectedControlOpacity),
        )
    }
}

/** Defaults for the bare [SwitchButton], named `Bare` to leave upstream's to the row in `SwitchButton.kt`. */
object BareSwitchButtonDefaults {
    @Tunable
    fun colors(): BareSwitchButtonColors = with(MaterialTheme.colorScheme) {
        BareSwitchButtonColors(
            checkedTrackColor = SwitchButtonTokens.CheckedTrackColor.resolve(this),
            checkedTrackBorderColor = SwitchButtonTokens.CheckedTrackBorderColor.resolve(this),
            uncheckedTrackColor = SwitchButtonTokens.UncheckedTrackColor.resolve(this),
            uncheckedTrackBorderColor = SwitchButtonTokens.UncheckedTrackBorderColor.resolve(this),
            checkedThumbColor = SwitchButtonTokens.CheckedThumbColor.resolve(this),
            uncheckedThumbColor = SwitchButtonTokens.UncheckedThumbColor.resolve(this),
            checkedThumbIconColor = SwitchButtonTokens.CheckedThumbIconColor.resolve(this),
            disabledCheckedThumbIconColor = SwitchButtonTokens.DisabledCheckedThumbIconColor.resolve(this)
                .toDisabledColor(SwitchButtonTokens.DisabledCheckedThumbIconOpacity),
            disabledCheckedTrackColor = SwitchButtonTokens.DisabledCheckedTrackColor.resolve(this)
                .toDisabledColor(SwitchButtonTokens.DisabledCheckedTrackOpacity),
        )
    }
}
