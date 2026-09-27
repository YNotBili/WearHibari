package com.huanli233.hibari.wear

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
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
import com.huanli233.hibari.ui.geometry.CornerBasedShape
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.lerp
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.thenViewAttributeIfNotNull
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.tokens.ShapeTokens
import com.huanli233.hibari.wear.tokens.SplitSwitchButtonTokens
import com.huanli233.hibari.wear.tokens.SwitchButtonTokens
import com.huanli233.hibari.wear.view.WearSwitchButtonSlowEffectMillis
import com.huanli233.hibari.wear.view.WearSwitchButtonView

/**
 * Ported from androidx.wear.compose.material3.SwitchButton, including its private `Switch`,
 * `drawThumbAndTick` and `Labels`, and the `materialcore.ToggleButton` stadium row that
 * `SwitchButton` drives (`SplitSwitchButton` inlines its own row upstream, and does here too).
 *
 * What did not survive the move to Views, and why:
 *
 *  * `interactionSource`, `toggleInteractionSource`, `containerInteractionSource` and `ripple`:
 *    Hibari has no interaction source, and press feedback arrives through [ContainerDrawable] only
 *    for components that own a pressed colour in their tokens. Neither of these two does — upstream
 *    shows a press purely as a ripple — so a press is invisible here.
 *  * `transformation` (`Modifier.surface`): the scrim/elevation treatment a button gets inside a
 *    [ContainerSpec]-backed container. Nothing in this module ports `SurfaceTransformation` yet, and
 *    the plain path of `Modifier.surface` is what [SwitchButtonSurfaceDrawable] reproduces.
 *  * `TextConfiguration(overflow = Ellipsis, maxLines = 3 / 2, textAlign = Start)`: upstream passes
 *    that to the slot's `Text` through a composition local, which Hibari has no equivalent of. The
 *    slot keeps its colour and type style, so a caller that wants the truncation has to spell out
 *    `maxLines` and `overflow` on its own [Text].
 *  * Semantics: `role = Switch`, `role = Button`, the `On`/`Off` `stateDescription` and
 *    `LocalHapticFeedback`'s toggle pulses have no Hibari surface. `toggleContentDescription` and
 *    `containerClickLabel` do survive, as `View.contentDescription`.
 *  * `defaultMinSize(minWidth = 48.dp)` on the split toggle section is left out because the floor
 *    cannot bind, not because it cannot be set: `CheckboxButton.kt` sets it through
 *    `View.setMinimumWidth`, and here the section is a 32.dp switch plus 14.dp of padding on each
 *    side, 60.dp, so `SPLIT_MIN_WIDTH` is already exceeded in every state.
 *  * `colors` arrives as `null` and resolves in the body, unlike upstream's theme-reading default
 *    expression: a `@Tunable` function's defaults are hoisted into a non-`@Tunable` method that
 *    cannot read the theme. `CheckboxButton.kt` and `RadioButton.kt` do the same.
 *
 * The bare control of `SelectionControls.kt` is a different component: upstream's bare switch is
 * `Switch` (material3's is a private drawing primitive), and its `SwitchButton` is the labelled row
 * below. That file still names its bare switch `SwitchButton`, so it overloads this function at a
 * shorter arity - this one requires a `label`, so no call can be ambiguous - and keeps its own
 * `BareSwitchButtonColors` / `BareSwitchButtonDefaults` out of upstream's way.
 */

/**
 * The Wear Material `SwitchButton`: an optional leading icon, a column of two label slots and the
 * switch at the end, inside a stadium-shaped tappable row; the whole row is the toggle.
 *
 * @param colors [SwitchButtonColors] for the container, label, icon and switch channels, or `null`
 *   for [SwitchButtonDefaults.switchButtonColors]. Upstream defaults this to a theme read, which
 *   a `@Tunable` default cannot do: default expressions are hoisted into a non-`@Tunable` method, so
 *   the theme is out of reach and the resolution happens in the body instead.
 */
