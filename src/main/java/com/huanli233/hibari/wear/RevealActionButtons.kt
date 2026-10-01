package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.attributes.height
import com.huanli233.hibari.foundation.attributes.paddingRelative
import com.huanli233.hibari.foundation.attributes.width
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.TunationLocalProvider
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.CircleShape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.thenLayoutAttribute
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.SwipeToRevealTokens
import com.huanli233.hibari.wear.view.WearRevealActionRowView
import com.huanli233.hibari.wear.view.WearRevealActionButtonView

/** `ICON_AND_TEXT_PADDING` (`material3/SwipeToReveal.kt:1941`). */
internal val RevealIconAndTextPadding: Dp = 4.dp

/** `ACTION_BUTTON_CONTENT_PADDING` (`:1943`). */
internal val RevealActionButtonContentPadding: Dp = 4.dp

/** `UNDO_ACTION_BUTTON_CONTENT_PADDING` (`:1945`). */
internal val RevealUndoButtonContentPadding: Dp = 14.dp

/**
 * Scope for the actions of a [SwipeToReveal] composable. Used to define the primary, secondary, undo
 * primary and undo secondary actions.
 *
 * Ported from `androidx.wear.compose.material3.SwipeToRevealScope`
 * (`material3/SwipeToReveal.kt:842-963`). Upstream's `coroutineScope` parameter (`:848`, the
 * composition's `rememberCoroutineScope`) is not part of this constructor: a click suspends on the
 * reveal box's own animation scope instead, which is where the settle and the drag already live, so a
 * new gesture interrupts a click's animation exactly as upstream's mutex does.
 *
 * The three builders are declared as `@Tunable` extension functions on this scope rather than as
 * members, because Hibari's tuner processes top-level declarations and no `@Tunable` member function
 * exists anywhere in this module; the call shape inside a `primaryAction = { … }` slot is unchanged.
 */
public class SwipeToRevealScope internal constructor(
    internal val revealState: RevealState,
    internal val hasSecondaryAction: Boolean,
    internal val hasPrimaryUndo: Boolean,
    internal val hasSecondaryUndo: Boolean,
    /**
     * Which [RevealState.lastActionType] the undo slot built through this scope answers to. Upstream
     * never needs it: `AnimatedContent` picks `undoSecondaryAction` or `undoPrimaryAction` at call time
     * (`material3/SwipeToReveal.kt:489-498`), while a `ViewGroup` has to know which of its two children
     * to hide, so [com.huanli233.hibari.wear.SwipeToReveal] hands the two undo slots scopes that differ
     * only here. Unused by the actions arm.
     */
    internal val undoSlotMarker: RevealActionType = RevealActionType.PrimaryAction,
)

/**
 * Provides a button for the primary action of a [SwipeToReveal].
 *
 * When first revealed the primary action displays an icon and then, if fully swiped, it additionally
 * shows text. By default the button height is [ButtonDefaults.Height] (`material3/SwipeToReveal.kt:884`
 * reads `ButtonDefaults.Height`, which is `FilledButtonTokens.ContainerHeight`, `Button.kt:1893`) — and
 * it is recommended to set it to
 * [SwipeToRevealDefaults.LargeActionButtonHeight] for large content items like [Card]s.
 *
 * @param onClick Callback to be executed when the action is performed via a button click.
 * @param icon Icon composable to be displayed for this action.
 * @param text Text composable to be displayed when the user fully swipes to execute the primary action.
 * @param modifier [Modifier] to be applied on the composable.
 * @param containerColor Container color for this action. This can be [Color.Unspecified], and in case
 *   it is, a default color will be used.
 * @param contentColor Content color for this action. This can be [Color.Unspecified], and in case it is,
 *   a default color will be used.
 */
