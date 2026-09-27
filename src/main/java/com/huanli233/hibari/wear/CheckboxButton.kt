package com.huanli233.hibari.wear

import android.view.Gravity
import android.view.View
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.Column
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.foundation.Spacer
import com.huanli233.hibari.foundation.attributes.matchParentHeight
import com.huanli233.hibari.foundation.attributes.minHeight
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.TunationLocalProvider
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.ViewClassAttribute
import com.huanli233.hibari.ui.geometry.CornerBasedShape
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.thenViewAttributeIfNotNull
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.unit.toPx
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.CheckboxButtonTokens
import com.huanli233.hibari.wear.tokens.ShapeTokens
import com.huanli233.hibari.wear.tokens.SplitCheckboxButtonTokens
import com.huanli233.hibari.wear.view.WearCheckboxButtonRow
import com.huanli233.hibari.wear.view.WearCheckboxButtonView

/**
 * Ported from androidx.wear.compose.material3.CheckboxButton.kt: `CheckboxButton`,
 * `SplitCheckboxButton`, their `Defaults`, `CheckboxButtonColors` and `SplitCheckboxButtonColors`,
 * plus the private `Checkbox`/`drawBox`/`Labels` half of that file.
 *
 * Three kinds of deviation are recorded below and in the classes themselves: parameters that need a
 * Compose runtime (`interactionSource`, `transformation`, ripple), the accessibility/haptics bundle
 * (`Role`, `stateDescription`, `onClickLabel`, `LocalHapticFeedback`), and the colour animation
 * (`animateSelectionColor`), which is a plain resolve here. The colour *values* are all ported; only
 * their tweening is missing - `WearSwitchButtonView` in this module does drive a slow-spec
 * cross-fade, so this is unpicked work rather than an unavailable mechanism.
 *
 * Duplication note: the stadium row `CheckboxButton` drives through `materialcore.ToggleButton` is
 * re-declared here privately, because `RadioButton.kt` and `SwitchButton.kt` were ported in parallel
 * and a shared name would collide. (`material3` `RadioButton` does not call `materialcore`
 * `ToggleButton` either - it inlines its own `Row` - and nothing in `androidx/wear/compose` calls
 * `materialcore.SplitToggleButton`, so the split rows below are ported from the material3 bodies.)
 */

/** `TOGGLE_CONTROL_SPACING`, `ICON_SPACING` and `MIN_HEIGHT` of CheckboxButton.kt. */
private val CheckboxButtonToggleControlSpacing: Dp = 6.dp
private val CheckboxButtonIconSpacing: Dp = 6.dp
private val CheckboxButtonMinHeight: Dp = 52.dp

/** `CHECKBOX_WIDTH` / `CHECKBOX_HEIGHT`, the slot the control is measured into. */
private val CheckboxButtonControlSize: Dp = 24.dp

/** CheckboxButton.kt's `SPLIT_MIN_WIDTH` and the 2.dp `Spacer(modifier = Modifier.size(2.dp))`. */
private val SplitCheckboxButtonMinWidth: Dp = 48.dp
private val SplitCheckboxButtonSectionGap: Dp = 2.dp

/** `SPLIT_SECTIONS_SHAPE = ShapeTokens.CornerExtraSmall`. */
private val SplitCheckboxButtonSectionCorner: Dp =
    (ShapeTokens.CornerExtraSmall as CornerBasedShape).topStart

/**
 * The Wear Material `CheckboxButton` offers three slots and a specific layout for an icon, a label,
 * and a secondaryLabel. The icon and secondaryLabel are optional. The items are laid out in a row
 * with the optional icon at the start and a column containing the two label slots in the middle.
 *
 * The `CheckboxButton` is Stadium shaped. The label should take no more than 3 lines of text. The
 * secondary label should take no more than 2 lines of text. With localisation and/or large font
 * sizes, the `CheckboxButton` height adjusts to accommodate the contents. The label and secondary
 * label are start aligned by default.
 *
 * A `CheckboxButton` can be enabled or disabled. A disabled button will not respond to click events.
 *
 * `interactionSource` and `transformation` are absent, and the `colors` default arrives as `null`
 * because a `@Tunable` default-parameter expression is hoisted out of the tunable context and cannot
 * read the theme: pass nothing to get [CheckboxButtonDefaults.checkboxButtonColors]. The whole row
 * is the tap area, as upstream's `Modifier.toggleable` on the row makes it; what is missing is the
 * ripple it draws, since this module has no indication layer - upstream's container colour does not
 * change on press either, so only the touch overlay is lost, not the rest state.
 *
 * @param checked Boolean flag indicating whether this button is currently checked.
 * @param onCheckedChange Callback to be invoked when this button's checked status is changed.
 * @param modifier Modifier to be applied to the `CheckboxButton`.
 * @param enabled Controls the enabled state of the button. When `false`, this button will not be
 *   clickable.
 * @param shape Defines the button's shape. It is strongly recommended to use the default as this
 *   shape is a key characteristic of the Wear Material Theme.
 * @param colors `CheckboxButtonColors` used to resolve the background and content colour for this
 *   button in different states, or `null` for the defaults.
 * @param contentPadding The spacing values to apply internally between the container and the content.
 * @param icon An optional slot for providing an icon to indicate the purpose of the button. The
 *   contents are expected to be a horizontally and vertically centre aligned icon of size 24.dp.
 * @param secondaryLabel A slot for providing the button's secondary label, expected to be no more
 *   than 2 lines of "start" aligned text.
 * @param label A slot for providing the button's main label, expected to be no more than 3 lines of
 *   "start" aligned text.
 */
@Tunable
fun CheckboxButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CheckboxButtonDefaults.checkboxButtonShape,
    colors: CheckboxButtonColors? = null,
    contentPadding: PaddingValues = CheckboxButtonDefaults.ContentPadding,
    icon: (@Tunable BoxScope.() -> Unit)? = null,
    secondaryLabel: (@Tunable RowScope.() -> Unit)? = null,
    label: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: CheckboxButtonDefaults.checkboxButtonColors()
    val iconScope = icon
    val typography = MaterialTheme.typography

    TunationLocalProvider(
        LocalContentColor provides resolved.contentColorFor(enabled, checked),
    ) {
        Row(
            modifier = modifier
                .checkboxButtonRow()
                .container(
                    ContainerSpec(
                        shape = shape,
                        containerColor = resolved.containerColorFor(enabled, checked),
                        disabledContainerColor = resolved.containerColorFor(false, checked),
                    ),
                )
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(contentPadding)
                .minHeight(CheckboxButtonMinHeight),
        ) {
            if (iconScope != null) {
                // `ToggleButtonIcon` is `Box(wrapContentSize(Center))`, and the frame is exactly as
                // wide as its content, so the extra alignment is a no-op and only the Box remains.
                provideContentColor(resolved.iconColorFor(enabled, checked)) {
                    Box(modifier = Modifier.gravity(Gravity.CENTER_VERTICAL)) { iconScope() }
                }
                Spacer(
                    Modifier
                        .size(DpSize(CheckboxButtonIconSpacing, CheckboxButtonIconSpacing))
                        .gravity(Gravity.CENTER_VERTICAL),
                )
            }
            CheckboxButtonLabels(
                labelStyle = typography.fromToken(CheckboxButtonTokens.LabelFont),
                secondaryLabelStyle = typography.fromToken(CheckboxButtonTokens.SecondaryLabelFont),
                labelColor = resolved.contentColorFor(enabled, checked),
                secondaryLabelColor = resolved.secondaryContentColorFor(enabled, checked),
                labelSpacerSize = CheckboxButtonDefaults.LabelSpacerSize,
                label = label,
                secondaryLabel = secondaryLabel,
            )
            Spacer(
                Modifier
                    .size(DpSize(CheckboxButtonToggleControlSpacing, CheckboxButtonToggleControlSpacing))
                    .gravity(Gravity.CENTER_VERTICAL),
            )
            // `ToggleControl` frames the control in a Box of the control's own size, which is a
            // no-op around a 24.dp control in a 24.dp slot, so the size rides on the control.
            Node(
                modifier = Modifier
                    .viewClass(WearCheckboxButtonView::class.java)
                    .size(DpSize(CheckboxButtonControlSize, CheckboxButtonControlSize))
                    .gravity(Gravity.CENTER_VERTICAL)
                    .checkboxButtonControl(
                        checked = checked,
                        enabled = enabled,
                        boxColor = resolved.boxColorFor(enabled, checked),
                        checkmarkColor = resolved.checkmarkColorFor(enabled, checked),
                    ),
            )
        }
    }
}