@Tunable
fun SwitchButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = SwitchButtonDefaults.switchButtonShape,
    colors: SwitchButtonColors? = null,
    contentPadding: PaddingValues = SwitchButtonDefaults.ContentPadding,
    icon: (@Tunable BoxScope.() -> Unit)? = null,
    secondaryLabel: (@Tunable RowScope.() -> Unit)? = null,
    label: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: SwitchButtonDefaults.switchButtonColors()
    val iconSlot = icon
    val labelSlot = label
    val secondarySlot = secondaryLabel
    val labelStyle = MaterialTheme.typography.fromToken(SwitchButtonTokens.LabelFont)
    val secondaryStyle = MaterialTheme.typography.fromToken(SwitchButtonTokens.SecondaryLabelFont)
    val contentColor = resolved.contentColor(enabled, checked)
    val secondaryColor = resolved.secondaryContentColor(enabled, checked)

    Row(
        modifier = modifier
            .minHeight(SwitchButtonMinHeight)
            .switchButtonSurface(
                SwitchButtonSurface(
                    shape = shape,
                    containerColor = resolved.containerColor(enabled, checked),
                    overlayColor = Color.Transparent,
                ),
            )
            // Upstream's whole row is the `toggleable`, so the labels toggle the control too.
            .clickable(enabled) { onCheckedChange(!checked) }
            .padding(contentPadding),
    ) {
        if (iconSlot != null) {
            Box(modifier = Modifier.gravity(Gravity.CENTER_VERTICAL)) {
                provideContentColor(resolved.iconColor(enabled, checked)) { iconSlot?.invoke(this) }
            }
            Spacer(modifier = Modifier.size(switchButtonSquare(SwitchButtonIconSpacing)))
        }
        // No `weight(1f)` on the labels column, unlike upstream: a weighted child of a
        // `wrap_content` LinearLayout claims the whole available width and shoves the switch out
        // of the row, while upstream's `width(IntrinsicSize.Max)` row just hugs its content.
        // CheckboxButton.kt reproduces hug-and-shrink with its own row view and keeps the weight;
        // when the shared row is factored out, this column should take the weight back.
        Column {
            Row {
                TunationLocalProvider(
                    LocalContentColor provides contentColor,
                    LocalTextStyle provides labelStyle,
                    content = { labelSlot() },
                )
            }
            if (secondarySlot != null) {
                Spacer(modifier = Modifier.size(SwitchButtonLabelSpacer))
                Row {
                    TunationLocalProvider(
                        LocalContentColor provides secondaryColor,
                        LocalTextStyle provides secondaryStyle,
                        content = { secondarySlot?.invoke(this) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(switchButtonSquare(SwitchButtonToggleSpacing)))
        Node(
            modifier = Modifier
                .viewClass(WearSwitchButtonView::class.java)
                .gravity(Gravity.CENTER_VERTICAL)
                .switchButtonToggle(
                    SwitchButtonToggleColors(
                        checked = checked,
                        trackColor = resolved.trackColor(enabled, checked),
                        trackBorderColor = resolved.trackBorderColor(enabled, checked),
                        thumbColor = resolved.thumbColor(enabled, checked),
                        thumbIconColor = resolved.thumbIconColor(enabled, checked),
                    ),
                ),
        )
    }
}

/**
 * The Wear Material `SplitSwitchButton`: the label column and the switch in two separately tappable
 * areas - [onContainerClick] over the body, [onCheckedChange] over the switch, whose background
 * carries the `splitContainerColor` overlay that divides the two.
 *
 * @param colors [SplitSwitchButtonColors], or `null` for
 *   [SwitchButtonDefaults.splitSwitchButtonColors]; see [SwitchButton] for why a `@Tunable`
 *   default cannot read the theme.
 * @param containerClickLabel Upstream's `onClickLabel` for the body's tap area, which has no
 *   accessibility surface here, so it lands on `View.contentDescription` as the toggle section's
 *   description does.
 */
@Tunable
fun SplitSwitchButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    toggleContentDescription: String?,
    onContainerClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = SwitchButtonDefaults.splitSwitchButtonShape,
    colors: SplitSwitchButtonColors? = null,
    containerClickLabel: String? = null,
    contentPadding: PaddingValues = SwitchButtonDefaults.ContentPadding,
    secondaryLabel: (@Tunable RowScope.() -> Unit)? = null,
    label: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: SwitchButtonDefaults.splitSwitchButtonColors()
    val labelSlot = label
    val secondarySlot = secondaryLabel
    val labelStyle = MaterialTheme.typography.fromToken(SplitSwitchButtonTokens.LabelFont)
    val secondaryStyle =
        MaterialTheme.typography.fromToken(SplitSwitchButtonTokens.SecondaryLabelFont)
    val containerColor = resolved.containerColor(enabled, checked)
    val contentColor = resolved.contentColor(enabled, checked)
    val secondaryColor = resolved.secondaryContentColor(enabled, checked)

    Row(
        modifier = modifier
            // Upstream clips the whole row to `shape` and lets the two sections paint the fill;
            // rounding each section's outer corners to that shape reproduces the silhouette.
            .minHeight(SwitchButtonMinHeight),
    ) {
        Row(
            modifier = Modifier
                .matchParentHeight()
                .minHeight(SwitchButtonMinHeight)
                .switchButtonSurface(
                    SwitchButtonSurface(
                        shape = switchButtonSplitSectionShape(shape, startSection = true),
                        containerColor = containerColor,
                        overlayColor = Color.Transparent,
                    ),
                )
                .clickable(enabled, onContainerClick)
                .switchButtonContentDescription(containerClickLabel)
                .padding(contentPadding),
        ) {
            Column {
                Row {
                    TunationLocalProvider(
                        LocalContentColor provides contentColor,
                        LocalTextStyle provides labelStyle,
                        content = { labelSlot() },
                    )
                }
                if (secondarySlot != null) {
                    Spacer(modifier = Modifier.size(SwitchButtonLabelSpacer))
                    Row {
                        TunationLocalProvider(
                            LocalContentColor provides secondaryColor,
                            LocalTextStyle provides secondaryStyle,
                            content = { secondarySlot?.invoke(this) },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.size(switchButtonSquare(SwitchButtonSplitGap)))

        Box(
            modifier = Modifier
                .matchParentHeight()
                .minHeight(SwitchButtonMinHeight)
                .switchButtonSurface(
                    SwitchButtonSurface(
                        shape = switchButtonSplitSectionShape(shape, startSection = false),
                        // Upstream paints black under the disabled overlay rather than the faded
                        // container colour, which is what makes the switch end read as recessed.
                        containerColor = if (enabled) containerColor else Color.Black,
                        overlayColor = resolved.splitContainerColor(enabled, checked),
                    ),
                )
                .clickable(enabled) { onCheckedChange(!checked) }
                .padding(contentPadding),
        ) {
            Node(
                modifier = Modifier
                    .viewClass(WearSwitchButtonView::class.java)
                    .gravity(Gravity.CENTER)
                    .switchButtonContentDescription(toggleContentDescription)
                    .switchButtonToggle(
                        SwitchButtonToggleColors(
                            checked = checked,
                            trackColor = resolved.trackColor(enabled, checked),
                            trackBorderColor = resolved.trackBorderColor(enabled, checked),
                            thumbColor = resolved.thumbColor(enabled, checked),
                            thumbIconColor = resolved.thumbIconColor(enabled, checked),
                        ),
                    ),
            )
        }
    }
}

/**
 * The defaults of androidx.wear.compose.material3.SwitchButtonDefaults, under upstream's own name:
 * every member is upstream's, and the bare switch of `SelectionControls.kt` - which has no material3
 * counterpart to name after - keeps its `BareSwitchButtonDefaults` out of the way.
 */
object SwitchButtonDefaults {
    private val HorizontalPadding: Dp = 14.dp
    private val VerticalPadding: Dp = 8.dp

    /** The spacing between a label and a secondary label, `SwitchButtonDefaults.LabelSpacerSize`. */
    internal val LabelSpacerSize: Dp = 1.dp

    /** `SwitchButtonTokens.ContainerShape`, `ShapeKeyTokens.CornerLarge`. */
    val switchButtonShape: Shape = ShapeTokens.CornerLarge

    /** `SplitSwitchButtonTokens.ContainerShape`, also `CornerLarge`. */
    val splitSwitchButtonShape: Shape = ShapeTokens.CornerLarge

    /** The default content padding used by both buttons: 14.dp across, 8.dp down. */
    val ContentPadding: PaddingValues = PaddingValues(
        start = HorizontalPadding,
        top = VerticalPadding,
        end = HorizontalPadding,
        bottom = VerticalPadding,
    )

    @Tunable
    fun switchButtonColors(): SwitchButtonColors =
        MaterialTheme.colorScheme.switchButtonDefaultColors()

    /** Every role of [switchButtonColors] overridable, [Color.Unspecified] keeping the default. */
    @Tunable
    fun switchButtonColors(
        checkedContainerColor: Color = Color.Unspecified,
        checkedContentColor: Color = Color.Unspecified,
        checkedSecondaryContentColor: Color = Color.Unspecified,
        checkedIconColor: Color = Color.Unspecified,
        checkedThumbColor: Color = Color.Unspecified,
        checkedThumbIconColor: Color = Color.Unspecified,
        checkedTrackColor: Color = Color.Unspecified,
        checkedTrackBorderColor: Color = Color.Unspecified,
        uncheckedContainerColor: Color = Color.Unspecified,
        uncheckedContentColor: Color = Color.Unspecified,
        uncheckedSecondaryContentColor: Color = Color.Unspecified,
        uncheckedIconColor: Color = Color.Unspecified,
        uncheckedThumbColor: Color = Color.Unspecified,
        uncheckedTrackColor: Color = Color.Unspecified,
        uncheckedTrackBorderColor: Color = Color.Unspecified,
        disabledCheckedContainerColor: Color = Color.Unspecified,
        disabledCheckedContentColor: Color = Color.Unspecified,
        disabledCheckedSecondaryContentColor: Color = Color.Unspecified,
        disabledCheckedIconColor: Color = Color.Unspecified,
        disabledCheckedThumbColor: Color = Color.Unspecified,
        disabledCheckedThumbIconColor: Color = Color.Unspecified,
        disabledCheckedTrackColor: Color = Color.Unspecified,
        disabledCheckedTrackBorderColor: Color = Color.Unspecified,
        disabledUncheckedContainerColor: Color = Color.Unspecified,
        disabledUncheckedContentColor: Color = Color.Unspecified,
        disabledUncheckedSecondaryContentColor: Color = Color.Unspecified,
        disabledUncheckedIconColor: Color = Color.Unspecified,
        disabledUncheckedThumbColor: Color = Color.Unspecified,
        disabledUncheckedTrackBorderColor: Color = Color.Unspecified,
    ): SwitchButtonColors = MaterialTheme.colorScheme.switchButtonDefaultColors().copy(
        checkedContainerColor = checkedContainerColor,
        checkedContentColor = checkedContentColor,
        checkedSecondaryContentColor = checkedSecondaryContentColor,
        checkedIconColor = checkedIconColor,
        checkedThumbColor = checkedThumbColor,
        checkedThumbIconColor = checkedThumbIconColor,
        checkedTrackColor = checkedTrackColor,
        checkedTrackBorderColor = checkedTrackBorderColor,
        uncheckedContainerColor = uncheckedContainerColor,
        uncheckedContentColor = uncheckedContentColor,
        uncheckedSecondaryContentColor = uncheckedSecondaryContentColor,
        uncheckedIconColor = uncheckedIconColor,
        uncheckedThumbColor = uncheckedThumbColor,
        uncheckedTrackColor = uncheckedTrackColor,
        uncheckedTrackBorderColor = uncheckedTrackBorderColor,
        disabledCheckedContainerColor = disabledCheckedContainerColor,
        disabledCheckedContentColor = disabledCheckedContentColor,
        disabledCheckedSecondaryContentColor = disabledCheckedSecondaryContentColor,
        disabledCheckedIconColor = disabledCheckedIconColor,
        disabledCheckedThumbColor = disabledCheckedThumbColor,
        disabledCheckedThumbIconColor = disabledCheckedThumbIconColor,
        disabledCheckedTrackColor = disabledCheckedTrackColor,
        disabledCheckedTrackBorderColor = disabledCheckedTrackBorderColor,
        disabledUncheckedContainerColor = disabledUncheckedContainerColor,
        disabledUncheckedContentColor = disabledUncheckedContentColor,
        disabledUncheckedSecondaryContentColor = disabledUncheckedSecondaryContentColor,
        disabledUncheckedIconColor = disabledUncheckedIconColor,
        disabledUncheckedThumbColor = disabledUncheckedThumbColor,
        disabledUncheckedTrackBorderColor = disabledUncheckedTrackBorderColor,
    )

    @Tunable
    fun splitSwitchButtonColors(): SplitSwitchButtonColors =
        MaterialTheme.colorScheme.splitSwitchButtonDefaultColors()

    /** Every role of [splitSwitchButtonColors] overridable, [Color.Unspecified] keeping the default. */
    @Tunable
    fun splitSwitchButtonColors(
        checkedContainerColor: Color = Color.Unspecified,
        checkedContentColor: Color = Color.Unspecified,
        checkedSecondaryContentColor: Color = Color.Unspecified,
        checkedSplitContainerColor: Color = Color.Unspecified,
        checkedThumbColor: Color = Color.Unspecified,
        checkedThumbIconColor: Color = Color.Unspecified,
        checkedTrackColor: Color = Color.Unspecified,
        checkedTrackBorderColor: Color = Color.Unspecified,
        uncheckedContainerColor: Color = Color.Unspecified,
        uncheckedContentColor: Color = Color.Unspecified,
        uncheckedSecondaryContentColor: Color = Color.Unspecified,
        uncheckedSplitContainerColor: Color = Color.Unspecified,
        uncheckedThumbColor: Color = Color.Unspecified,
        uncheckedTrackColor: Color = Color.Unspecified,
        uncheckedTrackBorderColor: Color = Color.Unspecified,
        disabledCheckedContainerColor: Color = Color.Unspecified,
        disabledCheckedContentColor: Color = Color.Unspecified,
        disabledCheckedSecondaryContentColor: Color = Color.Unspecified,
        disabledCheckedSplitContainerColor: Color = Color.Unspecified,
        disabledCheckedThumbColor: Color = Color.Unspecified,
        disabledCheckedThumbIconColor: Color = Color.Unspecified,
        disabledCheckedTrackColor: Color = Color.Unspecified,
        disabledCheckedTrackBorderColor: Color = Color.Unspecified,
        disabledUncheckedContainerColor: Color = Color.Unspecified,
        disabledUncheckedContentColor: Color = Color.Unspecified,
        disabledUncheckedSecondaryContentColor: Color = Color.Unspecified,
        disabledUncheckedSplitContainerColor: Color = Color.Unspecified,
        disabledUncheckedThumbColor: Color = Color.Unspecified,
        disabledUncheckedTrackBorderColor: Color = Color.Unspecified,
    ): SplitSwitchButtonColors = MaterialTheme.colorScheme.splitSwitchButtonDefaultColors().copy(
        checkedContainerColor = checkedContainerColor,
        checkedContentColor = checkedContentColor,
        checkedSecondaryContentColor = checkedSecondaryContentColor,
        checkedSplitContainerColor = checkedSplitContainerColor,
        checkedThumbColor = checkedThumbColor,
        checkedThumbIconColor = checkedThumbIconColor,
        checkedTrackColor = checkedTrackColor,
        checkedTrackBorderColor = checkedTrackBorderColor,
        uncheckedContainerColor = uncheckedContainerColor,
        uncheckedContentColor = uncheckedContentColor,
        uncheckedSecondaryContentColor = uncheckedSecondaryContentColor,
        uncheckedSplitContainerColor = uncheckedSplitContainerColor,
        uncheckedThumbColor = uncheckedThumbColor,
        uncheckedTrackColor = uncheckedTrackColor,
        uncheckedTrackBorderColor = uncheckedTrackBorderColor,
        disabledCheckedContainerColor = disabledCheckedContainerColor,
        disabledCheckedContentColor = disabledCheckedContentColor,
        disabledCheckedSecondaryContentColor = disabledCheckedSecondaryContentColor,
        disabledCheckedSplitContainerColor = disabledCheckedSplitContainerColor,
        disabledCheckedThumbColor = disabledCheckedThumbColor,
        disabledCheckedThumbIconColor = disabledCheckedThumbIconColor,
        disabledCheckedTrackColor = disabledCheckedTrackColor,
        disabledCheckedTrackBorderColor = disabledCheckedTrackBorderColor,
        disabledUncheckedContainerColor = disabledUncheckedContainerColor,
        disabledUncheckedContentColor = disabledUncheckedContentColor,
        disabledUncheckedSecondaryContentColor = disabledUncheckedSecondaryContentColor,
        disabledUncheckedSplitContainerColor = disabledUncheckedSplitContainerColor,
        disabledUncheckedThumbColor = disabledUncheckedThumbColor,
        disabledUncheckedTrackBorderColor = disabledUncheckedTrackBorderColor,
    )
}

/**
 * Every role of [SwitchButtonColors] resolved from [SwitchButtonTokens], which is what upstream
 * caches on the scheme as `ColorScheme.defaultSwitchButtonColors`.
 */
private fun ColorScheme.switchButtonDefaultColors(): SwitchButtonColors = SwitchButtonColors(
    checkedContainerColor = SwitchButtonTokens.CheckedContainerColor.resolve(this),
    checkedContentColor = SwitchButtonTokens.CheckedContentColor.resolve(this),
    checkedSecondaryContentColor =
        SwitchButtonTokens.CheckedSecondaryLabelColor.resolve(this)
            .copy(alpha = SwitchButtonTokens.CheckedSecondaryLabelOpacity),
    checkedIconColor = SwitchButtonTokens.CheckedIconColor.resolve(this),
    checkedThumbColor = SwitchButtonTokens.CheckedThumbColor.resolve(this),
    checkedThumbIconColor = SwitchButtonTokens.CheckedThumbIconColor.resolve(this),
    checkedTrackColor = SwitchButtonTokens.CheckedTrackColor.resolve(this),
    checkedTrackBorderColor = SwitchButtonTokens.CheckedTrackBorderColor.resolve(this),
    uncheckedContainerColor = SwitchButtonTokens.UncheckedContainerColor.resolve(this),
    uncheckedContentColor = SwitchButtonTokens.UncheckedContentColor.resolve(this),
    uncheckedSecondaryContentColor = SwitchButtonTokens.UncheckedSecondaryLabelColor.resolve(this),
    uncheckedIconColor = SwitchButtonTokens.UncheckedIconColor.resolve(this),
    uncheckedThumbColor = SwitchButtonTokens.UncheckedThumbColor.resolve(this),
    uncheckedTrackColor = SwitchButtonTokens.UncheckedTrackColor.resolve(this),
    uncheckedTrackBorderColor = SwitchButtonTokens.UncheckedTrackBorderColor.resolve(this),
    disabledCheckedContainerColor =
        SwitchButtonTokens.DisabledCheckedContainerColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledCheckedContainerOpacity),
    disabledCheckedContentColor =
        SwitchButtonTokens.DisabledCheckedContentColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledOpacity),
    disabledCheckedSecondaryContentColor =
        SwitchButtonTokens.DisabledCheckedSecondaryLabelColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledOpacity),
    disabledCheckedIconColor =
        SwitchButtonTokens.DisabledCheckedIconColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledOpacity),
    disabledCheckedThumbColor =
        SwitchButtonTokens.DisabledCheckedThumbColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledCheckedThumbOpacity),
    disabledCheckedThumbIconColor =
        SwitchButtonTokens.DisabledCheckedThumbIconColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledCheckedThumbIconOpacity),
    disabledCheckedTrackColor =
        SwitchButtonTokens.DisabledCheckedTrackColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledCheckedTrackOpacity),
    disabledCheckedTrackBorderColor =
        SwitchButtonTokens.DisabledCheckedTrackBorderColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledCheckedTrackBorderOpacity),
    disabledUncheckedContainerColor =
        SwitchButtonTokens.DisabledUncheckedContainerColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledUncheckedContainerOpacity),
    disabledUncheckedContentColor =
        SwitchButtonTokens.DisabledUncheckedContentColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledOpacity),
    disabledUncheckedSecondaryContentColor =
        SwitchButtonTokens.DisabledUncheckedSecondaryLabelColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledOpacity),
    disabledUncheckedIconColor =
        SwitchButtonTokens.DisabledUncheckedIconColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledOpacity),
    disabledUncheckedThumbColor =
        SwitchButtonTokens.DisabledUncheckedThumbColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledUncheckedThumbOpacity),
    disabledUncheckedTrackBorderColor =
        SwitchButtonTokens.DisabledUncheckedTrackBorderColor.resolve(this)
            .toDisabledColor(SwitchButtonTokens.DisabledUncheckedTrackBorderOpacity),
)