@Tunable
public fun SwipeToRevealScope.PrimaryActionButton(
    onClick: () -> Unit,
    icon: @Tunable () -> Unit,
    text: @Tunable () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    RevealActionButton(
        scope = this,
        revealActionType = RevealActionType.PrimaryAction,
        onClick = onClick,
        icon = icon,
        text = text,
        modifier = modifier.height(ButtonDefaults.Height),
        containerColor = containerColor,
        contentColor = contentColor,
        // `shouldSetLastActionType = true` (`:885`).
        shouldSetLastActionType = true,
    )
}

/**
 * Provides a button for the optional secondary action of a [SwipeToReveal].
 *
 * Secondary action only displays an icon, because, unlike the primary action, it is never extended to
 * full width so does not have room to display text. By default the button height is
 * [ButtonDefaults.Height] (`:921`) — and it is recommended
 * to set it to [SwipeToRevealDefaults.LargeActionButtonHeight] for large content items like [Card]s.
 *
 * @param onClick Callback to be executed when the action is performed via a button click.
 * @param icon Icon composable to be displayed for this action.
 * @param modifier [Modifier] to be applied on the composable.
 * @param containerColor Container color for this action. This can be [Color.Unspecified], and in case it
 *   is, a default color will be used.
 * @param contentColor Content color for this action. This can be [Color.Unspecified], and in case it is,
 *   a default color will be used.
 */
@Tunable
public fun SwipeToRevealScope.SecondaryActionButton(
    onClick: () -> Unit,
    icon: @Tunable () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    RevealActionButton(
        scope = this,
        revealActionType = RevealActionType.SecondaryAction,
        onClick = onClick,
        icon = icon,
        // `SwipeToRevealAction(onClick, icon, null, …)` (`:916`): the secondary action has no text.
        text = null,
        modifier = modifier.height(ButtonDefaults.Height),
        containerColor = containerColor,
        contentColor = contentColor,
        // `shouldSetLastActionType = hasSecondaryUndo` (`:922`) — stamping the action type only means
        // something when there is an undo to show afterwards.
        shouldSetLastActionType = hasSecondaryUndo,
    )
}

/**
 * Provides a button for the undo action of a [SwipeToReveal]. When the user performs either the primary
 * or secondary action, and the corresponding undo action is provided, the initial action will be hidden
 * once [SwipeToReveal] has animated to the fully revealed state, and the undo action button will be
 * displayed.
 *
 * It is recommended to always use the default undo button height, [ButtonDefaults.Height].
 *
 * @param onClick Callback to be executed when the action is performed via a button click.
 * @param text Text composable to indicate what the undo action is, to be displayed when the user executes
 *   the primary action.
 * @param modifier [Modifier] to be applied on the composable.
 * @param icon Optional Icon composable to be displayed for this action.
 * @param containerColor Container color for this action. This can be [Color.Unspecified], and in case it
 *   is, a default color will be used.
 * @param contentColor Content color for this action. This can be [Color.Unspecified], and in case it is,
 *   a default color will be used.
 */
@Tunable
public fun SwipeToRevealScope.UndoActionButton(
    onClick: () -> Unit,
    text: @Tunable () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Tunable () -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    RevealActionButton(
        scope = this,
        revealActionType = RevealActionType.UndoAction,
        onClick = onClick,
        icon = icon,
        text = text,
        modifier = modifier.height(ButtonDefaults.Height),
        containerColor = containerColor,
        contentColor = contentColor,
    )
}

