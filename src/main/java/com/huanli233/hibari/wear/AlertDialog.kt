package com.huanli233.hibari.wear

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.Column
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.foundation.Spacer
import com.huanli233.hibari.foundation.attributes.height
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.foundation.attributes.matchParentWidth
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.foundation.attributes.width
import com.huanli233.hibari.recyclerview.LazyListScope
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.TunationLocalProvider
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.CircleShape
import com.huanli233.hibari.ui.geometry.RectangleShape
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.layout.Alignment
import com.huanli233.hibari.ui.layout.Arrangement
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.lazy.ListTransformParams
import com.huanli233.hibari.wear.lazy.ScalingLazyColumn
import com.huanli233.hibari.wear.lazy.ScalingLazyListState
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.tokens.FilledIconButtonTokens
import com.huanli233.hibari.wear.tokens.FilledTonalIconButtonTokens
import kotlin.math.ceil

/**
 * Ported from androidx.wear.compose.material3.AlertDialog / AlertDialogContent. Upstream publishes
 * six `AlertDialog` overloads (`material3/AlertDialog.kt:130, :230, :312, :397, :486, :586`) and six
 * `AlertDialogContent` overloads (`:660, :768, :870, :961, :1055, :1146`). Three of each — the
 * `transformationSpec: TransformationSpec` variants — are not ported, for the reason below; the
 * other three of each are, with [Dialog] in place of upstream's `Dialog` and its content body
 * shared the way upstream shares `AlertDialogContentFixed*` against the scrollable layout.
 *
 * Window presentation still is not hibari-wear's job: [Dialog] is an in-place overlay, not a real
 * window, so there is no focus control and no outside-click dismissal here. What the overlay does
 * give back is upstream's `visible` and `properties: DialogProperties` parameters and the
 * `onDismissRequest` that goes with them: upstream gates its window on
 * `shouldShow = showState || currentState == Display` (`material3/Dialog.kt:90`) and emits the
 * compose-ui `Dialog` — whose `onDismissRequest` covers back press and outside tap (`:134`) — only
 * inside `if (shouldShow)` (`:132`), and it calls the same lambda from its `SwipeToDismissBox`
 * (`:175-176`). A settled, invisible upstream dialog emits nothing, and with no animation stack
 * ported [Dialog] reduces `visible` to exactly that settled gate. The entry/exit animations on that
 * window remain unported: content alpha 0→1 and scale 1.25f→1.0f, the host screen scaled to
 * `BackgroundMinScale = 0.85f`, all on `motionScheme` specs `faster(50f)`, plus the
 * `LocalReduceMotion` branch that snaps them. There is no scrim either, which is faithful: upstream
 * sets the window `dimAmount` to `0f` (`material3/Dialog.kt:151`) and paints the surface itself. Upstream paints `colorScheme.background` full bleed
 * on the `Dialog`'s own box (`material3/Dialog.kt:182-191`); here [dialogSurface] is applied both
 * by [Dialog] and by the content root, so a bare [AlertDialogContent] keeps the backdrop and inside
 * a [Dialog] the identical opaque colour simply lands twice.
 *
 * Further deviations, all in the same direction:
 *  - The `transformationSpec` overloads are not ported: `TransformationSpec` and
 *    `ResponsiveTransformationSpec` (androidx.wear.compose.material3.lazy) have no Hibari
 *    equivalent — this module's `TransformingLazyColumn` (lazy/WearLazyColumn.kt:145) takes a
 *    `ListTransformParams`, not a per-item spec — and [ScalingLazyColumn] is driven by
 *    [ListTransformParams] instead. Their `contentPadding: @Composable (Boolean) -> PaddingValues`
 *    shape goes with them; the padding functions that took the `isScrollable` flag are still
 *    exposed on [AlertDialogDefaults]. The `content` slots of those overloads, typed
 *    `TransformingLazyColumnScope`, have no scope to be typed with here; what exists
 *    ([LazyListScope]) is the [content] parameter the ported overloads carry.
 *  - `DynamicScrollableOrFixedLayout` (`material3/AlertDialog.kt:1474-1501`) is a `SubcomposeLayout`
 *    that measures the fixed layout unbounded and flips to a scrolling one when it overflows the
 *    viewport. Hibari nodes get one measure pass, so with `content == null` the fixed layout always
 *    wins: an icon + title + text dialog taller than the screen is clipped instead of becoming
 *    scrollable. Supplying a `content` slot picks the scrollable layout up front, which is also what
 *    upstream does. The `edgeButton` overloads need no such choice — upstream gives them a
 *    scrollable layout unconditionally, `content == null` included (`:1070-1073`), and so does this
 *    file.
 *  - The `edgeButton` overloads (`material3/AlertDialog.kt:486`, `:1055`) are ported, but without
 *    the scroll-linked reveal. Upstream hands the slot to a `ScreenScaffold` (`:1076-1086`) that
 *    grows and fades it as the list reaches its end (`material3/ScreenScaffold.kt:584-679`) driven
 *    by `scrollInfoProvider.lastItemOffset` (`:580-583`); this module's `ScalingLazyListState`
 *    carries no `layoutInfo` (lazy/WearLazyColumn.kt:36-87), so the button stands fully revealed.
 *    The same one-measure-pass gap stops upstream's runtime read of the slot's *intrinsic* height
 *    (`ScreenScaffold.kt:594-600`, folded into the list's bottom padding at `:610-624`): the inset
 *    here is computed for [AlertDialogDefaults.EdgeButton] — [EdgeButtonSize.Medium] plus the
 *    button's own top and bottom `EdgeButtonVerticalPadding` (view/WearEdgeButtonView.kt:186-194) —
 *    and a caller that passes a slot of a different size gets a different bottom inset than
 *    upstream's measurement would have produced.
 *  - Semantics (`semantics(mergeDescendants)`, `Role.Button`, `clearAndSetSemantics`) are dropped:
 *    hibari-wear has no semantics layer yet.
 */