/**
 * Every role of [SplitSwitchButtonColors] resolved from [SplitSwitchButtonTokens], which is what
 * upstream caches on the scheme as `ColorScheme.defaultSplitSwitchButtonColors`.
 */
private fun ColorScheme.splitSwitchButtonDefaultColors(): SplitSwitchButtonColors = SplitSwitchButtonColors(
    checkedContainerColor = SplitSwitchButtonTokens.CheckedContainerColor.resolve(this),
    checkedContentColor = SplitSwitchButtonTokens.CheckedContentColor.resolve(this),
    checkedSecondaryContentColor =
        SplitSwitchButtonTokens.CheckedSecondaryLabelColor.resolve(this)
            .copy(alpha = SplitSwitchButtonTokens.CheckedSecondaryLabelOpacity),
    checkedSplitContainerColor =
        SplitSwitchButtonTokens.CheckedSplitContainerColor.resolve(this)
            .copy(alpha = SplitSwitchButtonTokens.CheckedSplitContainerOpacity),
    checkedThumbColor = SplitSwitchButtonTokens.CheckedThumbColor.resolve(this),
    checkedThumbIconColor = SplitSwitchButtonTokens.CheckedThumbIconColor.resolve(this),
    checkedTrackColor = SplitSwitchButtonTokens.CheckedTrackColor.resolve(this),
    checkedTrackBorderColor = SplitSwitchButtonTokens.CheckedTrackBorderColor.resolve(this),
    uncheckedContainerColor = SplitSwitchButtonTokens.UncheckedContainerColor.resolve(this),
    uncheckedContentColor = SplitSwitchButtonTokens.UncheckedContentColor.resolve(this),
    uncheckedSecondaryContentColor =
        SplitSwitchButtonTokens.UncheckedSecondaryLabelColor.resolve(this),
    uncheckedSplitContainerColor =
        SplitSwitchButtonTokens.UncheckedSplitContainerColor.resolve(this),
    uncheckedThumbColor = SplitSwitchButtonTokens.UncheckedThumbColor.resolve(this),
    uncheckedTrackColor = SplitSwitchButtonTokens.UncheckedTrackColor.resolve(this),
    uncheckedTrackBorderColor = SplitSwitchButtonTokens.UncheckedTrackBorderColor.resolve(this),
    disabledCheckedContainerColor =
        SplitSwitchButtonTokens.DisabledCheckedContainerColor.resolve(this)
            .copy(alpha = SplitSwitchButtonTokens.DisabledCheckedContainerOpacity),
    disabledCheckedContentColor =
        SplitSwitchButtonTokens.DisabledCheckedContentColor.resolve(this)
            .toDisabledColor(SplitSwitchButtonTokens.DisabledOpacity),
    disabledCheckedSecondaryContentColor =
        SplitSwitchButtonTokens.DisabledCheckedSecondaryLabelColor.resolve(this)
            .toDisabledColor(SplitSwitchButtonTokens.DisabledOpacity),
    disabledCheckedSplitContainerColor =
        SplitSwitchButtonTokens.DisabledCheckedSplitContainerColor.resolve(this)
            .copy(alpha = SplitSwitchButtonTokens.DisabledCheckedSplitContainerOpacity),
    disabledCheckedThumbColor =
        SplitSwitchButtonTokens.DisabledCheckedThumbColor.resolve(this)
            .toDisabledColor(SplitSwitchButtonTokens.DisabledCheckedThumbOpacity),
    disabledCheckedThumbIconColor =
        SplitSwitchButtonTokens.DisabledCheckedThumbIconColor.resolve(this)
            .toDisabledColor(SplitSwitchButtonTokens.DisabledCheckedThumbIconOpacity),
    disabledCheckedTrackColor =
        SplitSwitchButtonTokens.DisabledCheckedTrackColor.resolve(this)
            .toDisabledColor(SplitSwitchButtonTokens.DisabledCheckedTrackOpacity),
    disabledCheckedTrackBorderColor =
        SplitSwitchButtonTokens.DisabledCheckedTrackBorderColor.resolve(this)
            .toDisabledColor(SplitSwitchButtonTokens.DisabledCheckedTrackBorderOpacity),
    disabledUncheckedContainerColor =
        SplitSwitchButtonTokens.DisabledUncheckedContainerColor.resolve(this)
            .copy(alpha = SplitSwitchButtonTokens.DisabledUncheckedContainerOpacity),
    disabledUncheckedContentColor =
        SplitSwitchButtonTokens.DisabledUncheckedContentColor.resolve(this)
            .toDisabledColor(SplitSwitchButtonTokens.DisabledOpacity),
    disabledUncheckedSecondaryContentColor =
        SplitSwitchButtonTokens.DisabledUncheckedSecondaryLabelColor.resolve(this)
            .toDisabledColor(SplitSwitchButtonTokens.DisabledOpacity),
    disabledUncheckedSplitContainerColor =
        SplitSwitchButtonTokens.DisabledUncheckedSplitContainerColor.resolve(this)
            .copy(alpha = SplitSwitchButtonTokens.DisabledUncheckedSplitContainerOpacity),
    disabledUncheckedThumbColor =
        SplitSwitchButtonTokens.DisabledUncheckedThumbColor.resolve(this)
            .toDisabledColor(SplitSwitchButtonTokens.DisabledUncheckedThumbOpacity),
    disabledUncheckedTrackBorderColor =
        SplitSwitchButtonTokens.DisabledUncheckedTrackBorderColor.resolve(this)
            .toDisabledColor(SplitSwitchButtonTokens.DisabledUncheckedTrackBorderOpacity),
)

