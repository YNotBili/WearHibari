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
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.layout.Alignment
import com.huanli233.hibari.ui.layout.Arrangement
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.text.TextAlign
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.lazy.ListTransformParams
import com.huanli233.hibari.wear.lazy.ScalingLazyColumn
import com.huanli233.hibari.wear.lazy.ScalingLazyListState
import com.huanli233.hibari.wear.lazy.TransformingLazyColumn
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import kotlin.math.ceil

/**
 * Ported from androidx.wear.compose.material3.AlertDialog / AlertDialogContent. Upstream publishes
 * six `AlertDialog` overloads (`material3/AlertDialog.kt:130, :230, :312, :397, :486, :586`) and six
 * `AlertDialogContent` overloads (`:660, :768, :870, :961, :1055, :1146`), all twelve ported, with
 * [Dialog] in place of upstream's window `Dialog` and the content body shared the way upstream shares
 * `AlertDialogContentFixed*` against the scrollable layout. The three `transformationSpec` pairs are
 * ported against [ListTransformParams] instead of upstream's `TransformationSpec` — see the first
 * bullet below for what that costs and what it does not.
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
 *  - The three `transformationSpec` overloads take a [ListTransformParams] where upstream takes a
 *    `TransformationSpec` (`lazy/TransformationSpec.kt:55-99`, `lazy/ResponsiveTransformationSpec.kt:47`).
 *    The substitution is exact for everything this module can act on: upstream's spec is nine numbers
 *    — `minElementHeightFraction`, `maxElementHeightFraction`, the two transition-area fractions, the
 *    easing and the container/content alpha plus the item scale (`ResponsiveTransformationSpec.kt:93-114`
 *    and `:160-181`) — and [ListTransformParams] carries each of them under its `ScalingLazyColumn`
 *    name (`lazy/WearListTransform.kt:28-48`), with upstream's `smallScreen()` numbers being that
 *    class's own defaults. What is genuinely not reachable is the three members whose signatures are
 *    compose-ui types with no source in the reference tree: `GraphicsLayerScope.applyContentTransformation`
 *    and `applyContainerTransformation` (`:73-84`) and `TransformedContainerPainterScope.createTransformedContainerPainter`
 *    (`:94-98`). Two further consequences of that substitution are behavioural:
 *      - the spec is one set for the whole list, because [com.huanli233.hibari.wear.lazy.TransformingLazyColumn]
 *        takes `transformParams` once (`lazy/WearLazyColumn.kt:145-165`), so upstream's per-item
 *        `Modifier.transformedHeight(...)` + `graphicsLayer` chain (`material3/AlertDialog.kt:1606-1658`,
 *        `:1731-1744`) has no counterpart, and its `TopItemTransformationSpec`
 *        (`material3/AlertDialog.kt:1799-1800`, the reduced `minTransitionAreaHeightFraction = 0.14f`
 *        the first item gets when there is no icon) cannot be applied to that item alone. Its Hibari
 *        value would be `ListTransformParams(minTransitionArea = 0.14f)`; the rest of upstream's spec
 *        defaults are [ListTransformParams]'s own defaults.
 *      - the `content` slots typed `TransformingLazyColumnScope` upstream are the [LazyListScope]
 *        [content] every overload here already carries.
 *    The `contentPadding: @Composable (Boolean) -> PaddingValues` shape of the buttonless pair
 *    (`material3/AlertDialog.kt:968-974`, `:406-412`) is ported as a nullable
 *    `(@Tunable (Boolean) -> PaddingValues)?`, resolved in the body because upstream's default calls
 *    the `@Tunable` [AlertDialogDefaults.buttonStackContentPadding] family. Its `isScrollable`
 *    argument is `content != null` here rather than the measured answer upstream passes at `:983` and
 *    `:1017`, for the reason the next bullet gives.
 *  - `DynamicScrollableOrFixedLayout` (`material3/AlertDialog.kt:1474-1501`) is not ported, and the
 *    reason this file used to give for that — "Hibari nodes get one measure pass" — was **false**. A
 *    measure policy is asked to lay its children out once per pass (`Renderer.kt:408-429`) but each
 *    child may be *measured* as often as the policy likes: `ViewMeasurable` keys its cache on one
 *    [Constraints] slot, re-measures whenever the constraints differ, and drops the slot at the start
 *    of every pass (`Renderer.kt:424`, `:495-496`, `:531-542`), which is exactly the
 *    measure-loose-then-measure-clamped shape upstream runs at `:1489`. A custom policy has also been
 *    exercised against this host (`app/src/main/java/com/huanli233/hibari/sample/MeasureProbe.kt`,
 *    added by `d189707`, with the host's child layout params fixed afterwards by `3e0ab6c`). So the
 *    double measurement is expressible. What is not, today, is the *decision* — two named gaps:
 *      - The host is never given the viewport. `Renderer.render` returns a `LayoutNodeHost` before it
 *        reads the node's attributes (`Renderer.kt:97-104`): no `viewClass`, no `padding`, no
 *        `matchParentSize()`, no `RefModifier`, none of them at creation — only the *changed* ones
 *        arrive later, and only through `Patcher.applyChange` (`Patcher.kt:151-157`). A
 *        `LayoutNodeHost` therefore keeps whatever its parent's `generateDefaultLayoutParams()` hands
 *        it, and inside a [Column] (a vertical `LinearLayout`) that is `WRAP_CONTENT` height, so
 *        `Constraints.fromMeasureSpec` (`Renderer.kt:378-390`) reports `maxHeight = Infinity` and
 *        upstream's test `fixedMeasurable.height > constraints.maxHeight` (`AlertDialog.kt:1490`)
 *        can never be true. **The fix is in `hibari-runtime`, in `Renderer.render(node, parent)`** at
 *        those lines: give the host the layout params the view path builds at `Renderer.kt:142-151`
 *        and run the same attribute walk (`:158-163`) and `RefModifier` pass (`:165`) over it, and
 *        `Modifier.matchParentSize()` on the host becomes the viewport-clamped box upstream measures
 *        inside.
 *      - The unbounded question itself is malformed on the way down. Upstream asks it as
 *        `constraints.copy(maxHeight = Constraints.Infinity)` (`:1489`), and this engine converts
 *        constraints to a measure spec by comparing only `min` and `max`
 *        (`hibari-ui/src/main/java/com/huanli233/hibari/ui/unit/Constraints.kt:188-196`) with no
 *        unbounded branch, so `Constraints.Infinity` (`Int.MAX_VALUE`, `Constraints.kt:213`) is passed
 *        to `View.MeasureSpec.makeMeasureSpec`, whose documented size range is
 *        `0 .. (1 shl 30) - 1`, instead of `MeasureSpec.UNSPECIFIED`. **The fix is in `hibari-ui`**, in
 *        `Constraints.toWidthMeasureSpec` / `toHeightMeasureSpec`.
 *    One more thing the port has to give up either way, and it is structural rather than a bug:
 *    upstream's answer decides *which subtree is composed* (the `subcompose` at `:1493-1497` picks
 *    between two slot lambdas), while a measure policy can only choose among the children the tune
 *    already emitted. The shape that would carry it is a policy that measures the `forMeasure` child
 *    loose and writes `isScrollable` into remembered state — a whole-`Tunation` invalidation, which is
 *    what this engine gives — so the retune emits the scrollable or the fixed branch and not both.
 *    Until those two fixes land, with `content == null` the fixed layout always wins here, as it did:
 *    an icon + title + text dialog taller than the screen is clipped instead of becoming scrollable.
 *    Supplying a `content` slot picks the scrollable layout up front, which is also what upstream does.
 *    The `edgeButton` overloads need no such choice — upstream gives them a scrollable layout
 *    unconditionally, `content == null` included (`:1070-1073`), and so does this file.
 *  - The `edgeButton` overloads (`material3/AlertDialog.kt:486`, `:586`, `:1055`, `:1146`) are ported,
 *    but without the scroll-linked reveal. Upstream hands the slot to a `ScreenScaffold` (`:1076-1086`)
 *    that grows and fades it as the list reaches its end (`material3/ScreenScaffold.kt:584-679`), the
 *    target height coming from `scrollInfoProvider.lastItemOffset` (`:580-583`). What is missing is not
 *    that number and this file used to claim it was: [ScrollInfoProvider] declares `lastItemOffset`
 *    (`ScrollAway.kt:97-102`) and every value behind it is recoverable from the `RecyclerView`
 *    [ScalingLazyListState] already holds (`lazy/WearLazyColumn.kt:39`); the state simply does not
 *    publish it yet, which is a gap in `lazy/`, not missing infrastructure. The reveal is missing
 *    because the height is applied by `Modifier.dynamicHeight(onIntrinsicHeightMeasured = …)`
 *    (`ScreenScaffold.kt:590-606`, `:797-884`) — a measure-time height clamp and an intrinsic-height
 *    callback, both compose-ui layout machinery — which is also the reason this module's own
 *    [ScreenScaffold] edge-button overload gives for the same gap (`Scaffold.kt:206-212`), and the
 *    same read is what folds the slot's *intrinsic* height into the list's bottom padding
 *    (`ScreenScaffold.kt:610-624`). The inset here is computed for [AlertDialogDefaults.EdgeButton] —
 *    [EdgeButtonSize.Medium] plus the button's own top and bottom `EdgeButtonVerticalPadding`
 *    (view/WearEdgeButtonView.kt:186-194) — and a caller that passes a slot of a different size gets a
 *    different bottom inset than upstream's measurement would have produced.
 *  - [AlertDialogDefaults.ConfirmButton] and [AlertDialogDefaults.DismissButton] are now the
 *    `FilledIconButton` (`material3/AlertDialog.kt:1270`) and `FilledTonalIconButton` (`:1318`) calls
 *    upstream writes, with its shapes (`:1279`, `:1322`), its size and rotation chain
 *    (`:1276-1277`, `:1320`), its 61.dp outer box (`:1317`) and its content `Row` — the padded,
 *    centred one of `:1281-1294` and the bare one of `:1324`. Three things riding those chains
 *    cannot be expressed here: the `interactionSource` remembered at `:1268` and passed at `:1272`
 *    (no indication system; whatever press feedback the button has is [FilledIconButton]'s own,
 *    documented there, and the ripple of `material3/IconButton.kt:209` goes with it); the
 *    `Modifier.onVisibilityChanged(minFractionVisible = 0.9f)` of `:1275`, whose `buttonVisible`
 *    (`:1269`) upstream writes and never reads, so nothing downstream is lost and Views has no
 *    subtree-visibility callback to hang it on; and the `semantics` wrapper of the confirm button's
 *    `Row` (`:1283-1291`), which is the semantics gap below. Upstream's `AlertDialog.kt` carries no
 *    `testTag` anywhere, so there is none to drop here either.
 *  - Semantics (`semantics(mergeDescendants)`, `Role.Button`, `clearAndSetSemantics`) are dropped:
 *    hibari-wear has no semantics layer yet.
 *  - The confirm and dismiss icon descriptions are read from this module's `res/values/strings.xml`
 *    under upstream's own keys, `wear_m3c_alert_dialog_content_description_{confirm,dismiss}_button`
 *    (`res/values/wear_m3c_strings.xml:18-19`). Those two are the only strings anywhere in upstream's
 *    `AlertDialog.kt` — `grep` for `getString` lands on exactly `:1448` and `:1457` — and both reach a
 *    plain `Icon(contentDescription = ...)`, i.e. the `ImageView`'s accessibility description; the
 *    dialog itself carries none, so there is no dialog-level string to chase. Upstream keeps them
 *    inline in the argument-less `ConfirmIcon` / `DismissIcon` vals and publishes no accessor, so
 *    unlike [SliderDefaults.increaseIconContentDescription] there is no property to add here; the two
 *    ported icons take `contentDescription: String? = null` and resolve the resource in the body,
 *    because a `@Tunable` default expression is hoisted out of the tuner. What is still missing from
 *    those vals is the glyph: `Icons.Check` and `Icons.Close` are drawables, and this module ships
 *    none — the gap is drawable-only now, not resource-only.
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
 *   one; both are ported and both land on [LazyListScope], the only list scope this module has.
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
 * Ported from the confirm/dismiss `AlertDialog` overload that takes a `transformationSpec`
 * (`material3/AlertDialog.kt:230-271`). Same content as the overload above, listed through
 * [com.huanli233.hibari.wear.lazy.TransformingLazyColumn] with [transformationSpec] instead of the
 * alert list's own fixed curve.
 *
 * `transformationSpec` is required, as upstream's is (`:235`) — that is also what keeps the two
 * overloads resolvable against each other, since the one above has no parameter in that slot.
 * [dismissButton] stays nullable for the hoisting reason the overload above documents; upstream
 * defaults it to `AlertDialogDefaults.DismissButton(onDismissRequest)` at `:237-239`, resolved here by
 * [alertDialogConfirmDismissButtons].
 *
 * @param transformationSpec upstream's `TransformationSpec`, carried here by [ListTransformParams] —
 *   see the file header for the exact mapping and the three members that cannot be carried.
 * @param contentPadding nullable, and resolved in the body for the hoisting reason, as in the
 *   overload above; [content] non-null selects the transforming list, its absence the fixed layout.
 */