/* ------------------------------------------------------------------ *
 * Primitives shared with ConfirmationDialog and OpenOnPhoneDialog.    *
 * Internal and dialog-prefixed so they claim no generic name.         *
 * ------------------------------------------------------------------ */

/** Upstream's `Dialog` surface: `Modifier.background(MaterialTheme.colorScheme.background)`. */
@Tunable
internal fun dialogSurface(): ContainerSpec = ContainerSpec(
    shape = RectangleShape,
    containerColor = MaterialTheme.colorScheme.background,
)

/** `screenWidthFraction` from `Padding.kt`: `Dp(ceil(screenWidthDp * fraction))`. */
internal fun dialogScreenWidthFraction(context: Context, fraction: Float): Dp =
    ceil(context.resources.configuration.screenWidthDp * fraction).dp

/** `screenHeightFraction` from `Padding.kt`. */
internal fun dialogScreenHeightFraction(context: Context, fraction: Float): Dp =
    ceil(context.resources.configuration.screenHeightDp * fraction).dp

/** `PaddingDefaults.horizontalContentPadding()`: 5.2% of the screen width. */
internal fun dialogHorizontalContentPadding(context: Context): Dp =
    WearScreen.horizontalContentPaddingDp(context).dp

/** `PaddingDefaults.verticalContentPadding()`: 10% of the screen height. */
internal fun dialogVerticalContentPadding(context: Context): Dp =
    WearScreen.verticalContentPaddingDp(context).dp

/**
 * Group-align a container's children: the Views stand-in for the `contentAlignment` /
 * `horizontalAlignment` / `verticalAlignment` parameters Hibari's [Box], [Row] and [Column] do not
 * expose. `LinearLayout.setGravity` is the same place-time computation Compose runs.
 *
 * It only exists on the linear hosts. `FrameLayout` — what a [Box] instantiates — has no
 * container-level child gravity at all (only `setForegroundGravity`), so a Box centres a child through
 * that child's own `BoxScope.gravity`, and single-child Boxes in these files are [Column]s for exactly
 * that reason.
 */
internal fun Modifier.dialogChildGravity(gravity: Int): Modifier =
    this.thenViewAttribute<ViewGroup, Int>(uniqueKey, gravity) { value ->
        // `host` rather than a smart cast of `this`: the applier lambda's receiver is not smart-cast
        // into the branch, and `ViewGroup` alone declares no `setGravity`. The value is named rather
        // than `gravity` because the host has a `gravity` property of its own that would shadow it.
        val host = this
        if (host is LinearLayout) host.setGravity(value)
    }

/** `Modifier.rotate(degrees)`: a `View` rotation about its centre, Compose's default origin too. */
internal fun Modifier.dialogRotation(degrees: Float): Modifier =
    this.thenViewAttribute<ViewGroup, Float>(uniqueKey, degrees) { rotation = it }

/* ------------------------------------------------------------------ *
 * Components                                                          *
 * ------------------------------------------------------------------ */

/**
 * Ported from the confirm/dismiss `AlertDialog` overload (`material3/AlertDialog.kt:130-169`):
 * icon, title and message slots over a confirm and a dismiss button laid out horizontally at the
 * bottom, hosted by [Dialog] the way upstream hosts it.
 *
 * `dismissButton` cannot default to `AlertDialogDefaults.DismissButton(onDismissRequest)` the way
 * upstream writes it (`:136-138`), because a `@Tunable` default expression is hoisted out of the
 * tunable context; `null` means "the default dismiss button, dismissing through [onDismissRequest]"
 * and is resolved by [alertDialogConfirmDismissButtons].
 *
 * @param visible whether the dialog is displayed; nothing is emitted while it is false. The
 *   implementation of [onDismissRequest] must make it false, as upstream's doc requires — here that
 *   means stopping the tune of this entry point.
 * @param onDismissRequest called when the wearer swipes the dialog away or presses back (see
 *   [Dialog]), and by the default [dismissButton].
 * @param confirmButton A slot for a button indicating positive sentiment. Clicking the button must
 *   dismiss the dialog. Recommended: [AlertDialogDefaults.ConfirmButton].
 * @param title A slot for the dialog title; upstream budgets it at 3 lines — see
 *   [AlertDialogDefaults.TitleMaxLines].
 * @param modifier Modifier to be applied to the dialog content.
 * @param dismissButton A slot for a button indicating negative sentiment; defaults to
 *   [AlertDialogDefaults.DismissButton] dismissing through [onDismissRequest].
 * @param icon upstream declares this slot as `@Composable (() -> Unit)?` around a Material
 *   `ImageVector`; the slot stays, and the image inside it is whatever [Modifier.image] takes
 *   (see [Icon]), because this project ships no Material icon pack.
 * @param text Optional slot for the message below the title.
 * @param verticalArrangement upstream's arrangement is applied by the layout; here only its `spacing`
 *   travels — see [alertDialogSlots] for the fixed layout and [alertDialogCommonContent] for the
 *   scrollable one, since Hibari's [Box], [Row], [Column] and [ScalingLazyColumn] take no arrangement.
 * @param contentPadding nullable, and resolved in the body, because upstream's default calls a
 *   `@Tunable` padding function, which a hoisted `@Tunable` default expression may not do. The same
 *   stands for the other entry points.
 * @param properties the [DialogProperties] carried by [Dialog].
 * @param content upstream offers a `ScalingLazyListScope` overload and a `TransformingLazyColumnScope`
 *   one; only [LazyListScope] exists here, and only the first overload's shape is ported.
 */
