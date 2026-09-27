package com.huanli233.hibari.wear

import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.Column
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.foundation.Spacer
import com.huanli233.hibari.foundation.attributes.height
import com.huanli233.hibari.foundation.attributes.minHeight
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.TunationLocalProvider
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.CornerBasedShape
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.RadioButtonTokens
import com.huanli233.hibari.wear.tokens.ShapeTokens
import com.huanli233.hibari.wear.tokens.SplitRadioButtonTokens
import com.huanli233.hibari.wear.view.WearRadioButtonView
import com.huanli233.hibari.wear.view.WearSelectionView

/**
 * Ported from androidx.wear.compose.material3.{RadioButton, SplitRadioButton}: the multi-slot rows,
 * their defaults, the four-state colour resolvers and the shared radio geometry.
 *
 * The upstream names are this file's: [RadioButtonColors] and [RadioButtonDefaults] are material3's,
 * while the bare radio of `SelectionControls.kt` — which material3 keeps as an
 * `internal fun RadioControl`, not API — carries `BareRadioButtonColors` /
 * `BareRadioButtonDefaults`. That file's `fun RadioButton` is still an overload of the row below at a
 * shorter arity, because upstream v1 names the bare control `RadioButton` too and this module has one
 * package rather than two.
 *
 * Dropped, and not silently:
 *  - `interactionSource` and `transformation`, for the same reasons Button.kt gives.
 *  - The `semantics` roles (`Role.RadioButton` on both rows, `Role.Button` on the split container)
 *    and the split section's `onClickLabel`: Hibari has no accessibility surface yet, so
 *    [SplitRadioButton]'s `containerClickLabel` is accepted and unused.
 *  - Upstream's `TextConfiguration` (label maxLines 3 + ellipsis, secondary label maxLines 2) rides
 *    `LocalTextConfiguration`, which Hibari's `Text` does not read. The label slots stay free-form, so
 *    pass `maxLines` / `overflow` to your own `Text`.
 *  - `animateSelectionColor`, the slow-spec colour animation behind every resolver here, is a plain
 *    resolve at retune time. [ContainerDrawable] still cross-fades the pressed/disabled variants of a
 *    container, but selecting a radio is instant.
 *
 * Kept against the odds: the ContextClick haptic upstream fires on selection, by way of
 * [radioSelectionClickable] rather than `LocalHapticFeedback`.
 *
 * Open sizing gap, not a documented stand-in: every row here is a plain `Row`, i.e. a
 * `wrap_content` `LinearLayout`, while upstream wraps these rows in `width(IntrinsicSize.Max)` and
 * then gives the labels column `weight(1f)`. A weighted child of a `wrap_content` `LinearLayout`
 * takes all the slack it is offered, so a short label still stretches the stadium across the parent
 * instead of hugging it. `CheckboxButton.kt` reproduces hug-and-shrink with its own row view; this
 * file keeps upstream's `weight(1f)` and waits for that row to be hoisted into a shared file rather
 * than copying it a second time.
 */

/**
 * The Wear Material `RadioButton`: an optional leading icon, a column of two label slots and the radio
 * control at the end, inside a stadium-shaped tappable row.
 *
 * @param selected Whether this button is currently selected.
 * @param onSelect Called when the row is clicked, after a ContextClick haptic.
 * @param modifier Applied to the row.
 * @param enabled When `false`, the row ignores clicks and the disabled colour set resolves.
 * @param shape The row's shape; the stadium default is a defining trait of the Wear theme.
 * @param colors The four-state colour set. `null` rather than upstream's default expression — a
 *   `@Tunable` function's defaults are hoisted out of the tunable context and cannot read the theme —
 *   and resolves [RadioButtonDefaults.radioButtonColors] in the body.
 * @param contentPadding Spacing between the container and the content.
 * @param icon Optional leading slot, expected to hold a 24.dp icon centered in itself.
 * @param secondaryLabel Optional second line of the label column.
 * @param label The main label.
 */
