package com.huanli233.hibari.wear

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.huanli233.hibari.foundation.Column
import com.huanli233.hibari.foundation.ColumnScope
import com.huanli233.hibari.foundation.attributes.matchParentWidth
import com.huanli233.hibari.foundation.attributes.minHeight
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.CardTokens
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.tokens.ImageCardTokens
import com.huanli233.hibari.wear.tokens.OutlinedCardTokens
import com.huanli233.hibari.wear.tokens.ShapeKeyTokens

/**
 * Ported from androidx.wear.compose.material3.CardColors (material3/Card.kt:1149-1208).
 *
 * Upstream declares it a plain class, not a `data class`, precisely so its [copy] can mean "keep the
 * current value" for an argument left at `Color.Unspecified` (:1168-1183) — the same rule its
 * parameterised `CardDefaults.cardColors(...)` overloads are built on (:879-895). [copy], `equals`
 * and `hashCode` are ported from :1168-1207. Upstream's `@Immutable` is a Compose annotation about
 * snapshot-read stability and Hibari has no equivalent, so it is dropped as everywhere else in this
 * module (see `MotionScheme.kt:20-22`).
 *
 * The parameter order is upstream's (:1151-1156): `appNameColor` and `timeColor` come **before**
 * `titleColor`. This port had them the other way round, which only mattered positionally.
 *
 * There are no disabled variants here, and that is upstream's, not this port's, choice: its KDoc
 * states "Unlike other Material 3 components, Cards do not change their color appearance when they
 * are disabled. All colors remain the same in enabled and disabled states"
 * (material3/Card.kt:1137-1139). The border is likewise not a colour — it is a card parameter
 * (material3/Card.kt:125, and :660 for `OutlinedCard`), so it is a parameter on [Card] /
 * [OutlinedCard] below rather than a field of this class.
 */
class CardColors(
    val containerColor: Color,
    val contentColor: Color,
    val appNameColor: Color,
    val timeColor: Color,
    val titleColor: Color,
    val subtitleColor: Color,
) {

    fun copy(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        appNameColor: Color = Color.Unspecified,
        timeColor: Color = Color.Unspecified,
        titleColor: Color = Color.Unspecified,
        subtitleColor: Color = Color.Unspecified,
    ): CardColors = CardColors(
        containerColor = containerColor.takeOrElse { this.containerColor },
        contentColor = contentColor.takeOrElse { this.contentColor },
        appNameColor = appNameColor.takeOrElse { this.appNameColor },
        timeColor = timeColor.takeOrElse { this.timeColor },
        titleColor = titleColor.takeOrElse { this.titleColor },
        subtitleColor = subtitleColor.takeOrElse { this.subtitleColor },
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is CardColors) return false

        if (containerColor != other.containerColor) return false
        if (contentColor != other.contentColor) return false
        if (appNameColor != other.appNameColor) return false
        if (timeColor != other.timeColor) return false
        if (titleColor != other.titleColor) return false
        if (subtitleColor != other.subtitleColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = containerColor.hashCode()
        result = 31 * result + contentColor.hashCode()
        result = 31 * result + appNameColor.hashCode()
        result = 31 * result + timeColor.hashCode()
        result = 31 * result + titleColor.hashCode()
        result = 31 * result + subtitleColor.hashCode()
        return result
    }

    fun containerSpec(shape: Shape, border: BorderStroke?): ContainerSpec = ContainerSpec(
        shape = shape,
        containerColor = containerColor,
        border = border,
    )
}

