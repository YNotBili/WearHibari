package com.huanli233.hibari.wear

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.Column
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.foundation.Spacer
import com.huanli233.hibari.foundation.attributes.minHeight
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.text.TextAlign
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.ChildButtonTokens
import com.huanli233.hibari.wear.tokens.FilledTonalButtonTokens
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.tokens.FilledButtonTokens
import com.huanli233.hibari.wear.tokens.OutlinedButtonTokens
import com.huanli233.hibari.wear.tokens.ShapeKeyTokens
import com.huanli233.hibari.wear.tokens.ShapeTokens

/**
 * Ported from androidx.wear.compose.material3.ButtonColors.
 *
 * Upstream animates every field through `animateColor` driven by `interactionSource`. Here the
 * pressed and disabled variants ride inside [ContainerSpec] and [ContainerDrawable] swaps them on
 * drawable state, so the pixels match while the press transition is instant instead of sprung.
 */
data class ButtonColors(
    val containerColor: Color,
    val contentColor: Color,
    val secondaryContentColor: Color,
    val iconColor: Color,
    val disabledContainerColor: Color,
    val disabledContentColor: Color,
    val disabledSecondaryContentColor: Color,
    val disabledIconColor: Color,
    val border: BorderStroke? = null,
    val disabledBorder: BorderStroke? = null,
) {

    fun containerSpec(shape: Shape): ContainerSpec = ContainerSpec(
        shape = shape,
        containerColor = containerColor,
        border = border,
        disabledContainerColor = disabledContainerColor,
        disabledBorder = disabledBorder,
    )
}

/**
 * Ported from androidx.wear.compose.material3.Button.
 *
 * The `containerPainter` overloads (image background plus scrim) are not ported: they need
 * Compose's `Painter`/`ContentScale` alignment maths, whose Views equivalent is a `Drawable` a
 * caller can supply through [Modifier.container]. `onLongClick`, `interactionSource` and
 * `transformation` go with them.
 *
 * @param colors Defaults to `null` and resolves in the body: `ButtonDefaults.buttonColors()` reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot.
 */
@Tunable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Tunable RowScope.() -> Unit,
) {
    // The default set comes from `ButtonDefaults.buttonColors()`, which memoises it per `ColorScheme`.
    // It cannot be memoised from here instead: `remember`'s calculation is `@DisallowTunableCalls`
    // (`runtime/Tunables.kt:18`, checked by `compiler/k2/TunableCallChecker.kt:103`), so a body may not
    // wrap a `@Tunable` factory call in one.
    val resolved = colors ?: ButtonDefaults.buttonColors()
    // `containerSpec` costs one ContainerSpec plus one boxed disabled colour per tune
    // (`ContainerSpec.disabledContainerColor` is `Color?`, so it cannot stay a value class), and the
    // container attribute then compares two equal-but-distinct specs field by field
    // (`ui/Attribute.kt:68-79` reads only key, value and reuseSupported). Both inputs are keys here and
    // both compare by value — `ButtonColors` is a data class, `CornerBasedShape` is one too — so a
    // caller that rebuilds an equal set still hits, and `shape` genuinely feeds the spec.
    val spec = remember(resolved, shape) { resolved.containerSpec(shape) }
    val contentColor = if (enabled) resolved.contentColor else resolved.disabledContentColor
    val scope = content
    Row(
        // Upstream routes every Button overload through `Modifier.buttonSizeModifier()`
        // (`material3/Button.kt:138` for this one, and `:230, :318, :405, :491, :609, :728, :846,
        // :959` for the rest of the public variants), which is
        // `defaultMinSize(minHeight = ButtonDefaults.Height)` (`:2359-2360`), applied immediately
        // after the caller's modifier. [FilledTonalButton] and [OutlinedButton] delegate here, so all
        // three variants get the floor from this line. `Modifier.minHeight` writes `View.minimumHeight`
        // (`hibari-foundation/.../ViewAttributes.kt:89-92`), which only bites when the height is
        // unspecified or at-most, so a caller's explicit `height` still wins - the precedence
        // `defaultMinSize` has upstream.
        modifier = modifier
            .minHeight(ButtonDefaults.Height)
            .container(spec)
            .clickable(enabled, onClick)
            .padding(contentPadding),
    ) {
        // Upstream's `SingleSlotButtonImpl` hands its children `LocalContentColor` *and*
        // `LocalTextStyle provides labelFont` (`material3/Button.kt:2385-2388`). Each variant passes its
        // own token — Filled `:143`, Outlined `:235` and `:410`, FilledTonal `:323` — but all four
        // `*ButtonTokens.LabelFont` are `TypographyKeyTokens.LabelMedium`
        // (`tokens/FilledButtonTokens.kt:37`, `tokens/FilledTonalButtonTokens.kt:36`,
        // `tokens/OutlinedButtonTokens.kt:37`), so this one delegation point states Filled's and every
        // variant lands on the same style, which is what [CompactButton] and [ChildButton] already do.
        // Of upstream's `DefaultTextStyle` base (`material3/Typography.kt:339-345`) the family, weight,
        // size, line height, tracking and the `"pnum"` features do reach the `TextView`
        // (`attributes/ContainerAttributes.kt:57-84`); `PlatformTextStyle(includeFontPadding = false)`
        // and `LineHeightStyle(Center, Trim.None)` have no write here — our route puts the whole line
        // surplus under each line rather than half-leading — and `TextMotion.Animated` has no surface at
        // all, so a label's line box sits taller than wear's.
        provideContentColorAndStyle(
            contentColor,
            MaterialTheme.typography.fromToken(FilledButtonTokens.LabelFont),
        ) { scope() }
    }
}