@Tunable
fun AlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    confirmButton: @Tunable RowScope.() -> Unit,
    title: @Tunable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Tunable RowScope.() -> Unit)? = null,
    icon: (@Tunable () -> Unit)? = null,
    text: (@Tunable () -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = AlertDialogDefaults.VerticalArrangement,
    contentPadding: PaddingValues? = null,
    properties: DialogProperties = DialogProperties(),
    content: (LazyListScope.() -> Unit)? = null,
) {
    val resolvedPadding = contentPadding
        ?: if (icon != null) {
            AlertDialogDefaults.confirmDismissWithIconContentPadding()
        } else {
            AlertDialogDefaults.confirmDismissContentPadding()
        }
    Dialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        modifier = Modifier,
        properties = properties,
    ) {
        alertDialogContent(
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            onDismissRequest = onDismissRequest,
            title = title,
            modifier = modifier,
            icon = icon,
            text = text,
            verticalArrangement = verticalArrangement,
            contentPadding = resolvedPadding,
            content = content,
        )
    }
}

/**
 * Ported from the buttonless `AlertDialog` overload (`material3/AlertDialog.kt:312-345`): the
 * caller seeks input through [content].
 *
 * Upstream's `onDismissRequest` on this overload reaches only the window — the swipe-to-dismiss
 * (`material3/Dialog.kt:175-176`) and the compose dialog's back/outside handling (`:134`) — and
 * never a button, since this overload has none. That is the same ground [Dialog] covers without a
 * window: the swipe and the back press both route here (`Dialog.kt`'s `onDismissed` attribute and
 * `dialogBackPressHandling`); the outside click stays inert, as [Dialog] documents.
 */
@Tunable
fun AlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: @Tunable () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Tunable () -> Unit)? = null,
    text: (@Tunable () -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = AlertDialogDefaults.VerticalArrangement,
    contentPadding: PaddingValues? = null,
    properties: DialogProperties = DialogProperties(),
    content: (LazyListScope.() -> Unit)? = null,
) {
    val resolvedPadding = contentPadding
        ?: if (icon != null) {
            AlertDialogDefaults.contentWithIconPadding()
        } else {
            AlertDialogDefaults.contentPadding()
        }
    Dialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        modifier = Modifier,
        properties = properties,
    ) {
        alertDialogContent(
            confirmButton = null,
            dismissButton = null,
            onDismissRequest = null,
            title = title,
            modifier = modifier,
            icon = icon,
            text = text,
            verticalArrangement = verticalArrangement,
            contentPadding = resolvedPadding,
            content = content,
        )
    }
}

/**
 * Ported from the edge-button `AlertDialog` overload (`material3/AlertDialog.kt:486-521`): a single
 * confirm [EdgeButton] at the bottom edge of the dialog, for a one-way acknowledgement.
 *
 * @param visible whether the dialog is displayed; nothing is emitted while it is false.
 * @param onDismissRequest called by the [Dialog] host's swipe and back press; clicking the edge
 *   button must dismiss the dialog the same way, as upstream's doc requires (`:458-460`).
 * @param edgeButton Slot for an [EdgeButton] indicating positive sentiment; recommended:
 *   [AlertDialogDefaults.EdgeButton]. Upstream notes (`:461-465`) that a non-Medium slot needs its
 *   own [contentPadding]; here a non-Medium slot additionally outruns the fixed bottom inset — see
 *   the file header.
 * @param title A slot for the dialog title.
 * @param modifier Modifier to be applied to the dialog content, i.e. inside [Dialog], exactly as
 *   upstream passes `Modifier` to the window and the caller's modifier to the content
 *   (`:504-520`).
 * @param icon Optional slot for an icon at the top of the dialog.
 * @param text Optional slot for the message below the title.
 * @param verticalArrangement The vertical arrangement of the dialog's children.
 * @param contentPadding The padding around the content; its bottom is ignored and replaced by the
 *   edge-button inset, as upstream's doc states (`:477-479`).
 * @param properties the [DialogProperties] carried by [Dialog].
 * @param content A slot for additional content, displayed within a scrollable list; any button in
 *   it that dismisses the dialog goes through [onDismissRequest].
 */
@Tunable
fun AlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    edgeButton: @Tunable BoxScope.() -> Unit,
    title: @Tunable () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Tunable () -> Unit)? = null,
    text: (@Tunable () -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = AlertDialogDefaults.VerticalArrangement,
    contentPadding: PaddingValues? = null,
    properties: DialogProperties = DialogProperties(),
    content: (LazyListScope.() -> Unit)? = null,
) {
    Dialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        modifier = Modifier,
        properties = properties,
    ) {
        AlertDialogContent(
            edgeButton = edgeButton,
            title = title,
            modifier = modifier,
            icon = icon,
            text = text,
            verticalArrangement = verticalArrangement,
            contentPadding = contentPadding,
            content = content,
        )
    }
}

/**
 * Ported from the confirm/dismiss `AlertDialogContent` overload
 * (`material3/AlertDialog.kt:660-726`) — the same content with no [AlertDialog] wrapper, for a
 * caller that brings its own presentation. [dismissButton] is required here, as upstream makes it.
 */
@Tunable
fun AlertDialogContent(
    confirmButton: @Tunable RowScope.() -> Unit,
    title: @Tunable () -> Unit,
    dismissButton: @Tunable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Tunable () -> Unit)? = null,
    text: (@Tunable () -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = AlertDialogDefaults.VerticalArrangement,
    contentPadding: PaddingValues? = null,
    content: (LazyListScope.() -> Unit)? = null,
) {
    val resolvedPadding = contentPadding
        ?: if (icon != null) {
            AlertDialogDefaults.confirmDismissWithIconContentPadding()
        } else {
            AlertDialogDefaults.confirmDismissContentPadding()
        }
    alertDialogContent(
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        onDismissRequest = null,
        title = title,
        modifier = modifier,
        icon = icon,
        text = text,
        verticalArrangement = verticalArrangement,
        contentPadding = resolvedPadding,
        content = content,
    )
}

/**
 * Ported from the buttonless `AlertDialogContent` overload (`material3/AlertDialog.kt:870-925`).
 */