/**
 * The Wear Material `SplitCheckboxButton` offers slots and a specific layout for a label and
 * secondaryLabel. The secondaryLabel is optional. The items are laid out with a column containing
 * the two label slots and a Checkbox at the end.
 *
 * The `SplitCheckboxButton` is Stadium shaped. The label should take no more than 3 lines of text.
 * The secondary label should take no more than 2 lines of text. With localisation and/or large font
 * sizes, the `SplitCheckboxButton` height adjusts to accommodate the contents. The label and
 * secondary label are start aligned by default.
 *
 * A `SplitCheckboxButton` has two tappable areas, one tap area for the labels and another for the
 * checkbox. The [onContainerClick] listener will be associated with the main body of the
 * `SplitCheckboxButton` and the [onCheckedChange] listener associated with the checkbox area only.
 *
 * For a `SplitCheckboxButton` the background of the tappable background area behind the toggle
 * control will have a visual effect applied to provide a "divider" between the two tappable areas.
 *
 * `toggleInteractionSource`, `containerInteractionSource`, `transformation` and
 * `containerClickLabel` are absent: the first two are Compose interaction hoisting, `transformation`
 * is not ported anywhere in this module, and the last is an accessibility click label.
 * `colors` arrives as `null` for the same hoisted-default reason as [CheckboxButton].
 *
 * Upstream draws each section clipped twice, to `SPLIT_SECTIONS_SHAPE` and then to the row's
 * container shape; a [ContainerSpec] paints one shape, so each section gets the container's radius
 * on its outer corners and the 4.dp `CornerExtraSmall` where the sections face each other, which is
 * that intersection. The row itself is not clip-bound, so slot content that overflowed the section
 * would not be trimmed the way `graphicsLayer { clip = true }` trims it. Upstream leaves the row
 * unpainted, so the 2.dp gap between the sections shows what is behind the button; this does too.
 *
 * @param checked Boolean flag indicating whether this button is currently checked.
 * @param onCheckedChange Callback to be invoked when this button's checked status is changed.
 * @param toggleContentDescription The content description for the checkbox control part of the
 *   component.
 * @param onContainerClick Click listener called when the user clicks the main body of the button,
 *   the area behind the labels.
 * @param modifier Modifier to be applied to the button.
 * @param enabled Controls the enabled state of the button. When `false`, this button will not be
 *   clickable.
 * @param shape Defines the button's shape. It is strongly recommended to use the default as this
 *   shape is a key characteristic of the Wear Material Theme.
 * @param colors `SplitCheckboxButtonColors` used to resolve the background and content colour for
 *   this button in different states, or `null` for the defaults.
 * @param contentPadding The spacing values to apply internally between the container and the content.
 * @param secondaryLabel A slot for providing the button's secondary label, expected to be "start"
 *   aligned.
 * @param label A slot for providing the button's main label, expected to be "start" aligned.
 */
@Tunable
fun SplitCheckboxButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    toggleContentDescription: String?,
    onContainerClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CheckboxButtonDefaults.splitCheckboxButtonShape,
    colors: SplitCheckboxButtonColors? = null,
    contentPadding: PaddingValues = CheckboxButtonDefaults.ContentPadding,
    secondaryLabel: (@Tunable RowScope.() -> Unit)? = null,
    label: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: CheckboxButtonDefaults.splitCheckboxButtonColors()
    val typography = MaterialTheme.typography
    val containerColor = resolved.containerColorFor(enabled, checked)

    TunationLocalProvider(
        LocalContentColor provides resolved.contentColorFor(enabled, checked),
    ) {
        Row(
            modifier = modifier
                .checkboxButtonRow()
                .minHeight(CheckboxButtonMinHeight),
        ) {
            Row(
                modifier = Modifier
                    .checkboxButtonRow()
                    .weight(1f)
                    .matchParentHeight()
                    .container(
                        ContainerSpec(
                            shape = checkboxButtonSectionShape(shape, first = true),
                            containerColor = containerColor,
                            disabledContainerColor = resolved.containerColorFor(false, checked),
                        ),
                    )
                    .clickable(enabled = enabled, onClick = onContainerClick)
                    .padding(contentPadding)
                    .gravity(Gravity.CENTER_VERTICAL),
            ) {
                CheckboxButtonLabels(
                    labelStyle = typography.fromToken(SplitCheckboxButtonTokens.LabelFont),
                    secondaryLabelStyle = typography.fromToken(SplitCheckboxButtonTokens.SecondaryLabelFont),
                    labelColor = resolved.contentColorFor(enabled, checked),
                    secondaryLabelColor = resolved.secondaryContentColorFor(enabled, checked),
                    labelSpacerSize = CheckboxButtonDefaults.LabelSpacerSize,
                    label = label,
                    secondaryLabel = secondaryLabel,
                )
            }

            Spacer(
                Modifier
                    .size(DpSize(SplitCheckboxButtonSectionGap, SplitCheckboxButtonSectionGap))
                    .gravity(Gravity.CENTER_VERTICAL),
            )

            Box(
                modifier = Modifier
                    .matchParentHeight()
                    .gravity(Gravity.CENTER_VERTICAL)
                    .checkboxButtonMinWidth(SplitCheckboxButtonMinWidth)
                    .container(
                        ContainerSpec(
                            shape = checkboxButtonSectionShape(shape, first = false),
                            containerColor = resolved.sectionColorFor(enabled, checked),
                            disabledContainerColor = resolved.sectionColorFor(false, checked),
                        ),
                    )
                    .clickable(enabled = enabled) { onCheckedChange(!checked) }
                    .padding(contentPadding)
                    .checkboxButtonContentDescription(toggleContentDescription),
            ) {
                Node(
                    modifier = Modifier
                        .viewClass(WearCheckboxButtonView::class.java)
                        .size(DpSize(CheckboxButtonControlSize, CheckboxButtonControlSize))
                        .gravity(Gravity.CENTER)
                        .checkboxButtonControl(
                            checked = checked,
                            enabled = enabled,
                            boxColor = resolved.boxColorFor(enabled, checked),
                            checkmarkColor = resolved.checkmarkColorFor(enabled, checked),
                        ),
                )
            }
        }
    }
}

/**
 * Represents the different container and content colours used for [CheckboxButton] in the checked,
 * unchecked, enabled and disabled states.
 *
 * Upstream's accessors are `@Composable internal fun ...Color(...): State<Color>` built from
 * `animateSelectionColor`; here the four-way pick is public and immediate, named `*ColorFor` so the
 * upstream accessor names stay free. The values are upstream's, the tween is not.
 */