@Tunable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: ButtonDefaults.filledTonalButtonColors()
    Button(onClick, modifier, enabled, shape, resolved, contentPadding, content)
}

@Tunable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: ButtonDefaults.outlinedButtonColors()
    Button(onClick, modifier, enabled, shape, resolved, contentPadding, content)
}

/**
 * Ported from androidx.wear.compose.material3.ButtonContent (`material3/Button.kt:1284-1344`): the
 * icon / label / secondary-label layout that upstream's slot-based `Button`, `FilledTonalButton`,
 * `OutlinedButton` and `ChildButton` overloads all hand their slots to (`:624`, `:743`, `:861`,
 * `:974`, `:1087`), public there so a caller building its own container can reuse the arrangement.
 * Those slot-based overloads themselves are not ported here — [Button], [FilledTonalButton] and
 * [OutlinedButton] take a single generic `content`, which upstream routes through
 * `SingleSlotButtonImpl` (`:2366-2410`) rather than through this layout — so the only Hibari caller
 * is the three-slot `ChildButton`.
 *
 * Upstream builds the two label rows as `provideScopeContent` *values* (`:1300-1313`, `:1315-1326`)
 * and hands them to `Row(content = ...)`; a `@Tunable` lambda cannot be carried as a value in this
 * engine, so each row applies the same three providers to the same slot one level in — the same scope,
 * for the same slots.
 *
 * @param colors Defaults to `null` and resolves to [ButtonDefaults.buttonColors] in the body, as
 *   upstream's default does (`:1290`); see [Button] for why a default cannot read the theme.
 */