/**
 * The shared body of the three slot builders — upstream's `ActionButton`
 * (`material3/SwipeToReveal.kt:1062-1196`), an `internal` function which is why the shape of this one
 * carries an explicit `scope` rather than a receiver.
 *
 * Two upstream pieces are resolved differently, the second one module-wide rather than here:
 *  - `buttonColors(containerColor = …, contentColor = …)` (`:1160`) copies the theme's default
 *    `ButtonColors` over the two given colours; Hibari's [ButtonDefaults] has no such parameterised
 *    factory, so the two colours are resolved here with the same `takeOrElse` defaults upstream applies
 *    just above (`:1074-1107`) and handed straight to a circular [ContainerSpec]. The disabled variants
 *    are not carried: upstream's `ActionButton` never passes `enabled`, so they can never render.
 *  - The button is touch-clickable only. Upstream's `ActionButton` *is* a `Button` (`:1122`), and wear
 *    m3's `Button` routes its click through Compose's `Modifier.clickable`
 *    (`materialcore/RoundButton.kt:86`), which per Compose also makes the element a keyboard/D-pad
 *    focus stop — `androidx.compose.foundation` is not in the reference tree, so that half is not
 *    readable here and is stated as unverified rather than asserted. The click attribute applied below
 *    deliberately mirrors this module's own `wear.attributes.clickable`
 *    (`attributes/WearInteractionAttributes.kt:22-32`), which sets `isClickable` / `setOnClickListener`
 *    and never `isFocusable`, so the reveal's actions are exactly as reachable as every other ported
 *    button here and no more. Raising `isFocusable` alone would not close it either: [ContainerSpec] and
 *    [ContainerDrawable] carry no focused variant (no `state_focused` read anywhere in either file), so
 *    a focus stop would be drawn with no focus indication. That is a module-wide item, not a
 *    reveal-specific one, and it is on the integration list rather than patched here.
 *
 * The row that hosts the button is told two things about the slot through its `LayoutParams`
 * ([WearRevealActionRowView.LayoutParams]): which action it is (weight and gap, `:587-624`) and, for the
 * undo arm, which `lastActionType` selects it (`:489-498`).
 */
