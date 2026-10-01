package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Column
import com.huanli233.hibari.foundation.ColumnScope
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.foundation.Spacer
import com.huanli233.hibari.foundation.attributes.height
import com.huanli233.hibari.foundation.attributes.matchParentWidth
import com.huanli233.hibari.foundation.attributes.minHeight
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.width
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.CardTokens

/**
 * Ported from androidx.wear.compose.material3.TitleCard.
 *
 * Opinionated five-slot card: title, optional time, optional subtitle, optional body content. The
 * slot layout lives in [TitleCardContent], exactly as upstream shares it between this and the
 * single-slot [Card] — the container chain below is upstream's `cardContainerModifier`
 * (material3/Card.kt:1248-1284) handed to the content layout as its `modifier`
 * (material3/Card.kt:469-490).
 *
 * There is no `TitleCardDefaults` type upstream to port: every value these cards read is a member of
 * [CardDefaults] (material3/Card.kt:861), so this module keeps that too and adds none.
 *
 * Not ported from this overload (material3/Card.kt:450-466):
 *  - `interactionSource` (:463) and `transformation` (:464), for the same reasons as in [Card].
 *    `onLongClick` / `onLongClickLabel` (:454-455) **are** ported, through [cardLongClickable] — the
 *    Views form of upstream's `combinedClickable` (:1265-1273) — as they are in [Card].
 *    `transformation` is the one parameter every entry point here loses, the two non-clickable ones
 *    too (`NonClickableCard.kt:324` and :423 declare it, neither declares `interactionSource`):
 *    `material3/SurfaceTransformation.kt:62-95` is an interface whose members are a `Painter` taken
 *    and returned (:74-78) plus two `GraphicsLayerScope` hooks (:86, :94), and Hibari has neither
 *    type. What that costs a caller: a card inside a transforming list cannot scale, rotate or fade
 *    its container separately from its content — the whole card scrolls as one flat view.
 *  - the `containerPainter` overload (material3/Card.kt:561-604) and its non-clickable twin
 *    (material3/NonClickableCard.kt:413-451). The reason is one type: there is no `Painter` in
 *    Hibari, so nothing here can layer an image under a scrim the way `cardContainerModifier`'s
 *    `painter` argument does (material3/Card.kt:1262). That reason covers the overload only, **not**
 *    the values it reads — [CardDefaults.cardWithContainerPainterColors],
 *    [CardDefaults.CardWithContainerPainterContentPadding] (material3/Card.kt:1069-1075, the pair
 *    those two overloads default to at :572/:574 and :420/:422) and [CardDefaults.scrimColor]
 *    (:1032-1038) are all ported on `CardDefaults`, so a card that grows a draw layer later has its
 *    numbers already. The same split is recorded in `Card.kt`'s own header note.
 *    What is therefore missing is the image-background card itself: the design docs' "image card"
 *    has no `ImageCard` composable in `androidx.wear.compose.material3` — only these
 *    `containerPainter` overloads, whose samples are named `*ImageCardSample`
 *    (material3/Card.kt:173-176, material3/NonClickableCard.kt:117-120) — and no such component is
 *    declared here either. The gap is exactly one parameter wide: strip `containerPainter` from
 *    :562-577 / :414-424 and what is left is the signature landed here for that entry point, slot for
 *    slot, the only other difference being the `colors` and `contentPadding` defaults named above. So
 *    the missing draw layer is the whole distance, and a caller cannot reach an image-background
 *    TitleCard by any combination of the parameters that are here.
 *
 * `minHeight` is deliberately *not* a parameter: upstream's `TitleCard` has none, and
 * `cardContainerModifier` pins it to `CardDefaults.Height` (material3/Card.kt:1257) through this
 * path, which is what [titleCardContainer] does.
 *
 * @param onClick Will be called when the user clicks the card
 * @param title A slot for displaying the title of the card, expected to be one or two lines of text.
 * @param modifier Modifier to be applied to the card
 * @param onLongClick Called when this card is long clicked (long-pressed). When this callback is
 *   set, [onLongClickLabel] should be set as well.
 * @param onLongClickLabel Semantic / accessibility label for the [onLongClick] action.
 * @param time An optional slot for displaying the time relevant to the contents of the card,
 *   expected to be a short piece of text. Depending on whether we have a [content] or not, can be
 *   placed at the end of the [title] line or above it.
 * @param subtitle An optional slot for displaying the subtitle of the card, expected to be one line
 *   of text.
 * @param enabled Controls the enabled state of the card. When false, this card will not be
 *   clickable and there will be no ripple effect on click. Wear cards do not have any specific
 *   elevation or alpha differences when not enabled - they are simply not clickable.
 * @param shape Defines the card's shape. It is strongly recommended to use the default as this
 *   shape is a key characteristic of the Wear Material Theme
 * @param colors [CardColors] that will be used to resolve the colors used for this card in
 *   different states. See [CardDefaults.cardColors].
 * @param border A BorderStroke object which is used for drawing outlines.
 * @param contentPadding The spacing values to apply internally between the container and the
 *   content
 * @param content The optional body content of the card. If not provided then title and subtitle are
 *   expected to be provided
 */