/**
 * Ported from androidx.wear.compose.material3.Card / NonClickableCard.
 *
 * Upstream's clickable entry points lead with `onClick` (`material3/Card.kt:117-118` for [Card],
 * :652-653 for `OutlinedCard`), and the clickable [Card] and [OutlinedCard] here do too; the
 * non-clickable twins lead with `modifier` (`NonClickableCard.kt:67-68`, :138-139 for the painter
 * form, :489-490 for `OutlinedCard`). Three of the clickable parameters are not ported, each for one
 * named reason:
 *
 *  - `onLongClick` / `onLongClickLabel` (:120-121, wired at :1265-1273 through `combinedClickable`;
 *    :655-656 for `OutlinedCard`) are ported by [cardLongClickable], which is the Views form of the
 *    same two arguments. The deviation is one of mechanism, not of surface: upstream routes the
 *    long-press through its gesture system (and gates it on `Modifier.semantics`'s
 *    `onClickLabel`/action list), while here it is `View.setOnLongClickListener` plus an
 *    `AccessibilityDelegate` that relabels the node's `ACTION_LONG_CLICK`. A long press does not drive
 *    a card's pressed/ripple state here, because Hibari's press feedback lives in
 *    [ContainerDrawable]'s drawable-state read of a *pressed* view, which a long press does not
 *    change.
 *  - `containerPainter` (:205-207, and the non-clickable twin `NonClickableCard.kt:138-139`) and
 *    `transformation` (:129, :663): see [CardDefaults.scrimColor] for the missing `Painter`/`Brush`
 *    types. The values that overload reads — [CardDefaults.cardWithContainerPainterColors],
 *    [CardDefaults.CardWithContainerPainterContentPadding] and [CardDefaults.scrimColor] — are all
 *    ported, so what is absent is the draw layer, not the numbers.
 *  - `interactionSource` (:128, :662): the ripple and pressed-state carrier, and this module has no
 *    indication system (as recorded in `IconButton.kt:36-42`).
 *
 * There is no `toggleableCard`, `ToggleableCard` or checked-`Card` to port either: a case-insensitive
 * grep of the whole reference tree (`/home/rj/qmce/app-new/src/main/java`) returns zero hits, and the
 * only checked-card-shaped API anywhere in it is v1's `material/ToggleChip.kt:106` `checked` chip,
 * which is a chip and not a card. So no such component is declared here — an invented name for it
 * would read as a port.
 *
 * The non-clickable [Card] and [OutlinedCard] below keep two parameters upstream's twins do not
 * declare: `enabled` (`NonClickableCard.kt:67-74` hardcodes the `enabled = false` path at :82) and
 * `minHeight`, which upstream gives to exactly one of its six single-slot entry points — the clickable
 * [Card] (`material3/Card.kt:127`) — while the other five take `CardDefaults.Height` from
 * `SingleSlotCardImpl`'s default (:1222). Only `minHeight` is read here: with no click handler to
 * gate, `enabled` is inert, and a later pass should drop it rather than wire it.
 *
 * @param shape Defaults to null and resolves in the body, for the same reason as `colors`:
 *   `CardDefaults.shape` reads `MaterialTheme.shapes`.
 * @param colors Defaults to null and resolves in the body: `CardDefaults.cardColors()` reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot.
 * @param onLongClick Called when this card is long clicked (long-pressed). When this callback is
 *   set, `onLongClickLabel` should be set as well.
 * @param onLongClickLabel Semantic / accessibility label for the [onLongClick] action.
 */
@Tunable
fun Card(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: CardColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CardDefaults.ContentPadding,
    minHeight: Dp = CardDefaults.Height,
    content: @Tunable ColumnScope.() -> Unit,
) {
    val resolved = colors ?: CardDefaults.cardColors()
    val resolvedShape = shape ?: CardDefaults.shape
    val titleStyle = MaterialTheme.typography.fromToken(CardTokens.TitleTypography)
    val scope = content
    Column(
        modifier = modifier.cardContainerModifier(
            colors = resolved,
            shape = resolvedShape,
            border = border,
            contentPadding = contentPadding,
            onClick = null,
            minHeight = minHeight,
        ),
    ) {
        // The non-clickable wrapper provides the title pair, not `contentColor`:
        // `LocalContentColor provides colors.titleColor` plus `LocalTextStyle provides
        // CardTokens.TitleTypography` (material3/NonClickableCard.kt:90-93).
        provideContentColorAndStyle(resolved.titleColor, titleStyle) { scope() }
    }
}

@Tunable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: CardColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CardDefaults.ContentPadding,
    minHeight: Dp = CardDefaults.Height,
    content: @Tunable ColumnScope.() -> Unit,
) {
    val resolved = colors ?: CardDefaults.cardColors()
    val resolvedShape = shape ?: CardDefaults.shape
    val titleStyle = MaterialTheme.typography.fromToken(CardTokens.TitleTypography)
    val scope = content
    Column(
        modifier = modifier.cardContainerModifier(
            colors = resolved,
            shape = resolvedShape,
            border = border,
            contentPadding = contentPadding,
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
            enabled = enabled,
            minHeight = minHeight,
        ),
    ) {
        // Same title pair as the non-clickable form: material3/Card.kt:147-150 provides
        // `colors.titleColor` and `CardTokens.TitleTypography` around this overload's content.
        provideContentColorAndStyle(resolved.titleColor, titleStyle) { scope() }
    }
}

