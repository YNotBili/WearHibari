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
import com.huanli233.hibari.wear.view.WearCheckboxView
import com.huanli233.hibari.wear.view.WearRadioView
import com.huanli233.hibari.wear.view.WearSelectionView
import com.huanli233.hibari.wear.view.WearSwitchView

/**
 * The bare selection controls: the tappable glyph on its own, with no label slots.
 *
 * These are the three public functions of `androidx.wear.compose.material/ToggleControl.kt` — `Checkbox`
 * (`:61`), `Switch` (`:109`) and `RadioButton` (`:168`), each forwarding to the `@RestrictTo` core
 * drawing in `materialcore/SelectionControls.kt` (`:83`, `:171`, `:263` → `:318`). Their parameter
 * names, order, types and defaults are reproduced so a v1 call pastes in and compiles.
 *
 * material3 publishes no bare control to compete with: its own copies are kept out of the public
 * surface — `private fun Checkbox` (`material3/CheckboxButton.kt:1516`), `private fun Switch`
 * (`material3/SwitchButton.kt:1874`) and an `internal fun RadioControl` (`material3/RadioButton.kt:1459`)
 * that only forwards to `materialcore.RadioButton`. material3's public selection controls are the three
 * labelled rows `CheckboxButton` / `RadioButton` / `SwitchButton`, ported in the files of those names,
 * which own `CheckboxButtonDefaults`, `RadioButtonDefaults` (`RadioButton.kt:320`) and
 * `SwitchButtonDefaults`, plus the `CheckboxButtonColors`, `RadioButtonColors` (`RadioButton.kt:560`) and
 * `SwitchButtonColors` types.
 *
 * **Names in this flat package.** v1's names were adopted wherever the rows do not hold them: [Checkbox],
 * [Switch], [CheckboxColors], [CheckboxDefaults], [SwitchColors], [SwitchDefaults]. The two that could
 * **not** be adopted are the radio's colour names — v1's `RadioButtonColors` (`ToggleControl.kt:243`) and
 * `RadioButtonDefaults` (`:341`) are already material3's row types in `RadioButton.kt`, so the bare
 * radio keeps [BareRadioButtonColors] and [BareRadioButtonDefaults]. [RadioButton] itself did not need a
 * prefix: its signature and the row's are disjoint (see below).
 *
 * **Colour-role gap.** v1's three colour types are `@Stable interface`s of two `@Composable
 * (enabled, checked) -> State<Color>` roles each — `boxColor`/`checkmarkColor` (`:199`),
 * `thumbColor`/`trackColor` (`:221`), `ringColor`/`dotColor` (`:243`) — and each role reads four plain
 * colours through `animateSelectionColor` (`materialcore/SelectionControls.kt:415-432`), so the colour
 * tween runs on its own slow spec beside the shape's fast one. Here they are data classes of plain
 * [Color]s resolved once per tune into the state-selecting function each view's colour slot takes
 * ([CheckboxColors.controlColor], [BareRadioButtonColors.controlColor], and [SwitchColors]'s two roles,
 * named and ordered as v1 names them); the shared views note the lost split-spec colour tween in
 * `view/WearSelectionViews.kt:20-23`. Two role pairs still collapse because those views have fewer
 * slots: the checkbox's tick has no colour of its own — [CheckboxColors.checkedCheckmarkColor] is stored
 * and never read, because `WearSelectionView.controlColor` (`view/WearSelectionViews.kt:54`) paints both
 * the box and the tick (`WearCheckboxView` at `:188`) — and the radio's dot shares the ring's colour
 * (`WearRadioView` at `:211`, the dot then filling with the same paint). Giving each of the two views a
 * second slot is what closes them.
 *
 * The switch draws v1's geometry: a 24 x 10 dp round-capped track and a 7 dp thumb travelling 7 to 17 dp,
 * with no border ring and no tick ([WearSwitchView], `view/WearSelectionViews.kt:233`, from
 * `ToggleControl.kt:627-629` and its `drawThumb` at `:398-416`, which discards the icon colour it is
 * handed at `:137`). The checkbox's box matches as well (`BOX_SIZE 18` / `BOX_STROKE 2` on a 24 x 24
 * canvas, `:622-625`, `:634-635`), but its tick is material3's fork rather than v1's: v1 grows the two
 * segments from (6.7, 12.3) and (9.3, 16.3) under a linear 15 deg-to-0 rotation, strokes them with
 * `StrokeCap.Butt` and switches to `BlendMode.Hardlight` while disabled
 * (`materialcore/SelectionControls.kt:547-592`, erased on the way out at `:625-662`), where this port
 * starts at (7.4, 13.0), eases the rotation cubically and caps the ends round.
 *
 * Only [RadioButton] still doubles a row name, overloading `RadioButton.kt:92` at a shorter arity: the row
 * requires `onSelect: () -> Unit` (`:94`) and a trailing `label` (`:102`), neither of which this signature
 * has, so a call that names either picks the row and a call that supplies neither picks this one.
 * `Checkbox` and `Switch` stand alone — renaming the bare switch off `SwitchButton` is what freed `Switch`,
 * and the row keeps `SwitchButton` uncontested.
 *
 * `interactionSource` is absent from all three; see each function's KDoc for the consequence.
 */