@Tunable
fun AlertDialogContent(
    title: @Tunable () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Tunable () -> Unit)? = null,
    text: (@Tunable () -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = AlertDialogDefaults.VerticalArrangement,
    contentPadding: PaddingValues? = null,
    content: (LazyListScope.() -> Unit)? = null,
) {
    val resolvedPadding = contentPadding
        ?: if (icon != null) {
            AlertDialogDefaults.contentWithIconPadding()
        } else {
            AlertDialogDefaults.contentPadding()
        }
    alertDialogContent(
        confirmButton = null,
        dismissButton = null,
        onDismissRequest = null,
        title = title,
        modifier = modifier,
        icon = icon,
        text = text,
        verticalArrangement = verticalArrangement,
        contentPadding = resolvedPadding,
        content = content,
    )
}

/**
 * Ported from the edge-button `AlertDialogContent` overload (`material3/AlertDialog.kt:1055-1099`).
 *
 * Like upstream this layout does not choose between a fixed and a scrollable arrangement — it is
 * the scrollable one even with `content == null` (`:1070-1073`) — and the [contentPadding] bottom is
 * replaced, not consumed: upstream's `ScreenScaffold` hands the list
 * `ReplacePaddingValues(padding, buttonIntrinsicHeight + effectiveSpacing)`
 * (`ScreenScaffold.kt:610-624`, `:886-889`), and [AlertDialogEdgePaddingValues] is that same
 * delegating override. The inset itself is computed for the default slot size rather than measured
 * from a live one — see the file header for the two reasons.
 */
@Tunable
fun AlertDialogContent(
    edgeButton: @Tunable BoxScope.() -> Unit,
    title: @Tunable () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Tunable () -> Unit)? = null,
    text: (@Tunable () -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = AlertDialogDefaults.VerticalArrangement,
    contentPadding: PaddingValues? = null,
    content: (LazyListScope.() -> Unit)? = null,
) {
    val resolvedPadding = contentPadding
        ?: if (icon != null) {
            AlertDialogDefaults.contentWithIconPadding()
        } else {
            AlertDialogDefaults.contentPadding()
        }
    val slot = edgeButton
    val spacing = verticalArrangement.spacing
    val edgeButtonSpacing =
        if (text == null && content == null) {
            AlertEdgeButtonSpacingWithoutTextAndContent
        } else {
            AlertEdgeButtonSpacing
        }
    // `ScreenScaffold.kt:561-562`: the gap the scaffold keeps beyond the button's own padding.
    val effectiveSpacing = (edgeButtonSpacing - EdgeButtonVerticalPadding).coerceAtLeast(0.dp)
    val state = remember { ScalingLazyListState(initialCenterItemIndex = 0) }
    Box(modifier = modifier.container(dialogSurface()).matchParentSize()) {
        ScalingLazyColumn(
            modifier = Modifier.matchParentSize(),
            state = state,
            contentPadding = AlertDialogEdgePaddingValues(
                resolvedPadding,
                AlertEdgeButtonDefaultHeight + effectiveSpacing,
            ),
            centerVertically = false,
            scalingParams = AlertDialogDefaults.AlertScalingParams,
            content = { alertDialogCommonContent(icon, title, text, spacing, content) },
        )
        // `ScreenScaffold.kt:590-594` aligns the slot's wrapper to BottomCenter; a FrameLayout
        // child aligns itself, and the wrapper is what the slot then fills at its natural height.
        Box(modifier = Modifier.gravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)) {
            slot()
        }
    }
}

/**
 * The one body behind the confirm/dismiss and buttonless entry points, mirroring upstream's private
 * `AlertDialogContentFixedWithConfirmationButtons` / `AlertDialogContentFixed` split against the
 * scrollable layout (`material3/AlertDialog.kt:1504-1577`). The `edgeButton` overloads do not pass
 * through it — upstream keeps them on a scrollable-only path, and so does this file.
 */
@Tunable
private fun alertDialogContent(
    confirmButton: (@Tunable RowScope.() -> Unit)?,
    dismissButton: (@Tunable RowScope.() -> Unit)?,
    onDismissRequest: (() -> Unit)?,
    title: @Tunable () -> Unit,
    modifier: Modifier,
    icon: (@Tunable () -> Unit)?,
    text: (@Tunable () -> Unit)?,
    verticalArrangement: Arrangement.Vertical,
    contentPadding: PaddingValues,
    content: (LazyListScope.() -> Unit)?,
) {
    val context = currentContext
    val spacing = verticalArrangement.spacing
    val surface = dialogSurface()

    if (content != null) {
        val userContent = content
        val buttons = confirmButton
        val dismissSlot = dismissButton
        val dismissAction = onDismissRequest
        // Upstream wraps this in `ScreenScaffold(scrollIndicator = { })`
        // (`material3/AlertDialog.kt:679-685`), whose only contribution to the dialog is the content
        // padding it hands back — so the padding goes straight to the list.
        val state = remember { ScalingLazyListState(initialCenterItemIndex = 0) }
        Box(modifier = modifier.container(surface).matchParentSize()) {
            ScalingLazyColumn(
                modifier = Modifier.matchParentSize(),
                state = state,
                contentPadding = contentPadding,
                centerVertically = false,
                scalingParams = AlertDialogDefaults.AlertScalingParams,
                content = {
                    alertDialogCommonContent(icon, title, text, spacing, userContent)
                    if (buttons != null) {
                        item {
                            alertDialogListItem(spacing) {
                                alertDialogConfirmDismissButtons(
                                    confirmButton = buttons,
                                    dismissButton = dismissSlot,
                                    onDismissRequest = dismissAction,
                                    extraBottomPaddingEnabled = true,
                                    context = context,
                                )
                            }
                        }
                    }
                },
            )
        }
    } else if (confirmButton != null) {
        val buttons = confirmButton
        val dismissSlot = dismissButton
        val dismissAction = onDismissRequest
        Column(
            modifier = modifier
                .container(surface)
                .padding(contentPadding)
                .matchParentSize(),
        ) {
            Column(
                modifier = Modifier
                    .matchParentWidth()
                    .weight(1f)
                    .dialogChildGravity(Gravity.CENTER),
            ) {
                alertDialogSlots(icon, title, text, spacing)
            }
            Spacer(Modifier.height(spacing))
            // A Column, not the upstream Box: group-aligning children is exactly what `Box` cannot do
            // in Views, and this box has a single child.
            Column(
                modifier = Modifier
                    .matchParentWidth()
                    .dialogChildGravity(Gravity.CENTER),
            ) {
                alertDialogConfirmDismissButtons(
                    confirmButton = buttons,
                    dismissButton = dismissSlot,
                    onDismissRequest = dismissAction,
                    extraBottomPaddingEnabled = false,
                    context = context,
                )
            }
        }
    } else {
        Column(
            modifier = modifier
                .container(surface)
                .padding(contentPadding)
                .matchParentSize()
                .dialogChildGravity(Gravity.CENTER),
        ) {
            alertDialogSlots(icon, title, text, spacing)
        }
    }
}