@Tunable
fun RadioButton(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RadioButtonDefaults.radioButtonShape,
    colors: RadioButtonColors? = null,
    contentPadding: PaddingValues = RadioButtonDefaults.ContentPadding,
    icon: (@Tunable BoxScope.() -> Unit)? = null,
    secondaryLabel: (@Tunable RowScope.() -> Unit)? = null,
    label: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: RadioButtonDefaults.radioButtonColors()
    val typography = MaterialTheme.typography
    val contentColor = resolved.contentColor(enabled = enabled, selected = selected)
    val iconSlot = icon
    val primaryLabel = label
    val optionalLabel = secondaryLabel

    provideContentColor(contentColor) {
        Row(
            modifier = modifier
                .minHeight(RadioButtonDefaults.MinHeight)
                .container(
                    ContainerSpec(
                        shape = shape,
                        containerColor = resolved.containerColor(
                            enabled = enabled,
                            selected = selected,
                        ),
                    )
                )
                .radioSelectionClickable(enabled, onSelect)
                .padding(contentPadding),
        ) {
            if (iconSlot != null) {
                Box(modifier = Modifier.gravity(Gravity.CENTER_VERTICAL)) {
                    provideContentColor(
                        resolved.iconColor(enabled = enabled, selected = selected)
                    ) {
                        iconSlot()
                    }
                }
                Spacer(
                    modifier = Modifier.size(
                        DpSize(
                            RadioButtonDefaults.IconSpacing,
                            RadioButtonDefaults.IconSpacing,
                        )
                    )
                )
            }
            radioLabels(
                modifier = Modifier.weight(1f),
                contentColor = contentColor,
                secondaryContentColor = resolved.secondaryContentColor(
                    enabled = enabled,
                    selected = selected,
                ),
                labelStyle = typography.fromToken(RadioButtonTokens.LabelFont),
                secondaryLabelStyle = typography.fromToken(RadioButtonTokens.SecondaryLabelFont),
                label = primaryLabel,
                secondaryLabel = optionalLabel,
            )
            Spacer(
                modifier = Modifier.size(
                    DpSize(
                        RadioButtonDefaults.SelectionControlSpacing,
                        RadioButtonDefaults.SelectionControlSpacing,
                    )
                )
            )
            // Upstream reserves a 32 x 24 slot for the 24 x 24 control and wraps it in
            // `wrapContentWidth(align = Alignment.End)`, so the control sits flush with the end of
            // the slot and the spare 8 dp opens up the label-to-control gap; `Gravity.END` on the
            // FrameLayout child reproduces that, and it flips with the layout direction as
            // `placeRelative` does.
            Box(
                modifier = Modifier.size(
                    DpSize(
                        RadioButtonDefaults.SelectionControlWidth,
                        RadioButtonDefaults.SelectionControlHeight,
                    )
                ).gravity(Gravity.CENTER_VERTICAL),
            ) {
                radioControl(
                    selected = selected,
                    color = resolved.controlColor(enabled = enabled, selected = selected),
                    modifier = Modifier
                        .gravity(Gravity.END)
                        .size(
                            DpSize(
                                RadioButtonDefaults.ControlWidth,
                                RadioButtonDefaults.ControlHeight,
                            )
                        ),
                )
            }
        }
    }
}

/**
 * The Wear Material `SplitRadioButton`: the label column and the radio control in two separately
 * tappable areas — [onContainerClick] over the body, [onSelectionClick] over the selection control,
 * which upstream also tints with a `splitContainerColor` overlay to divide them.
 *
 * Upstream clips the row to the stadium shape and then each section to `SPLIT_SECTIONS_SHAPE`, so a
 * section's silhouette is the intersection: the container radius on the corners that face out of the
 * button, 4.dp `CornerExtraSmall` on the ones that face the other section. Views cannot clip a
 * `LinearLayout`'s children to an outline, so each section paints that intersection as its own
 * container and the row itself stays unpainted, which leaves the 2 dp gap showing what is behind the
 * button, as upstream does. Slot content that overflowed a section is still not trimmed the way
 * `graphicsLayer { clip = true }` trims it.
 *
 * @param selected Whether this button is currently selected.
 * @param onSelectionClick Called when the selection control area is clicked, after a haptic.
 * @param selectionContentDescription Description for the selection control area, set on the control.
 * @param onContainerClick Called when the body containing the labels is clicked.
 * @param modifier Applied to the row.
 * @param enabled When `false`, neither area responds and the disabled colour set resolves.
 * @param shape The row's shape.
 * @param colors `null` resolves [RadioButtonDefaults.splitRadioButtonColors]; see [RadioButton]
 *   for why the default is `null` rather than upstream's theme-reading expression.
 * @param containerClickLabel Accepted for API parity and unused; see the file note on semantics.
 * @param contentPadding Spacing between each section's container and its content.
 * @param secondaryLabel Optional second line of the label column.
 * @param label The main label.
 */