@Tunable
fun OutlinedCard(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: CardColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CardDefaults.ContentPadding,
    minHeight: Dp = CardDefaults.Height,
    content: @Tunable ColumnScope.() -> Unit,
) {
    val resolved = colors ?: CardDefaults.outlinedCardColors()
    // Upstream's default here is the non-null `CardDefaults.outlinedCardBorder()`
    // (material3/Card.kt:660). It is `null` + resolved-in-body instead because that factory reads
    // `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable`
    // `$default` method that cannot.
    val resolvedBorder = border ?: CardDefaults.outlinedCardBorder()
    val resolvedShape = shape ?: CardDefaults.shape
    val contentStyle = MaterialTheme.typography.fromToken(OutlinedCardTokens.ContentTypography)
    val scope = content
    Column(
        modifier = modifier.cardContainerModifier(
            colors = resolved,
            shape = resolvedShape,
            border = resolvedBorder,
            contentPadding = contentPadding,
            onClick = null,
            minHeight = minHeight,
        ),
    ) {
        // The content pair, not the title pair: material3/NonClickableCard.kt:512-515 provides
        // `colors.contentColor` and `OutlinedCardTokens.ContentTypography`. It can no longer delegate
        // to [Card] now that [Card] provides the title pair.
        provideContentColorAndStyle(resolved.contentColor, contentStyle) { scope() }
    }
}

/**
 * Upstream's clickable `OutlinedCard(onClick, ...)` (material3/Card.kt:652-687), which routes through
 * `SingleSlotCardImpl` with `containerPainter = null` (:666-668) and then provides the *content* pair
 * around the slot — `colors.contentColor` and `OutlinedCardTokens.ContentTypography` (:680-685) —
 * exactly as the non-clickable [OutlinedCard] above does, and not the title pair the clickable [Card]
 * provides (:147-150).
 *
 * Like upstream it has no `minHeight` parameter: the height there comes from `SingleSlotCardImpl`'s
 * default, `CardDefaults.Height` (:1222), and is applied here the same way. `containerPainter`,
 * `transformation` and `interactionSource` are absent for the three reasons the [Card] header gives,
 * and `onLongClick` / `onLongClickLabel` are ported by [cardLongClickable], as they are there.
 *
 * @param border Upstream's default is the non-null `CardDefaults.outlinedCardBorder()`
 *   (material3/Card.kt:660). It is `null` + resolved-in-body because that factory reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot. Same for [shape] and [colors].
 */
@Tunable
fun OutlinedCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: CardColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CardDefaults.ContentPadding,
    content: @Tunable ColumnScope.() -> Unit,
) {
    val resolved = colors ?: CardDefaults.outlinedCardColors()
    val resolvedBorder = border ?: CardDefaults.outlinedCardBorder()
    val resolvedShape = shape ?: CardDefaults.shape
    val contentStyle = MaterialTheme.typography.fromToken(OutlinedCardTokens.ContentTypography)
    val scope = content
    Column(
        modifier = modifier.cardContainerModifier(
            colors = resolved,
            shape = resolvedShape,
            border = resolvedBorder,
            contentPadding = contentPadding,
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
            enabled = enabled,
            // No `minHeight`: upstream's clickable `OutlinedCard` has no such parameter either and
            // lets `SingleSlotCardImpl`'s default in (material3/Card.kt:1222).
        ),
    ) {
        provideContentColorAndStyle(resolved.contentColor, contentStyle) { scope() }
    }
}

object CardDefaults {
    /**
     * `CardDefaults.shape` (material3/Card.kt:1081-1082) is `CardTokens.Shape.value`, which upstream
     * resolves through `MaterialTheme.shapes` inside a `@Composable` getter — so a theme that
     * overrides its corner radii moves the card with it. Same here, as a `@Tunable` getter; that is
     * also why `Card`'s `shape` parameter is `Shape? = null` rather than defaulting to this property,
     * since a `@Tunable` default expression is hoisted into a method that cannot read the theme.
     */
    val shape: Shape
        @Tunable get() = MaterialTheme.shapes.fromToken(CardTokens.Shape)