/** Icon, title and message, kept [spacing] apart — `verticalArrangement`'s spacing. */
@Tunable
private fun alertDialogSlots(
    icon: (@Tunable () -> Unit)?,
    title: @Tunable () -> Unit,
    text: (@Tunable () -> Unit)?,
    spacing: Dp,
) {
    val titleSlot = title
    if (icon != null) {
        val iconSlot = icon
        Row { alertDialogIconAlert(iconSlot) }
        Spacer(Modifier.height(spacing))
    }
    Row { alertDialogTitle(titleSlot) }
    if (text != null) {
        val textSlot = text
        Spacer(Modifier.height(spacing))
        Row { alertDialogTextMessage(textSlot) }
    }
}

/** `IconAlert`: the icon slot plus the 4.dp that separates it from the title. */
@Tunable
private fun alertDialogIconAlert(content: @Tunable () -> Unit) {
    Column {
        content()
        Spacer(Modifier.height(AlertIconBottomSpacing))
    }
}

/**
 * `Title`: `titleMedium` on `onBackground`, inset 12% of the screen width on both sides.
 *
 * Upstream also pushes `TextConfiguration(textAlign = Center, maxLines = 3, overflow = Ellipsis)`
 * through `LocalTextConfiguration`. Hibari has no text-configuration local that [Text] reads, so
 * those three stay with the caller; [AlertDialogDefaults.TitleMaxLines] carries the number.
 */
@Tunable
private fun alertDialogTitle(content: @Tunable () -> Unit) {
    val horizontalPadding = dialogScreenWidthFraction(currentContext, TitlePaddingFraction)
    Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
        alertDialogProvideContent(
            contentColor = ColorSchemeKeyTokens.OnBackground.resolve(MaterialTheme.colorScheme),
            textStyle = MaterialTheme.typography.titleMedium,
            content = content,
        )
    }
}

/**
 * `TextMessage`: `bodyMedium` on `onBackground`, inset 4.16% of the screen width on both sides. Its
 * 4.dp `AlertTextMessageTopSpacing` sits on top of the arrangement gap above it, as upstream, so the
 * title and the message end up 8.dp apart.
 *
 * Like [alertDialogTitle], upstream's `TextConfiguration(textAlign = Center, overflow = Ellipsis,
 * maxLines = TextConfigurationDefaults.MaxLines)` is not provided: [Text] takes those as per-call
 * parameters and reads no local for them. Its `MaxLines` is `Int.MAX_VALUE`, which is what [Text]
 * already defaults to, so only the centring and the ellipsis are lost.
 */
@Tunable
private fun alertDialogTextMessage(content: @Tunable () -> Unit) {
    val horizontalPadding = dialogScreenWidthFraction(currentContext, TextPaddingFraction)
    Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
        Spacer(Modifier.height(AlertTextMessageTopSpacing))
        alertDialogProvideContent(
            contentColor = ColorSchemeKeyTokens.OnBackground.resolve(MaterialTheme.colorScheme),
            textStyle = MaterialTheme.typography.bodyMedium,
            content = content,
        )
    }
}

@Tunable
private fun alertDialogProvideContent(
    contentColor: Color,
    textStyle: TextStyle,
    content: @Tunable () -> Unit,
) {
    TunationLocalProvider(
        LocalContentColor provides contentColor,
        LocalTextStyle provides textStyle,
        content = content,
    )
}

/** `ConfirmDismissButtons`: 12.dp above a centred dismiss–gap–confirm row. */
@Tunable
private fun alertDialogConfirmDismissButtons(
    confirmButton: @Tunable RowScope.() -> Unit,
    dismissButton: (@Tunable RowScope.() -> Unit)?,
    onDismissRequest: (() -> Unit)?,
    extraBottomPaddingEnabled: Boolean,
    context: Context,
) {
    val confirm = confirmButton
    val dismiss = dismissButton
    val dismissAction = onDismissRequest
    val gap = dialogScreenWidthFraction(context, ConfirmDismissBetweenButtonsPaddingFraction)
    Column {
        Spacer(Modifier.height(ConfirmDismissButtonsTopSpacing))
        Row(modifier = Modifier.dialogChildGravity(Gravity.CENTER_VERTICAL)) {
            Spacer(Modifier.width(6.dp))
            if (dismiss != null) {
                dismiss.invoke(this)
            } else if (dismissAction != null) {
                // Upstream's `dismissButton` default, resolved here because a `@Tunable` default
                // expression may not call one: see `material3/AlertDialog.kt:136-138`.
                AlertDialogDefaults.DismissButton(dismissAction)
            }
            Spacer(Modifier.width(gap))
            confirm.invoke(this)
            Spacer(Modifier.width(2.dp))
        }
        if (extraBottomPaddingEnabled) {
            Spacer(
                Modifier.height(
                    dialogScreenHeightFraction(context, ConfirmDismissButtonsBottomSpacingFraction),
                ),
            )
        }
    }
}