@Tunable
fun ButtonContent(
    modifier: Modifier = Modifier,
    secondaryLabel: (@Tunable RowScope.() -> Unit)? = null,
    icon: (@Tunable BoxScope.() -> Unit)? = null,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    label: @Tunable RowScope.() -> Unit,
) {
    val resolved = colors ?: ButtonDefaults.buttonColors()
    // Upstream's `colors.contentColor(enabled)` / `secondaryContentColor(enabled)` / `iconColor(enabled)`
    // (`:1293-1295`) are each the plain pick between the enabled and the disabled field.
    val labelColor = if (enabled) resolved.contentColor else resolved.disabledContentColor
    val secondaryLabelColor =
        if (enabled) resolved.secondaryContentColor else resolved.disabledSecondaryContentColor
    val iconColor = if (enabled) resolved.iconColor else resolved.disabledIconColor
    val typography = MaterialTheme.typography
    val iconSlot = icon
    val secondarySlot = secondaryLabel
    val primaryLabel = label
    Row(modifier = modifier.buttonContentCentered()) {
        if (iconSlot != null) {
            // Upstream's `Box(Modifier.wrapContentSize(align = Alignment.Center))` (`:1331`): the box
            // wraps its content, so the axis the port can express is the cross axis the row gives it.
            Box(modifier = Modifier.gravity(Gravity.CENTER_VERTICAL)) {
                provideContentColor(iconColor) { iconSlot() }
            }
            Spacer(
                modifier = Modifier.size(
                    DpSize(ButtonDefaults.IconSpacing, ButtonDefaults.IconSpacing)
                )
            )
        }
        Column {
            Row {
                provideContentColorAndStyle(
                    labelColor,
                    typography.fromToken(FilledButtonTokens.LabelFont),
                    TextConfiguration(
                        // Upstream's ternary (`:1307`): start-aligned as soon as anything sits beside
                        // the label, centred only when the label is alone.
                        if (iconSlot != null || secondarySlot != null) TextAlign.Start
                        else TextAlign.Center,
                        TextOverflow.Ellipsis,
                        maxLines = 3,
                    ),
                ) { primaryLabel() }
            }
            if (secondarySlot != null) {
                // `Spacer(Modifier.size(1.dp))` (`:1339`).
                Spacer(modifier = Modifier.size(DpSize(1.dp, 1.dp)))
                Row {
                    provideContentColorAndStyle(
                        secondaryLabelColor,
                        typography.fromToken(FilledButtonTokens.SecondaryLabelFont),
                        TextConfiguration(TextAlign.Start, TextOverflow.Ellipsis, maxLines = 2),
                    ) { secondarySlot() }
                }
            }
        }
    }
}

/**
 * `Row(verticalAlignment = Alignment.CenterVertically)` (`material3/Button.kt:1328`) as a Views
 * attribute: `LinearLayout.setGravity` is the only way to centre the cross axis of children that come
 * from a caller's slot, where this file cannot hand them a `RowScope.gravity`.
 */
private fun Modifier.buttonContentCentered(): Modifier =
    this.thenViewAttribute<View, Int>(uniqueKey, Gravity.CENTER_VERTICAL) {
        if (this is LinearLayout) gravity = it
    }

object ButtonDefaults {
    val shape: Shape = ShapeTokens.CornerLarge

    val ButtonHorizontalPadding: Dp = 14.dp
    val ButtonVerticalPadding: Dp = 6.dp
    val CompactButtonHorizontalPadding: Dp = 12.dp
    val CompactButtonVerticalPadding: Dp = 0.dp

    val ContentPadding: PaddingValues =
        PaddingValues(horizontal = ButtonHorizontalPadding, vertical = ButtonVerticalPadding)

    val CompactContentPadding: PaddingValues = PaddingValues(
        horizontal = CompactButtonHorizontalPadding,
        vertical = CompactButtonVerticalPadding,
    )

    /**
     * `ButtonDefaults.Height` (`material3/Button.kt:1893`), whose value is
     * `FilledButtonTokens.ContainerHeight` — 52.dp, and the same 52.dp as `ChildButtonTokens.ContainerHeight`.
     *
     * Renamed from this port's former `ContainerHeight`, which took the *token's* name for the
     * *member*: upstream names the member `Height` and only the token `ContainerHeight`, exactly as
     * `CardDefaults.Height` (`material3/Card.kt:1088`) already reads here, and as
     * `CompactButtonDefaults.Height` (`material3/Button.kt:2210`) does one object below it. Upstream
     * does keep one `*Height` member with a prefix — `CompactButtonHeight` (`:1932`) — and that one is
     * `@Deprecated` with `ReplaceWith("CompactButtonDefaults.Height")` (`:1925-1931`), i.e. their own
     * migration for a name that sat on the wrong object.
     */
    val Height: Dp = FilledButtonTokens.ContainerHeight