/** [checked] is applied as an animated 0..1 progress, matching `updateTransition(...).animateFloat`. */
private fun Modifier.selectionControl(
    checked: Boolean,
    color: Color,
): Modifier = this.thenViewAttribute<WearSelectionView, Pair<Boolean, Color>>(uniqueKey, checked to color) {
    controlColor = it.second
    animateProgressTo(if (it.first) 1f else 0f)
}

/**
 * The bare [Switch]'s two v1 roles and its state, keyed as one attribute so a retune diffs atomically.
 * [controlColor] carries the track: the shared base guards on it before drawing, and v1 paints the
 * track's fill with the colour it strokes it with (`material/ToggleControl.kt:123-129`), so one colour
 * covers both of upstream's `trackFillColor` / `trackStrokeColor` arguments.
 */
private fun Modifier.switchControl(
    checked: Boolean,
    trackColor: Color,
    thumbColor: Color,
): Modifier = this.thenViewAttribute<WearSwitchView, SwitchRenderState>(
    uniqueKey,
    SwitchRenderState(checked, trackColor, thumbColor),
) {
    controlColor = it.track
    this.thumbColor = it.thumb
    animateProgressTo(if (it.checked) 1f else 0f)
}

/** [checked] folded in with the two colours, so one diff covers the whole switch. */
private data class SwitchRenderState(
    val checked: Boolean,
    val track: Color,
    val thumb: Color,
)

/**
 * @param checked Whether this checkbox is currently checked (`ToggleControl.kt:62`).
 * @param modifier Applied to the checkbox; upstream notes it can carry the content description for
 *   accessibility (`:63`).
 * @param colors [CheckboxColors] the box and checkmark colours come from (`:64`). `null` rather than
 *   upstream's `@Composable CheckboxDefaults.colors()` default — a `@Tunable` default expression is
 *   hoisted into a non-`@Tunable` `$default` that cannot read the theme — so it resolves in the body.
 * @param enabled Affects the colour (`:65`). One deviation: upstream keeps
 *   `toggleable(enabled = false, ...)` in the chain (`materialcore/SelectionControls.kt:504-516`), a tap
 *   that does nothing but still carries the click semantics, while a disabled control here drops the click
 *   attribute outright and the view keeps its XML clickable default — the deliberate conditional style of
 *   `attributes/ContainerAttributes.kt:89-105`. A `null` [onCheckedChange] matches upstream exactly: core's
 *   `maybeToggleable` skips the modifier too.
 * @param onCheckedChange Called with the toggled value, as core's
 *   `toggleable(value = checked, onValueChange = onCheckedChange)` does
 *   (`materialcore/SelectionControls.kt:508-515`). `null` (`:66`) makes this passive and relies on a
 *   higher-level component such as a row.
 *
 * `interactionSource` (`:67`) is not ported: Hibari has no interaction or indication system, so there is
 * nothing to hoist and no ripple to route through it. The consequence is that [onCheckedChange] is the
 * last parameter here and `interactionSource` is upstream's, so the trailing-lambda call
 * `Checkbox(checked = c) { ... }` binds to the callback here and is a type error upstream.
 */
@Tunable
fun Checkbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    colors: CheckboxColors? = null,
    enabled: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val resolved = colors ?: CheckboxDefaults.colors()
    Node(
        modifier = modifier
            .viewClass(WearCheckboxView::class.java)
            .selectionControl(checked, resolved.controlColor(enabled, checked))
            .run {
                if (onCheckedChange == null || !enabled) this
                else onClick { onCheckedChange(!checked) }
            }
    )
}

/**
 * @param checked Whether this switch is currently toggled on (`ToggleControl.kt:110`).
 * @param modifier Applied to the switch (`:111`).
 * @param colors [SwitchColors] the thumb and track colours come from (`:112`); `null` resolves
 *   [SwitchDefaults.colors] in the body, see [Checkbox].
 * @param enabled Affects the colour (`:113`); the disabled-drops-the-click-attribute deviation is recorded
 *   at [Checkbox].
 * @param onCheckedChange Called with the toggled value, or `null` for passive (`:114`).
 *
 * `interactionSource` (`:115`) is not ported, for the reason and with the trailing-lambda consequence
 * recorded at [Checkbox].
 */