/**
 * Ported from androidx.wear.compose.material3.SwitchButtonColors.
 *
 * Upstream hands out `State<Color>`s from an `animateColorAsState`; here the state has no
 * composition to live in, so each role resolves to the colour the animation is heading for and the
 * two callers that draw it — [SwitchButtonSurfaceDrawable] for the container,
 * [WearSwitchButtonView] for the track and thumb — run the slow cross-fade between those targets.
 */
class SwitchButtonColors(
    val checkedContainerColor: Color,
    val checkedContentColor: Color,
    val checkedSecondaryContentColor: Color,
    val checkedIconColor: Color,
    val checkedThumbColor: Color,
    val checkedThumbIconColor: Color,
    val checkedTrackBorderColor: Color,
    val checkedTrackColor: Color,
    val uncheckedContainerColor: Color,
    val uncheckedContentColor: Color,
    val uncheckedSecondaryContentColor: Color,
    val uncheckedIconColor: Color,
    val uncheckedThumbColor: Color,
    val uncheckedTrackColor: Color,
    val uncheckedTrackBorderColor: Color,
    val disabledCheckedContainerColor: Color,
    val disabledCheckedContentColor: Color,
    val disabledCheckedSecondaryContentColor: Color,
    val disabledCheckedIconColor: Color,
    val disabledCheckedThumbColor: Color,
    val disabledCheckedThumbIconColor: Color,
    val disabledCheckedTrackColor: Color,
    val disabledCheckedTrackBorderColor: Color,
    val disabledUncheckedContainerColor: Color,
    val disabledUncheckedContentColor: Color,
    val disabledUncheckedSecondaryContentColor: Color,
    val disabledUncheckedIconColor: Color,
    val disabledUncheckedThumbColor: Color,
    val disabledUncheckedTrackBorderColor: Color,
) {

    fun copy(
        checkedContainerColor: Color = this.checkedContainerColor,
        checkedContentColor: Color = this.checkedContentColor,
        checkedSecondaryContentColor: Color = this.checkedSecondaryContentColor,
        checkedIconColor: Color = this.checkedIconColor,
        checkedThumbColor: Color = this.checkedThumbColor,
        checkedThumbIconColor: Color = this.checkedThumbIconColor,
        checkedTrackColor: Color = this.checkedTrackColor,
        checkedTrackBorderColor: Color = this.checkedTrackBorderColor,
        uncheckedContainerColor: Color = this.uncheckedContainerColor,
        uncheckedContentColor: Color = this.uncheckedContentColor,
        uncheckedSecondaryContentColor: Color = this.uncheckedSecondaryContentColor,
        uncheckedIconColor: Color = this.uncheckedIconColor,
        uncheckedThumbColor: Color = this.uncheckedThumbColor,
        uncheckedTrackColor: Color = this.uncheckedTrackColor,
        uncheckedTrackBorderColor: Color = this.uncheckedTrackBorderColor,
        disabledCheckedContainerColor: Color = this.disabledCheckedContainerColor,
        disabledCheckedContentColor: Color = this.disabledCheckedContentColor,
        disabledCheckedSecondaryContentColor: Color = this.disabledCheckedSecondaryContentColor,
        disabledCheckedIconColor: Color = this.disabledCheckedIconColor,
        disabledCheckedThumbColor: Color = this.disabledCheckedThumbColor,
        disabledCheckedThumbIconColor: Color = this.disabledCheckedThumbIconColor,
        disabledCheckedTrackColor: Color = this.disabledCheckedTrackColor,
        disabledCheckedTrackBorderColor: Color = this.disabledCheckedTrackBorderColor,
        disabledUncheckedContainerColor: Color = this.disabledUncheckedContainerColor,
        disabledUncheckedContentColor: Color = this.disabledUncheckedContentColor,
        disabledUncheckedSecondaryContentColor: Color = this.disabledUncheckedSecondaryContentColor,
        disabledUncheckedIconColor: Color = this.disabledUncheckedIconColor,
        disabledUncheckedThumbColor: Color = this.disabledUncheckedThumbColor,
        disabledUncheckedTrackBorderColor: Color = this.disabledUncheckedTrackBorderColor,
    ): SwitchButtonColors = SwitchButtonColors(
        checkedContainerColor = checkedContainerColor.takeOrElse { this.checkedContainerColor },
        checkedContentColor = checkedContentColor.takeOrElse { this.checkedContentColor },
        checkedSecondaryContentColor =
            checkedSecondaryContentColor.takeOrElse { this.checkedSecondaryContentColor },
        checkedIconColor = checkedIconColor.takeOrElse { this.checkedIconColor },
        checkedThumbColor = checkedThumbColor.takeOrElse { this.checkedThumbColor },
        checkedThumbIconColor = checkedThumbIconColor.takeOrElse { this.checkedThumbIconColor },
        checkedTrackColor = checkedTrackColor.takeOrElse { this.checkedTrackColor },
        checkedTrackBorderColor =
            checkedTrackBorderColor.takeOrElse { this.checkedTrackBorderColor },
        uncheckedContainerColor =
            uncheckedContainerColor.takeOrElse { this.uncheckedContainerColor },
        uncheckedContentColor = uncheckedContentColor.takeOrElse { this.uncheckedContentColor },
        uncheckedSecondaryContentColor =
            uncheckedSecondaryContentColor.takeOrElse { this.uncheckedSecondaryContentColor },
        uncheckedIconColor = uncheckedIconColor.takeOrElse { this.uncheckedIconColor },
        uncheckedThumbColor = uncheckedThumbColor.takeOrElse { this.uncheckedThumbColor },
        uncheckedTrackColor = uncheckedTrackColor.takeOrElse { this.uncheckedTrackColor },
        uncheckedTrackBorderColor =
            uncheckedTrackBorderColor.takeOrElse { this.uncheckedTrackBorderColor },
        disabledCheckedContainerColor =
            disabledCheckedContainerColor.takeOrElse { this.disabledCheckedContainerColor },
        disabledCheckedContentColor =
            disabledCheckedContentColor.takeOrElse { this.disabledCheckedContentColor },
        disabledCheckedSecondaryContentColor =
            disabledCheckedSecondaryContentColor.takeOrElse {
                this.disabledCheckedSecondaryContentColor
            },
        disabledCheckedIconColor =
            disabledCheckedIconColor.takeOrElse { this.disabledCheckedIconColor },
        disabledCheckedThumbColor =
            disabledCheckedThumbColor.takeOrElse { this.disabledCheckedThumbColor },
        disabledCheckedThumbIconColor =
            disabledCheckedThumbIconColor.takeOrElse { this.disabledCheckedThumbIconColor },
        disabledCheckedTrackColor =
            disabledCheckedTrackColor.takeOrElse { this.disabledCheckedTrackColor },
        disabledCheckedTrackBorderColor =
            disabledCheckedTrackBorderColor.takeOrElse { this.disabledCheckedTrackBorderColor },
        disabledUncheckedContainerColor =
            disabledUncheckedContainerColor.takeOrElse { this.disabledUncheckedContainerColor },
        disabledUncheckedContentColor =
            disabledUncheckedContentColor.takeOrElse { this.disabledUncheckedContentColor },
        disabledUncheckedSecondaryContentColor =
            disabledUncheckedSecondaryContentColor.takeOrElse {
                this.disabledUncheckedSecondaryContentColor
            },
        disabledUncheckedIconColor =
            disabledUncheckedIconColor.takeOrElse { this.disabledUncheckedIconColor },
        disabledUncheckedThumbColor =
            disabledUncheckedThumbColor.takeOrElse { this.disabledUncheckedThumbColor },
        disabledUncheckedTrackBorderColor =
            disabledUncheckedTrackBorderColor.takeOrElse { this.disabledUncheckedTrackBorderColor },
    )

    /** The container colour, `animateSelectionColor`'s target with [enabled] and [checked]. */
    internal fun containerColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedContainerColor else disabledUncheckedContainerColor
        checked -> checkedContainerColor
        else -> uncheckedContainerColor
    }

    internal fun contentColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedContentColor else disabledUncheckedContentColor
        checked -> checkedContentColor
        else -> uncheckedContentColor
    }

    /** The secondary label colour, used for [SplitSwitchButton]'s and [SwitchButton]'s second row. */
    internal fun secondaryContentColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled ->
            if (checked) disabledCheckedSecondaryContentColor
            else disabledUncheckedSecondaryContentColor
        checked -> checkedSecondaryContentColor
        else -> uncheckedSecondaryContentColor
    }

    internal fun iconColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedIconColor else disabledUncheckedIconColor
        checked -> checkedIconColor
        else -> uncheckedIconColor
    }

    internal fun thumbColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedThumbColor else disabledUncheckedThumbColor
        checked -> checkedThumbColor
        else -> uncheckedThumbColor
    }

    /** Upstream fades the thumb icon in from and out to nothing at all. */
    internal fun thumbIconColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedThumbIconColor else Color.Transparent
        checked -> checkedThumbIconColor
        else -> Color.Transparent
    }

    /** There is no disabled *unchecked* track token: upstream resolves it to transparent. */
    internal fun trackColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedTrackColor else Color.Transparent
        checked -> checkedTrackColor
        else -> uncheckedTrackColor
    }

    internal fun trackBorderColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled ->
            if (checked) disabledCheckedTrackBorderColor else disabledUncheckedTrackBorderColor
        checked -> checkedTrackBorderColor
        else -> uncheckedTrackBorderColor
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (this::class != other::class) return false

        other as SwitchButtonColors

        if (checkedContainerColor != other.checkedContainerColor) return false
        if (checkedContentColor != other.checkedContentColor) return false
        if (checkedSecondaryContentColor != other.checkedSecondaryContentColor) return false
        if (checkedIconColor != other.checkedIconColor) return false
        if (checkedThumbColor != other.checkedThumbColor) return false
        if (checkedThumbIconColor != other.checkedThumbIconColor) return false
        if (checkedTrackColor != other.checkedTrackColor) return false
        if (checkedTrackBorderColor != other.checkedTrackBorderColor) return false
        if (uncheckedContainerColor != other.uncheckedContainerColor) return false
        if (uncheckedContentColor != other.uncheckedContentColor) return false
        if (uncheckedSecondaryContentColor != other.uncheckedSecondaryContentColor) return false
        if (uncheckedIconColor != other.uncheckedIconColor) return false
        if (uncheckedThumbColor != other.uncheckedThumbColor) return false
        if (uncheckedTrackColor != other.uncheckedTrackColor) return false
        if (uncheckedTrackBorderColor != other.uncheckedTrackBorderColor) return false
        if (disabledCheckedContainerColor != other.disabledCheckedContainerColor) return false
        if (disabledCheckedContentColor != other.disabledCheckedContentColor) return false
        if (disabledCheckedSecondaryContentColor != other.disabledCheckedSecondaryContentColor)
            return false
        if (disabledCheckedIconColor != other.disabledCheckedIconColor) return false
        if (disabledCheckedThumbColor != other.disabledCheckedThumbColor) return false
        if (disabledCheckedThumbIconColor != other.disabledCheckedThumbIconColor) return false
        if (disabledCheckedTrackColor != other.disabledCheckedTrackColor) return false
        if (disabledCheckedTrackBorderColor != other.disabledCheckedTrackBorderColor) return false
        if (disabledUncheckedContainerColor != other.disabledUncheckedContainerColor) return false
        if (disabledUncheckedContentColor != other.disabledUncheckedContentColor) return false
        if (disabledUncheckedSecondaryContentColor != other.disabledUncheckedSecondaryContentColor)
            return false
        if (disabledUncheckedIconColor != other.disabledUncheckedIconColor) return false
        if (disabledUncheckedThumbColor != other.disabledUncheckedThumbColor) return false
        if (disabledUncheckedTrackBorderColor != other.disabledUncheckedTrackBorderColor)
            return false

        return true
    }

    override fun hashCode(): Int {
        var result = checkedContainerColor.hashCode()
        result = 31 * result + checkedContentColor.hashCode()
        result = 31 * result + checkedSecondaryContentColor.hashCode()
        result = 31 * result + checkedIconColor.hashCode()
        result = 31 * result + checkedThumbColor.hashCode()
        result = 31 * result + checkedThumbIconColor.hashCode()
        result = 31 * result + checkedTrackColor.hashCode()
        result = 31 * result + checkedTrackBorderColor.hashCode()
        result = 31 * result + uncheckedContainerColor.hashCode()
        result = 31 * result + uncheckedContentColor.hashCode()
        result = 31 * result + uncheckedSecondaryContentColor.hashCode()
        result = 31 * result + uncheckedIconColor.hashCode()
        result = 31 * result + uncheckedThumbColor.hashCode()
        result = 31 * result + uncheckedTrackColor.hashCode()
        result = 31 * result + uncheckedTrackBorderColor.hashCode()
        result = 31 * result + disabledCheckedContainerColor.hashCode()
        result = 31 * result + disabledCheckedContentColor.hashCode()
        result = 31 * result + disabledCheckedSecondaryContentColor.hashCode()
        result = 31 * result + disabledCheckedIconColor.hashCode()
        result = 31 * result + disabledCheckedThumbColor.hashCode()
        result = 31 * result + disabledCheckedThumbIconColor.hashCode()
        result = 31 * result + disabledCheckedTrackColor.hashCode()
        result = 31 * result + disabledCheckedTrackBorderColor.hashCode()
        result = 31 * result + disabledUncheckedContainerColor.hashCode()
        result = 31 * result + disabledUncheckedContentColor.hashCode()
        result = 31 * result + disabledUncheckedSecondaryContentColor.hashCode()
        result = 31 * result + disabledUncheckedIconColor.hashCode()
        result = 31 * result + disabledUncheckedThumbColor.hashCode()
        result = 31 * result + disabledUncheckedTrackBorderColor.hashCode()
        return result
    }
}