    /**
     * `ButtonDefaults.IconSpacing` (`material3/Button.kt:1945-1949`): the gap between an icon and its
     * text inside a button. [ButtonContent] applies it, and [CompactButtonContent] reads it from here
     * rather than restating the number.
     */
    val IconSpacing: Dp = 6.dp

    /**
     * The filled set, memoised per [ColorScheme]. Building it costs eight token resolves, one
     * `Color.copy`, four `toDisabledColor` and the [ButtonColors] itself, and every `@Tunable` body
     * re-runs on every tune — there is no group skipping yet (`runtime/GroupCensus.kt:7` calls itself a
     * census, not a decision).
     *
     * The key is the scheme instance: [Theme.kt]'s `LocalColorScheme` is a `staticTunationLocalOf`
     * (`Theme.kt:19`) that hands back the same object until a caller re-`provides` one, and
     * `ColorScheme` declares no `equals` (`ColorScheme.kt:14`), so identity is exactly "did the theme
     * move". Hoisting the read above the memo is required, not style: the calculation is
     * `@DisallowTunableCalls` (`runtime/Tunables.kt:18`). The read itself is free and adds no
     * invalidation edge — a provided static local resolves through `StaticValueHolder.readValue`, a
     * plain field read that records no snapshot dependency (`runtime/ValueHolders.kt:9`).
     */
    @Tunable
    fun buttonColors(): ButtonColors {
        val scheme = MaterialTheme.colorScheme
        return remember(scheme) { scheme.filledButtonColors() }
    }

    /**
     * `ButtonDefaults.buttonColors(containerColor, contentColor, secondaryContentColor, iconColor,
     * disabledContainerColor, disabledContentColor, disabledSecondaryContentColor, disabledIconColor)`
     * (`material3/Button.kt:1797-1816`): the default filled set with any of its eight roles replaced,
     * each `Color.Unspecified` keeping the default — upstream routes every argument through
     * `ButtonColors.copy`'s `takeOrElse` (`:2268-2287`).
     *
     * The merge is spelled out here rather than through a `copy` member because this module's
     * [ButtonColors] is a data class, whose generated `copy` already takes these eight plus the two
     * border fields with *plain* defaults: a second, eight-parameter `copy` overload would make every
     * colour-only `copy(...)` call ambiguous, and the generated one would hand back
     * [Color.Unspecified] for a role the caller never mentioned.
     *
     * This object's `border` / `disabledBorder` are a port-side addition with no upstream counterpart
     * (`OutlinedButtonTokens` carries them instead), so an override here never touches them: they ride
     * in from the defaults, which is what upstream's `copy` does for the fields it does not name.
     */
    @Tunable
    fun buttonColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        secondaryContentColor: Color = Color.Unspecified,
        iconColor: Color = Color.Unspecified,
        disabledContainerColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
        disabledSecondaryContentColor: Color = Color.Unspecified,
        disabledIconColor: Color = Color.Unspecified,
    ): ButtonColors = MaterialTheme.colorScheme.filledButtonColors().buttonColorsOverriding(
        containerColor = containerColor,
        contentColor = contentColor,
        secondaryContentColor = secondaryContentColor,
        iconColor = iconColor,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = disabledContentColor,
        disabledSecondaryContentColor = disabledSecondaryContentColor,
        disabledIconColor = disabledIconColor,
    )

    @Tunable
    fun filledTonalButtonColors(): ButtonColors =
        MaterialTheme.colorScheme.filledTonalButtonColors()

    @Tunable
    fun outlinedButtonColors(): ButtonColors = MaterialTheme.colorScheme.outlinedButtonColors()

    /**
     * `ButtonDefaults.childButtonColors()` (`material3/Button.kt:1601-1603`): the transparent,
     * lowest-emphasis set upstream recommends for a `ChildButton`, built from `ChildButtonTokens`
     * with the three disabled roles all resolved from the one `DisabledContentColor`
     * (`material3/Button.kt:2071-2097`).
     *
     * [ChildButton] already called this name: `ChildButton.kt` declares the same two factories as
     * `ButtonDefaults` extensions because this object used to have no room for them. A member beats an
     * extension with the same signature, so those call sites now bind here and the extensions are dead
     * code awaiting removal - their bodies are the same token table, so no colour moves.
     */
    @Tunable
    fun childButtonColors(): ButtonColors = MaterialTheme.colorScheme.childButtonColors()

    /**
     * The overriding overload (`material3/Button.kt:1622-1640`). It names six roles, not eight:
     * upstream hard-codes `containerColor` and `disabledContainerColor` to
     * [Color.Transparent] through `ButtonColors.copy` and gives a caller no way to replace them,
     * because a child button has no container by design. [buttonColorsOverriding] is the same fold
     * this object's other factory uses.
     */
    @Tunable
    fun childButtonColors(
        contentColor: Color = Color.Unspecified,
        secondaryContentColor: Color = Color.Unspecified,
        iconColor: Color = Color.Unspecified,
        disabledContentColor: Color = Color.Unspecified,
        disabledSecondaryContentColor: Color = Color.Unspecified,
        disabledIconColor: Color = Color.Unspecified,
    ): ButtonColors = MaterialTheme.colorScheme.childButtonColors().buttonColorsOverriding(
        containerColor = Color.Transparent,
        contentColor = contentColor,
        secondaryContentColor = secondaryContentColor,
        iconColor = iconColor,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = disabledContentColor,
        disabledSecondaryContentColor = disabledSecondaryContentColor,
        disabledIconColor = disabledIconColor,
    )
}