    /**
     * `CardDefaults.Height` (material3/Card.kt:1088), whose KDoc there says the card grows past it to
     * fit its content — so the value is a floor, which is what this module can express through
     * `Modifier.minHeight`. Upstream's own parameter for it is also named `minHeight`.
     */
    val Height: Dp = CardTokens.ContainerMinHeight

    /**
     * `CardDefaults.AppImageSize` (material3/Card.kt:1078): the default size of the app icon or
     * image an [AppCard] shows. It is `CardTokens.AppImageSize`, and [CardTokens] is internal, so
     * this is the only way a caller outside the module can name 18.dp.
     */
    val AppImageSize: Dp = CardTokens.AppImageSize

    // Upstream keeps these two `private` (material3/Card.kt:1025-1026); only the PaddingValues built
    // from them are public.
    private val CardHorizontalPadding: Dp = 12.dp
    private val CardVerticalPadding: Dp = 12.dp

    /** `CardDefaults.ImageBottomPadding` (material3/Card.kt:1064): the extra bottom padding a card
     * with an image background takes so more of the image shows. */
    val ImageBottomPadding: Dp = 12.dp

    /** The default content padding used by [Card] — material3/Card.kt:1041-1047. */
    val ContentPadding: PaddingValues = PaddingValues(
        start = CardHorizontalPadding,
        top = CardVerticalPadding,
        end = CardHorizontalPadding,
        bottom = CardVerticalPadding,
    )

    /**
     * `CardDefaults.CardWithContainerPainterContentPadding` (material3/Card.kt:1069-1075): [ContentPadding]
     * with [ImageBottomPadding] added to the bottom, for a card that shows an image background.
     */
    val CardWithContainerPainterContentPadding: PaddingValues = PaddingValues(
        start = CardHorizontalPadding,
        top = CardVerticalPadding,
        end = CardHorizontalPadding,
        bottom = CardVerticalPadding + ImageBottomPadding,
    )

    /**
     * `CardDefaults.minimumVerticalListContentPadding` (material3/Card.kt:1060-1061):
     * `screenHeightFraction(LARGE_VERTICAL_CONTENT_PADDING_FRACTION)`, the content padding a card
     * asks the list for when it sits at the top or bottom edge. Upstream's consumer is
     * `TransformingLazyColumnItemScope.minimumVerticalContentPadding`, which the `wear.lazy` port has
     * no counterpart for (see `ButtonGroup.kt:48-50`), so this is the number without the hook.
     */
    val minimumVerticalListContentPadding: Dp
        @Tunable get() = screenHeightFraction(LARGE_VERTICAL_CONTENT_PADDING_FRACTION)

    /**
     * `CardDefaults.scrimColor` (material3/Card.kt:1032-1038): `ImageCardTokens.OverlayScrimColor`
     * at `OverlayScrimOpacity` (0.5f, `tokens/ImageCardTokens.kt:24-25`).
     *
     * Its two upstream consumers are **not** ported, and the reason is one missing type each:
     * `scrimBrush()` (:1007-1011) returns a `Brush`, and `com.huanli233.hibari.ui.graphics` holds
     * only `Color.kt`; `containerPainter()` (:985-1004) returns a `Painter`, and the image slot here
     * is `Modifier.image(Any?)` (`attributes/ImageAttributes.kt:13`), which sets one drawable on one
     * `ImageView` rather than layering an image under a scrim. The colour itself needs neither, so it
     * is ported and they are not.
     */
    val scrimColor: Color
        @Tunable get() = ImageCardTokens.OverlayScrimColor
            .resolve(MaterialTheme.colorScheme)
            .copy(alpha = ImageCardTokens.OverlayScrimOpacity)

    @Tunable
    fun cardColors(): CardColors = MaterialTheme.colorScheme.filledCardColors()