/**
 * `alertDialogCommonContent`: icon, title, message, then the caller's items below an 8.dp gap. The
 * gap belongs to the caller's content and nothing is emitted for it when there is none, as upstream
 * guards it (`material3/AlertDialog.kt:1592-1595`).
 *
 * Upstream hands that list `verticalArrangement` (its spacing) and `horizontalAlignment =
 * CenterHorizontally` and lets `ScalingLazyColumn` apply both between its items. This module's
 * [ScalingLazyColumn] exposes neither knob, so each of the dialog's own items carries its gap as top
 * padding on a full-width centring box — see [alertDialogListItem]. What that cannot reproduce is a
 * gap *between* the items the caller's [content] lambda emits, so [spacing] spaces the dialog's slots
 * and the 8.dp separator only.
 */
private fun LazyListScope.alertDialogCommonContent(
    icon: (@Tunable () -> Unit)?,
    title: @Tunable () -> Unit,
    text: (@Tunable () -> Unit)?,
    spacing: Dp,
    content: (LazyListScope.() -> Unit)?,
) {
    val titleSlot = title
    if (icon != null) {
        val iconSlot = icon
        item { alertDialogListItem(0.dp) { alertDialogIconAlert(iconSlot) } }
    }
    item {
        alertDialogListItem(if (icon != null) spacing else 0.dp) { alertDialogTitle(titleSlot) }
    }
    if (text != null) {
        val textSlot = text
        item { alertDialogListItem(spacing) { alertDialogTextMessage(textSlot) } }
    }
    if (content != null) {
        item { alertDialogListItem(spacing) { Spacer(Modifier.height(AlertContentTopSpacing)) } }
        content()
    }
}

/**
 * One item of the scrollable layout: full width, content centred, and pushed [spacing] away from the
 * item above it — the per-item stand-in for the arrangement the list itself cannot apply.
 */
@Tunable
private fun alertDialogListItem(spacing: Dp, content: @Tunable () -> Unit) {
    val slot = content
    Column(
        modifier = Modifier
            .matchParentWidth()
            .padding(top = spacing)
            .dialogChildGravity(Gravity.CENTER_HORIZONTAL),
        content = { slot() },
    )
}

/** Contains the default values used by [AlertDialog]. */
object AlertDialogDefaults {

    /**
     * Default composable for the edge button in an [AlertDialog]: a medium-sized [EdgeButton]
     * (`material3/AlertDialog.kt:1211-1247`). Should be used with the [AlertDialog] overload which
     * contains a single edgeButton slot.
     *
     * Upstream's `isButtonFullyVisible` derived state (`:1222-1230`) is dropped: it is computed and
     * never read — the same dead end as [ConfirmButton]'s `onVisibilityChanged` — and its
     * `onSizeChanged` tracker exists only to feed it. `interactionSource` goes the way of every
     * other slot in this file.
     *
     * @param colors Defaults to `null` and resolves through [ButtonDefaults.buttonColors] in the
     *   body, because upstream's default reads the theme.
     * @param content has no default because upstream's is `ConfirmIcon`, which draws `Icons.Check`
     *   out of the Material icon pack — see [ConfirmButton] for the same gap.
     */
    @Tunable
    fun EdgeButton(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        colors: ButtonColors? = null,
        content: (@Tunable RowScope.() -> Unit)? = null,
    ) {
        alertDialogEdgeButton(onClick, modifier, colors, content)
    }

    /** Default vertical arrangement for an [AlertDialog]: 4.dp between slots, group centred. */
    val VerticalArrangement: Arrangement.Vertical =
        Arrangement.spacedBy(space = 4.dp, alignment = Alignment.CenterVertically)

    /**
     * `ScalingLazyColumnDefaults.scalingParams(minTransitionArea = 0.2f)`: the flatter scaling curve
     * the alert lists use so short dialogs do not shrink much at the edge.
     */
    val AlertScalingParams: ListTransformParams = ListTransformParams(minTransitionArea = 0.2f)

    /** `AlertTitleMaxLines`: the title should not exceed 3 lines. Upstream applies it through a text local. */
    const val TitleMaxLines = 3

    /** The icon size upstream sets on `ConfirmIcon` / `DismissIcon`. */
    val IconSize: Dp = 28.dp

    /** `confirmWidth`, `confirmHeight` and the -45° the confirm button is rotated by. */
    val ConfirmButtonWidth: Dp = 63.dp
    val ConfirmButtonHeight: Dp = 54.dp
    const val ConfirmButtonRotation: Float = -45f

    /** `dismissSize`, and the 1.dp `cancelButtonPadding` the dismiss button's outer box adds. */
    val DismissButtonSize: Dp = 60.dp
    val DismissButtonPadding: Dp = 1.dp

    /**
     * Default composable for the confirm button.
     *
     * Upstream builds it from `FilledIconButton` with `IconButtonColors`; this module's
     * [FilledIconButton] exists but is not adopted for this slot — the two live colour roles are
     * resolved into [AlertDialogButtonColors] and the circle is drawn by [ContainerSpec]. Folding
     * the slot onto [FilledIconButton] is an integration-pass question, not a missing type.
     * `interactionSource` and the `semantics` wrapper (`mergeDescendants`, `onClick`, `Role.Button`)
     * go with those upstream types. `onVisibilityChanged` is dropped: upstream stores its result in
     * `buttonVisible` and never reads it. Also dropped are `minimumInteractiveComponentSize()`
     * (48.dp) and the `size` of `IconButtonTokens`'s `ContainerDefaultSize` (52.dp) that upstream's
     * icon buttons apply *inside* the caller's chain: this slot's own 63×54.dp `size` is outermost
     * and pins the constraints, so both are no-ops there and here.
     *
     * `content` has no default because upstream's is `ConfirmIcon`, which draws `Icons.Check` out of
     * the Material icon pack and no drawable id may be invented here — pass [ConfirmIcon] to keep
     * the 28.dp slot.
     */
    @Tunable
    fun ConfirmButton(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        colors: AlertDialogButtonColors? = null,
        content: (@Tunable RowScope.() -> Unit)? = null,
    ) {
        val resolved = colors ?: confirmButtonColors()
        val slot = content
        // A Column, not the icon button's Box: the centring of its single Row is what group alignment
        // is for, and only a linear host can do it in Views.
        Column(
            modifier = modifier
                .container(resolved.containerSpec(CircleShape))
                .clickable(enabled = true, onClick = onClick)
                .dialogRotation(ConfirmButtonRotation)
                .size(DpSize(ConfirmButtonWidth, ConfirmButtonHeight))
                .dialogChildGravity(Gravity.CENTER),
        ) {
            // Upstream's `LocalContentColor provides colors.contentColor(enabled = true)`.
            provideContentColor(resolved.contentColor) {
                Row(
                    modifier = Modifier
                        .padding(10.dp)
                        .dialogChildGravity(Gravity.CENTER_VERTICAL),
                    content = { slot?.invoke(this) },
                )
            }
        }
    }