/**
 * Upstream's `ButtonColors.copy` (`material3/Button.kt:2268-2287`): every named role replaced, every
 * `Color.Unspecified` kept, and the two border fields carried through untouched.
 */
private fun ButtonColors.buttonColorsOverriding(
    containerColor: Color,
    contentColor: Color,
    secondaryContentColor: Color,
    iconColor: Color,
    disabledContainerColor: Color,
    disabledContentColor: Color,
    disabledSecondaryContentColor: Color,
    disabledIconColor: Color,
): ButtonColors = ButtonColors(
    containerColor = containerColor.takeOrElse { this.containerColor },
    contentColor = contentColor.takeOrElse { this.contentColor },
    secondaryContentColor = secondaryContentColor.takeOrElse { this.secondaryContentColor },
    iconColor = iconColor.takeOrElse { this.iconColor },
    disabledContainerColor = disabledContainerColor.takeOrElse { this.disabledContainerColor },
    disabledContentColor = disabledContentColor.takeOrElse { this.disabledContentColor },
    disabledSecondaryContentColor =
        disabledSecondaryContentColor.takeOrElse { this.disabledSecondaryContentColor },
    disabledIconColor = disabledIconColor.takeOrElse { this.disabledIconColor },
    border = border,
    disabledBorder = disabledBorder,
)