@Tunable
fun Switch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    colors: SwitchColors? = null,
    enabled: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val resolved = colors ?: SwitchDefaults.colors()
    Node(
        modifier = modifier
            .viewClass(WearSwitchView::class.java)
            .switchControl(
                checked = checked,
                trackColor = resolved.trackColor(enabled, checked),
                thumbColor = resolved.thumbColor(enabled, checked),
            )
            .run {
                if (onCheckedChange == null || !enabled) this
                else onClick { onCheckedChange(!checked) }
            }
    )
}

/**
 * @param selected Whether this radio button is currently selected (`ToggleControl.kt:169`).
 * @param modifier Applied to the radio button (`:170`).
 * @param colors [BareRadioButtonColors] the ring and dot colours come from (`:171`); `null` resolves
 *   [BareRadioButtonDefaults.colors] in the body, see [Checkbox].
 * @param enabled Affects the colour (`:172`); the disabled-drops-the-click-attribute deviation is recorded
 *   at [Checkbox].
 * @param onClick Called on tap with **no argument** — core wires this to
 *   `selectable(selected = selected, onClick = onClick)`
 *   (`materialcore/SelectionControls.kt:535-542`), so selecting an already-selected radio still calls it.
 *   `null` (`:173`) makes this passive. This is upstream's parameter: it is not the rows' `onSelect`, and
 *   it is not the `onCheckedChange` of [Checkbox] and [Switch].
 *
 * `interactionSource` (`:174`) is not ported for the reason recorded at [Checkbox]; because it is a
 * `MutableInteractionSource?` and not a lambda, dropping it leaves [onClick] last and makes a
 * trailing-lambda call bind to it here.
 */
@Tunable
fun RadioButton(
    selected: Boolean,
    modifier: Modifier = Modifier,
    colors: BareRadioButtonColors? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val resolved = colors ?: BareRadioButtonDefaults.colors()
    // The parameter is upstream's `onClick`, which shadows the `Modifier.onClick` extension inside the
    // chain below, so the listener is read through a local and the extension is called on an explicit
    // receiver.
    val click = onClick
    Node(
        modifier = modifier
            .viewClass(WearRadioView::class.java)
            .selectionControl(selected, resolved.controlColor(enabled, selected))
            .run {
                if (click == null || !enabled) this
                else this.onClick { click() }
            }
    )
}

/**
 * The bare [Checkbox]'s colour set — v1's `CheckboxColors` (`ToggleControl.kt:199`), which is free here
 * because material3's row colour type is `CheckboxButtonColors`.
 *
 * v1 splits this into two independently animated roles, `boxColor` and `checkmarkColor`; see the file
 * header for why the checkmark half has no slot to reach the canvas.
 */
data class CheckboxColors(
    val checkedBoxColor: Color,
    val checkedCheckmarkColor: Color,
    val uncheckedBoxColor: Color,
    val disabledCheckedBoxColor: Color,
    val disabledUncheckedBoxColor: Color,
) {
    /**
     * The outline and the fill share one colour in upstream's `drawBox` (`ToggleControl.kt:81`, `:383-396`)
     * and here in the tick too, and which colour depends on the state: an unchecked box is the outline
     * token, not a faded primary.
     */
    fun controlColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedBoxColor else disabledUncheckedBoxColor
        checked -> checkedBoxColor
        else -> uncheckedBoxColor
    }
}

/**
 * The bare [Switch]'s colour set — v1's `SwitchColors` (`ToggleControl.kt:221`), free here because
 * material3's row colour type is `SwitchButtonColors`.
 *
 * v1's interface is two `@Composable (enabled, checked) -> State<Color>` roles over eight plain colours
 * (`DefaultSwitchColors`, `:421-460`); here they are eight fields and two plain functions, the same
 * collapse as at [CheckboxColors]. The role order is v1's (`enabled` first), which is also what
 * material3's rows use.
 */
data class SwitchColors(
    val checkedThumbColor: Color,
    val checkedTrackColor: Color,
    val uncheckedThumbColor: Color,
    val uncheckedTrackColor: Color,
    val disabledCheckedThumbColor: Color,
    val disabledCheckedTrackColor: Color,
    val disabledUncheckedThumbColor: Color,
    val disabledUncheckedTrackColor: Color,
) {
    fun thumbColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedThumbColor else disabledUncheckedThumbColor
        checked -> checkedThumbColor
        else -> uncheckedThumbColor
    }

    fun trackColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedTrackColor else disabledUncheckedTrackColor
        checked -> checkedTrackColor
        else -> uncheckedTrackColor
    }
}

/**
 * Colour set for the bare [RadioButton]. v1's names for it, `RadioButtonColors` (`ToggleControl.kt:243`)
 * and `RadioButtonDefaults` (`:341`), could not be adopted: both are material3's row types in
 * `RadioButton.kt:560` and `:320`, so this carries the `Bare` prefix.
 *
 * v1's two roles, `ringColor` and `dotColor`, are four-state each; here one colour drives ring and dot
 * (see the file header) and disabled unselected falls back to [disabledSelectedControlColor].
 */