@Tunable
fun SplitRadioButton(
    selected: Boolean,
    onSelectionClick: () -> Unit,
    selectionContentDescription: String?,
    onContainerClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RadioButtonDefaults.splitRadioButtonShape,
    colors: SplitRadioButtonColors? = null,
    containerClickLabel: String? = null,
    contentPadding: PaddingValues = RadioButtonDefaults.ContentPadding,
    secondaryLabel: (@Tunable RowScope.() -> Unit)? = null,
    label: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: RadioButtonDefaults.splitRadioButtonColors()
    val typography = MaterialTheme.typography
    val containerColor = resolved.containerColor(enabled = enabled, selected = selected)
    val contentColor = resolved.contentColor(enabled = enabled, selected = selected)
    // Upstream composites the overlay over the container, and over black once disabled.
    val splitColor = resolved.splitContainerColor(enabled = enabled, selected = selected)
        .compositeOver(if (enabled) containerColor else Color.Black)
    val primaryLabel = label
    val optionalLabel = secondaryLabel

    provideContentColor(contentColor) {
        Row(
            modifier = modifier
                .minHeight(RadioButtonDefaults.MinHeight),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .gravity(Gravity.CENTER_VERTICAL)
                    .container(
                        ContainerSpec(
                            shape = radioSectionShape(shape, first = true),
                            containerColor = containerColor,
                        ),
                    )
                    .clickable(enabled, onContainerClick)
                    .padding(contentPadding),
            ) {
                radioLabels(
                    modifier = Modifier.weight(1f),
                    contentColor = contentColor,
                    secondaryContentColor = resolved.secondaryContentColor(
                        enabled = enabled,
                        selected = selected,
                    ),
                    labelStyle = typography.fromToken(SplitRadioButtonTokens.LabelFont),
                    secondaryLabelStyle = typography.fromToken(
                        SplitRadioButtonTokens.SecondaryLabelFont
                    ),
                    label = primaryLabel,
                    secondaryLabel = optionalLabel,
                )
            }

            Spacer(modifier = Modifier.size(DpSize(SplitSectionGap, SplitSectionGap)))

            // The section is held at the row's minimum height as a stand-in for upstream's
            // `fillMaxHeight()`; it carries its own outer radius, so nothing needs the row to be
            // painted behind it. Upstream's `defaultMinSize(minWidth = SPLIT_MIN_WIDTH)` is left out
            // because the floor cannot bind - a 24 dp control plus 14 dp of padding either side is
            // already 52 dp.
            Box(
                modifier = Modifier
                    .gravity(Gravity.CENTER_VERTICAL)
                    .minHeight(RadioButtonDefaults.MinHeight)
                    .container(
                        ContainerSpec(
                            shape = radioSectionShape(shape, first = false),
                            containerColor = splitColor,
                        ),
                    )
                    .radioSelectionClickable(enabled, onSelectionClick)
                    .padding(contentPadding),
            ) {
                radioControl(
                    selected = selected,
                    color = resolved.controlColor(enabled = enabled, selected = selected),
                    modifier = Modifier
                        // The section Box is `contentAlignment = Alignment.Center` upstream.
                        .gravity(Gravity.CENTER)
                        .radioContentDescription(selectionContentDescription)
                        .size(
                            DpSize(
                                RadioButtonDefaults.ControlWidth,
                                RadioButtonDefaults.ControlHeight,
                            )
                        ),
                )
            }
        }
    }
}

/** Upstream's `RadioButtonDefaults`: the values shared by [RadioButton] and [SplitRadioButton]. */
object RadioButtonDefaults {
    /**
     * Recommended [Shape] for [RadioButton]: `CornerLarge`, the Wear stadium.
     *
     * Upstream reads this through `LocalShapes` so a theme could reshape it; it is a plain static
     * token here for the same reason as `ButtonDefaults.shape` — a default-parameter expression is
     * hoisted out of the tunable context and cannot read a local.
     */
    val radioButtonShape: Shape = ShapeTokens.CornerLarge

    /** Recommended [Shape] for [SplitRadioButton]. */
    val splitRadioButtonShape: Shape = ShapeTokens.CornerLarge

    /** Upstream's `MIN_HEIGHT`, the row's minimum height. */
    internal val MinHeight: Dp = 52.dp

    /** Upstream's `LabelSpacerSize`: the gap between the label and the secondary label. */
    internal val LabelSpacerSize: Dp = 1.dp

    /**
     * Upstream's file-private geometry (`SELECTION_CONTROL_*`, `ICON_SPACING`, `CONTROL_*`); they
     * are `private val`s at the top level of upstream's `RadioButton.kt`, not members of its
     * `RadioButtonDefaults`, so they are `internal` here rather than public and exist only to keep
     * this file's call sites readable.
     */
    internal val SelectionControlWidth: Dp = 32.dp
    internal val SelectionControlHeight: Dp = 24.dp
    internal val SelectionControlSpacing: Dp = 6.dp
    internal val IconSpacing: Dp = 6.dp
    internal val ControlWidth: Dp = 24.dp
    internal val ControlHeight: Dp = 24.dp

    private val HorizontalPadding: Dp = 14.dp
    private val VerticalPadding: Dp = 8.dp

    /** Upstream's `ContentPadding`, 14 dp horizontally and 8 dp vertically. */
    val ContentPadding: PaddingValues = PaddingValues(
        start = HorizontalPadding,
        top = VerticalPadding,
        end = HorizontalPadding,
        bottom = VerticalPadding,
    )

    @Tunable
    fun radioButtonColors(): RadioButtonColors =
        MaterialTheme.colorScheme.defaultRadioButtonColors()

