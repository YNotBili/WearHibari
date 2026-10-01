package com.huanli233.hibari.wear

import android.view.Gravity
import android.widget.LinearLayout
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
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.CardTokens

/**
 * Ported from androidx.wear.compose.material3.AppCard.
 *
 * Opinionated five-slot card for information about an application: app image + app name + time on
 * the first row, title on the second, then the body content. The slot layout lives in
 * [AppCardContent], which receives this card's container chain as its `modifier` exactly as
 * upstream hands it the result of `cardContainerModifier` (material3/Card.kt:347-369).
 *
 * There is no `AppCardDefaults` type upstream to port: the one value specific to this card, the
 * 18.dp app-icon size, is `CardDefaults.AppImageSize` (material3/Card.kt:1078,
 * `CardTokens.AppImageSize`) and it already sits on [CardDefaults], which lives in `Card.kt` in this
 * same package — so this file reads it rather than re-declaring it, and a caller sizes the
 * `appImage` slot with that constant. There is likewise no `AppCardColors` upstream: a whole-tree
 * grep of the reference source for that name is empty, and `AppCard` (:327) takes plain [CardColors].
 *
 * Upstream declares exactly two public `AppCard` entry points and this file carries one function per
 * entry point, each with upstream's parameters in upstream's order: the clickable
 * `AppCard(onClick, appName, title, ...)` (material3/Card.kt:327-371) and the non-clickable
 * `AppCard(appName, title, ...)` (material3/NonClickableCard.kt:226-265). Neither is an addition of
 * this port, and there is no third to write: unlike [TitleCard], `AppCard` has no `containerPainter`
 * form upstream at all — the same whole-tree grep, this time for `fun AppCard(`, returns those two
 * inside `androidx.wear.compose.material3` and nothing else — so an image-background `AppCard` is
 * absent from upstream's surface, not from this port.
 *
 * Not ported from this overload (material3/Card.kt:327-343):
 *  - `interactionSource` (:339) and `transformation` (:340), for the same reasons as in [Card].
 *    `onLongClick` / `onLongClickLabel` (:332-333) **are** ported, through [cardLongClickable] — the
 *    Views form of upstream's `combinedClickable` (:1265-1273) — as they are in [Card] and [TitleCard].
 *    The non-clickable form loses `transformation` too (`NonClickableCard.kt:234`; it declares no
 *    `interactionSource` to lose) and that is the one parameter every entry point in this file drops:
 *    `material3/SurfaceTransformation.kt:62-95` asks for a `Painter` taken and returned (:74-78) plus
 *    two `GraphicsLayerScope` hooks (:86, :94), and Hibari has neither type, so a card here cannot
 *    morph its container separately from its content while a transforming list scrolls it away.
 *
 * `minHeight` is not a parameter here either: upstream's `AppCard` has none, and
 * `cardContainerModifier` pins it to `CardDefaults.Height` (material3/Card.kt:1257).
 *
 * @param onClick Will be called when the user clicks the card
 * @param appName A slot for displaying the application name, expected to be a single line of start
 *   aligned text
 * @param title A slot for displaying the title of the card, expected to be one or two lines of
 *   start aligned text
 * @param modifier Modifier to be applied to the card
 * @param onLongClick Called when this card is long clicked (long-pressed). When this callback is
 *   set, [onLongClickLabel] should be set as well.
 * @param onLongClickLabel Semantic / accessibility label for the [onLongClick] action.
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
 * @param appImage A slot for a small (`CardDefaults.AppImageSize` x `CardDefaults.AppImageSize`)
 *   image associated with the application.
 * @param time A slot for displaying the time relevant to the contents of the card, expected to be a
 *   short piece of end aligned text
 * @param content The main slot for a content of this card
 */
@Tunable
fun AppCard(
    onClick: () -> Unit,
    appName: @Tunable RowScope.() -> Unit,
    title: @Tunable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: CardColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CardDefaults.ContentPadding,
    appImage: (@Tunable RowScope.() -> Unit)? = null,
    time: (@Tunable RowScope.() -> Unit)? = null,
    content: @Tunable ColumnScope.() -> Unit,
) {
    val resolved = colors ?: CardDefaults.cardColors()
    // material3/Card.kt:345-346: the card scopes its content colour before the container, and each
    // slot inside re-scopes its own.
    provideContentColor(resolved.contentColor) {
        AppCardContent(
            appName = appName,
            title = title,
            modifier = modifier.cardContainerModifier(
                colors = resolved,
                shape = shape ?: CardDefaults.shape,
                border = border,
                contentPadding = contentPadding,
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
                enabled = enabled,
            ),
            colors = resolved,
            appImage = appImage,
            time = time,
            content = content,
        )
    }
}

/**
 * Ported from androidx.wear.compose.material3.AppCard (non-clickable form,
 * material3/NonClickableCard.kt:226-265).
 *
 * No `enabled` parameter, as upstream: with no click handling there is nothing to disable.
 * Upstream's `focusable(enabled = true, interactionSource)` and `mergeDescendants` semantics wrapper
 * (material3/Card.kt:1275-1277) are not portable here and are dropped, as they are in [Card].
 * Upstream's `transformation` (material3/NonClickableCard.kt:234) is dropped as well, for the one
 * reason this file's header gives.
 *
 * @param appName A slot for displaying the application name, expected to be a single line of start
 *   aligned text
 * @param title A slot for displaying the title of the card, expected to be one or two lines of
 *   start aligned text
 * @param modifier Modifier to be applied to the card
 * @param shape Defines the card's shape. It is strongly recommended to use the default as this
 *   shape is a key characteristic of the Wear Material Theme
 * @param colors [CardColors] that will be used to resolve the colors used for this card. See
 *   [CardDefaults.cardColors].
 * @param border A BorderStroke object which is used for drawing outlines.
 * @param contentPadding The spacing values to apply internally between the container and the
 *   content
 * @param appImage A slot for a small (`CardDefaults.AppImageSize` x `CardDefaults.AppImageSize`)
 *   image associated with the application.
 * @param time A slot for displaying the time relevant to the contents of the card, expected to be a
 *   short piece of end aligned text.
 * @param content The main slot for a content of this card
 */