/**
 * Ported from androidx.wear.compose.material3.SplitSwitchButtonColors: [SwitchButtonColors] with
 * the switch end's overlay in place of the icon role.
 */
class SplitSwitchButtonColors(
    val checkedContainerColor: Color,
    val checkedContentColor: Color,
    val checkedSecondaryContentColor: Color,
    val checkedSplitContainerColor: Color,
    val checkedThumbColor: Color,
    val checkedThumbIconColor: Color,
    val checkedTrackColor: Color,
    val checkedTrackBorderColor: Color,
    val uncheckedContainerColor: Color,
    val uncheckedContentColor: Color,
    val uncheckedSecondaryContentColor: Color,
    val uncheckedSplitContainerColor: Color,
    val uncheckedThumbColor: Color,
    val uncheckedTrackColor: Color,
    val uncheckedTrackBorderColor: Color,
    val disabledCheckedContainerColor: Color,
    val disabledCheckedContentColor: Color,
    val disabledCheckedSecondaryContentColor: Color,
    val disabledCheckedSplitContainerColor: Color,
    val disabledCheckedThumbColor: Color,
    val disabledCheckedThumbIconColor: Color,
    val disabledCheckedTrackColor: Color,
    val disabledCheckedTrackBorderColor: Color,
    val disabledUncheckedContainerColor: Color,
    val disabledUncheckedContentColor: Color,
    val disabledUncheckedSecondaryContentColor: Color,
    val disabledUncheckedSplitContainerColor: Color,
    val disabledUncheckedThumbColor: Color,
    val disabledUncheckedTrackBorderColor: Color,
) {

    fun copy(
        checkedContainerColor: Color = this.checkedContainerColor,
        checkedContentColor: Color = this.checkedContentColor,
        checkedSecondaryContentColor: Color = this.checkedSecondaryContentColor,
        checkedSplitContainerColor: Color = this.checkedSplitContainerColor,
        checkedThumbColor: Color = this.checkedThumbColor,
        checkedThumbIconColor: Color = this.checkedThumbIconColor,
        checkedTrackColor: Color = this.checkedTrackColor,
        checkedTrackBorderColor: Color = this.checkedTrackBorderColor,
        uncheckedContainerColor: Color = this.uncheckedContainerColor,
        uncheckedContentColor: Color = this.uncheckedContentColor,
        uncheckedSecondaryContentColor: Color = this.uncheckedSecondaryContentColor,
        uncheckedSplitContainerColor: Color = this.uncheckedSplitContainerColor,
        uncheckedThumbColor: Color = this.uncheckedThumbColor,
        uncheckedTrackColor: Color = this.uncheckedTrackColor,
        uncheckedTrackBorderColor: Color = this.uncheckedTrackBorderColor,
        disabledCheckedContainerColor: Color = this.disabledCheckedContainerColor,
        disabledCheckedContentColor: Color = this.disabledCheckedContentColor,
        disabledCheckedSecondaryContentColor: Color = this.disabledCheckedSecondaryContentColor,
        disabledCheckedSplitContainerColor: Color = this.disabledCheckedSplitContainerColor,
        disabledCheckedThumbColor: Color = this.disabledCheckedThumbColor,
        disabledCheckedThumbIconColor: Color = this.disabledCheckedThumbIconColor,
        disabledCheckedTrackColor: Color = this.disabledCheckedTrackColor,
        disabledCheckedTrackBorderColor: Color = this.disabledCheckedTrackBorderColor,
        disabledUncheckedContainerColor: Color = this.disabledUncheckedContainerColor,
        disabledUncheckedContentColor: Color = this.disabledUncheckedContentColor,
        disabledUncheckedSecondaryContentColor: Color = this.disabledUncheckedSecondaryContentColor,
        disabledUncheckedSplitContainerColor: Color = this.disabledUncheckedSplitContainerColor,
        disabledUncheckedThumbColor: Color = this.disabledUncheckedThumbColor,
        disabledUncheckedTrackBorderColor: Color = this.disabledUncheckedTrackBorderColor,
    ): SplitSwitchButtonColors = SplitSwitchButtonColors(
        checkedContainerColor = checkedContainerColor.takeOrElse { this.checkedContainerColor },
        checkedContentColor = checkedContentColor.takeOrElse { this.checkedContentColor },
        checkedSecondaryContentColor =
            checkedSecondaryContentColor.takeOrElse { this.checkedSecondaryContentColor },
        checkedSplitContainerColor =
            checkedSplitContainerColor.takeOrElse { this.checkedSplitContainerColor },
        checkedThumbColor = checkedThumbColor.takeOrElse { this.checkedThumbColor },
        checkedThumbIconColor = checkedThumbIconColor.takeOrElse { this.checkedThumbIconColor },
        checkedTrackColor = checkedTrackColor.takeOrElse { this.checkedTrackColor },
        checkedTrackBorderColor =
            checkedTrackBorderColor.takeOrElse { this.checkedTrackBorderColor },
        uncheckedContainerColor =
            uncheckedContainerColor.takeOrElse { this.uncheckedContainerColor },
        uncheckedContentColor = uncheckedContentColor.takeOrElse { this.uncheckedContentColor },
        uncheckedSecondaryContentColor =
            uncheckedSecondaryContentColor.takeOrElse { this.uncheckedSecondaryContentColor },
        uncheckedSplitContainerColor =
            uncheckedSplitContainerColor.takeOrElse { this.uncheckedSplitContainerColor },
        uncheckedThumbColor = uncheckedThumbColor.takeOrElse { this.uncheckedThumbColor },
        uncheckedTrackColor = uncheckedTrackColor.takeOrElse { this.uncheckedTrackColor },
        uncheckedTrackBorderColor =
            uncheckedTrackBorderColor.takeOrElse { this.uncheckedTrackBorderColor },
        disabledCheckedContainerColor =
            disabledCheckedContainerColor.takeOrElse { this.disabledCheckedContainerColor },
        disabledCheckedContentColor =
            disabledCheckedContentColor.takeOrElse { this.disabledCheckedContentColor },
        disabledCheckedSecondaryContentColor =
            disabledCheckedSecondaryContentColor.takeOrElse {
                this.disabledCheckedSecondaryContentColor
            },
        disabledCheckedSplitContainerColor =
            disabledCheckedSplitContainerColor.takeOrElse {
                this.disabledCheckedSplitContainerColor
            },
        disabledCheckedThumbColor =
            disabledCheckedThumbColor.takeOrElse { this.disabledCheckedThumbColor },
        disabledCheckedThumbIconColor =
            disabledCheckedThumbIconColor.takeOrElse { this.disabledCheckedThumbIconColor },
        disabledCheckedTrackColor =
            disabledCheckedTrackColor.takeOrElse { this.disabledCheckedTrackColor },
        disabledCheckedTrackBorderColor =
            disabledCheckedTrackBorderColor.takeOrElse { this.disabledCheckedTrackBorderColor },
        disabledUncheckedContainerColor =
            disabledUncheckedContainerColor.takeOrElse { this.disabledUncheckedContainerColor },
        disabledUncheckedContentColor =
            disabledUncheckedContentColor.takeOrElse { this.disabledUncheckedContentColor },
        disabledUncheckedSecondaryContentColor =
            disabledUncheckedSecondaryContentColor.takeOrElse {
                this.disabledUncheckedSecondaryContentColor
            },
        disabledUncheckedSplitContainerColor =
            disabledUncheckedSplitContainerColor.takeOrElse {
                this.disabledUncheckedSplitContainerColor
            },
        disabledUncheckedThumbColor =
            disabledUncheckedThumbColor.takeOrElse { this.disabledUncheckedThumbColor },
        disabledUncheckedTrackBorderColor =
            disabledUncheckedTrackBorderColor.takeOrElse { this.disabledUncheckedTrackBorderColor },
    )

    internal fun containerColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedContainerColor else disabledUncheckedContainerColor
        checked -> checkedContainerColor
        else -> uncheckedContainerColor
    }

    internal fun contentColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedContentColor else disabledUncheckedContentColor
        checked -> checkedContentColor
        else -> uncheckedContentColor
    }

    internal fun secondaryContentColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled ->
            if (checked) disabledCheckedSecondaryContentColor
            else disabledUncheckedSecondaryContentColor
        checked -> checkedSecondaryContentColor
        else -> uncheckedSecondaryContentColor
    }

    /** The overlay drawn on top of the switch end, which reads as the divider between the halves. */
    internal fun splitContainerColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled ->
            if (checked) disabledCheckedSplitContainerColor
            else disabledUncheckedSplitContainerColor
        checked -> checkedSplitContainerColor
        else -> uncheckedSplitContainerColor
    }

    internal fun thumbColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedThumbColor else disabledUncheckedThumbColor
        checked -> checkedThumbColor
        else -> uncheckedThumbColor
    }

    internal fun thumbIconColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedThumbIconColor else Color.Transparent
        checked -> checkedThumbIconColor
        else -> Color.Transparent
    }

    internal fun trackColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled -> if (checked) disabledCheckedTrackColor else Color.Transparent
        checked -> checkedTrackColor
        else -> uncheckedTrackColor
    }

    internal fun trackBorderColor(enabled: Boolean, checked: Boolean): Color = when {
        !enabled ->
            if (checked) disabledCheckedTrackBorderColor else disabledUncheckedTrackBorderColor
        checked -> checkedTrackBorderColor
        else -> uncheckedTrackBorderColor
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (this::class != other::class) return false

        other as SplitSwitchButtonColors

        if (checkedContainerColor != other.checkedContainerColor) return false
        if (checkedContentColor != other.checkedContentColor) return false
        if (checkedSecondaryContentColor != other.checkedSecondaryContentColor) return false
        if (checkedSplitContainerColor != other.checkedSplitContainerColor) return false
        if (checkedThumbColor != other.checkedThumbColor) return false
        if (checkedThumbIconColor != other.checkedThumbIconColor) return false
        if (checkedTrackColor != other.checkedTrackColor) return false
        if (checkedTrackBorderColor != other.checkedTrackBorderColor) return false
        if (uncheckedContainerColor != other.uncheckedContainerColor) return false
        if (uncheckedContentColor != other.uncheckedContentColor) return false
        if (uncheckedSecondaryContentColor != other.uncheckedSecondaryContentColor) return false
        if (uncheckedSplitContainerColor != other.uncheckedSplitContainerColor) return false
        if (uncheckedThumbColor != other.uncheckedThumbColor) return false
        if (uncheckedTrackColor != other.uncheckedTrackColor) return false
        if (uncheckedTrackBorderColor != other.uncheckedTrackBorderColor) return false
        if (disabledCheckedContainerColor != other.disabledCheckedContainerColor) return false
        if (disabledCheckedContentColor != other.disabledCheckedContentColor) return false
        if (disabledCheckedSecondaryContentColor != other.disabledCheckedSecondaryContentColor)
            return false
        if (disabledCheckedSplitContainerColor != other.disabledCheckedSplitContainerColor)
            return false
        if (disabledCheckedThumbColor != other.disabledCheckedThumbColor) return false
        if (disabledCheckedThumbIconColor != other.disabledCheckedThumbIconColor) return false
        if (disabledCheckedTrackColor != other.disabledCheckedTrackColor) return false
        if (disabledCheckedTrackBorderColor != other.disabledCheckedTrackBorderColor) return false
        if (disabledUncheckedContainerColor != other.disabledUncheckedContainerColor) return false
        if (disabledUncheckedContentColor != other.disabledUncheckedContentColor) return false
        if (disabledUncheckedSecondaryContentColor != other.disabledUncheckedSecondaryContentColor)
            return false
        if (disabledUncheckedSplitContainerColor != other.disabledUncheckedSplitContainerColor)
            return false
        if (disabledUncheckedThumbColor != other.disabledUncheckedThumbColor) return false
        if (disabledUncheckedTrackBorderColor != other.disabledUncheckedTrackBorderColor)
            return false

        return true
    }

    override fun hashCode(): Int {
        var result = checkedContainerColor.hashCode()
        result = 31 * result + checkedContentColor.hashCode()
        result = 31 * result + checkedSecondaryContentColor.hashCode()
        result = 31 * result + checkedSplitContainerColor.hashCode()
        result = 31 * result + checkedThumbColor.hashCode()
        result = 31 * result + checkedThumbIconColor.hashCode()
        result = 31 * result + checkedTrackColor.hashCode()
        result = 31 * result + checkedTrackBorderColor.hashCode()
        result = 31 * result + uncheckedContainerColor.hashCode()
        result = 31 * result + uncheckedContentColor.hashCode()
        result = 31 * result + uncheckedSecondaryContentColor.hashCode()
        result = 31 * result + uncheckedSplitContainerColor.hashCode()
        result = 31 * result + uncheckedThumbColor.hashCode()
        result = 31 * result + uncheckedTrackColor.hashCode()
        result = 31 * result + uncheckedTrackBorderColor.hashCode()
        result = 31 * result + disabledCheckedContainerColor.hashCode()
        result = 31 * result + disabledCheckedContentColor.hashCode()
        result = 31 * result + disabledCheckedSecondaryContentColor.hashCode()
        result = 31 * result + disabledCheckedSplitContainerColor.hashCode()
        result = 31 * result + disabledCheckedThumbColor.hashCode()
        result = 31 * result + disabledCheckedThumbIconColor.hashCode()
        result = 31 * result + disabledCheckedTrackColor.hashCode()
        result = 31 * result + disabledCheckedTrackBorderColor.hashCode()
        result = 31 * result + disabledUncheckedContainerColor.hashCode()
        result = 31 * result + disabledUncheckedContentColor.hashCode()
        result = 31 * result + disabledUncheckedSecondaryContentColor.hashCode()
        result = 31 * result + disabledUncheckedSplitContainerColor.hashCode()
        result = 31 * result + disabledUncheckedThumbColor.hashCode()
        result = 31 * result + disabledUncheckedTrackBorderColor.hashCode()
        return result
    }
}