    /**
     * [radioButtonColors] with per-slot overrides. An [Color.Unspecified] argument keeps the token
     * default, exactly as upstream's own `copy` does.
     */
    @Tunable
    @Suppress("LongParameterList")
    fun radioButtonColors(
        selectedContainerColor: Color = Color.Unspecified,
        selectedContentColor: Color = Color.Unspecified,
        selectedSecondaryContentColor: Color = Color.Unspecified,
        selectedIconColor: Color = Color.Unspecified,
        selectedControlColor: Color = Color.Unspecified,
        unselectedContainerColor: Color = Color.Unspecified,
        unselectedContentColor: Color = Color.Unspecified,
        unselectedSecondaryContentColor: Color = Color.Unspecified,
        unselectedIconColor: Color = Color.Unspecified,
        unselectedControlColor: Color = Color.Unspecified,
        disabledSelectedContainerColor: Color = Color.Unspecified,
        disabledSelectedContentColor: Color = Color.Unspecified,
        disabledSelectedSecondaryContentColor: Color = Color.Unspecified,
        disabledSelectedIconColor: Color = Color.Unspecified,
        disabledSelectedControlColor: Color = Color.Unspecified,
        disabledUnselectedContainerColor: Color = Color.Unspecified,
        disabledUnselectedContentColor: Color = Color.Unspecified,
        disabledUnselectedSecondaryContentColor: Color = Color.Unspecified,
        disabledUnselectedIconColor: Color = Color.Unspecified,
        disabledUnselectedControlColor: Color = Color.Unspecified,
    ): RadioButtonColors {
        val defaults = MaterialTheme.colorScheme.defaultRadioButtonColors()
        return defaults.copy(
            selectedContainerColor = selectedContainerColor.takeOrElse {
                defaults.selectedContainerColor
            },
            selectedContentColor = selectedContentColor.takeOrElse {
                defaults.selectedContentColor
            },
            selectedSecondaryContentColor = selectedSecondaryContentColor.takeOrElse {
                defaults.selectedSecondaryContentColor
            },
            selectedIconColor = selectedIconColor.takeOrElse { defaults.selectedIconColor },
            selectedControlColor = selectedControlColor.takeOrElse { defaults.selectedControlColor },
            unselectedContainerColor = unselectedContainerColor.takeOrElse {
                defaults.unselectedContainerColor
            },
            unselectedContentColor = unselectedContentColor.takeOrElse {
                defaults.unselectedContentColor
            },
            unselectedSecondaryContentColor = unselectedSecondaryContentColor.takeOrElse {
                defaults.unselectedSecondaryContentColor
            },
            unselectedIconColor = unselectedIconColor.takeOrElse { defaults.unselectedIconColor },
            unselectedControlColor = unselectedControlColor.takeOrElse {
                defaults.unselectedControlColor
            },
            disabledSelectedContainerColor = disabledSelectedContainerColor.takeOrElse {
                defaults.disabledSelectedContainerColor
            },
            disabledSelectedContentColor = disabledSelectedContentColor.takeOrElse {
                defaults.disabledSelectedContentColor
            },
            disabledSelectedSecondaryContentColor =
                disabledSelectedSecondaryContentColor.takeOrElse {
                    defaults.disabledSelectedSecondaryContentColor
                },
            disabledSelectedIconColor = disabledSelectedIconColor.takeOrElse {
                defaults.disabledSelectedIconColor
            },
            disabledSelectedControlColor = disabledSelectedControlColor.takeOrElse {
                defaults.disabledSelectedControlColor
            },
            disabledUnselectedContainerColor = disabledUnselectedContainerColor.takeOrElse {
                defaults.disabledUnselectedContainerColor
            },
            disabledUnselectedContentColor = disabledUnselectedContentColor.takeOrElse {
                defaults.disabledUnselectedContentColor
            },
            disabledUnselectedSecondaryContentColor =
                disabledUnselectedSecondaryContentColor.takeOrElse {
                    defaults.disabledUnselectedSecondaryContentColor
                },
            disabledUnselectedIconColor = disabledUnselectedIconColor.takeOrElse {
                defaults.disabledUnselectedIconColor
            },
            disabledUnselectedControlColor = disabledUnselectedControlColor.takeOrElse {
                defaults.disabledUnselectedControlColor
            },
        )
    }

    @Tunable
    fun splitRadioButtonColors(): SplitRadioButtonColors =
        MaterialTheme.colorScheme.defaultSplitRadioButtonColors()