    /**
     * `CardDefaults.cardColors(...)` (material3/Card.kt:879-895): [cardColors] with individual roles
     * overridden. Every default is `Color.Unspecified` there (:881-886) and [CardColors.copy] reads
     * that as "keep the current value" (:1168-1183), so an omitted role still comes from the theme.
     */
    @Tunable
    fun cardColors(
        containerColor: Color = Color.Unspecified,
        contentColor: Color = Color.Unspecified,
        appNameColor: Color = Color.Unspecified,
        timeColor: Color = Color.Unspecified,
        titleColor: Color = Color.Unspecified,
        subtitleColor: Color = Color.Unspecified,
    ): CardColors = MaterialTheme.colorScheme.filledCardColors().copy(
        containerColor = containerColor,
        contentColor = contentColor,
        appNameColor = appNameColor,
        timeColor = timeColor,
        titleColor = titleColor,
        subtitleColor = subtitleColor,
    )

    @Tunable
    fun outlinedCardColors(): CardColors = MaterialTheme.colorScheme.outlinedCardColors()

    /**
     * `CardDefaults.outlinedCardColors(...)` (material3/Card.kt:915-930): [outlinedCardColors] with
     * individual roles overridden, `Color.Unspecified` keeping the default (:917-921). There is no
     * `containerColor` parameter, upstream's or here.
     */
    @Tunable
    fun outlinedCardColors(
        contentColor: Color = Color.Unspecified,
        appNameColor: Color = Color.Unspecified,
        timeColor: Color = Color.Unspecified,
        titleColor: Color = Color.Unspecified,
        subtitleColor: Color = Color.Unspecified,
    ): CardColors = MaterialTheme.colorScheme.outlinedCardColors().copy(
        // The explicit `containerColor` mirrors upstream (:924). [outlinedCardColors] already returns
        // `Color.Transparent`, so it changes nothing on this side of the call.
        containerColor = Color.Transparent,
        contentColor = contentColor,
        appNameColor = appNameColor,
        timeColor = timeColor,
        titleColor = titleColor,
        subtitleColor = subtitleColor,
    )

    /**
     * `CardDefaults.cardWithContainerPainterColors()` (material3/Card.kt:936-938, backed by
     * :1118-1130): the colours of the image-background card, all of them from `ImageCardTokens`.
     * Upstream leaves `containerColor` at `Color.Unspecified` because the container painter paints
     * it, so passing these to [Card] — which has no painter overload, see [scrimColor] — gives an
     * unspecified container.
     */
    @Tunable
    fun cardWithContainerPainterColors(): CardColors =
        MaterialTheme.colorScheme.cardWithContainerPainterColors()

    /**
     * `CardDefaults.cardWithContainerPainterColors(...)` (material3/Card.kt:950-964):
     * [cardWithContainerPainterColors] with individual roles overridden, `Color.Unspecified` keeping
     * the default (:952-956). Like upstream this copy leaves `containerColor` out, so it stays
     * `Color.Unspecified` for a painter to stand in for — and with no painter slot (see [scrimColor])
     * the same caveat as [cardWithContainerPainterColors] applies.
     */
    @Tunable
    fun cardWithContainerPainterColors(
        contentColor: Color = Color.Unspecified,
        appNameColor: Color = Color.Unspecified,
        timeColor: Color = Color.Unspecified,
        titleColor: Color = Color.Unspecified,
        subtitleColor: Color = Color.Unspecified,
    ): CardColors = MaterialTheme.colorScheme.cardWithContainerPainterColors().copy(
        contentColor = contentColor,
        appNameColor = appNameColor,
        timeColor = timeColor,
        titleColor = titleColor,
        subtitleColor = subtitleColor,
    )

    /**
     * `CardDefaults.outlinedCardBorder(outlineColor, borderWidth)` (material3/Card.kt:1019-1023).
     *
     * `borderWidth` is ported with upstream's default intact — `OutlinedCardTokens.BorderWidth`
     * (:1022) is a plain `1.0.dp` field, not a theme read. `outlineColor` is overridable, but its
     * upstream *default* is not portable: `OutlinedCardTokens.ContainerBorderColor.value` (:1021) is
     * the `@Composable` extension at material3/ColorScheme.kt:410-411, which reads `MaterialTheme`,
     * and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default` method that
     * cannot reach it. So the parameter is `Color?` and `null` resolves in the body against that same
     * token — upstream needs no sentinel because its default already is the token. What a caller
     * sees: `outlinedCardBorder()` and `outlinedCardBorder(Color.Red)` give the same two strokes as
     * upstream gives them, and an explicitly passed `Color.Unspecified` stays unspecified here as it
     * does there.
     */
    @Tunable
    fun outlinedCardBorder(
        outlineColor: Color? = null,
        borderWidth: Dp = OutlinedCardTokens.BorderWidth,
    ): BorderStroke {
        val tokenColor = OutlinedCardTokens.ContainerBorderColor
        return BorderStroke(
            width = borderWidth,
            color = outlineColor ?: tokenColor.resolve(MaterialTheme.colorScheme),
        )
    }
}