/**
 * One painted section of these buttons: [overlayColor] goes on top of [containerColor], which is
 * how upstream renders the split button's divider (`drawRect(container)` then `drawRect(overlay)`).
 */
private data class SwitchButtonSurface(
    val shape: Shape,
    val containerColor: Color,
    val overlayColor: Color,
)

private fun Modifier.switchButtonSurface(surface: SwitchButtonSurface): Modifier =
    this.thenViewAttribute<View, SwitchButtonSurface>(uniqueKey, surface) {
        val density = resources.displayMetrics.density
        val current = background as? SwitchButtonSurfaceDrawable
        if (current == null) background = SwitchButtonSurfaceDrawable(surface, density)
        else current.animateTo(surface, density)
    }

/**
 * `Modifier.surface(transformation, shape, ColorPainter(backgroundColorState))` for the two places
 * upstream builds a switch-button container: a shape-clipped fill with the animated container
 * colour, plus the split section's overlay.
 *
 * The animation is upstream's `COLOR_ANIMATION_SPEC`: a retune that changes the resolved colour
 * cross-fades over the slow spec, while [WearSwitchButtonView] travels the thumb on the fast one.
 *
 * It deliberately does not override `isStateful()`: neither button has a pressed or focused
 * container colour in its tokens — upstream shows a press as a ripple — so the resolved colour the
 * tuner hands in already covers every state this surface can be in.
 */