    /** [splitRadioButtonColors] with per-slot overrides, unspecified meaning "keep the default". */
    @Tunable
    @Suppress("LongParameterList")
    fun splitRadioButtonColors(
        selectedContainerColor: Color = Color.Unspecified,
        selectedContentColor: Color = Color.Unspecified,
        selectedSecondaryContentColor: Color = Color.Unspecified,
        selectedSplitContainerColor: Color = Color.Unspecified,
        selectedControlColor: Color = Color.Unspecified,
        unselectedContainerColor: Color = Color.Unspecified,
        unselectedContentColor: Color = Color.Unspecified,
        unselectedSecondaryContentColor: Color = Color.Unspecified,
        unselectedSplitContainerColor: Color = Color.Unspecified,
        unselectedControlColor: Color = Color.Unspecified,
        disabledSelectedContainerColor: Color = Color.Unspecified,
        disabledSelectedContentColor: Color = Color.Unspecified,
        disabledSelectedSecondaryContentColor: Color = Color.Unspecified,
        disabledSelectedSplitContainerColor: Color = Color.Unspecified,
        disabledSelectedControlColor: Color = Color.Unspecified,
        disabledUnselectedContainerColor: Color = Color.Unspecified,
        disabledUnselectedContentColor: Color = Color.Unspecified,
        disabledUnselectedSecondaryContentColor: Color = Color.Unspecified,
        disabledUnselectedSplitContainerColor: Color = Color.Unspecified,
        disabledUnselectedControlColor: Color = Color.Unspecified,
    ): SplitRadioButtonColors {
        val defaults = MaterialTheme.colorScheme.defaultSplitRadioButtonColors()
        return defaults.copy(
            selectedContainerColor = selectedContainerColor.takeOrElse {
                defaults.selectedContainerColor
            },
            selectedContentColor = selectedContentColor.takeOrElse {
                defaults.selectedContentColor
            },
            selectedSecondaryContentColor = selectedSecondaryContentColor.takeOrElse {
                defaults.selectedSecondaryContentColor
            },
            selectedSplitContainerColor = selectedSplitContainerColor.takeOrElse {
                defaults.selectedSplitContainerColor
            },
            selectedControlColor = selectedControlColor.takeOrElse { defaults.selectedControlColor },
            unselectedContainerColor = unselectedContainerColor.takeOrElse {
                defaults.unselectedContainerColor
            },
            unselectedContentColor = unselectedContentColor.takeOrElse {
                defaults.unselectedContentColor
            },
            unselectedSecondaryContentColor = unselectedSecondaryContentColor.takeOrElse {
                defaults.unselectedSecondaryContentColor
            },
            unselectedSplitContainerColor = unselectedSplitContainerColor.takeOrElse {
                defaults.unselectedSplitContainerColor
            },
            unselectedControlColor = unselectedControlColor.takeOrElse {
                defaults.unselectedControlColor
            },
            disabledSelectedContainerColor = disabledSelectedContainerColor.takeOrElse {
                defaults.disabledSelectedContainerColor
            },
            disabledSelectedContentColor = disabledSelectedContentColor.takeOrElse {
                defaults.disabledSelectedContentColor
            },
            disabledSelectedSecondaryContentColor =
                disabledSelectedSecondaryContentColor.takeOrElse {
                    defaults.disabledSelectedSecondaryContentColor
                },
            disabledSelectedSplitContainerColor = disabledSelectedSplitContainerColor.takeOrElse {
                defaults.disabledSelectedSplitContainerColor
            },
            disabledSelectedControlColor = disabledSelectedControlColor.takeOrElse {
                defaults.disabledSelectedControlColor
            },
            disabledUnselectedContainerColor = disabledUnselectedContainerColor.takeOrElse {
                defaults.disabledUnselectedContainerColor
            },
            disabledUnselectedContentColor = disabledUnselectedContentColor.takeOrElse {
                defaults.disabledUnselectedContentColor
            },
            disabledUnselectedSecondaryContentColor =
                disabledUnselectedSecondaryContentColor.takeOrElse {
                    defaults.disabledUnselectedSecondaryContentColor
                },
            disabledUnselectedSplitContainerColor =
                disabledUnselectedSplitContainerColor.takeOrElse {
                    defaults.disabledUnselectedSplitContainerColor
                },
            disabledUnselectedControlColor = disabledUnselectedControlColor.takeOrElse {
                defaults.disabledUnselectedControlColor
            },
        )
    }
}

/**
 * Upstream's `RadioButtonColors`: the twenty-role four-state colour set of [RadioButton].
 *
 * A data class rather than upstream's hand-written `equals`/`hashCode` over the same twenty fields,
 * which is equivalent. The one real difference is that the generated `copy` substitutes exactly what
 * it is handed, while upstream's runs every argument through `takeOrElse` so [Color.Unspecified]
 * means "keep what was there"; [RadioButtonDefaults.radioButtonColors] does that itself.
 */
data class RadioButtonColors(
    val selectedContainerColor: Color,
    val selectedContentColor: Color,
    val selectedSecondaryContentColor: Color,
    val selectedIconColor: Color,
    val selectedControlColor: Color,
    val unselectedContainerColor: Color,
    val unselectedContentColor: Color,
    val unselectedSecondaryContentColor: Color,
    val unselectedIconColor: Color,
    val unselectedControlColor: Color,
    val disabledSelectedContainerColor: Color,
    val disabledSelectedContentColor: Color,
    val disabledSelectedSecondaryContentColor: Color,
    val disabledSelectedIconColor: Color,
    val disabledSelectedControlColor: Color,
    val disabledUnselectedContainerColor: Color,
    val disabledUnselectedContentColor: Color,
    val disabledUnselectedSecondaryContentColor: Color,
    val disabledUnselectedIconColor: Color,
    val disabledUnselectedControlColor: Color,
) {

    internal fun containerColor(enabled: Boolean, selected: Boolean): Color = radioSelectionColor(
        enabled = enabled,
        checked = selected,
        checkedColor = selectedContainerColor,
        uncheckedColor = unselectedContainerColor,
        disabledCheckedColor = disabledSelectedContainerColor,
        disabledUncheckedColor = disabledUnselectedContainerColor,
    )

    internal fun contentColor(enabled: Boolean, selected: Boolean): Color = radioSelectionColor(
        enabled = enabled,
        checked = selected,
        checkedColor = selectedContentColor,
        uncheckedColor = unselectedContentColor,
        disabledCheckedColor = disabledSelectedContentColor,
        disabledUncheckedColor = disabledUnselectedContentColor,
    )

    internal fun secondaryContentColor(enabled: Boolean, selected: Boolean): Color =
        radioSelectionColor(
            enabled = enabled,
            checked = selected,
            checkedColor = selectedSecondaryContentColor,
            uncheckedColor = unselectedSecondaryContentColor,
            disabledCheckedColor = disabledSelectedSecondaryContentColor,
            disabledUncheckedColor = disabledUnselectedSecondaryContentColor,
        )

    internal fun iconColor(enabled: Boolean, selected: Boolean): Color = radioSelectionColor(
        enabled = enabled,
        checked = selected,
        checkedColor = selectedIconColor,
        uncheckedColor = unselectedIconColor,
        disabledCheckedColor = disabledSelectedIconColor,
        disabledUncheckedColor = disabledUnselectedIconColor,
    )

    internal fun controlColor(enabled: Boolean, selected: Boolean): Color = radioSelectionColor(
        enabled = enabled,
        checked = selected,
        checkedColor = selectedControlColor,
        uncheckedColor = unselectedControlColor,
        disabledCheckedColor = disabledSelectedControlColor,
        disabledUncheckedColor = disabledUnselectedControlColor,
    )
}