data class BareRadioButtonColors(
    val selectedControlColor: Color,
    val unselectedControlColor: Color,
    val disabledSelectedControlColor: Color,
) {
    fun controlColor(enabled: Boolean, selected: Boolean): Color = when {
        !enabled -> disabledSelectedControlColor
        selected -> selectedControlColor
        else -> unselectedControlColor
    }
}

/**
 * Defaults for the bare [Checkbox] — v1's `CheckboxDefaults` (`ToggleControl.kt:264`), whose name is free
 * here.
 *
 * v1's `colors(...)` (`:276`) takes four optional colours and derives the four disabled roles with
 * `toDisabledColor()` (alpha 0.38, `material/ContentAlpha.kt:110`), resolving the enabled ones from v1's
 * `Colors` roles: `secondary` for the checked box and `contentColorFor(primary @ 0.5 alpha composited over
 * surface)` for the unchecked one (`:277-285`). This module ports material3's [ColorScheme] and has no v1
 * `Colors` object to read, so those three roles have no counterpart and the enabled colours come from the
 * row's material3 tokens instead; the factory is also no-arg, because its defaults would read
 * `MaterialTheme` and a `@Tunable` default cannot (see [Checkbox]). Callers who want v1's shape construct
 * [CheckboxColors] directly.
 */
object CheckboxDefaults {
    @Tunable
    fun colors(): CheckboxColors = with(MaterialTheme.colorScheme) {
        CheckboxColors(
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

/**
 * Defaults for the bare [Switch] — v1's `SwitchDefaults` (`ToggleControl.kt:301`), whose name is free here.
 *
 * The eight colours are v1's derivation chain (`:311-340`) rather than a token table: checked thumb
 * `Colors.secondary`, checked track that colour at `ContentAlpha.disabled`, unchecked thumb `onSurface`
 * at 0.6, unchecked track that at `0.6 * disabled`, and the disabled quartet through v1's
 * `toDisabledColor` (`material/Colors.kt:151-152`), which **sets** alpha — unlike this module's
 * material3-shaped [toDisabledColor], which multiplies it, so the writes are spelled out here.
 *
 * One substitution: v1 reads `Colors.secondary`, and this module ports material3's [ColorScheme] with no
 * v1 `Colors` object to read, so the checked thumb comes from `primary`. Callers who want v1's exact
 * ink construct [SwitchColors] directly. v1's four optional `colors(...)` parameters are not ported
 * either, because their defaults would read the theme and a `@Tunable` default cannot — see [Checkbox].
 */
object SwitchDefaults {
    @Tunable
    fun colors(): SwitchColors = with(MaterialTheme.colorScheme) {
        val checkedThumb = primary
        val checkedTrack = checkedThumb.copy(alpha = ColorScheme.DisabledContentAlpha)
        val uncheckedThumb = onSurface.copy(alpha = UncheckedThumbAlpha)
        val uncheckedTrack =
            uncheckedThumb.copy(alpha = uncheckedThumb.alpha * ColorScheme.DisabledContentAlpha)
        SwitchColors(
            checkedThumbColor = checkedThumb,
            checkedTrackColor = checkedTrack,
            uncheckedThumbColor = uncheckedThumb,
            uncheckedTrackColor = uncheckedTrack,
            disabledCheckedThumbColor = checkedThumb.copy(alpha = ColorScheme.DisabledContentAlpha),
            disabledCheckedTrackColor = checkedTrack.copy(
                alpha = checkedTrack.alpha * ColorScheme.DisabledContentAlpha
            ),
            disabledUncheckedThumbColor = uncheckedThumb.copy(
                alpha = uncheckedThumb.alpha * ColorScheme.DisabledContentAlpha
            ),
            disabledUncheckedTrackColor = uncheckedTrack.copy(
                alpha = uncheckedThumb.alpha * ColorScheme.DisabledContentAlpha
            ),
        )
    }

    /** v1's `uncheckedThumbColor = onSurface.copy(alpha = 0.6f)`, `ToggleControl.kt:314`. */
    private const val UncheckedThumbAlpha = 0.6f
}

/**
 * Defaults for the bare [RadioButton], keeping v1's `RadioButtonDefaults` name for material3's row in
 * `RadioButton.kt:320`; [RadioButton] reads this one.
 *
 * v1's `colors(...)` (`ToggleControl.kt:354`) takes the selected ring and dot from `Colors.secondary` and
 * the unselected pair from `contentColorFor(primary @ 0.5 alpha composited over surface)`; the same
 * missing-`Colors` substitution as at [CheckboxDefaults] applies.
 */
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