    /**
     * Default composable for the dismiss button: a 60.dp `shapes.medium` square aligned to the
     * bottom end of a 61.dp box, which is exactly how upstream sizes it. See [ConfirmButton] for
     * why the icon-button types are not adopted.
     */
    @Tunable
    fun DismissButton(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        colors: AlertDialogButtonColors? = null,
        content: (@Tunable RowScope.() -> Unit)? = null,
    ) {
        val resolved = colors ?: dismissButtonColors()
        val slot = content
        val outerSize = DismissButtonSize + DismissButtonPadding
        Box(modifier = Modifier.size(DpSize(outerSize, outerSize))) {
            Column(
                modifier = modifier
                    .size(DpSize(DismissButtonSize, DismissButtonSize))
                    .gravity(Gravity.BOTTOM or Gravity.END)
                    .container(resolved.containerSpec(MaterialTheme.shapes.medium))
                    .clickable(enabled = true, onClick = onClick)
                    .dialogChildGravity(Gravity.CENTER),
            ) {
                // Upstream's `Row(content = content)`: the slot is a `RowScope` lambda, so the button's
                // box has to hand it a row. The row is scoped to the button's content colour, as
                // `FilledTonalIconButton` does.
                provideContentColor(resolved.contentColor) {
                    Row(content = { slot?.invoke(this) })
                }
            }
        }
    }

    /**
     * Upstream's `ConfirmIcon`, which drew `Icons.Check` at 28.dp inside the confirm button's row.
     * [image] is a parameter rather than a baked-in `ImageVector` because this project ships no
     * Material icon pack; see [Icon] for what it accepts.
     */
    @Tunable
    fun ConfirmIcon(
        image: Any?,
        contentDescription: String?,
        modifier: Modifier = Modifier,
    ) {
        FixedSizeIcon(
            image = image,
            contentDescription = contentDescription,
            iconSize = IconSize,
            modifier = modifier,
        )
    }

    /** Upstream's `DismissIcon`, which drew `Icons.Close` at 28.dp. See [ConfirmIcon]. */
    @Tunable
    fun DismissIcon(
        image: Any?,
        contentDescription: String?,
        modifier: Modifier = Modifier,
    ) {
        FixedSizeIcon(
            image = image,
            contentDescription = contentDescription,
            iconSize = IconSize,
            modifier = modifier,
        )
    }

    /**
     * The padding around the confirm/dismiss [AlertDialog] with no icon: top and bottom are
     * `PaddingDefaults.verticalContentPadding()`, sides 5.2% of the width.
     */
    @Tunable
    fun confirmDismissContentPadding(): PaddingValues {
        val context = currentContext
        val vertical = dialogVerticalContentPadding(context)
        val horizontal = dialogHorizontalContentPadding(context)
        return PaddingValues(top = vertical, bottom = vertical, start = horizontal, end = horizontal)
    }

    /** The same with an icon, whose top collapses to `screenHeightFraction(0.012f)`. */
    @Tunable
    fun confirmDismissWithIconContentPadding(): PaddingValues {
        val context = currentContext
        val horizontal = dialogHorizontalContentPadding(context)
        return PaddingValues(
            top = dialogScreenHeightFraction(context, IconTopPaddingFraction),
            bottom = dialogVerticalContentPadding(context),
            start = horizontal,
            end = horizontal,
        )
    }

    /**
     * Padding for the button-stack and EdgeButton variations without an icon. The bottom is
     * `screenHeightFraction(0.3646f)`, the room a stack of buttons takes; the edgeButton overloads
     * replace it with the button inset, as upstream does (`material3/AlertDialog.kt:1367-1375`).
     */
    @Tunable
    fun contentPadding(): PaddingValues {
        val context = currentContext
        val horizontal = dialogHorizontalContentPadding(context)
        return PaddingValues(
            top = dialogVerticalContentPadding(context),
            bottom = dialogScreenHeightFraction(context, NoEdgeButtonBottomPaddingFraction),
            start = horizontal,
            end = horizontal,
        )
    }

    /** [contentPadding] with an icon. */
    @Tunable
    fun contentWithIconPadding(): PaddingValues {
        val context = currentContext
        val horizontal = dialogHorizontalContentPadding(context)
        return PaddingValues(
            top = dialogScreenHeightFraction(context, IconTopPaddingFraction),
            bottom = dialogScreenHeightFraction(context, NoEdgeButtonBottomPaddingFraction),
            start = horizontal,
            end = horizontal,
        )
    }

    /**
     * Padding for the `TransformingLazyColumn` button-stack variation. Only the [isScrollable]
     * bottom separates it from [contentPadding]: scrollable content keeps the fraction, fixed
     * content drops to 10% of the screen height.
     */
    @Tunable
    fun buttonStackContentPadding(isScrollable: Boolean): PaddingValues {
        val context = currentContext
        val horizontal = dialogHorizontalContentPadding(context)
        return PaddingValues(
            top = dialogVerticalContentPadding(context),
            bottom = if (isScrollable) {
                dialogScreenHeightFraction(context, NoEdgeButtonBottomPaddingFraction)
            } else {
                dialogVerticalContentPadding(context)
            },
            start = horizontal,
            end = horizontal,
        )
    }