/** Upstream's `SplitRadioButtonColors`: the same slots, a split container instead of an icon. */
data class SplitRadioButtonColors(
    val selectedContainerColor: Color,
    val selectedContentColor: Color,
    val selectedSecondaryContentColor: Color,
    val selectedSplitContainerColor: Color,
    val selectedControlColor: Color,
    val unselectedContainerColor: Color,
    val unselectedContentColor: Color,
    val unselectedSecondaryContentColor: Color,
    val unselectedSplitContainerColor: Color,
    val unselectedControlColor: Color,
    val disabledSelectedContainerColor: Color,
    val disabledSelectedContentColor: Color,
    val disabledSelectedSecondaryContentColor: Color,
    val disabledSelectedSplitContainerColor: Color,
    val disabledSelectedControlColor: Color,
    val disabledUnselectedContainerColor: Color,
    val disabledUnselectedContentColor: Color,
    val disabledUnselectedSecondaryContentColor: Color,
    val disabledUnselectedSplitContainerColor: Color,
    val disabledUnselectedControlColor: Color,
) {

    internal fun containerColor(enabled: Boolean, selected: Boolean): Color = radioSelectionColor(
        enabled = enabled,
        checked = selected,
        checkedColor = selectedContainerColor,
        uncheckedColor = unselectedContainerColor,
        disabledCheckedColor = disabledSelectedContainerColor,
        disabledUncheckedColor = disabledUnselectedContainerColor,
    )

    internal fun contentColor(enabled: Boolean, selected: Boolean): Color = radioSelectionColor(
        enabled = enabled,
        checked = selected,
        checkedColor = selectedContentColor,
        uncheckedColor = unselectedContentColor,
        disabledCheckedColor = disabledSelectedContentColor,
        disabledUncheckedColor = disabledUnselectedContentColor,
    )

    internal fun secondaryContentColor(enabled: Boolean, selected: Boolean): Color =
        radioSelectionColor(
            enabled = enabled,
            checked = selected,
            checkedColor = selectedSecondaryContentColor,
            uncheckedColor = unselectedSecondaryContentColor,
            disabledCheckedColor = disabledSelectedSecondaryContentColor,
            disabledUncheckedColor = disabledUnselectedSecondaryContentColor,
        )

    internal fun splitContainerColor(enabled: Boolean, selected: Boolean): Color =
        radioSelectionColor(
            enabled = enabled,
            checked = selected,
            checkedColor = selectedSplitContainerColor,
            uncheckedColor = unselectedSplitContainerColor,
            disabledCheckedColor = disabledSelectedSplitContainerColor,
            disabledUncheckedColor = disabledUnselectedSplitContainerColor,
        )

    internal fun controlColor(enabled: Boolean, selected: Boolean): Color = radioSelectionColor(
        enabled = enabled,
        checked = selected,
        checkedColor = selectedControlColor,
        uncheckedColor = unselectedControlColor,
        disabledCheckedColor = disabledSelectedControlColor,
        disabledUncheckedColor = disabledUnselectedControlColor,
    )
}

/** The target value of upstream's `animateSelectionColor`, which is the half of it you can see. */
private fun radioSelectionColor(
    enabled: Boolean,
    checked: Boolean,
    checkedColor: Color,
    uncheckedColor: Color,
    disabledCheckedColor: Color,
    disabledUncheckedColor: Color,
): Color = if (enabled) {
    if (checked) checkedColor else uncheckedColor
} else {
    if (checked) disabledCheckedColor else disabledUncheckedColor
}

/** Upstream's private `Labels`: the weighted column of two label rows, 1 dp apart. */
@Tunable
private fun radioLabels(
    modifier: Modifier,
    contentColor: Color,
    secondaryContentColor: Color,
    labelStyle: TextStyle,
    secondaryLabelStyle: TextStyle,
    label: @Tunable RowScope.() -> Unit,
    secondaryLabel: (@Tunable RowScope.() -> Unit)?,
) {
    val primary = label
    val optional = secondaryLabel
    Column(modifier = modifier) {
        radioLabelRow(contentColor, labelStyle, primary)
        if (optional != null) {
            Spacer(modifier = Modifier.height(RadioButtonDefaults.LabelSpacerSize))
            radioLabelRow(secondaryContentColor, secondaryLabelStyle, optional)
        }
    }
}

/**
 * Upstream's `provideScopeContent(contentColor, textStyle)`: the resolved slot colour and the token's
 * `LabelFont`/`LabelSmall` ride down to whatever the caller puts in the slot.
 *
 * Duplicated rather than shared: `Button.kt` and `Card.kt` each keep their own private version of this
 * idea, `provideContentColor` carries only the colour, and no file here may be edited from another.
 */