@Tunable
fun AlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    confirmButton: @Tunable RowScope.() -> Unit,
    title: @Tunable () -> Unit,
    transformationSpec: ListTransformParams,
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
        alertDialogSpecifiedContent(
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            onDismissRequest = onDismissRequest,
            title = title,
            modifier = modifier,
            icon = icon,
            text = text,
            verticalArrangement = verticalArrangement,
            contentPadding = resolvedPadding,
            transformationSpec = transformationSpec,
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
 * Ported from the buttonless `AlertDialog` overload that takes a `transformationSpec`
 * (`material3/AlertDialog.kt:397-433`).
 *
 * This is the pair where upstream's `contentPadding` is a function of `isScrollable` (`:406-412`),
 * because upstream learns `isScrollable` from `DynamicScrollableOrFixedLayout`; the file header says
 * why this port answers it from `content != null` instead.
 *
 * @param transformationSpec upstream's `TransformationSpec`, carried here by [ListTransformParams] —
 *   see the file header.
 * @param contentPadding called with the scrollable answer this entry point reduces to
 *   `content != null`; null resolves upstream's default, which reads
 *   [AlertDialogDefaults.buttonStackContentPadding] or its with-icon twin depending on [icon], in the
 *   body because a `@Tunable` default expression may not call a `@Tunable`.
 */
@Tunable
fun AlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: @Tunable () -> Unit,
    transformationSpec: ListTransformParams,
    modifier: Modifier = Modifier,
    icon: (@Tunable () -> Unit)? = null,
    text: (@Tunable () -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = AlertDialogDefaults.VerticalArrangement,
    contentPadding: (@Tunable (Boolean) -> PaddingValues)? = null,
    properties: DialogProperties = DialogProperties(),
    content: (LazyListScope.() -> Unit)? = null,
) {
    val isScrollable = content != null
    val resolvedPadding = contentPadding?.invoke(isScrollable)
        ?: if (icon != null) {
            AlertDialogDefaults.buttonStackWithIconContentPadding(isScrollable)
        } else {
            AlertDialogDefaults.buttonStackContentPadding(isScrollable)
        }
    Dialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        modifier = Modifier,
        properties = properties,
    ) {
        alertDialogSpecifiedContent(
            confirmButton = null,
            dismissButton = null,
            onDismissRequest = null,
            title = title,
            modifier = modifier,
            icon = icon,
            text = text,
            verticalArrangement = verticalArrangement,
            contentPadding = resolvedPadding,
            transformationSpec = transformationSpec,
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
 * Ported from the edge-button `AlertDialog` overload that takes a `transformationSpec`
 * (`material3/AlertDialog.kt:586-623`): the dialog above, listed through
 * [com.huanli233.hibari.wear.lazy.TransformingLazyColumn] with [transformationSpec].
 *
 * `transformationSpec` is required, as upstream's is (`:591`) — the same slot-position difference that
 * keeps this pair of edge-button overloads resolvable against each other, since the one above has no
 * parameter there.
 *
 * @param transformationSpec upstream's `TransformationSpec`, carried here by [ListTransformParams] —
 *   see the file header.
 * @param contentPadding nullable, and resolved by the [AlertDialogContent] this forwards to. Upstream
 *   keeps a plain `PaddingValues` on this pair (`:596-601`, `:1154-1159`) rather than the
 *   `isScrollable` function the buttonless pair has, because the edge-button layout has no fixed
 *   branch to answer differently — see the file header.
 */
@Tunable
fun AlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    edgeButton: @Tunable BoxScope.() -> Unit,
    title: @Tunable () -> Unit,
    transformationSpec: ListTransformParams,
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
            transformationSpec = transformationSpec,
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
 * Ported from the confirm/dismiss `AlertDialogContent` overload that takes a `transformationSpec`
 * (`material3/AlertDialog.kt:768-841`).
 *
 * @param transformationSpec upstream's `TransformationSpec`, carried here by [ListTransformParams] —
 *   see the file header. It reaches the items through [TransformingLazyColumn] only: the per-item
 *   transform upstream hangs on it has no counterpart here, and the fixed branch this entry point falls
 *   to without [content] takes no spec upstream either.
 * @param contentPadding nullable, and resolved in the body for the hoisting reason, as in the
 *   overload above.
 */
@Tunable
fun AlertDialogContent(
    confirmButton: @Tunable RowScope.() -> Unit,
    title: @Tunable () -> Unit,
    dismissButton: @Tunable RowScope.() -> Unit,
    transformationSpec: ListTransformParams,
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
    alertDialogSpecifiedContent(
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        onDismissRequest = null,
        title = title,
        modifier = modifier,
        icon = icon,
        text = text,
        verticalArrangement = verticalArrangement,
        contentPadding = resolvedPadding,
        transformationSpec = transformationSpec,
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
 * Ported from the buttonless `AlertDialogContent` overload that takes a `transformationSpec`
 * (`material3/AlertDialog.kt:961-1023`) — the pair where upstream's `contentPadding` is a function of
 * `isScrollable` (`:968-974`), answered from `content != null` for the reason the file header gives.
 *
 * @param transformationSpec upstream's `TransformationSpec`, carried here by [ListTransformParams] —
 *   see the file header.
 * @param contentPadding called with the scrollable answer this entry point reduces to
 *   `content != null`; null resolves upstream's default, which reads
 *   [AlertDialogDefaults.buttonStackContentPadding] or its with-icon twin depending on [icon], in the
 *   body because a `@Tunable` default expression may not call a `@Tunable`.
 */
@Tunable
fun AlertDialogContent(
    title: @Tunable () -> Unit,
    transformationSpec: ListTransformParams,
    modifier: Modifier = Modifier,
    icon: (@Tunable () -> Unit)? = null,
    text: (@Tunable () -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = AlertDialogDefaults.VerticalArrangement,
    contentPadding: (@Tunable (Boolean) -> PaddingValues)? = null,
    content: (LazyListScope.() -> Unit)? = null,
) {
    val isScrollable = content != null
    val resolvedPadding = contentPadding?.invoke(isScrollable)
        ?: if (icon != null) {
            AlertDialogDefaults.buttonStackWithIconContentPadding(isScrollable)
        } else {
            AlertDialogDefaults.buttonStackContentPadding(isScrollable)
        }
    alertDialogSpecifiedContent(
        confirmButton = null,
        dismissButton = null,
        onDismissRequest = null,
        title = title,
        modifier = modifier,
        icon = icon,
        text = text,
        verticalArrangement = verticalArrangement,
        contentPadding = resolvedPadding,
        transformationSpec = transformationSpec,
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
    alertDialogEdgeButtonContent(
        edgeButton = edgeButton,
        title = title,
        modifier = modifier,
        icon = icon,
        text = text,
        verticalArrangement = verticalArrangement,
        contentPadding = resolvedPadding,
        transformationSpec = null,
        content = content,
    )
}

/**
 * Ported from the edge-button `AlertDialogContent` overload that takes a `transformationSpec`
 * (`material3/AlertDialog.kt:1146-1196`).
 *
 * @param transformationSpec upstream's `TransformationSpec`, carried here by [ListTransformParams] —
 *   see the file header. Unlike the other two `AlertDialogContent` pairs, this one has no fixed branch
 *   for the spec to be irrelevant in: upstream always takes the list, `content == null` included
 *   (`:1162-1165`).
 * @param contentPadding nullable, and resolved in the body for the hoisting reason. Plain
 *   [PaddingValues] rather than the buttonless pair's `isScrollable` function, as upstream writes it
 *   (`:1154-1159`).
 */
@Tunable
fun AlertDialogContent(
    edgeButton: @Tunable BoxScope.() -> Unit,
    title: @Tunable () -> Unit,
    transformationSpec: ListTransformParams,
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
    alertDialogEdgeButtonContent(
        edgeButton = edgeButton,
        title = title,
        modifier = modifier,
        icon = icon,
        text = text,
        verticalArrangement = verticalArrangement,
        contentPadding = resolvedPadding,
        transformationSpec = transformationSpec,
        content = content,
    )
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

/**
 * The one body behind the three `transformationSpec` entry points that take confirm/dismiss or no
 * buttons at all, mirroring upstream's `AlertDialogContentFixed*`-against-`TransformingLazyColumn`
 * split (`material3/AlertDialog.kt:785-841`, `:977-1022`). Only the scrollable branch differs from
 * [alertDialogContent]: upstream's fixed bodies are handed no `transformationSpec` at all
 * (`:1504-1514`, `:1552-1560`) — a spec transforms list items, and the fixed layout has no list — so
 * the two families share that layout and this file shares it by calling through.
 *
 * Upstream's per-item `Modifier.transformedHeight(...)` + `graphicsLayer` chain
 * (`material3/AlertDialog.kt:1606-1658`, `:1731-1744`) and the `TopItemTransformationSpec` it swaps in
 * for the first item (`:1610`, `:1622`) have no counterpart here for the two reasons the file header
 * gives, which is why the emission below is [alertDialogCommonContent] unchanged: this call selects
 * which list the items go into and with what params, and nothing more.
 */
@Tunable
private fun alertDialogSpecifiedContent(
    confirmButton: (@Tunable RowScope.() -> Unit)?,
    dismissButton: (@Tunable RowScope.() -> Unit)?,
    onDismissRequest: (() -> Unit)?,
    title: @Tunable () -> Unit,
    modifier: Modifier,
    icon: (@Tunable () -> Unit)?,
    text: (@Tunable () -> Unit)?,
    verticalArrangement: Arrangement.Vertical,
    contentPadding: PaddingValues,
    transformationSpec: ListTransformParams,
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
        // The same `ScreenScaffold(scrollIndicator = { })` detour as in [alertDialogContent]
        // (`material3/AlertDialog.kt:788-794`): the scaffold's only contribution here is the padding it
        // hands back, so the padding goes straight to the list. No `state` is passed — upstream's
        // `rememberTransformingLazyColumnState(initialAnchorItemIndex = 0)` (`:786`) exists to feed the
        // scroll-linked pieces this port does not have, and [TransformingLazyColumn] remembers its own.
        Box(modifier = modifier.container(surface).matchParentSize()) {
            TransformingLazyColumn(
                modifier = Modifier.matchParentSize(),
                contentPadding = contentPadding,
                transformParams = transformationSpec,
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
    } else {
        // Upstream would ask `DynamicScrollableOrFixedLayout` here and take the transforming list when
        // the fixed content overflows; the header says why this port always answers fixed. Passing
        // `content = null` is what puts the call through to that fixed layout and nothing else.
        alertDialogContent(
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            onDismissRequest = onDismissRequest,
            title = title,
            modifier = modifier,
            icon = icon,
            text = text,
            verticalArrangement = verticalArrangement,
            contentPadding = contentPadding,
            content = null,
        )
    }
}

/**
 * The one body behind the two `edgeButton` [AlertDialogContent] entry points. Upstream writes the two
 * `ScreenScaffold` calls out separately (`material3/AlertDialog.kt:1076-1098`, `:1168-1195`) and they
 * differ only in which list the slot is laid over, so the inset arithmetic lives here once and
 * [transformationSpec] is the one argument that chooses the list: `null` is the alert list's own fixed
 * curve ([AlertDialogDefaults.AlertScalingParams]), a value is [TransformingLazyColumn]'s.
 */
@Tunable
private fun alertDialogEdgeButtonContent(
    edgeButton: @Tunable BoxScope.() -> Unit,
    title: @Tunable () -> Unit,
    modifier: Modifier,
    icon: (@Tunable () -> Unit)?,
    text: (@Tunable () -> Unit)?,
    verticalArrangement: Arrangement.Vertical,
    contentPadding: PaddingValues,
    transformationSpec: ListTransformParams?,
    content: (LazyListScope.() -> Unit)?,
) {
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
    val listPadding =
        AlertDialogEdgePaddingValues(contentPadding, AlertEdgeButtonDefaultHeight + effectiveSpacing)
    val listContent: LazyListScope.() -> Unit = {
        alertDialogCommonContent(icon, title, text, spacing, content)
    }
    Box(modifier = modifier.container(dialogSurface()).matchParentSize()) {
        if (transformationSpec != null) {
            TransformingLazyColumn(
                modifier = Modifier.matchParentSize(),
                contentPadding = listPadding,
                transformParams = transformationSpec,
                content = listContent,
            )
        } else {
            val state = remember { ScalingLazyListState(initialCenterItemIndex = 0) }
            ScalingLazyColumn(
                modifier = Modifier.matchParentSize(),
                state = state,
                contentPadding = listPadding,
                centerVertically = false,
                scalingParams = AlertDialogDefaults.AlertScalingParams,
                content = listContent,
            )
        }
        // `ScreenScaffold.kt:590-594` aligns the slot's wrapper to BottomCenter; a FrameLayout
        // child aligns itself, and the wrapper is what the slot then fills at its natural height.
        Box(modifier = Modifier.gravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)) {
            slot()
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
 * The three locals upstream's `Title` provides (`material3/AlertDialog.kt:1678-1688`) all ride down
 * here, so a caller's bare [Text] in the `title` slot is centred, ellipsised and budgeted at
 * [AlertDialogDefaults.TitleMaxLines] lines by the dialog rather than by the caller.
 */
@Tunable
private fun alertDialogTitle(content: @Tunable () -> Unit) {
    val horizontalPadding = dialogScreenWidthFraction(currentContext, TitlePaddingFraction)
    Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
        alertDialogProvideContent(
            contentColor = ColorSchemeKeyTokens.OnBackground.resolve(MaterialTheme.colorScheme),
            textStyle = MaterialTheme.typography.titleMedium,
            textConfiguration = TextConfiguration(
                TextAlign.Center,
                TextOverflow.Ellipsis,
                maxLines = AlertDialogDefaults.TitleMaxLines,
            ),
            content = content,
        )
    }
}

/**
 * `TextMessage`: `bodyMedium` on `onBackground`, inset 4.16% of the screen width on both sides. Its
 * 4.dp `AlertTextMessageTopSpacing` sits on top of the arrangement gap above it, as upstream, so the
 * title and the message end up 8.dp apart.
 *
 * Like [alertDialogTitle], the message row carries upstream's [TextConfiguration]
 * (`material3/AlertDialog.kt:1772-1782`): centred, ellipsised, and `maxLines =
 * TextConfigurationDefaults.MaxLines`, which is `Int.MAX_VALUE` — the line budget therefore asks for
 * nothing, while the centring and the ellipsis bite just as they do upstream.
 */
@Tunable
private fun alertDialogTextMessage(content: @Tunable () -> Unit) {
    val horizontalPadding = dialogScreenWidthFraction(currentContext, TextPaddingFraction)
    Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
        Spacer(Modifier.height(AlertTextMessageTopSpacing))
        alertDialogProvideContent(
            contentColor = ColorSchemeKeyTokens.OnBackground.resolve(MaterialTheme.colorScheme),
            textStyle = MaterialTheme.typography.bodyMedium,
            textConfiguration = TextConfiguration(
                TextAlign.Center,
                TextOverflow.Ellipsis,
                maxLines = TextConfigurationDefaults.MaxLines,
            ),
            content = content,
        )
    }
}

@Tunable
private fun alertDialogProvideContent(
    contentColor: Color,
    textStyle: TextStyle,
    textConfiguration: TextConfiguration,
    content: @Tunable () -> Unit,
) {
    TunationLocalProvider(
        LocalContentColor provides contentColor,
        LocalTextStyle provides textStyle,
        LocalTextConfiguration provides textConfiguration,
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

    /**
     * `AlertTitleMaxLines` (`material3/AlertDialog.kt:1793`): the title should not exceed 3 lines.
     * [alertDialogTitle] hands it to [Text] through [LocalTextConfiguration], as upstream does.
     */
    const val TitleMaxLines = 3

    /** The icon size upstream sets on `ConfirmIcon` / `DismissIcon`. */
    val IconSize: Dp = 28.dp

    /**
     * Upstream's function-locals `confirmWidth` and `confirmHeight` (`material3/AlertDialog.kt:1265,
     * :1266`) and the `-45f` of its `Modifier.rotate(-45f)` (`:1276`), exposed because a `@Tunable`
     * default expression cannot reach a local of a body.
     */
    val ConfirmButtonWidth: Dp = 63.dp
    val ConfirmButtonHeight: Dp = 54.dp
    const val ConfirmButtonRotation: Float = -45f

    /**
     * `dismissSize` (`material3/AlertDialog.kt:1315`) and the 1.dp `cancelButtonPadding` (`:1470`)
     * that the outer box of `:1317` adds to it.
     */
    val DismissButtonSize: Dp = 60.dp
    val DismissButtonPadding: Dp = 1.dp

    /**
     * Default composable for the confirm button: upstream's `FilledIconButton`
     * (`material3/AlertDialog.kt:1270-1296`), circular through
     * `IconButtonDefaults.shapes(confirmShape)` with `confirmShape = CircleShape` (`:1267, :1279`),
     * rotated -45° and fixed at 63 x 54.dp inside the caller's chain (`:1276-1277`), with the padded
     * centred `Row` upstream gives it as content (`:1281-1294`).
     *
     * What is not there is what the file header lists for these two slots: `interactionSource`
     * (`:1268, :1272`), `onVisibilityChanged` (`:1275`) and the `semantics` wrapper of the content
     * `Row` (`:1283-1291`). The 10.dp padding stays a literal, as upstream writes it.
     *
     * Upstream's own `.size(confirmWidth, confirmHeight)` wins over `FilledIconButton`'s inner
     * `minimumInteractiveComponentSize()` and `size(IconButtonDefaults.DefaultButtonSize)`
     * (`material3/IconButton.kt:198-201`) because the caller's chain is outermost there and the inner
     * size is coerced; [FilledIconButton] reproduces that precedence by putting its own default first
     * and the caller's chain after it (`IconButton.kt:168-169`), so the same 63 x 54.dp lands.
     *
     * The one geometry difference is not this slot's: our `CircleShape` rounds a non-square box by
     * `min(width, height) / 2` — a 63 x 54.dp stadium — where Compose's `CircleShape` traces an oval
     * (`hibari-ui/src/main/java/com/huanli233/hibari/ui/geometry/Shape.kt:64-69`). The stand-in this
     * call replaced had the same primitive, so the fold changes nothing there.
     *
     * `content` has no default because upstream's is `ConfirmIcon`, which draws `Icons.Check` out of
     * the Material icon pack and no drawable id may be invented here — pass [ConfirmIcon] to keep the
     * 28.dp slot, which since this round also reads upstream's `Icon` description from the resource,
     * so the only half of that val still unported is the glyph.
     *
     * @param colors upstream's `IconButtonColors = IconButtonDefaults.filledIconButtonColors()`
     *   (`:1262`, `material3/IconButton.kt:512-513`), defaulted to `null` and resolved in the body
     *   because a `@Tunable` default expression may not call a `@Tunable`.
     */
    @Tunable
    fun ConfirmButton(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        colors: IconButtonColors? = null,
        content: (@Tunable RowScope.() -> Unit)? = null,
    ) {
        val resolved = colors ?: IconButtonDefaults.filledIconButtonColors()
        val slot = content
        FilledIconButton(
            onClick = onClick,
            modifier = modifier
                .dialogRotation(ConfirmButtonRotation)
                .size(DpSize(ConfirmButtonWidth, ConfirmButtonHeight)),
            shapes = IconButtonDefaults.shapes(CircleShape),
            colors = resolved,
        ) {
            // Upstream's `Row(modifier = Modifier.align(Alignment.Center).padding(10.dp))`. The
            // centring is written by upstream and is a no-op in both directions: the Row wraps its
            // content, and the box it sits in wraps the Row.
            Row(
                modifier = Modifier
                    .gravity(Gravity.CENTER)
                    .padding(10.dp),
                content = { slot?.invoke(this) },
            )
        }
    }

    /**
     * Default composable for the dismiss button: upstream's `FilledTonalIconButton`
     * (`material3/AlertDialog.kt:1318-1325`) — a `shapes.medium` square (`:1316, :1322`) at 60.dp,
     * aligned to the bottom end of the 61.dp box of `:1317`, with the caller's `modifier` inside that
     * box and not on it, exactly as upstream threads it (`:1320`). The bare `Row(content = content)`
     * of `:1324` is the content.
     *
     * See [ConfirmButton] for the three dropped upstream arguments and why.
     *
     * @param colors upstream's `IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors()`
     *   (`:1312`, `material3/IconButton.kt:595-596`), `null` and resolved in the body for the same
     *   hoisting reason.
     */
    @Tunable
    fun DismissButton(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        colors: IconButtonColors? = null,
        content: (@Tunable RowScope.() -> Unit)? = null,
    ) {
        val resolved = colors ?: IconButtonDefaults.filledTonalIconButtonColors()
        val slot = content
        val outerSize = DismissButtonSize + DismissButtonPadding
        Box(modifier = Modifier.size(DpSize(outerSize, outerSize))) {
            FilledTonalIconButton(
                onClick = onClick,
                modifier = modifier
                    .size(DpSize(DismissButtonSize, DismissButtonSize))
                    .gravity(Gravity.BOTTOM or Gravity.END),
                shapes = IconButtonDefaults.shapes(MaterialTheme.shapes.medium),
                colors = resolved,
                content = {
                    Row(content = { slot?.invoke(this) })
                },
            )
        }
    }

    /**
     * Upstream's `ConfirmIcon`, which drew `Icons.Check` at 28.dp inside the confirm button's row
     * (`material3/AlertDialog.kt:1445-1451`).
     *
     * [image] is a parameter rather than a baked-in `ImageVector` because this project ships no
     * Material icon pack; see [Icon] for what it accepts.
     *
     * @param contentDescription Upstream does not expose this at all: it bakes
     *   `getString(Strings.AlertDialogContentDescriptionConfirmButton)` into the `Icon` call
     *   (`material3/AlertDialog.kt:1448`, key `wear_m3c_alert_dialog_content_description_confirm_button`
     *   through `internal/Strings.kt:101-102`), which is what the `Icon`'s — and so the `ImageView`'s —
     *   description ends up being. The parameter stays, and null resolves that same string in the
     *   body, because a `@Tunable` default expression is hoisted into a non-`@Tunable` method with no
     *   tuner to read a resource through. Consequence of keeping the slot: a caller cannot ask for the
     *   descriptionless icon that a bare `contentDescription = null` would once have meant here — and
     *   upstream's `ConfirmIcon`, taking no arguments at all, cannot express one either.
     */
    @Tunable
    fun ConfirmIcon(
        image: Any?,
        contentDescription: String? = null,
        modifier: Modifier = Modifier,
    ) {
        val description = contentDescription ?: currentContext.getString(
            R.string.wear_m3c_alert_dialog_content_description_confirm_button,
        )
        FixedSizeIcon(
            image = image,
            contentDescription = description,
            iconSize = IconSize,
            modifier = modifier,
        )
    }

    /**
     * Upstream's `DismissIcon`, which drew `Icons.Close` at 28.dp (`material3/AlertDialog.kt:1454-1460`)
     * with `getString(Strings.AlertDialogContentDescriptionDismissButton)` baked into the `Icon`
     * (`:1457`, key `wear_m3c_alert_dialog_content_description_dismiss_button`). See [ConfirmIcon] for
     * why the description is a defaulted parameter resolved in the body.
     */
    @Tunable
    fun DismissIcon(
        image: Any?,
        contentDescription: String? = null,
        modifier: Modifier = Modifier,
    ) {
        val description = contentDescription ?: currentContext.getString(
            R.string.wear_m3c_alert_dialog_content_description_dismiss_button,
        )
        FixedSizeIcon(
            image = image,
            contentDescription = description,
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

    // Upstream keeps these three on `AlertDialogDefaults` as internal members.
    private const val IconTopPaddingFraction = 0.012f
    private const val NoEdgeButtonBottomPaddingFraction = 0.3646f
    private val GroupSeparatorHeight: Dp = 8.dp
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