@Tunable
fun AppCard(
    appName: @Tunable RowScope.() -> Unit,
    title: @Tunable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    colors: CardColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CardDefaults.ContentPadding,
    appImage: (@Tunable RowScope.() -> Unit)? = null,
    time: (@Tunable RowScope.() -> Unit)? = null,
    content: @Tunable ColumnScope.() -> Unit,
) {
    val resolved = colors ?: CardDefaults.cardColors()
    provideContentColor(resolved.contentColor) {
        AppCardContent(
            appName = appName,
            title = title,
            modifier = modifier.cardContainerModifier(
                colors = resolved,
                shape = shape ?: CardDefaults.shape,
                border = border,
                contentPadding = contentPadding,
                onClick = null,
            ),
            colors = resolved,
            appImage = appImage,
            time = time,
            content = content,
        )
    }
}

/**
 * Ported from androidx.wear.compose.material3.AppCardContent (material3/Card.kt:797-858).
 *
 * The layout only, with no container: use it inside [Card]'s generic `content` slot to rebuild this
 * slot structure around something that has to wrap the whole card (upstream's example is
 * `OneHandedGestureIndicator`, material3/Card.kt:779-782).
 *
 * Two of upstream's row parameters have no counterpart on this module's [Row], and both are
 * reproduced rather than dropped:
 *  - `verticalAlignment = Alignment.CenterVertically` (:811, :840) becomes container gravity on the
 *    `LinearLayout` behind the row, which is the Views equivalent and, unlike a per-child
 *    `layout_gravity`, can also reach the caller-supplied slots whose modifiers this layout does not
 *    own. The second row's centring is a no-op upstream — one child, row height equals child height
 *    — and is applied anyway.
 *  - `horizontalArrangement = Arrangement.SpaceBetween` (:812) needs no arrangement at all: the
 *    leading group already carries `weight(1f)`, so it absorbs the slack and the time sits at the end
 *    — the same distribution Compose computes once a weighted child has taken the remaining width.
 *
 * @param appName A slot for providing the app name.
 * @param title A slot for providing the card's title.
 * @param modifier Modifier to be applied to the app card content layout.
 * @param colors [CardColors] that will be used to resolve the content, title, and app name colors
 *   in different states. See [CardDefaults.cardColors].
 * @param appImage A slot for providing the app icon, expected to be `CardDefaults.AppImageSize`
 *   (18.dp) square — upstream's only app-icon size at all (material3/Card.kt:320-321), and the
 *   constant is on [CardDefaults] here, so a caller names it instead of hard-coding 18.dp.
 * @param time A slot for providing the time.
 * @param content A slot for providing the card's body content.
 */
@Tunable
fun AppCardContent(
    appName: @Tunable RowScope.() -> Unit,
    title: @Tunable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    colors: CardColors? = null,
    appImage: (@Tunable RowScope.() -> Unit)? = null,
    time: (@Tunable RowScope.() -> Unit)? = null,
    content: @Tunable ColumnScope.() -> Unit,
) {
    val resolved = colors ?: CardDefaults.cardColors()
    val typography = MaterialTheme.typography
    val appNameStyle = typography.fromToken(CardTokens.AppNameTypography)
    val timeStyle = typography.fromToken(CardTokens.TimeTypography)
    val titleStyle = typography.fromToken(CardTokens.TitleTypography)
    val contentStyle = typography.fromToken(CardTokens.ContentTypography)
    val appImageSlot = appImage
    val appNameSlot = appName
    val timeSlot = time
    val titleSlot = title
    val contentSlot = content
    Column(modifier = modifier) {
        // NB We are in ColumnScope, so spacing between elements is done with a Spacer whose height
        // is the gap upstream uses (material3/Card.kt:807-808).
        Row(modifier = Modifier.matchParentWidth().appCardCenterVertically()) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .appCardCenterVertically(),
            ) {
                if (appImageSlot != null) {
                    appImageSlot()
                    Spacer(Modifier.width(4.dp))
                }
                provideContentColorAndStyle(resolved.appNameColor, appNameStyle) { appNameSlot() }
            }
            if (timeSlot != null) {
                Spacer(Modifier.width(6.dp))
                provideContentColorAndStyle(resolved.timeColor, timeStyle) { timeSlot() }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.matchParentWidth().appCardCenterVertically()) {
            provideContentColorAndStyle(resolved.titleColor, titleStyle) { titleSlot() }
        }
        Spacer(modifier = Modifier.height(2.dp))
        provideContentColorAndStyle(resolved.contentColor, contentStyle) { contentSlot() }
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
private fun Modifier.appCardContainer(
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
            .cardLongClickable(
                enabled = enabled,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            )
    }
    return chain.padding(contentPadding).minHeight(CardDefaults.Height)
}

/** `Row(verticalAlignment = Alignment.CenterVertically)` on a Views row: container gravity. */
private fun Modifier.appCardCenterVertically(): Modifier =
    thenViewAttribute<LinearLayout, Int>(uniqueKey, Gravity.CENTER_VERTICAL) {
        gravity = it
    }