@Tunable
private fun radioLabelRow(
    contentColor: Color,
    textStyle: TextStyle,
    content: @Tunable RowScope.() -> Unit,
) {
    val scope = content
    Row {
        TunationLocalProvider(
            LocalContentColor provides contentColor,
            LocalTextStyle provides textStyle,
        ) {
            scope()
        }
    }
}

/** The drawing half of upstream's `RadioControl`; the row owns the click, so this owns nothing. */
@Tunable
private fun radioControl(
    selected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Node(
        modifier = modifier
            .viewClass(WearRadioButtonView::class.java)
            .radioControlProgress(selected, color),
    )
}

/**
 * Duplicate of the private `selectionControl` in `SelectionControls.kt`, which cannot be shared: it is
 * `private` to that file, and upstream keeps a drawing primitive of its own per row file too.
 */
private fun Modifier.radioControlProgress(
    selected: Boolean,
    color: Color,
): Modifier = this.thenViewAttribute<WearSelectionView, Pair<Boolean, Color>>(
    uniqueKey,
    selected to color,
) {
    controlColor = it.second
    animateProgressTo(if (it.first) 1f else 0f)
}

/** `contentDescription` in `wear.attributes` is `ImageView`-typed; this control is not an ImageView. */
private fun Modifier.radioContentDescription(description: String?): Modifier =
    this.thenViewAttribute<View, String?>(uniqueKey, description) {
        contentDescription = it
    }

/**
 * [clickable] plus upstream's ContextClick haptic. `HapticFeedbackConstants.CONTEXT_CLICK` arrived in
 * API 29 and the module floors at 25, so older watches just get the click.
 */
private fun Modifier.radioSelectionClickable(enabled: Boolean, onClick: () -> Unit): Modifier =
    this.thenViewAttribute<View, Boolean>(uniqueKey, enabled) {
        val view = this
        setOnClickListener(if (enabled) {
            View.OnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
                onClick()
            }
        } else {
            null
        })
        isClickable = enabled
        isLongClickable = enabled
        this.isEnabled = enabled
    }

/** The gap upstream leaves between the two tappable sections of a `SplitRadioButton`. */
private val SplitSectionGap: Dp = 2.dp

/** `SPLIT_SECTIONS_SHAPE = ShapeTokens.CornerExtraSmall`, the radius facing the other section. */
private val SplitSectionCorner: Dp = (ShapeTokens.CornerExtraSmall as CornerBasedShape).topStart

/**
 * Upstream clips each split section to [SplitSectionCorner] and the row to `shape`, so a section's
 * silhouette is the intersection: the container radius where it faces out of the button,
 * [SplitSectionCorner] where it faces the other section. Every Wear shape is corner-based, so only
 * [CornerBasedShape] containers can be decomposed that way.
 */
private fun radioSectionShape(container: Shape, first: Boolean): Shape = when (container) {
    is CornerBasedShape -> if (first) {
        CornerBasedShape(
            container.topStart,
            SplitSectionCorner,
            SplitSectionCorner,
            container.bottomStart,
        )
    } else {
        CornerBasedShape(
            SplitSectionCorner,
            container.topEnd,
            container.bottomEnd,
            SplitSectionCorner,
        )
    }

    else -> container
}

private fun ColorScheme.defaultRadioButtonColors(): RadioButtonColors = RadioButtonColors(
    selectedContainerColor = RadioButtonTokens.SelectedContainerColor.resolve(this),
    selectedContentColor = RadioButtonTokens.SelectedContentColor.resolve(this),
    selectedSecondaryContentColor = RadioButtonTokens.SelectedSecondaryLabelColor.resolve(this)
        .copy(alpha = RadioButtonTokens.SelectedSecondaryLabelOpacity),
    selectedIconColor = RadioButtonTokens.SelectedIconColor.resolve(this),
    selectedControlColor = RadioButtonTokens.SelectedControlColor.resolve(this),
    unselectedContainerColor = RadioButtonTokens.UnselectedContainerColor.resolve(this),
    unselectedContentColor = RadioButtonTokens.UnselectedContentColor.resolve(this),
    unselectedSecondaryContentColor =
        RadioButtonTokens.UnselectedSecondaryLabelColor.resolve(this),
    unselectedIconColor = RadioButtonTokens.UnselectedIconColor.resolve(this),
    unselectedControlColor = RadioButtonTokens.UnselectedControlColor.resolve(this),
    disabledSelectedContainerColor = RadioButtonTokens.DisabledSelectedContainerColor.resolve(this)
        .toDisabledColor(RadioButtonTokens.DisabledSelectedContainerOpacity),
    disabledSelectedContentColor = RadioButtonTokens.DisabledSelectedContentColor.resolve(this)
        .toDisabledColor(RadioButtonTokens.DisabledOpacity),
    disabledSelectedSecondaryContentColor =
        RadioButtonTokens.DisabledSelectedSecondaryLabelColor.resolve(this)
            .toDisabledColor(RadioButtonTokens.DisabledOpacity),
    disabledSelectedIconColor = RadioButtonTokens.DisabledSelectedIconColor.resolve(this)
        .toDisabledColor(RadioButtonTokens.DisabledOpacity),
    disabledSelectedControlColor = RadioButtonTokens.DisabledSelectedControlColor.resolve(this)
        .toDisabledColor(RadioButtonTokens.DisabledSelectedControlOpacity),
    disabledUnselectedContainerColor =
        RadioButtonTokens.DisabledUnselectedContainerColor.resolve(this)
            .toDisabledColor(RadioButtonTokens.DisabledUnselectedContainerOpacity),
    disabledUnselectedContentColor = RadioButtonTokens.DisabledUnselectedContentColor.resolve(this)
        .toDisabledColor(RadioButtonTokens.DisabledOpacity),
    disabledUnselectedSecondaryContentColor =
        RadioButtonTokens.DisabledUnselectedSecondaryLabelColor.resolve(this)
            .toDisabledColor(RadioButtonTokens.DisabledOpacity),
    disabledUnselectedIconColor = RadioButtonTokens.DisabledUnselectedIconColor.resolve(this)
        .toDisabledColor(RadioButtonTokens.DisabledOpacity),
    disabledUnselectedControlColor = RadioButtonTokens.DisabledUnselectedControlColor.resolve(this)
        .toDisabledColor(RadioButtonTokens.DisabledUnselectedControlOpacity),
)