    /** [buttonStackContentPadding] with an icon. */
    @Tunable
    fun buttonStackWithIconContentPadding(isScrollable: Boolean): PaddingValues {
        val context = currentContext
        val horizontal = dialogHorizontalContentPadding(context)
        return PaddingValues(
            top = dialogScreenHeightFraction(context, IconTopPaddingFraction),
            bottom = if (isScrollable) {
                dialogScreenHeightFraction(context, NoEdgeButtonBottomPaddingFraction)
            } else {
                dialogVerticalContentPadding(context)
            },
            start = horizontal,
            end = horizontal,
        )
    }

    /** `GroupSeparator`: a flat 8.dp gap for splitting groups inside [AlertDialog]'s content. */
    @Tunable
    fun GroupSeparator() {
        Spacer(Modifier.height(GroupSeparatorHeight))
    }

    /** `ButtonDefaults.filledIconButtonColors()`, reduced to the two roles this slot paints. */
    @Tunable
    fun confirmButtonColors(): AlertDialogButtonColors {
        val scheme = MaterialTheme.colorScheme
        return AlertDialogButtonColors(
            containerColor = FilledIconButtonTokens.ContainerColor.resolve(scheme),
            contentColor = FilledIconButtonTokens.ContentColor.resolve(scheme),
        )
    }

    /** `IconButtonDefaults.filledTonalIconButtonColors()`, same reduction. */
    @Tunable
    fun dismissButtonColors(): AlertDialogButtonColors {
        val scheme = MaterialTheme.colorScheme
        return AlertDialogButtonColors(
            containerColor = FilledTonalIconButtonTokens.ContainerColor.resolve(scheme),
            contentColor = FilledTonalIconButtonTokens.ContentColor.resolve(scheme),
        )
    }

    // Upstream keeps these three on `AlertDialogDefaults` as internal members.
    private const val IconTopPaddingFraction = 0.012f
    private const val NoEdgeButtonBottomPaddingFraction = 0.3646f
    private val GroupSeparatorHeight: Dp = 8.dp
}

/**
 * The two colour roles an [AlertDialog] button paints.
 *
 * Upstream types both button slots as `IconButtonColors`, which also carries pressed and disabled
 * variants resolved from an `interactionSource`; the alert's buttons are never disabled and there is
 * no interaction source here, so only the two live roles travel.
 */
data class AlertDialogButtonColors(
    val containerColor: Color,
    val contentColor: Color,
) {
    fun containerSpec(shape: Shape): ContainerSpec =
        ContainerSpec(shape = shape, containerColor = containerColor)
}

/**
 * Upstream's `ReplacePaddingValues` (`material3/ScreenScaffold.kt:886-889`): the same delegating
 * `PaddingValues` that keeps every edge but the bottom. The padding modifier reads the four
 * `calculate*Padding` methods off the live view's direction, so delegation preserves the start/end
 * semantics of the wrapped values exactly.
 */
private class AlertDialogEdgePaddingValues(
    private val delegate: PaddingValues,
    private val bottomPadding: Dp,
) : PaddingValues by delegate {
    override fun calculateBottomPadding(): Dp = bottomPadding

    override fun equals(other: Any?): Boolean =
        other is AlertDialogEdgePaddingValues &&
            other.delegate == delegate &&
            other.bottomPadding == bottomPadding

    override fun hashCode(): Int = 31 * delegate.hashCode() + bottomPadding.hashCode()
}

/**
 * The body behind [AlertDialogDefaults.EdgeButton]. It is top-level because the member function has
 * the same simple name as this module's [EdgeButton] component and, inside `AlertDialogDefaults`,
 * the member shadows it; from here the call below is unambiguous. Upstream needs no such detour only
 * because its wrapper drops `buttonSize` and wraps the row content itself (`material3/AlertDialog.kt:1234-1246`)
 * — two things this module's [EdgeButton] already does (`EdgeButton.kt:38-76`).
 */
@Tunable
private fun alertDialogEdgeButton(
    onClick: () -> Unit,
    modifier: Modifier,
    colors: ButtonColors?,
    content: (@Tunable RowScope.() -> Unit)?,
) {
    val resolved = colors ?: ButtonDefaults.buttonColors()
    val slot = content
    EdgeButton(
        onClick = onClick,
        modifier = modifier,
        buttonSize = EdgeButtonSize.Medium,
        colors = resolved,
        content = { slot?.invoke(this) },
    )
}

private val AlertIconBottomSpacing: Dp = 4.dp
private val AlertTextMessageTopSpacing: Dp = 4.dp
private val ConfirmDismissButtonsTopSpacing: Dp = 12.dp
private val AlertContentTopSpacing: Dp = 8.dp
private const val ConfirmDismissButtonsBottomSpacingFraction = 0.045f
private const val TextPaddingFraction = 0.0416f
private const val TitlePaddingFraction = 0.12f
private const val ConfirmDismissBetweenButtonsPaddingFraction = 0.03f

/** `AlertEdgeButtonSpacing` and its no-text-no-content twin (`material3/AlertDialog.kt:1788-1789`). */
private val AlertEdgeButtonSpacing: Dp = 4.dp
private val AlertEdgeButtonSpacingWithoutTextAndContent: Dp = 16.dp

/**
 * Stand-in for the slot's measured `intrinsicButtonHeight` (`material3/ScreenScaffold.kt:594-600`):
 * [EdgeButtonSize.Medium], the size of [AlertDialogDefaults.EdgeButton], plus the
 * `EdgeButtonVerticalPadding` the button adds above and below itself
 * (`view/WearEdgeButtonView.kt:186-194`). See the file header for why it is not measured.
 */
private val AlertEdgeButtonDefaultHeight: Dp =
    EdgeButtonSize.Medium.maximumHeight + EdgeButtonVerticalPadding + EdgeButtonVerticalPadding
