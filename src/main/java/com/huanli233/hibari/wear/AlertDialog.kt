package com.huanli233.hibari.wear

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.huanli233.hibari.foundation.Box
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
 * Ported from androidx.wear.compose.material3.AlertDialog / AlertDialogContent.
 *
 * Window presentation is not hibari-wear's job: there is no `Dialog`/`DialogFragment` hosting, no
 * back handling, no swipe-to-dismiss and no focus control here, and upstream's `visible` and
 * `properties: DialogProperties` parameters go with it. There is no scrim either, which is faithful:
 * upstream sets the window `dimAmount` to `0f` and paints the surface itself. Neither are the
 * entry/exit animations `Dialog` puts on that window: content alpha 0→1 and scale 1.25f→1.0f, the
 * host screen scaled to `BackgroundMinScale = 0.85f`, all on `motionScheme` specs `faster(50f)`,
 * plus the `LocalReduceMotion` branch that snaps them. The caller places this in whatever surface it
 * chooses, exactly like [Card], and stops composing it to dismiss it. What *is* ported is the
 * dialog's own surface: upstream's `Dialog` paints `colorScheme.background` full bleed behind the
 * content, and that is what [dialogSurface] applies.
 *
 * Further deviations, all in the same direction:
 *  - `DynamicScrollableOrFixedLayout` is a `SubcomposeLayout` that measures the fixed layout
 *    unbounded and flips to a scrolling one when it overflows the viewport. Hibari nodes get one
 *    measure pass, so with `content == null` the fixed layout always wins: an icon + title + text
 *    dialog taller than the screen is clipped instead of becoming scrollable. Supplying a `content`
 *    slot picks the scrollable layout up front, which is also what upstream does.
 *  - The `transformationSpec` overloads are not ported: `TransformationSpec` and
 *    `ResponsiveTransformationSpec` (androidx.wear.compose.material3.lazy) have no Hibari
 *    equivalent, and [ScalingLazyColumn] is driven by [ListTransformParams] instead. Their
 *    `contentPadding: @Composable (Boolean) -> PaddingValues` shape goes with them; the padding
 *    functions that took the `isScrollable` flag are still exposed on [AlertDialogDefaults].
 *  - The `edgeButton` overloads are not ported: this module has no `EdgeButton`, which needs the
 *    screen-curvature path solve (see [ScreenScaffold]).
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
 * Ported from the confirm/dismiss `AlertDialog` overload: icon, title and message slots over a
 * confirm and a dismiss button laid out horizontally at the bottom.
 *
 * `dismissButton` cannot default to `AlertDialogDefaults.DismissButton(onDismissRequest)` the way
 * upstream writes it, because a `@Tunable` default expression is hoisted out of the tunable context;
 * `null` means "the default dismiss button, dismissing through [onDismissRequest]" and is resolved
 * in the body.
 *
 * @param icon upstream declares this slot as `@Composable (() -> Unit)?` around a Material
 *   `ImageVector`; the slot stays, and the image inside it is whatever [Modifier.image] takes
 *   (see [Icon]), because this project ships no Material icon pack.
 * @param verticalArrangement upstream's arrangement is applied by the layout; here only its `spacing`
 *   travels — see [alertDialogSlots] for the fixed layout and [alertDialogCommonContent] for the
 *   scrollable one, since Hibari's [Box], [Row], [Column] and [ScalingLazyColumn] take no arrangement.
 * @param contentPadding nullable, and resolved in the body, because upstream's default calls a
 *   `@Tunable` padding function, which a hoisted `@Tunable` default expression may not do. The same
 *   stands for the other three entry points.
 * @param content upstream offers a `ScalingLazyListScope` overload and a `TransformingLazyColumnScope`
 *   one; only [LazyListScope] exists here, so the two collapse into this parameter.
 */
@Tunable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Tunable RowScope.() -> Unit,
    title: @Tunable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Tunable RowScope.() -> Unit)? = null,
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

/**
 * Ported from the buttonless `AlertDialog` overload: the caller seeks input through [content].
 *
 * Upstream's `onDismissRequest` on this overload only ever reached the window's swipe-to-dismiss,
 * so it is absent along with the window.
 */
@Tunable
fun AlertDialog(
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
 * Ported from the confirm/dismiss `AlertDialogContent` overload — the same content with no
 * [AlertDialog] wrapper, for a caller that brings its own presentation. [dismissButton] is required
 * here, as upstream makes it.
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

/** Ported from the buttonless `AlertDialogContent` overload. */
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
 * The one body behind all four entry points, mirroring upstream's private
 * `AlertDialogContentFixedWithConfirmationButtons` / `AlertDialogContentFixed` split against the
 * scrollable layout.
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
        // Upstream wraps this in `ScreenScaffold(scrollIndicator = { })`, whose only contribution to
        // the dialog is the content padding it hands back — so the padding goes straight to the list.
        // This module's `ScreenScaffold` is a `SwipeDismissFrameLayout`, which is window behaviour the
        // dialog does not own, so it is not used here.
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
 * `alertDialogCommonContent`: icon, title, message, then the caller's items below an 8.dp gap.
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
    content: LazyListScope.() -> Unit,
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
    item { alertDialogListItem(spacing) { Spacer(Modifier.height(AlertContentTopSpacing)) } }
    content()
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
     * Upstream builds it from `FilledIconButton` with `IconButtonColors`; neither exists in this
     * module yet, so the same tokens are resolved into [AlertDialogButtonColors] and the circle is
     * drawn by [ContainerSpec]. `interactionSource` and the `semantics` wrapper
     * (`mergeDescendants`, `onClick`, `Role.Button`) go with those types. `onVisibilityChanged` is
     * dropped: upstream stores its result in `buttonVisible` and never reads it. Also dropped are
     * `minimumInteractiveComponentSize()` (48.dp) and the `size` of `IconButtonTokens`'s
     * `ContainerDefaultSize` (52.dp) that upstream's icon buttons apply *inside* the caller's chain:
     * this slot's own 63×54.dp `size` is outermost and pins the constraints, so both are no-ops
     * there and here.
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
     * bottom end of a 61.dp box, which is exactly how upstream sizes it. See [ConfirmButton] for why
     * the icon-button types are not used.
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
     * `screenHeightFraction(0.3646f)`, the room a stack of buttons takes; for an EdgeButton upstream
     * ignores it, and there is no EdgeButton here.
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

private val AlertIconBottomSpacing: Dp = 4.dp
private val AlertTextMessageTopSpacing: Dp = 4.dp
private val ConfirmDismissButtonsTopSpacing: Dp = 12.dp
private val AlertContentTopSpacing: Dp = 8.dp
private const val ConfirmDismissButtonsBottomSpacingFraction = 0.045f
private const val TextPaddingFraction = 0.0416f
private const val TitlePaddingFraction = 0.12f
private const val ConfirmDismissBetweenButtonsPaddingFraction = 0.03f