/**
 * Upstream's `defaultSplitRadioButtonColors`, including its uneven disabled path: the container and
 * split-container slots *replace* alpha with the token opacity (`copy`), while the label and control
 * slots *scale* it (`toDisabledColor`). Copied as written rather than tidied.
 */
private fun ColorScheme.defaultSplitRadioButtonColors(): SplitRadioButtonColors =
    SplitRadioButtonColors(
        selectedContainerColor = SplitRadioButtonTokens.SelectedContainerColor.resolve(this),
        selectedContentColor = SplitRadioButtonTokens.SelectedContentColor.resolve(this),
        selectedSecondaryContentColor = SplitRadioButtonTokens.SelectedSecondaryLabelColor.resolve(this)
            .copy(alpha = SplitRadioButtonTokens.SelectedSecondaryLabelOpacity),
        selectedSplitContainerColor = SplitRadioButtonTokens.SelectedSplitContainerColor.resolve(this)
            .copy(alpha = SplitRadioButtonTokens.SelectedSplitContainerOpacity),
        selectedControlColor = SplitRadioButtonTokens.SelectedControlColor.resolve(this),
        unselectedContainerColor = SplitRadioButtonTokens.UnselectedContainerColor.resolve(this),
        unselectedContentColor = SplitRadioButtonTokens.UnselectedContentColor.resolve(this),
        unselectedSecondaryContentColor =
            SplitRadioButtonTokens.UnselectedSecondaryLabelColor.resolve(this),
        unselectedSplitContainerColor =
            SplitRadioButtonTokens.UnselectedSplitContainerColor.resolve(this),
        unselectedControlColor = SplitRadioButtonTokens.UnselectedControlColor.resolve(this),
        disabledSelectedContainerColor =
            SplitRadioButtonTokens.DisabledSelectedContainerColor.resolve(this)
                .copy(alpha = SplitRadioButtonTokens.DisabledSelectedContainerOpacity),
        disabledSelectedContentColor = SplitRadioButtonTokens.DisabledSelectedContentColor.resolve(this)
            .toDisabledColor(SplitRadioButtonTokens.DisabledOpacity),
        disabledSelectedSecondaryContentColor =
            SplitRadioButtonTokens.DisabledSelectedSecondaryLabelColor.resolve(this)
                .toDisabledColor(SplitRadioButtonTokens.DisabledOpacity),
        disabledSelectedSplitContainerColor =
            SplitRadioButtonTokens.DisabledSelectedSplitContainerColor.resolve(this)
                .copy(alpha = SplitRadioButtonTokens.DisabledSelectedSplitContainerOpacity),
        disabledSelectedControlColor = SplitRadioButtonTokens.DisabledSelectedControlColor.resolve(this)
            .toDisabledColor(SplitRadioButtonTokens.DisabledSelectedControlOpacity),
        disabledUnselectedContainerColor =
            SplitRadioButtonTokens.DisabledUnselectedContainerColor.resolve(this)
                .copy(alpha = SplitRadioButtonTokens.DisabledUnselectedContainerOpacity),
        disabledUnselectedContentColor =
            SplitRadioButtonTokens.DisabledUnselectedContentColor.resolve(this)
                .toDisabledColor(SplitRadioButtonTokens.DisabledOpacity),
        disabledUnselectedSecondaryContentColor =
            SplitRadioButtonTokens.DisabledUnselectedSecondaryLabelColor.resolve(this)
                .toDisabledColor(SplitRadioButtonTokens.DisabledOpacity),
        disabledUnselectedSplitContainerColor =
            SplitRadioButtonTokens.DisabledUnselectedSplitContainerColor.resolve(this)
                .copy(alpha = SplitRadioButtonTokens.DisabledUnselectedSplitContainerOpacity),
        disabledUnselectedControlColor =
            SplitRadioButtonTokens.DisabledUnselectedControlColor.resolve(this)
                .toDisabledColor(SplitRadioButtonTokens.DisabledUnselectedControlOpacity),
    )