private fun ColorScheme.filledCardColors(): CardColors = CardColors(
    containerColor = CardTokens.ContainerColor.resolve(this),
    contentColor = CardTokens.ContentColor.resolve(this),
    appNameColor = CardTokens.AppNameColor.resolve(this),
    timeColor = CardTokens.TimeColor.resolve(this),
    titleColor = CardTokens.TitleColor.resolve(this),
    subtitleColor = CardTokens.SubtitleColor.resolve(this),
)

private fun ColorScheme.outlinedCardColors(): CardColors = CardColors(
    containerColor = Color.Transparent,
    contentColor = OutlinedCardTokens.ContentColor.resolve(this),
    appNameColor = OutlinedCardTokens.AppNameColor.resolve(this),
    timeColor = OutlinedCardTokens.TimeColor.resolve(this),
    titleColor = OutlinedCardTokens.TitleColor.resolve(this),
    subtitleColor = OutlinedCardTokens.SubtitleColor.resolve(this),
)

private fun ColorScheme.cardWithContainerPainterColors(): CardColors = CardColors(
    containerColor = Color.Unspecified,
    contentColor = ImageCardTokens.ContentColor.resolve(this),
    appNameColor = ImageCardTokens.AppNameColor.resolve(this),
    timeColor = ImageCardTokens.TimeColor.resolve(this),
    titleColor = ImageCardTokens.TitleColor.resolve(this),
    subtitleColor = ImageCardTokens.SubtitleColor.resolve(this),
)

internal fun ColorSchemeKeyTokens.resolve(scheme: ColorScheme): Color = scheme.fromToken(this)

/**
 * Upstream's `Modifier.cardContainerModifier` (material3/Card.kt:1248-1284): the one container chain
 * every card in this family is built from — the single-slot [Card] / [OutlinedCard] pair and the
 * opinionated [AppCard] and [TitleCard], whose own files handed it to `AppCardContent` /
 * `TitleCardContent` as the content layout's `modifier` (material3/Card.kt:347-360, :469-482).
 *
 * `onClick = null` is the non-clickable path, and what is dropped there is a real deviation rather
 * than a shorthand: upstream still runs its other branch —
 * `Modifier.focusable(enabled = true, interactionSource = interactionSource)` followed by
 * `Modifier.semantics(mergeDescendants = true) {}` (material3/Card.kt:1275-1277) — so an upstream
 * non-clickable card is a d-pad and rotary target that a screen reader reads as one merged node. Note
 * the `enabled = true` hardcoded there: the `enabled = false` the non-clickable single-slot twin hands
 * in (`NonClickableCard.kt:82`) and the `enabled = true` the opinionated twins hand in
 * (`:246`, `:334`) are both consulted only by the clickable branch (:1266), so that parameter is inert
 * on every non-clickable path upstream too — and here, where it is not even passed. This null branch emits neither half: a non-clickable card
 * is not focusable (`isFocusable` is raised only by [clickable],
 * `attributes/WearInteractionAttributes.kt:42`, and by nothing else in the module) and its children
 * stay separate accessibility nodes, since there is no `mergeDescendants` counterpart anywhere here.
 * `onLongClick`, `onLongClickLabel` and `enabled` are defaulted because the clickable branch is the
 * only thing that reads them, and the non-clickable call sites have no values for them.
 *
 * `minHeight` defaults to `CardDefaults.Height` exactly as upstream's parameter does (:1257): the
 * single-slot cards pass the caller's value, and [AppCard] / [TitleCard] / the clickable
 * [OutlinedCard], which declare no such parameter upstream either, take the default.
 *
 * `matchParentWidth()` is this port's `fillMaxWidth()`, which upstream applies between
 * `cardSizeModifier(minHeight)` and `surface(...)` (:1279-1283). Without it a card shrink-wraps its
 * content and sits flush-left instead of spanning the dial.
 *
 * The textual order below differs from upstream's `cardSizeModifier(minHeight) → fillMaxWidth →
 * surface → clickable → padding`, and that difference is not observable in Views: `minHeight` writes
 * `View.minimumHeight` (`hibari-foundation/.../attributes/ViewAttributes.kt:97-101`) and `padding`
 * writes the view's own padding (`.../PaddingAttributes.kt:15`), two independent sinks on one view, so
 * no chain order can change the result. Upstream's outer `defaultMinSize` floors the padded box; a
 * wrap_content `ViewGroup` measures its own padding into its result and `getSuggestedMinimumHeight()`
 * supplies the same floor, which is the same `max(content + padding, minHeight)` expression.
 *
 * What is NOT settled from source on this machine: the framework's measure step was not read (no
 * `LinearLayout` sources here), so the last sentence rests on standard Android behaviour plus this
 * module's own notes in `InteractiveComponentSize.kt:23,34-38,48-50`. The one test that decides it is
 * a height read of a `CardDefaults.Height` card on a watch: 88.dp end to end is parity, 112.dp means
 * the floor really is inside the padding.
 *
 * There is no painter argument, because there is no `Painter`: upstream's `painter` (:1262) is where
 * a `containerPainter` overload would hand its image in, and [CardColors.containerSpec] carries flat
 * [Color]s only — the reason recorded on [CardDefaults.scrimColor].
 */