@Tunable
fun TitleCard(
    onClick: () -> Unit,
    title: @Tunable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    time: (@Tunable () -> Unit)? = null,
    subtitle: (@Tunable ColumnScope.() -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: CardColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CardDefaults.ContentPadding,
    content: (@Tunable () -> Unit)? = null,
) {
    val resolved = colors ?: CardDefaults.cardColors()
    // Upstream's `CompositionLocalProvider(LocalContentColor provides colors.contentColor)` around
    // the whole card (material3/Card.kt:467-468), *inside* which each slot re-provides its own.
    provideContentColor(resolved.contentColor) {
        TitleCardContent(
            title = title,
            modifier = modifier.titleCardContainer(
                colors = resolved,
                shape = shape ?: CardDefaults.shape,
                border = border,
                contentPadding = contentPadding,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
                enabled = enabled,
            ),
            time = time,
            subtitle = subtitle,
            colors = resolved,
            content = content,
        )
    }
}

/**
 * Ported from androidx.wear.compose.material3.TitleCard (non-clickable form,
 * material3/NonClickableCard.kt:315-352).
 *
 * This overload has no `enabled` parameter upstream either — with no click handling there is nothing
 * to disable — so it differs from [Card]'s non-clickable form, which does take one. Upstream's
 * `focusable(enabled = true, interactionSource)` and the `mergeDescendants` semantics wrapper
 * (material3/Card.kt:1275-1277) are not portable here and are dropped, as they are in [Card].
 * Upstream's `transformation` (:324) is dropped too, for the reason its clickable twin's header gives.
 *
 * @param title A slot for displaying the title of the card, expected to be one or two lines of text.
 * @param modifier Modifier to be applied to the card
 * @param time An optional slot for displaying the time relevant to the contents of the card,
 *   expected to be a short piece of text. Depending on whether we have a [content] or not, can be
 *   placed at the end of the [title] line or above it.
 * @param subtitle An optional slot for displaying the subtitle of the card, expected to be one line
 *   of text.
 * @param shape Defines the card's shape. It is strongly recommended to use the default as this
 *   shape is a key characteristic of the Wear Material Theme
 * @param colors [CardColors] that will be used to resolve the colors used for this card. See
 *   [CardDefaults.cardColors].
 * @param border A BorderStroke object which is used for drawing outlines.
 * @param contentPadding The spacing values to apply internally between the container and the
 *   content
 * @param content The optional body content of the card. If not provided then title and subtitle are
 *   expected to be provided
 */
@Tunable
fun TitleCard(
    title: @Tunable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    time: (@Tunable () -> Unit)? = null,
    subtitle: (@Tunable ColumnScope.() -> Unit)? = null,
    shape: Shape? = null,
    colors: CardColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CardDefaults.ContentPadding,
    content: (@Tunable () -> Unit)? = null,
) {
    val resolved = colors ?: CardDefaults.cardColors()
    provideContentColor(resolved.contentColor) {
        TitleCardContent(
            title = title,
            modifier = modifier.titleCardContainer(
                colors = resolved,
                shape = shape ?: CardDefaults.shape,
                border = border,
                contentPadding = contentPadding,
                onClick = null,
                // Upstream's non-clickable twin forwards `onLongClick = null` / `onLongClickLabel =
                // null` with `enabled = true` (material3/NonClickableCard.kt:332-334) — note it is
                // `true` there, unlike the single-slot non-clickable Card's `false` at :82.
                onLongClick = null,
                onLongClickLabel = null,
                enabled = true,
            ),
            time = time,
            subtitle = subtitle,
            colors = resolved,
            content = content,
        )
    }
}

/**
 * Ported from androidx.wear.compose.material3.TitleCardContent (material3/Card.kt:712-773).
 *
 * The layout only, with no container: use it inside [Card]'s generic `content` slot to rebuild this
 * slot structure around something that has to wrap the whole card (upstream's example is
 * `OneHandedGestureIndicator`, material3/Card.kt:692-695).
 *
 * Upstream's `Row(verticalAlignment = Alignment.Top)` (:738) is the default for a horizontal
 * `LinearLayout`, so nothing has to be done for it here.
 *
 * @param title A slot for displaying the title of the card, expected to be one of two lines of
 *   text.
 * @param modifier Modifier to be applied to the title card content layout.
 * @param time An optional slot for displaying the time relevant to the contents of the card,
 *   expected to be a short piece of text. Depending on whether we have a [content] or not, can be
 *   placed at the end of the [title] line or above it.
 * @param subtitle An optional slot for displaying the subtitle of the card, expected to be one line
 *   of text.
 * @param colors [CardColors] that will be used to resolve the colors used for this card.
 * @param content The optional body content of the card.
 */
@Tunable
fun TitleCardContent(
    title: @Tunable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    time: (@Tunable () -> Unit)? = null,
    subtitle: (@Tunable ColumnScope.() -> Unit)? = null,
    colors: CardColors? = null,
    content: (@Tunable () -> Unit)? = null,
) {
    val resolved = colors ?: CardDefaults.cardColors()
    val typography = MaterialTheme.typography
    val titleStyle = typography.fromToken(CardTokens.TitleTypography)
    val timeStyle = typography.fromToken(CardTokens.TimeTypography)
    val contentStyle = typography.fromToken(CardTokens.ContentTypography)
    val subtitleStyle = typography.fromToken(CardTokens.SubtitleTypography)
    val titleSlot = title
    val timeSlot = time
    val subtitleSlot = subtitle
    val contentSlot = content
    Column(modifier = modifier) {
        // NB We are in ColumnScope, so spacing between elements is done with a Spacer whose height
        // is the gap upstream uses (material3/Card.kt:732-733).
        if (contentSlot == null && timeSlot != null) {
            provideContentColorAndStyle(resolved.timeColor, timeStyle) { timeSlot() }
            Spacer(Modifier.height(4.dp))
        }
        Row(modifier = Modifier.matchParentWidth()) {
            Row(modifier = Modifier.weight(1f)) {
                provideContentColorAndStyle(resolved.titleColor, titleStyle) { titleSlot() }
            }
            if (contentSlot != null) {
                Spacer(Modifier.width(4.dp))
                if (timeSlot != null) {
                    provideContentColorAndStyle(resolved.timeColor, timeStyle) { timeSlot() }
                }
            }
        }
        if (contentSlot != null) {
            Spacer(Modifier.height(2.dp))
            provideContentColorAndStyle(resolved.contentColor, contentStyle) { contentSlot() }
        }
        if (subtitleSlot != null) {
            val gap = if (timeSlot == null && contentSlot == null) 2.dp else 6.dp
            Spacer(Modifier.height(gap))
            provideContentColorAndStyle(resolved.subtitleColor, subtitleStyle) { subtitleSlot() }
        }
    }
}

/**
 * This port's [Card] container chain, for a card that lays its own content out instead of using
 * [Card]'s single slot.
 *
 * The textual order below differs from upstream's `cardSizeModifier(minHeight) → fillMaxWidth →
 * surface → clickable → padding` (material3/Card.kt:1279-1283), and that difference is not
 * observable in Views: `minHeight` writes `View.minimumHeight`
 * (`hibari-foundation/.../attributes/ViewAttributes.kt:97-101`) and `padding` writes the view's own
 * padding (`.../PaddingAttributes.kt:15`), two independent sinks on one view, so no chain order can
 * change the result. Upstream's outer `defaultMinSize` floors the padded box; a wrap_content
 * `ViewGroup` measures its own padding into its result and `getSuggestedMinimumHeight()` supplies
 * the same floor, which is the same `max(content + padding, minHeight)` expression. [Card]'s own
 * chains end the same way for the same reason.
 *
 * What is NOT settled from source on this machine: the framework's measure step was not read (no
 * `LinearLayout` sources here), so the last sentence rests on standard Android behaviour plus this
 * module's own notes in `InteractiveComponentSize.kt:23,34-38,48-50`. The one test that decides it is
 * a height read of a `CardDefaults.Height` card on a watch: 88.dp end to end is parity, 112.dp means
 * the floor really is inside the padding.
 */
private fun Modifier.titleCardContainer(
    colors: CardColors,
    shape: Shape,
    border: BorderStroke?,
    contentPadding: PaddingValues,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String?,
    enabled: Boolean,
): Modifier {
    var chain = matchParentWidth()
        .container(colors.containerSpec(shape, border))
    if (onClick != null) {
        chain = chain.clickable(enabled = enabled, onClick = onClick)
            .cardLongClickable(enabled = enabled, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel)
    }
    return chain.padding(contentPadding).minHeight(CardDefaults.Height)
}