private fun ColorScheme.filledButtonColors(): ButtonColors = ButtonColors(
    containerColor = FilledButtonTokens.ContainerColor.resolve(this),
    contentColor = FilledButtonTokens.LabelColor.resolve(this),
    secondaryContentColor = FilledButtonTokens.SecondaryLabelColor.resolve(this)
        .copy(alpha = FilledButtonTokens.SecondaryLabelOpacity),
    iconColor = FilledButtonTokens.IconColor.resolve(this),
    disabledContainerColor = FilledButtonTokens.DisabledContainerColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContainerOpacity),
    disabledContentColor = FilledButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContentOpacity),
    disabledSecondaryContentColor = FilledButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContentOpacity),
    disabledIconColor = FilledButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.variantButtonColors(): ButtonColors = ButtonColors(
    containerColor = FilledButtonTokens.VariantContainerColor.resolve(this),
    contentColor = FilledButtonTokens.VariantLabelColor.resolve(this),
    secondaryContentColor = FilledButtonTokens.VariantSecondaryLabelColor.resolve(this)
        .copy(alpha = FilledButtonTokens.VariantSecondaryLabelOpacity),
    iconColor = FilledButtonTokens.VariantIconColor.resolve(this),
    disabledContainerColor = FilledButtonTokens.DisabledContainerColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContainerOpacity),
    disabledContentColor = FilledButtonTokens.VariantLabelColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContentOpacity),
    disabledSecondaryContentColor = FilledButtonTokens.VariantLabelColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContentOpacity),
    disabledIconColor = FilledButtonTokens.VariantLabelColor.resolve(this)
        .toDisabledColor(FilledButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.filledTonalButtonColors(): ButtonColors = ButtonColors(
    containerColor = FilledTonalButtonTokens.ContainerColor.resolve(this),
    contentColor = FilledTonalButtonTokens.LabelColor.resolve(this),
    secondaryContentColor = FilledTonalButtonTokens.SecondaryLabelColor.resolve(this),
    iconColor = FilledTonalButtonTokens.IconColor.resolve(this),
    disabledContainerColor = FilledTonalButtonTokens.DisabledContainerColor.resolve(this)
        .toDisabledColor(FilledTonalButtonTokens.DisabledContainerOpacity),
    disabledContentColor = FilledTonalButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledTonalButtonTokens.DisabledContentOpacity),
    disabledSecondaryContentColor = FilledTonalButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledTonalButtonTokens.DisabledContentOpacity),
    disabledIconColor = FilledTonalButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(FilledTonalButtonTokens.DisabledContentOpacity),
)

private fun ColorScheme.outlinedButtonColors(): ButtonColors = ButtonColors(
    containerColor = Color.Transparent,
    contentColor = OutlinedButtonTokens.LabelColor.resolve(this),
    secondaryContentColor = OutlinedButtonTokens.SecondaryLabelColor.resolve(this),
    iconColor = OutlinedButtonTokens.IconColor.resolve(this),
    disabledContainerColor = Color.Transparent,
    disabledContentColor = OutlinedButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(OutlinedButtonTokens.DisabledContentOpacity),
    disabledSecondaryContentColor = OutlinedButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(OutlinedButtonTokens.DisabledContentOpacity),
    disabledIconColor = OutlinedButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(OutlinedButtonTokens.DisabledContentOpacity),
    border = BorderStroke(
        OutlinedButtonTokens.ContainerBorderWidth,
        OutlinedButtonTokens.ContainerBorderColor.resolve(this),
    ),
    disabledBorder = BorderStroke(
        OutlinedButtonTokens.ContainerBorderWidth,
        OutlinedButtonTokens.DisabledContainerBorderColor.resolve(this)
            .toDisabledColor(OutlinedButtonTokens.DisabledContainerBorderOpacity),
    ),
)

/**
 * Upstream's private `ColorScheme.defaultChildButtonColors` (`material3/Button.kt:2071-2097`), named
 * after this object's factory the way the three above are named after theirs.
 */
private fun ColorScheme.childButtonColors(): ButtonColors = ButtonColors(
    containerColor = Color.Transparent,
    contentColor = ChildButtonTokens.LabelColor.resolve(this),
    secondaryContentColor = ChildButtonTokens.SecondaryLabelColor.resolve(this),
    iconColor = ChildButtonTokens.IconColor.resolve(this),
    disabledContainerColor = Color.Transparent,
    disabledContentColor = ChildButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(ChildButtonTokens.DisabledContentOpacity),
    disabledSecondaryContentColor = ChildButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(ChildButtonTokens.DisabledContentOpacity),
    disabledIconColor = ChildButtonTokens.DisabledContentColor.resolve(this)
        .toDisabledColor(ChildButtonTokens.DisabledContentOpacity),
)