private class SwitchButtonSurfaceDrawable(
    private var surface: SwitchButtonSurface,
    private var density: Float,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radii = FloatArray(8)
    private val rect = RectF()
    private val outline = Path()
    private var drawnColor = surface.containerColor
    private var drawnOverlay = surface.overlayColor
    private var transition: ValueAnimator? = null

    fun animateTo(next: SwitchButtonSurface, density: Float) {
        val fromColor = drawnColor
        val fromOverlay = drawnOverlay
        surface = next
        this.density = density
        if (fromColor == next.containerColor && fromOverlay == next.overlayColor) {
            // Only the shape moved, if anything: the bounds are redrawn with the new outline.
            invalidateSelf()
            return
        }
        transition?.cancel()
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = WearSwitchButtonSlowEffectMillis
            addUpdateListener {
                val fraction = it.animatedValue as Float
                drawnColor = lerp(fromColor, next.containerColor, fraction)
                drawnOverlay = lerp(fromOverlay, next.overlayColor, fraction)
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        val width = b.width().toFloat()
        val height = b.height().toFloat()
        surface.shape.toRadii(width, height, density, radii)
        rect.set(0f, 0f, width, height)
        // Canvas has no per-corner `drawRoundRect`, and a section's four corners differ, so the
        // outline has to be a path.
        outline.reset()
        outline.addRoundRect(rect, radii, Path.Direction.CW)

        canvas.translate(b.left.toFloat(), b.top.toFloat())
        fillPaint.color = drawnColor.toArgb()
        canvas.drawPath(outline, fillPaint)
        if (drawnOverlay.alpha > 0f) {
            fillPaint.color = drawnOverlay.toArgb()
            canvas.drawPath(outline, fillPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION") // Required below API 33, ignored by Canvas above it.
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** The four switch channels plus the state they are resolved for, keyed as one attribute. */
private data class SwitchButtonToggleColors(
    val checked: Boolean,
    val trackColor: Color,
    val trackBorderColor: Color,
    val thumbColor: Color,
    val thumbIconColor: Color,
)

private fun Modifier.switchButtonToggle(colors: SwitchButtonToggleColors): Modifier =
    this.thenViewAttribute<WearSwitchButtonView, SwitchButtonToggleColors>(uniqueKey, colors) {
        animateSwitchTo(
            checked = colors.checked,
            trackColor = colors.trackColor,
            trackBorderColor = colors.trackBorderColor,
            thumbColor = colors.thumbColor,
            thumbIconColor = colors.thumbIconColor,
        )
    }

/**
 * `View.contentDescription`, which is the only accessibility string Hibari carries: upstream's
 * `onClickLabel` and the switch's `semantics { contentDescription = ... }` both land here.
 */
private fun Modifier.switchButtonContentDescription(description: String?): Modifier =
    this.thenViewAttributeIfNotNull<View, String>(uniqueKey, description) {
        contentDescription = it
    }

/**
 * The corners of one half of a [SplitSwitchButton]: `shape` on the edge facing out of the button,
 * `SPLIT_SECTIONS_SHAPE` — 4.dp — on the edge facing the other half. Upstream reaches the same
 * outline by clipping the row and then each section; only corner-based container shapes can be
 * decomposed that way, and every Wear shape is one.
 */
private fun switchButtonSplitSectionShape(containerShape: Shape, startSection: Boolean): Shape {
    val container = containerShape as? CornerBasedShape ?: return containerShape
    val inner = SwitchButtonSplitInnerRadius
    return if (startSection) {
        CornerBasedShape(
            topStart = container.topStart,
            topEnd = inner,
            bottomEnd = inner,
            bottomStart = container.bottomStart,
        )
    } else {
        CornerBasedShape(
            topStart = inner,
            topEnd = container.topEnd,
            bottomEnd = container.bottomEnd,
            bottomStart = inner,
        )
    }
}

/** `ShapeTokens.CornerExtraSmall`, the radius upstream clips each split section to. */
private val SwitchButtonSplitInnerRadius: Dp =
    (ShapeTokens.CornerExtraSmall as CornerBasedShape).topStart

/** `Modifier.size(spacerSize)` for the square spacers between the slots. */
private fun switchButtonSquare(size: Dp): DpSize = DpSize(size, size)

/** `SwitchButtonDefaults.LabelSpacerSize`, as the square spacer upstream puts between labels. */
private val SwitchButtonLabelSpacer: DpSize =
    switchButtonSquare(SwitchButtonDefaults.LabelSpacerSize)

private val SwitchButtonIconSpacing = 6.dp
private val SwitchButtonToggleSpacing = 6.dp
private val SwitchButtonMinHeight = 52.dp
private val SwitchButtonSplitGap = 2.dp