@Tunable
private fun RevealActionButton(
    scope: SwipeToRevealScope,
    revealActionType: RevealActionType,
    onClick: () -> Unit,
    icon: (@Tunable () -> Unit)?,
    text: (@Tunable () -> Unit)?,
    modifier: Modifier,
    containerColor: Color,
    contentColor: Color,
    shouldSetLastActionType: Boolean = false,
) {
    val state = scope.revealState
    val colorScheme = MaterialTheme.colorScheme
    val resolvedContainer = containerColor.takeOrElse {
        when (revealActionType) {
            RevealActionType.PrimaryAction ->
                SwipeToRevealTokens.PrimaryActionContainerColor.resolve(colorScheme)
            RevealActionType.SecondaryAction ->
                SwipeToRevealTokens.SecondaryActionContainerColor.resolve(colorScheme)
            RevealActionType.UndoAction ->
                SwipeToRevealTokens.UndoActionContainerColor.resolve(colorScheme)
            else -> Color.Unspecified
        }
    }
    val resolvedContent = contentColor.takeOrElse {
        when (revealActionType) {
            RevealActionType.PrimaryAction ->
                SwipeToRevealTokens.PrimaryActionContentColor.resolve(colorScheme)
            RevealActionType.SecondaryAction ->
                SwipeToRevealTokens.SecondaryActionContentColor.resolve(colorScheme)
            RevealActionType.UndoAction ->
                SwipeToRevealTokens.UndoActionContentColor.resolve(colorScheme)
            else -> Color.Unspecified
        }
    }
    // `fullScreenPaddingDp = screenWidthFraction(FULL_SCREEN_PADDING_FRACTION)`, applied to the undo
    // button only (`:1108-1118`).
    val fullScreenPadding =
        if (revealActionType == RevealActionType.UndoAction) {
            (currentContext.resources.configuration.screenWidthDp *
                RevealMath.FULL_SCREEN_PADDING_FRACTION).dp
        } else {
            0.dp
        }
    val contentPadding =
        if (revealActionType == RevealActionType.UndoAction) {
            RevealUndoButtonContentPadding
        } else {
            RevealActionButtonContentPadding
        }
    val iconSlot = icon
    val textSlot = text

    Node(
        modifier = modifier
            .viewClass(WearRevealActionButtonView::class.java)
            .container(
                ContainerSpec(
                    shape = CircleShape,
                    containerColor = resolvedContainer,
                ),
            )
            // `padding(startPadding, 0.dp, endPadding, 0.dp)` then the `Button`'s own `contentPadding`
            // (`:1124`, `:1161-1165`), which Compose nests and a `View` cannot: `setPadding` replaces, so
            // the two are added here and applied once. `fillMaxWidth` is what the hosting row's weighted
            // slot supplies — the width is set by [WearRevealActionRowView] every frame — so only the
            // padding lands here.
            .paddingRelative(
                start = fullScreenPadding + contentPadding,
                top = contentPadding,
                end = fullScreenPadding + contentPadding,
                bottom = contentPadding,
            )
            .thenViewAttribute<WearRevealActionButtonView, Function0<Unit>>(uniqueKey, onClick) { handler ->
                isClickable = true
                onActionClick = handler
                setOnClickListener { performActionClick() }
            }
            .thenViewAttribute<WearRevealActionButtonView, RevealActionType>(
                uniqueKey,
                revealActionType,
            ) { actionType = it }
            .thenViewAttribute<WearRevealActionButtonView, RevealState>(uniqueKey, state) {
                revealState = it
            }
            .thenViewAttribute<WearRevealActionButtonView, Boolean>(
                uniqueKey,
                shouldSetLastActionType,
            ) { this.shouldSetLastActionType = it }
            .thenViewAttribute<WearRevealActionButtonView, Boolean>(uniqueKey, iconSlot != null) {
                hasIconSlot = it
            }
            .thenViewAttribute<WearRevealActionButtonView, Boolean>(uniqueKey, textSlot != null) {
                hasTextSlot = it
            }
            .revealSlotSpec(
                RevealSlotSpec(
                    actionType = revealActionType,
                    showsWhenLastActionType = scope.undoSlotMarker,
                ),
            ),
        content = {
            // The `Row(Modifier.fillMaxWidth().fillMaxHeight(), Center, CenterVertically)` of `:1168-1172`
            // is the button view itself (a `LinearLayout` with `gravity = CENTER`), so its children are
            // the icon box and the text box, in this order.
            if (iconSlot != null) {
                // `ActionIconWrapper`'s `Modifier.size(IconSize, Dp.Unspecified)` (`:1229`) — a fixed
                // 26dp width and an unconstrained height — plus the offset-driven alpha the button view
                // writes onto this child every frame.
                Box(modifier = Modifier.width(SwipeToRevealDefaults.IconSize)) {
                    provideContentColor(resolvedContent) { iconSlot() }
                }
            }
            if (textSlot != null) {
                // `ActionText`'s `Row(Modifier.padding(start = icon?.let { 4.dp } ?: 0.dp))` (`:1201`).
                Box(
                    modifier = Modifier.paddingRelative(
                        start = if (iconSlot != null) RevealIconAndTextPadding else 0.dp,
                    ),
                ) {
                    // Upstream's `ActionText` (`material3/SwipeToReveal.kt:1202-1211`) keeps the ambient
                    // `textAlign`, pins `Ellipsis`, and budgets the slot at one line — the slot is opaque
                    // caller content, so the budget has to ride down through the local.
                    TunationLocalProvider(
                        LocalContentColor provides resolvedContent,
                        LocalTextConfiguration provides TextConfiguration(
                            textAlign = LocalTextConfiguration.current.textAlign,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        ),
                        content = { textSlot() },
                    )
                }
            }
        }
    )
}

/**
 * What the hosting [WearRevealActionRowView] needs to know about a slot, as one immutable value so the
 * attribute only re-applies when it actually changes (`WEAR_PORT_CONTRACT.md`: attribute equality drives
 * diffing).
 */
private data class RevealSlotSpec(
    val actionType: RevealActionType,
    val showsWhenLastActionType: RevealActionType,
)

/** Write a [RevealSlotSpec] into the reveal row's own `LayoutParams`. */
private fun Modifier.revealSlotSpec(spec: RevealSlotSpec): Modifier =
    this.thenLayoutAttribute<WearRevealActionRowView.LayoutParams, RevealSlotSpec>(
        uniqueKey,
        spec,
    ) { value, _ ->
        slotType = value.actionType
        showsWhenLastActionType = value.showsWhenLastActionType
    }