class CheckboxButtonColors(
    val checkedContainerColor: Color,
    val checkedContentColor: Color,
    val checkedSecondaryContentColor: Color,
    val checkedIconColor: Color,
    val checkedBoxColor: Color,
    val checkedCheckmarkColor: Color,
    val uncheckedContainerColor: Color,
    val uncheckedContentColor: Color,
    val uncheckedSecondaryContentColor: Color,
    val uncheckedIconColor: Color,
    val uncheckedBoxColor: Color,
    val disabledCheckedContainerColor: Color,
    val disabledCheckedContentColor: Color,
    val disabledCheckedSecondaryContentColor: Color,
    val disabledCheckedIconColor: Color,
    val disabledCheckedBoxColor: Color,
    val disabledCheckedCheckmarkColor: Color,
    val disabledUncheckedContainerColor: Color,
    val disabledUncheckedContentColor: Color,
    val disabledUncheckedSecondaryContentColor: Color,
    val disabledUncheckedIconColor: Color,
    val disabledUncheckedBoxColor: Color,
) {

    /**
     * Returns a copy of this [CheckboxButtonColors], optionally overriding some of the values. An
     * [Color.Unspecified] override keeps the current value, as upstream's `takeOrElse` does.
     */
    fun copy(
        checkedContainerColor: Color = this.checkedContainerColor,
        checkedContentColor: Color = this.checkedContentColor,
        checkedSecondaryContentColor: Color = this.checkedSecondaryContentColor,
        checkedIconColor: Color = this.checkedIconColor,
        checkedBoxColor: Color = this.checkedBoxColor,
        checkedCheckmarkColor: Color = this.checkedCheckmarkColor,
        uncheckedContainerColor: Color = this.uncheckedContainerColor,
        uncheckedContentColor: Color = this.uncheckedContentColor,
        uncheckedSecondaryContentColor: Color = this.uncheckedSecondaryContentColor,
        uncheckedIconColor: Color = this.uncheckedIconColor,
        uncheckedBoxColor: Color = this.uncheckedBoxColor,
        disabledCheckedContainerColor: Color = this.disabledCheckedContainerColor,
        disabledCheckedContentColor: Color = this.disabledCheckedContentColor,
        disabledCheckedSecondaryContentColor: Color = this.disabledCheckedSecondaryContentColor,
        disabledCheckedIconColor: Color = this.disabledCheckedIconColor,
        disabledCheckedBoxColor: Color = this.disabledCheckedBoxColor,
        disabledCheckedCheckmarkColor: Color = this.disabledCheckedCheckmarkColor,
        disabledUncheckedContainerColor: Color = this.disabledUncheckedContainerColor,
        disabledUncheckedContentColor: Color = this.disabledUncheckedContentColor,
        disabledUncheckedSecondaryContentColor: Color = this.disabledUncheckedSecondaryContentColor,
        disabledUncheckedIconColor: Color = this.disabledUncheckedIconColor,
        disabledUncheckedBoxColor: Color = this.disabledUncheckedBoxColor,
    ): CheckboxButtonColors = CheckboxButtonColors(
        checkedContainerColor = checkedContainerColor.takeOrElse { this.checkedContainerColor },
        checkedContentColor = checkedContentColor.takeOrElse { this.checkedContentColor },
        checkedSecondaryContentColor =
            checkedSecondaryContentColor.takeOrElse { this.checkedSecondaryContentColor },
        checkedIconColor = checkedIconColor.takeOrElse { this.checkedIconColor },
        checkedBoxColor = checkedBoxColor.takeOrElse { this.checkedBoxColor },
        checkedCheckmarkColor = checkedCheckmarkColor.takeOrElse { this.checkedCheckmarkColor },
        uncheckedContainerColor = uncheckedContainerColor.takeOrElse { this.uncheckedContainerColor },
        uncheckedContentColor = uncheckedContentColor.takeOrElse { this.uncheckedContentColor },
        uncheckedSecondaryContentColor =
            uncheckedSecondaryContentColor.takeOrElse { this.uncheckedSecondaryContentColor },
        uncheckedIconColor = uncheckedIconColor.takeOrElse { this.uncheckedIconColor },
        uncheckedBoxColor = uncheckedBoxColor.takeOrElse { this.uncheckedBoxColor },
        disabledCheckedContainerColor =
            disabledCheckedContainerColor.takeOrElse { this.disabledCheckedContainerColor },
        disabledCheckedContentColor =
            disabledCheckedContentColor.takeOrElse { this.disabledCheckedContentColor },
        disabledCheckedSecondaryContentColor =
            disabledCheckedSecondaryContentColor.takeOrElse { this.disabledCheckedSecondaryContentColor },
        disabledCheckedIconColor = disabledCheckedIconColor.takeOrElse { this.disabledCheckedIconColor },
        disabledCheckedBoxColor = disabledCheckedBoxColor.takeOrElse { this.disabledCheckedBoxColor },
        disabledCheckedCheckmarkColor =
            disabledCheckedCheckmarkColor.takeOrElse { this.disabledCheckedCheckmarkColor },
        disabledUncheckedContainerColor =
            disabledUncheckedContainerColor.takeOrElse { this.disabledUncheckedContainerColor },
        disabledUncheckedContentColor =
            disabledUncheckedContentColor.takeOrElse { this.disabledUncheckedContentColor },
        disabledUncheckedSecondaryContentColor =
            disabledUncheckedSecondaryContentColor.takeOrElse { this.disabledUncheckedSecondaryContentColor },
        disabledUncheckedIconColor =
            disabledUncheckedIconColor.takeOrElse { this.disabledUncheckedIconColor },
        disabledUncheckedBoxColor =
            disabledUncheckedBoxColor.takeOrElse { this.disabledUncheckedBoxColor },
    )

    /** The container colour, from upstream's `containerColor(enabled, checked)`. */
    fun containerColorFor(enabled: Boolean, checked: Boolean): Color = when {
        enabled && checked -> checkedContainerColor
        enabled -> uncheckedContainerColor
        checked -> disabledCheckedContainerColor
        else -> disabledUncheckedContainerColor
    }

    /** The label colour, from upstream's `contentColor(enabled, checked)`. */
    fun contentColorFor(enabled: Boolean, checked: Boolean): Color = when {
        enabled && checked -> checkedContentColor
        enabled -> uncheckedContentColor
        checked -> disabledCheckedContentColor
        else -> disabledUncheckedContentColor
    }

    /** The secondary label colour, from upstream's `secondaryContentColor(enabled, checked)`. */
    fun secondaryContentColorFor(enabled: Boolean, checked: Boolean): Color = when {
        enabled && checked -> checkedSecondaryContentColor
        enabled -> uncheckedSecondaryContentColor
        checked -> disabledCheckedSecondaryContentColor
        else -> disabledUncheckedSecondaryContentColor
    }

    /** The icon colour, from upstream's `iconColor(enabled, checked)`. */
    fun iconColorFor(enabled: Boolean, checked: Boolean): Color = when {
        enabled && checked -> checkedIconColor
        enabled -> uncheckedIconColor
        checked -> disabledCheckedIconColor
        else -> disabledUncheckedIconColor
    }

    /** The box stroke and fill colour, from upstream's `boxColor(enabled, checked)`. */
    fun boxColorFor(enabled: Boolean, checked: Boolean): Color = when {
        enabled && checked -> checkedBoxColor
        enabled -> uncheckedBoxColor
        checked -> disabledCheckedBoxColor
        else -> disabledUncheckedBoxColor
    }

    /**
     * The checkmark colour, from upstream's `checkmarkColor(enabled, checked)`, whose unchecked and
     * disabled-unchecked arms are `Color.Transparent`.
     */
    fun checkmarkColorFor(enabled: Boolean, checked: Boolean): Color = when {
        checked && enabled -> checkedCheckmarkColor
        checked -> disabledCheckedCheckmarkColor
        else -> Color.Transparent
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (this::class != other::class) return false

        other as CheckboxButtonColors

        if (checkedContainerColor != other.checkedContainerColor) return false
        if (checkedContentColor != other.checkedContentColor) return false
        if (checkedSecondaryContentColor != other.checkedSecondaryContentColor) return false
        if (checkedIconColor != other.checkedIconColor) return false
        if (checkedBoxColor != other.checkedBoxColor) return false
        if (checkedCheckmarkColor != other.checkedCheckmarkColor) return false
        if (uncheckedContainerColor != other.uncheckedContainerColor) return false
        if (uncheckedContentColor != other.uncheckedContentColor) return false
        if (uncheckedSecondaryContentColor != other.uncheckedSecondaryContentColor) return false
        if (uncheckedIconColor != other.uncheckedIconColor) return false
        if (uncheckedBoxColor != other.uncheckedBoxColor) return false
        if (disabledCheckedContainerColor != other.disabledCheckedContainerColor) return false
        if (disabledCheckedContentColor != other.disabledCheckedContentColor) return false
        if (disabledCheckedSecondaryContentColor != other.disabledCheckedSecondaryContentColor)
            return false
        if (disabledCheckedIconColor != other.disabledCheckedIconColor) return false
        if (disabledCheckedBoxColor != other.disabledCheckedBoxColor) return false
        if (disabledCheckedCheckmarkColor != other.disabledCheckedCheckmarkColor) return false
        if (disabledUncheckedContainerColor != other.disabledUncheckedContainerColor) return false
        if (disabledUncheckedContentColor != other.disabledUncheckedContentColor) return false
        if (disabledUncheckedSecondaryContentColor != other.disabledUncheckedSecondaryContentColor)
            return false
        if (disabledUncheckedIconColor != other.disabledUncheckedIconColor) return false
        if (disabledUncheckedBoxColor != other.disabledUncheckedBoxColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = checkedContainerColor.hashCode()
        result = 31 * result + checkedContentColor.hashCode()
        result = 31 * result + checkedSecondaryContentColor.hashCode()
        result = 31 * result + checkedIconColor.hashCode()
        result = 31 * result + checkedBoxColor.hashCode()
        result = 31 * result + checkedCheckmarkColor.hashCode()
        result = 31 * result + uncheckedContainerColor.hashCode()
        result = 31 * result + uncheckedContentColor.hashCode()
        result = 31 * result + uncheckedSecondaryContentColor.hashCode()
        result = 31 * result + uncheckedIconColor.hashCode()
        result = 31 * result + uncheckedBoxColor.hashCode()
        result = 31 * result + disabledCheckedContainerColor.hashCode()
        result = 31 * result + disabledCheckedContentColor.hashCode()
        result = 31 * result + disabledCheckedSecondaryContentColor.hashCode()
        result = 31 * result + disabledCheckedIconColor.hashCode()
        result = 31 * result + disabledCheckedBoxColor.hashCode()
        result = 31 * result + disabledCheckedCheckmarkColor.hashCode()
        result = 31 * result + disabledUncheckedContainerColor.hashCode()
        result = 31 * result + disabledUncheckedContentColor.hashCode()
        result = 31 * result + disabledUncheckedSecondaryContentColor.hashCode()
        result = 31 * result + disabledUncheckedIconColor.hashCode()
        result = 31 * result + disabledUncheckedBoxColor.hashCode()
        return result
    }
}

/**
 * Represents the different colours used in [SplitCheckboxButton] in different states.
 *
 * Same shape as [CheckboxButtonColors]: the icon role is replaced by the split container role, which
 * is the "divider" the toggle section is painted with.
 */
class SplitCheckboxButtonColors(
    val checkedContainerColor: Color,
    val checkedContentColor: Color,
    val checkedSecondaryContentColor: Color,
    val checkedSplitContainerColor: Color,
    val checkedBoxColor: Color,
    val checkedCheckmarkColor: Color,
    val uncheckedContainerColor: Color,
    val uncheckedContentColor: Color,
    val uncheckedSecondaryContentColor: Color,
    val uncheckedSplitContainerColor: Color,
    val uncheckedBoxColor: Color,
    val disabledCheckedContainerColor: Color,
    val disabledCheckedContentColor: Color,
    val disabledCheckedSecondaryContentColor: Color,
    val disabledCheckedSplitContainerColor: Color,
    val disabledCheckedBoxColor: Color,
    val disabledCheckedCheckmarkColor: Color,
    val disabledUncheckedContainerColor: Color,
    val disabledUncheckedContentColor: Color,
    val disabledUncheckedSecondaryContentColor: Color,
    val disabledUncheckedSplitContainerColor: Color,
    val disabledUncheckedBoxColor: Color,
) {

    /** Returns a copy, optionally overriding some of the values; see [CheckboxButtonColors.copy]. */
    fun copy(
        checkedContainerColor: Color = this.checkedContainerColor,
        checkedContentColor: Color = this.checkedContentColor,
        checkedSecondaryContentColor: Color = this.checkedSecondaryContentColor,
        checkedSplitContainerColor: Color = this.checkedSplitContainerColor,
        checkedBoxColor: Color = this.checkedBoxColor,
        checkedCheckmarkColor: Color = this.checkedCheckmarkColor,
        uncheckedContainerColor: Color = this.uncheckedContainerColor,
        uncheckedContentColor: Color = this.uncheckedContentColor,
        uncheckedSecondaryContentColor: Color = this.uncheckedSecondaryContentColor,
        uncheckedSplitContainerColor: Color = this.uncheckedSplitContainerColor,
        uncheckedBoxColor: Color = this.uncheckedBoxColor,
        disabledCheckedContainerColor: Color = this.disabledCheckedContainerColor,
        disabledCheckedContentColor: Color = this.disabledCheckedContentColor,
        disabledCheckedSecondaryContentColor: Color = this.disabledCheckedSecondaryContentColor,
        disabledCheckedSplitContainerColor: Color = this.disabledCheckedSplitContainerColor,
        disabledCheckedBoxColor: Color = this.disabledCheckedBoxColor,
        disabledCheckedCheckmarkColor: Color = this.disabledCheckedCheckmarkColor,
        disabledUncheckedContainerColor: Color = this.disabledUncheckedContainerColor,
        disabledUncheckedContentColor: Color = this.disabledUncheckedContentColor,
        disabledUncheckedSecondaryContentColor: Color = this.disabledUncheckedSecondaryContentColor,
        disabledUncheckedSplitContainerColor: Color = this.disabledUncheckedSplitContainerColor,
        disabledUncheckedBoxColor: Color = this.disabledUncheckedBoxColor,
    ): SplitCheckboxButtonColors = SplitCheckboxButtonColors(
        checkedContainerColor = checkedContainerColor.takeOrElse { this.checkedContainerColor },
        checkedContentColor = checkedContentColor.takeOrElse { this.checkedContentColor },
        checkedSecondaryContentColor =
            checkedSecondaryContentColor.takeOrElse { this.checkedSecondaryContentColor },
        checkedSplitContainerColor =
            checkedSplitContainerColor.takeOrElse { this.checkedSplitContainerColor },
        checkedBoxColor = checkedBoxColor.takeOrElse { this.checkedBoxColor },
        checkedCheckmarkColor = checkedCheckmarkColor.takeOrElse { this.checkedCheckmarkColor },
        uncheckedContainerColor = uncheckedContainerColor.takeOrElse { this.uncheckedContainerColor },
        uncheckedContentColor = uncheckedContentColor.takeOrElse { this.uncheckedContentColor },
        uncheckedSecondaryContentColor =
            uncheckedSecondaryContentColor.takeOrElse { this.uncheckedSecondaryContentColor },
        uncheckedSplitContainerColor =
            uncheckedSplitContainerColor.takeOrElse { this.uncheckedSplitContainerColor },
        uncheckedBoxColor = uncheckedBoxColor.takeOrElse { this.uncheckedBoxColor },
        disabledCheckedContainerColor =
            disabledCheckedContainerColor.takeOrElse { this.disabledCheckedContainerColor },
        disabledCheckedContentColor =
            disabledCheckedContentColor.takeOrElse { this.disabledCheckedContentColor },
        disabledCheckedSecondaryContentColor =
            disabledCheckedSecondaryContentColor.takeOrElse { this.disabledCheckedSecondaryContentColor },
        disabledCheckedSplitContainerColor =
            disabledCheckedSplitContainerColor.takeOrElse { this.disabledCheckedSplitContainerColor },
        disabledCheckedBoxColor = disabledCheckedBoxColor.takeOrElse { this.disabledCheckedBoxColor },
        disabledCheckedCheckmarkColor =
            disabledCheckedCheckmarkColor.takeOrElse { this.disabledCheckedCheckmarkColor },
        disabledUncheckedContainerColor =
            disabledUncheckedContainerColor.takeOrElse { this.disabledUncheckedContainerColor },
        disabledUncheckedContentColor =
            disabledUncheckedContentColor.takeOrElse { this.disabledUncheckedContentColor },
        disabledUncheckedSecondaryContentColor =
            disabledUncheckedSecondaryContentColor.takeOrElse { this.disabledUncheckedSecondaryContentColor },
        disabledUncheckedSplitContainerColor =
            disabledUncheckedSplitContainerColor.takeOrElse { this.disabledUncheckedSplitContainerColor },
        disabledUncheckedBoxColor =
            disabledUncheckedBoxColor.takeOrElse { this.disabledUncheckedBoxColor },
    )

    /** The label-section colour, from upstream's `containerColor(enabled, checked)`. */
    fun containerColorFor(enabled: Boolean, checked: Boolean): Color = when {
        enabled && checked -> checkedContainerColor
        enabled -> uncheckedContainerColor
        checked -> disabledCheckedContainerColor
        else -> disabledUncheckedContainerColor
    }

    /** The label colour, from upstream's `contentColor(enabled, checked)`. */
    fun contentColorFor(enabled: Boolean, checked: Boolean): Color = when {
        enabled && checked -> checkedContentColor
        enabled -> uncheckedContentColor
        checked -> disabledCheckedContentColor
        else -> disabledUncheckedContentColor
    }

    /** The secondary label colour, from upstream's `secondaryContentColor(enabled, checked)`. */
    fun secondaryContentColorFor(enabled: Boolean, checked: Boolean): Color = when {
        enabled && checked -> checkedSecondaryContentColor
        enabled -> uncheckedSecondaryContentColor
        checked -> disabledCheckedSecondaryContentColor
        else -> disabledUncheckedSecondaryContentColor
    }

    /** The raw split overlay, from upstream's `splitContainerColor(enabled, checked)`. */
    fun splitContainerColorFor(enabled: Boolean, checked: Boolean): Color = when {
        enabled && checked -> checkedSplitContainerColor
        enabled -> uncheckedSplitContainerColor
        checked -> disabledCheckedSplitContainerColor
        else -> disabledUncheckedSplitContainerColor
    }

    /** The box stroke and fill colour, from upstream's `boxColor(enabled, checked)`. */
    fun boxColorFor(enabled: Boolean, checked: Boolean): Color = when {
        enabled && checked -> checkedBoxColor
        enabled -> uncheckedBoxColor
        checked -> disabledCheckedBoxColor
        else -> disabledUncheckedBoxColor
    }

    /** The checkmark colour; transparent unless checked, as upstream's resolver is. */
    fun checkmarkColorFor(enabled: Boolean, checked: Boolean): Color = when {
        checked && enabled -> checkedCheckmarkColor
        checked -> disabledCheckedCheckmarkColor
        else -> Color.Transparent
    }

    /**
     * The colour the toggle section is actually painted: upstream composites the split overlay over
     * the container colour, or over `Color.Black` when the button is disabled.
     */
    fun sectionColorFor(enabled: Boolean, checked: Boolean): Color =
        splitContainerColorFor(enabled, checked).compositeOver(
            if (enabled) containerColorFor(enabled, checked) else Color.Black,
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (this::class != other::class) return false

        other as SplitCheckboxButtonColors

        if (checkedContainerColor != other.checkedContainerColor) return false
        if (checkedContentColor != other.checkedContentColor) return false
        if (checkedSecondaryContentColor != other.checkedSecondaryContentColor) return false
        if (checkedSplitContainerColor != other.checkedSplitContainerColor) return false
        if (checkedBoxColor != other.checkedBoxColor) return false
        if (checkedCheckmarkColor != other.checkedCheckmarkColor) return false
        if (uncheckedContainerColor != other.uncheckedContainerColor) return false
        if (uncheckedContentColor != other.uncheckedContentColor) return false
        if (uncheckedSecondaryContentColor != other.uncheckedSecondaryContentColor) return false
        if (uncheckedSplitContainerColor != other.uncheckedSplitContainerColor) return false
        if (uncheckedBoxColor != other.uncheckedBoxColor) return false
        if (disabledCheckedContainerColor != other.disabledCheckedContainerColor) return false
        if (disabledCheckedContentColor != other.disabledCheckedContentColor) return false
        if (disabledCheckedSecondaryContentColor != other.disabledCheckedSecondaryContentColor)
            return false
        if (disabledCheckedSplitContainerColor != other.disabledCheckedSplitContainerColor)
            return false
        if (disabledCheckedBoxColor != other.disabledCheckedBoxColor) return false
        if (disabledCheckedCheckmarkColor != other.disabledCheckedCheckmarkColor) return false
        if (disabledUncheckedContainerColor != other.disabledUncheckedContainerColor) return false
        if (disabledUncheckedContentColor != other.disabledUncheckedContentColor) return false
        if (disabledUncheckedSecondaryContentColor != other.disabledUncheckedSecondaryContentColor)
            return false
        if (disabledUncheckedSplitContainerColor != other.disabledUncheckedSplitContainerColor)
            return false
        if (disabledUncheckedBoxColor != other.disabledUncheckedBoxColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = checkedContainerColor.hashCode()
        result = 31 * result + checkedContentColor.hashCode()
        result = 31 * result + checkedSecondaryContentColor.hashCode()
        result = 31 * result + checkedSplitContainerColor.hashCode()
        result = 31 * result + checkedBoxColor.hashCode()
        result = 31 * result + checkedCheckmarkColor.hashCode()
        result = 31 * result + uncheckedContainerColor.hashCode()
        result = 31 * result + uncheckedContentColor.hashCode()
        result = 31 * result + uncheckedSecondaryContentColor.hashCode()
        result = 31 * result + uncheckedSplitContainerColor.hashCode()
        result = 31 * result + uncheckedBoxColor.hashCode()
        result = 31 * result + disabledCheckedContainerColor.hashCode()
        result = 31 * result + disabledCheckedContentColor.hashCode()
        result = 31 * result + disabledCheckedSecondaryContentColor.hashCode()
        result = 31 * result + disabledCheckedSplitContainerColor.hashCode()
        result = 31 * result + disabledCheckedBoxColor.hashCode()
        result = 31 * result + disabledCheckedCheckmarkColor.hashCode()
        result = 31 * result + disabledUncheckedContainerColor.hashCode()
        result = 31 * result + disabledUncheckedContentColor.hashCode()
        result = 31 * result + disabledUncheckedSecondaryContentColor.hashCode()
        result = 31 * result + disabledUncheckedSplitContainerColor.hashCode()
        result = 31 * result + disabledUncheckedBoxColor.hashCode()
        return result
    }
}

/**
 * The recommended values used by [CheckboxButton] and [SplitCheckboxButton]: upstream material3's
 * `CheckboxButtonDefaults`, name and members included.
 *
 * The bare `Checkbox` of `SelectionControls.kt` is a different component with its own
 * [BareCheckboxDefaults] - material3 publishes no bare checkbox to name it after, only a
 * `private fun Checkbox` drawing primitive inside its own `CheckboxButton.kt`.
 *
 * Members are upstream's; the two `Dp`/`PaddingValues` constants are declared ahead of the colour
 * factories here, where upstream lists them after, because an `object` initialises in declaration
 * order and [ContentPadding] reads the paddings below it.
 */
object CheckboxButtonDefaults {

    /**
     * Recommended `Shape` for [CheckboxButton]: `CheckboxButtonTokens.ContainerShape`, i.e.
     * `ShapeKeyTokens.CornerLarge`. Upstream resolves it through `MaterialTheme.shapes` in a
     * `@Composable get`; it is a static token here because a `@Tunable` default-parameter expression
     * is hoisted out of the tunable context and cannot read the theme.
     */
    val checkboxButtonShape: Shape = ShapeTokens.CornerLarge

    /** Recommended `Shape` for [SplitCheckboxButton]: `SplitCheckboxButtonTokens.ContainerShape`. */
    val splitCheckboxButtonShape: Shape = ShapeTokens.CornerLarge

    /** `CheckboxButtonDefaults.LabelSpacerSize`, the gap between the two label rows. */
    internal val LabelSpacerSize: Dp = 1.dp

    private val HorizontalPadding: Dp = 14.dp
    private val VerticalPadding: Dp = 8.dp

    /** The default content padding used by [CheckboxButton] and [SplitCheckboxButton]. */
    val ContentPadding: PaddingValues = PaddingValues(
        start = HorizontalPadding,
        top = VerticalPadding,
        end = HorizontalPadding,
        bottom = VerticalPadding,
    )

    /** Creates a [CheckboxButtonColors] for use in a [CheckboxButton]. */
    @Tunable
    fun checkboxButtonColors(): CheckboxButtonColors = defaultCheckboxButtonColors()

    /**
     * Creates a [CheckboxButtonColors] for use in a [CheckboxButton], overriding single roles.
     *
     * The parameter list is upstream's; each default is `Color.Unspecified`, which leaves the
     * theme-derived value in place.
     */
    @Tunable
    fun checkboxButtonColors(
        checkedContainerColor: Color = Color.Unspecified,
        checkedContentColor: Color = Color.Unspecified,
        checkedSecondaryContentColor: Color = Color.Unspecified,
        checkedIconColor: Color = Color.Unspecified,
        checkedBoxColor: Color = Color.Unspecified,
        checkedCheckmarkColor: Color = Color.Unspecified,
        uncheckedContainerColor: Color = Color.Unspecified,
        uncheckedContentColor: Color = Color.Unspecified,
        uncheckedSecondaryContentColor: Color = Color.Unspecified,
        uncheckedIconColor: Color = Color.Unspecified,
        uncheckedBoxColor: Color = Color.Unspecified,
        disabledCheckedContainerColor: Color = Color.Unspecified,
        disabledCheckedContentColor: Color = Color.Unspecified,
        disabledCheckedSecondaryContentColor: Color = Color.Unspecified,
        disabledCheckedIconColor: Color = Color.Unspecified,
        disabledCheckedBoxColor: Color = Color.Unspecified,
        disabledCheckedCheckmarkColor: Color = Color.Unspecified,
        disabledUncheckedContainerColor: Color = Color.Unspecified,
        disabledUncheckedContentColor: Color = Color.Unspecified,
        disabledUncheckedSecondaryContentColor: Color = Color.Unspecified,
        disabledUncheckedIconColor: Color = Color.Unspecified,
        disabledUncheckedBoxColor: Color = Color.Unspecified,
    ): CheckboxButtonColors = defaultCheckboxButtonColors().copy(
        checkedContainerColor = checkedContainerColor,
        checkedContentColor = checkedContentColor,
        checkedSecondaryContentColor = checkedSecondaryContentColor,
        checkedIconColor = checkedIconColor,
        checkedBoxColor = checkedBoxColor,
        checkedCheckmarkColor = checkedCheckmarkColor,
        uncheckedContainerColor = uncheckedContainerColor,
        uncheckedContentColor = uncheckedContentColor,
        uncheckedSecondaryContentColor = uncheckedSecondaryContentColor,
        uncheckedIconColor = uncheckedIconColor,
        uncheckedBoxColor = uncheckedBoxColor,
        disabledCheckedContainerColor = disabledCheckedContainerColor,
        disabledCheckedContentColor = disabledCheckedContentColor,
        disabledCheckedSecondaryContentColor = disabledCheckedSecondaryContentColor,
        disabledCheckedIconColor = disabledCheckedIconColor,
        disabledCheckedBoxColor = disabledCheckedBoxColor,
        disabledCheckedCheckmarkColor = disabledCheckedCheckmarkColor,
        disabledUncheckedContainerColor = disabledUncheckedContainerColor,
        disabledUncheckedContentColor = disabledUncheckedContentColor,
        disabledUncheckedSecondaryContentColor = disabledUncheckedSecondaryContentColor,
        disabledUncheckedIconColor = disabledUncheckedIconColor,
        disabledUncheckedBoxColor = disabledUncheckedBoxColor,
    )

    /** Creates a [SplitCheckboxButtonColors] for use in a [SplitCheckboxButton]. */
    @Tunable
    fun splitCheckboxButtonColors(): SplitCheckboxButtonColors = defaultSplitCheckboxButtonColors()

    /** Creates a [SplitCheckboxButtonColors], overriding single roles; see [checkboxButtonColors]. */
    @Tunable
    fun splitCheckboxButtonColors(
        checkedContainerColor: Color = Color.Unspecified,
        checkedContentColor: Color = Color.Unspecified,
        checkedSecondaryContentColor: Color = Color.Unspecified,
        checkedSplitContainerColor: Color = Color.Unspecified,
        checkedBoxColor: Color = Color.Unspecified,
        checkedCheckmarkColor: Color = Color.Unspecified,
        uncheckedContainerColor: Color = Color.Unspecified,
        uncheckedContentColor: Color = Color.Unspecified,
        uncheckedSecondaryContentColor: Color = Color.Unspecified,
        uncheckedSplitContainerColor: Color = Color.Unspecified,
        uncheckedBoxColor: Color = Color.Unspecified,
        disabledCheckedContainerColor: Color = Color.Unspecified,
        disabledCheckedContentColor: Color = Color.Unspecified,
        disabledCheckedSecondaryContentColor: Color = Color.Unspecified,
        disabledCheckedSplitContainerColor: Color = Color.Unspecified,
        disabledCheckedBoxColor: Color = Color.Unspecified,
        disabledCheckedCheckmarkColor: Color = Color.Unspecified,
        disabledUncheckedContainerColor: Color = Color.Unspecified,
        disabledUncheckedContentColor: Color = Color.Unspecified,
        disabledUncheckedSecondaryContentColor: Color = Color.Unspecified,
        disabledUncheckedSplitContainerColor: Color = Color.Unspecified,
        disabledUncheckedBoxColor: Color = Color.Unspecified,
    ): SplitCheckboxButtonColors = defaultSplitCheckboxButtonColors().copy(
        checkedContainerColor = checkedContainerColor,
        checkedContentColor = checkedContentColor,
        checkedSecondaryContentColor = checkedSecondaryContentColor,
        checkedSplitContainerColor = checkedSplitContainerColor,
        checkedBoxColor = checkedBoxColor,
        checkedCheckmarkColor = checkedCheckmarkColor,
        uncheckedContainerColor = uncheckedContainerColor,
        uncheckedContentColor = uncheckedContentColor,
        uncheckedSecondaryContentColor = uncheckedSecondaryContentColor,
        uncheckedSplitContainerColor = uncheckedSplitContainerColor,
        uncheckedBoxColor = uncheckedBoxColor,
        disabledCheckedContainerColor = disabledCheckedContainerColor,
        disabledCheckedContentColor = disabledCheckedContentColor,
        disabledCheckedSecondaryContentColor = disabledCheckedSecondaryContentColor,
        disabledCheckedSplitContainerColor = disabledCheckedSplitContainerColor,
        disabledCheckedBoxColor = disabledCheckedBoxColor,
        disabledCheckedCheckmarkColor = disabledCheckedCheckmarkColor,
        disabledUncheckedContainerColor = disabledUncheckedContainerColor,
        disabledUncheckedContentColor = disabledUncheckedContentColor,
        disabledUncheckedSecondaryContentColor = disabledUncheckedSecondaryContentColor,
        disabledUncheckedSplitContainerColor = disabledUncheckedSplitContainerColor,
        disabledUncheckedBoxColor = disabledUncheckedBoxColor,
    )
}

/**
 * `CheckboxButtonDefaults.defaultCheckboxButtonColors`, with upstream's cached slot dropped the way
 * `ColorScheme`'s were: there is no composition to cache against, and [toDisabledColor] and
 * [copy] are applied to the same tokens in the same order.
 */
@Tunable
private fun defaultCheckboxButtonColors(): CheckboxButtonColors = with(MaterialTheme.colorScheme) {
    CheckboxButtonColors(
        checkedContainerColor = CheckboxButtonTokens.CheckedContainerColor.resolve(this),
        checkedContentColor = CheckboxButtonTokens.CheckedContentColor.resolve(this),
        checkedSecondaryContentColor = CheckboxButtonTokens.CheckedSecondaryLabelColor.resolve(this)
            .copy(alpha = CheckboxButtonTokens.CheckedSecondaryLabelOpacity),
        checkedIconColor = CheckboxButtonTokens.CheckedIconColor.resolve(this),
        checkedBoxColor = CheckboxButtonTokens.CheckedBoxColor.resolve(this),
        checkedCheckmarkColor = CheckboxButtonTokens.CheckedCheckmarkColor.resolve(this),
        uncheckedContainerColor = CheckboxButtonTokens.UncheckedContainerColor.resolve(this),
        uncheckedContentColor = CheckboxButtonTokens.UncheckedContentColor.resolve(this),
        uncheckedSecondaryContentColor = CheckboxButtonTokens.UncheckedSecondaryLabelColor.resolve(this),
        uncheckedIconColor = CheckboxButtonTokens.UncheckedIconColor.resolve(this),
        uncheckedBoxColor = CheckboxButtonTokens.UncheckedBoxColor.resolve(this),
        disabledCheckedContainerColor = CheckboxButtonTokens.DisabledCheckedContainerColor.resolve(this)
            .toDisabledColor(CheckboxButtonTokens.DisabledCheckedContainerOpacity),
        disabledCheckedContentColor = CheckboxButtonTokens.DisabledCheckedContentColor.resolve(this)
            .toDisabledColor(CheckboxButtonTokens.DisabledOpacity),
        disabledCheckedSecondaryContentColor =
        CheckboxButtonTokens.DisabledCheckedSecondaryLabelColor.resolve(this)
            .toDisabledColor(CheckboxButtonTokens.DisabledOpacity),
        disabledCheckedIconColor = CheckboxButtonTokens.DisabledCheckedIconColor.resolve(this)
            .toDisabledColor(CheckboxButtonTokens.DisabledOpacity),
        disabledCheckedBoxColor = CheckboxButtonTokens.DisabledCheckedBoxColor.resolve(this)
            .toDisabledColor(CheckboxButtonTokens.DisabledCheckedBoxOpacity),
        disabledCheckedCheckmarkColor =
        CheckboxButtonTokens.DisabledCheckedCheckmarkColor.resolve(this)
            .toDisabledColor(CheckboxButtonTokens.DisabledCheckedCheckmarkOpacity),
        disabledUncheckedContainerColor =
        CheckboxButtonTokens.DisabledUncheckedContainerColor.resolve(this)
            .toDisabledColor(CheckboxButtonTokens.DisabledUncheckedContainerOpacity),
        disabledUncheckedContentColor = CheckboxButtonTokens.DisabledUncheckedContentColor.resolve(this)
            .toDisabledColor(CheckboxButtonTokens.DisabledOpacity),
        disabledUncheckedSecondaryContentColor =
        CheckboxButtonTokens.DisabledUncheckedSecondaryLabelColor.resolve(this)
            .toDisabledColor(CheckboxButtonTokens.DisabledOpacity),
        disabledUncheckedIconColor = CheckboxButtonTokens.DisabledUncheckedIconColor.resolve(this)
            .toDisabledColor(CheckboxButtonTokens.DisabledOpacity),
        disabledUncheckedBoxColor = CheckboxButtonTokens.DisabledUncheckedBoxColor.resolve(this)
            .toDisabledColor(CheckboxButtonTokens.DisabledUncheckedBoxOpacity),
    )
}

/** `CheckboxButtonDefaults.defaultSplitCheckboxButtonColors`, token for token. */
@Tunable
private fun defaultSplitCheckboxButtonColors(): SplitCheckboxButtonColors =
    with(MaterialTheme.colorScheme) {
        SplitCheckboxButtonColors(
            checkedContainerColor = SplitCheckboxButtonTokens.CheckedContainerColor.resolve(this),
            checkedContentColor = SplitCheckboxButtonTokens.CheckedContentColor.resolve(this),
            checkedSecondaryContentColor =
            SplitCheckboxButtonTokens.CheckedSecondaryLabelColor.resolve(this)
                .copy(alpha = SplitCheckboxButtonTokens.CheckedSecondaryLabelOpacity),
            checkedSplitContainerColor =
            SplitCheckboxButtonTokens.CheckedSplitContainerColor.resolve(this)
                .copy(alpha = SplitCheckboxButtonTokens.CheckedSplitContainerOpacity),
            checkedBoxColor = SplitCheckboxButtonTokens.CheckedBoxColor.resolve(this),
            checkedCheckmarkColor = SplitCheckboxButtonTokens.CheckedCheckmarkColor.resolve(this),
            uncheckedContainerColor = SplitCheckboxButtonTokens.UncheckedContainerColor.resolve(this),
            uncheckedContentColor = SplitCheckboxButtonTokens.UncheckedContentColor.resolve(this),
            uncheckedSecondaryContentColor =
            SplitCheckboxButtonTokens.UncheckedSecondaryLabelColor.resolve(this),
            uncheckedSplitContainerColor =
            SplitCheckboxButtonTokens.UncheckedSplitContainerColor.resolve(this),
            uncheckedBoxColor = SplitCheckboxButtonTokens.UncheckedBoxColor.resolve(this),
            // Upstream copies the alpha here rather than multiplying it, unlike its neighbours;
            // kept as written.
            disabledCheckedContainerColor =
            SplitCheckboxButtonTokens.DisabledCheckedContainerColor.resolve(this)
                .copy(alpha = SplitCheckboxButtonTokens.DisabledCheckedContainerOpacity),
            disabledCheckedContentColor =
            SplitCheckboxButtonTokens.DisabledCheckedContentColor.resolve(this)
                .toDisabledColor(SplitCheckboxButtonTokens.DisabledOpacity),
            disabledCheckedSecondaryContentColor =
            SplitCheckboxButtonTokens.DisabledCheckedSecondaryLabelColor.resolve(this)
                .toDisabledColor(SplitCheckboxButtonTokens.DisabledOpacity),
            disabledCheckedSplitContainerColor =
            SplitCheckboxButtonTokens.DisabledCheckedSplitContainerColor.resolve(this)
                .copy(alpha = SplitCheckboxButtonTokens.DisabledCheckedSplitContainerOpacity),
            disabledCheckedBoxColor = SplitCheckboxButtonTokens.DisabledCheckedBoxColor.resolve(this)
                .toDisabledColor(SplitCheckboxButtonTokens.DisabledCheckedBoxOpacity),
            disabledCheckedCheckmarkColor =
            SplitCheckboxButtonTokens.DisabledCheckedCheckmarkColor.resolve(this)
                .toDisabledColor(SplitCheckboxButtonTokens.DisabledCheckedCheckmarkOpacity),
            disabledUncheckedContainerColor =
            SplitCheckboxButtonTokens.DisabledUncheckedContainerColor.resolve(this)
                .copy(alpha = SplitCheckboxButtonTokens.DisabledUncheckedContainerOpacity),
            disabledUncheckedContentColor =
            SplitCheckboxButtonTokens.DisabledUncheckedContentColor.resolve(this)
                .toDisabledColor(SplitCheckboxButtonTokens.DisabledOpacity),
            disabledUncheckedSecondaryContentColor =
            SplitCheckboxButtonTokens.DisabledUncheckedSecondaryLabelColor.resolve(this)
                .toDisabledColor(SplitCheckboxButtonTokens.DisabledOpacity),
            disabledUncheckedSplitContainerColor =
            SplitCheckboxButtonTokens.DisabledUncheckedSplitContainerColor.resolve(this)
                .copy(alpha = SplitCheckboxButtonTokens.DisabledUncheckedSplitContainerOpacity),
            disabledUncheckedBoxColor =
            SplitCheckboxButtonTokens.DisabledUncheckedBoxColor.resolve(this)
                .toDisabledColor(SplitCheckboxButtonTokens.DisabledUncheckedBoxOpacity),
        )
    }

/**
 * The `RowScope.Labels` of CheckboxButton.kt: a weighted column holding the label row and, when the
 * slot is filled, the 1.dp spacer and the secondary label row.
 *
 * Upstream also injects a `TextConfiguration` (ellipsis, 3 lines for the label, 2 for the secondary)
 * that `Text` reads from a composition local. Hibari has no such local, so the line limits stay the
 * caller's business: the slots here only receive the colour and the type scale role.
 */
@Tunable
private fun RowScope.CheckboxButtonLabels(
    labelStyle: TextStyle,
    secondaryLabelStyle: TextStyle,
    labelColor: Color,
    secondaryLabelColor: Color,
    labelSpacerSize: Dp,
    label: @Tunable RowScope.() -> Unit,
    secondaryLabel: (@Tunable RowScope.() -> Unit)?,
) {
    val labelScope = label
    val secondaryScope = secondaryLabel
    Column(modifier = Modifier.weight(1f)) {
        TunationLocalProvider(
            LocalContentColor provides labelColor,
            LocalTextStyle provides labelStyle,
        ) { Row { labelScope() } }
        if (secondaryScope != null) {
            Spacer(Modifier.size(DpSize(labelSpacerSize, labelSpacerSize)))
            TunationLocalProvider(
                LocalContentColor provides secondaryLabelColor,
                LocalTextStyle provides secondaryLabelStyle,
            ) { Row { secondaryScope() } }
        }
    }
}

/**
 * Swap the `LinearLayout` that `Row` builds for the row these buttons measure themselves; the
 * renderer reads the first `ViewClassAttribute` in the chain, and this one comes before `Row`'s.
 *
 * Every row of the component uses it, not just the outer one: `LinearLayout` measures a weighted
 * child of a horizontal row against the row's whole width, ignoring the siblings after it, so the
 * split button's label section needs the same shrink pass to keep the control section from being
 * pushed out of the row.
 */
private fun Modifier.checkboxButtonRow(): Modifier =
    this.then(ViewClassAttribute(WearCheckboxButtonRow::class.java))

/** The resolved state of the control, as one immutable value so a no-change retune diffs away. */
private data class CheckboxButtonControlState(
    val checked: Boolean,
    val enabled: Boolean,
    val boxColor: Color,
    val checkmarkColor: Color,
)

private fun Modifier.checkboxButtonControl(
    checked: Boolean,
    enabled: Boolean,
    boxColor: Color,
    checkmarkColor: Color,
): Modifier = this.thenViewAttribute<WearCheckboxButtonView, CheckboxButtonControlState>(
    uniqueKey,
    CheckboxButtonControlState(checked, enabled, boxColor, checkmarkColor),
) {
    setCheckboxState(it.checked, it.enabled, it.boxColor, it.checkmarkColor)
}

/** `defaultMinSize(minWidth = SPLIT_MIN_WIDTH)`; `View.minimumWidth` is the Views stand-in. */
private fun Modifier.checkboxButtonMinWidth(width: Dp): Modifier =
    this.thenViewAttribute<View, Dp>(uniqueKey, width) { minimumWidth = it.toPx(this) }

/** The `semantics { contentDescription = toggleContentDescription }` of the toggle section. */
private fun Modifier.checkboxButtonContentDescription(description: String?): Modifier =
    this.thenViewAttributeIfNotNull<View, String>(uniqueKey, description) {
        contentDescription = it
    }

/**
 * Upstream clips each section to `SPLIT_SECTIONS_SHAPE` and the row to the container shape, so a
 * section's silhouette is the intersection of the two: the container's radius on the corners that
 * face outwards, [SplitCheckboxButtonSectionCorner] on the ones that face the other section.
 */
private fun checkboxButtonSectionShape(container: Shape, first: Boolean): Shape = when (container) {
    is CornerBasedShape -> if (first) {
        CornerBasedShape(
            container.topStart,
            SplitCheckboxButtonSectionCorner,
            SplitCheckboxButtonSectionCorner,
            container.bottomStart,
        )
    } else {
        CornerBasedShape(
            SplitCheckboxButtonSectionCorner,
            container.topEnd,
            container.bottomEnd,
            SplitCheckboxButtonSectionCorner,
        )
    }

    else -> container
}