internal fun Modifier.cardContainerModifier(
    colors: CardColors,
    shape: Shape,
    border: BorderStroke?,
    contentPadding: PaddingValues,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    enabled: Boolean = true,
    minHeight: Dp = CardDefaults.Height,
): Modifier {
    var chain = matchParentWidth()
        .container(colors.containerSpec(shape, border))
    if (onClick != null) {
        chain = chain.clickable(enabled = enabled, onClick = onClick)
            .cardLongClickable(
                enabled = enabled,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            )
    }
    return chain.padding(contentPadding).minHeight(minHeight)
}

/**
 * The long-press half of upstream's `combinedClickable` (`material3/Card.kt:1265-1273`), shared by
 * the clickable [Card], [OutlinedCard], [TitleCard] and [AppCard] entries: `onLongClick` becomes
 * `View.setOnLongClickListener`, and `onLongClickLabel` becomes the label of the node's
 * `ACTION_LONG_CLICK`.
 *
 * The label is worth the delegate it costs: `AccessibilityNodeInfo.addAction` keys its action map by
 * id, so `AccessibilityAction(ACTION_LONG_CLICK, label)` added after `super` *relabels* the action the
 * framework already announced for a long-clickable view instead of appending a second one, and with no
 * semantics layer in this module that delegate is the whole of a card's accessibility surface. With no
 * label the delegate is left off entirely and the framework's own wording stands, which is upstream's
 * behaviour for `onLongClickLabel = null` too.
 *
 * The listener is written on every tune, including as `null`: an attribute that leaves the chain cannot
 * undo what it set on the view, so a card whose long-press handler disappeared would otherwise keep
 * firing the first closure. `isLongClickable` is only raised, never lowered, because [clickable]
 * already drives it from `enabled` (`attributes/WearInteractionAttributes.kt:36`) and lowering it here
 * would take the press state away from a card that still has a click handler.
 */
internal fun Modifier.cardLongClickable(
    enabled: Boolean,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String?,
): Modifier = thenViewAttribute<View, LongPressCommand>(
    uniqueKey,
    LongPressCommand(enabled, onLongClick, onLongClickLabel),
) { command ->
    val handler = command.onLongClick?.takeIf { command.enabled }
    setOnLongClickListener(handler?.let { action ->
        View.OnLongClickListener { action(); true }
    })
    val label = command.onLongClickLabel
    if (handler != null && label != null) {
        isLongClickable = true
        accessibilityDelegate = LongPressLabelDelegate(label)
    } else {
        accessibilityDelegate = null
    }
}

/** The compared value of [cardLongClickable], so a captured handler that moved re-applies. */
private data class LongPressCommand(
    val enabled: Boolean,
    val onLongClick: (() -> Unit)?,
    val onLongClickLabel: String?,
)

/** Relabels the node's `ACTION_LONG_CLICK` with [label]; see [cardLongClickable]. */
private class LongPressLabelDelegate(private val label: String) : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_LONG_CLICK,
                label,
            )
        )
    }
}
