package com.huanli233.hibari.wear

import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.huanli233.hibari.foundation.BoxScope
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.view.PickerGroupProps
import com.huanli233.hibari.wear.view.WearPickerGroupView
import com.huanli233.hibari.wear.view.WearPickerView

/**
 * A group of [Picker]s to build components where multiple pickers are required to be combined
 * together. At most one [Picker] can be selected at a time. When touch exploration services are
 * enabled, the focus moves to the picker which is clicked.
 *
 * It is recommended to ensure that a [Picker] in non read only mode should have user scroll enabled
 * when touch exploration services are running.
 *
 * Ported from androidx.wear.compose.material3.PickerGroup; the row itself is
 * [WearPickerGroupView] (`AutoCenteringRow`).
 *
 * Parameter deviations, all forced by the compiler plugin or by the Views model:
 *  - Upstream's `focusRequester: FocusRequester?` is here an optional
 *    [HierarchicalFocusRequester] bound to the column's own view with
 *    [hierarchicalFocusRequester], and the focus tree is wired: every
 *    [PickerGroupScope.PickerGroupItem] carries [hierarchicalFocusGroup] (`active = selected`) plus
 *    [requestFocusOnHierarchyActive] when the caller passed no requester — the same either/or
 *    upstream's `PickerGroupItem` chooses between. What is still absent next to upstream is that
 *    element's `onPreviewKeyEvent` dpad swallow (`HierarchicalFocus.kt:79, 120-125`), which Views
 *    cannot express per subtree; see deviation 1 of [HierarchicalFocusCoordinator].
 *    [WearPickerView] also keeps taking focus when it becomes the auto-centring target, which is the
 *    same event as being selected and is what carries rotary input — the two requests land on one
 *    view, so they agree.
 *  - `selectedPickerState` no longer installs `Modifier.scrollableForTouchExploration` on the row.
 *    Instead the row answers `ACTION_SCROLL_FORWARD`/`ACTION_SCROLL_BACKWARD` by moving
 *    [selectedPickerState]'s picker one option, which is what that `Modifier.scrollable` did for
 *    TalkBack's swipe.
 *  - `LocalTouchExplorationStateProvider` is read once per tune instead of subscribed to, so a
 *    touch-exploration change while the screen is idle does not re-tune the group; the pickers
 *    still read it live for their own gestures. See [WearPickerView] for the same trade-off.
 *  - `MaterialTheme.motionScheme.fastSpatialSpec()` is not what feeds the centring spring here, even
 *    though this module has that member (`MotionScheme.kt`, reached through `MaterialTheme`). It hands
 *    out a `FiniteAnimationSpec<T>`, whose damping and stiffness are only reachable by an unchecked
 *    downcast to `SpringSpec`, and [WearPickerGroupView] integrates the spring itself rather than
 *    running a Compose spec — so the two numbers are passed straight through, taken from
 *    `MotionScheme.standard()`, which is upstream's default scheme.
 *
 * @param modifier [Modifier] to be applied to the [PickerGroup].
 * @param selectedPickerState The [PickerState] of the [Picker] that is selected. Null value means
 *   that no [Picker] is selected.
 * @param autoCenter Indicates whether the selected [Picker] should be centered on the screen. It is
 *   recommended to set this as true when all the pickers cannot be fit into the screen. Or provide
 *   a mechanism to navigate to pickers which are not visible on screen. If false, the whole row
 *   containing pickers would be centered.
 * @param propagateMinConstraints Whether the incoming min constraints should be passed to content.
 * @param content The content of the [PickerGroup] as a container of [Picker]s.
 */
@Tunable
fun PickerGroup(
    modifier: Modifier = Modifier,
    selectedPickerState: PickerState? = null,
    autoCenter: Boolean = true,
    propagateMinConstraints: Boolean = false,
    content: @Tunable PickerGroupScope.() -> Unit,
) {
    val context = currentContext
    val scope = remember { PickerGroupScope() }
    val touchExplorationEnabled = isPickerTouchExplorationEnabled(context)

    Node(
        modifier = modifier
            .viewClass(WearPickerGroupView::class.java)
            .pickerGroupProps(
                PickerGroupProps(
                    autoCenter = autoCenter,
                    propagateMinConstraints = propagateMinConstraints,
                    // Upstream adds `Modifier.singlePointerInput()` only in this branch.
                    singlePointerInput = !touchExplorationEnabled && autoCenter,
                    centeringDampingRatio = FastSpatialDampingRatio,
                    centeringStiffness = FastSpatialStiffness,
                    selectedState = selectedPickerState,
                )
            ),
        content = {
            scope.autoCenteringEnabled = autoCenter
            scope.touchExplorationEnabled = touchExplorationEnabled
            scope.content()
        }
    )
}

/**
 * Receiver scope which is used by [PickerGroup], holding the one slot it exposes. Upstream's class is
 * stateless apart from `autoCenteringEnabled`, which [PickerGroup] writes before tuning the content.
 */
class PickerGroupScope {

    /**
     * A [Picker] in a [PickerGroup].
     *
     * Upstream's `Modifier.pointerInput(touchExplorationServicesEnabled, selected) { awaitEachGesture
     * { awaitFirstDown(requireUnconsumed = true); latestOnSelected() } }` — installed only while this
     * item is neither selected nor being explored by touch — becomes
     * [pickerSelectOnDown], which [WearPickerView] acts on in its own `ACTION_DOWN`, so the picker is
     * selected by the down rather than by the click exactly as upstream. `latestOnSelected`
     * (`rememberUpdatedState`) is the fresh `onSelected` in the content attribute of each tune, which
     * is also what makes the rows re-bind.
     *
     * The focus half of upstream's chain is `.hierarchicalFocusGroup(active = selected)` followed by
     * `focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier.requestFocusOnHierarchyActive()`,
     * and it is reproduced with [hierarchicalFocusGroup], [hierarchicalFocusRequester] and
     * [requestFocusOnHierarchyActive]. So an item with no requester of its own is what the group
     * focuses when it becomes the selected one — which is how rotary input reaches it — and an item
     * handed a requester is left to whoever supplied it, exactly as upstream documents.
     *
     * @param pickerState The state of the picker.
     * @param selected If the [Picker] is selected.
     * @param onSelected Action triggered when the [Picker] is selected by clicking.
     * @param modifier [Modifier] to be applied to the [Picker].
     * @param contentDescription A block which computes text used by accessibility services to
     *   describe what the selected option represents. This text should be localized.
     * @param focusRequester Optional [HierarchicalFocusRequester] for the [Picker]. When it is
     *   `null` — upstream's default — this item asks for focus itself, through
     *   [requestFocusOnHierarchyActive], which is what coordinates focus between the pickers. When
     *   one is supplied, the caller owns focus for this column and the focus site is not installed;
     *   [hierarchicalFocusGroup] still is, so an inactive column never keeps the hierarchy live.
     *   Switching the parameter across retunes is harmless: both roles sit on one merged node whose
     *   View cannot change, so the resolved focus target is the same column either way.
     * @param readOnlyLabel A slot for providing a label, displayed above the selected option when
     *   the [Picker] is read-only. The label is overlaid with the currently selected option within a
     *   box, so it is recommended that the label is given `Gravity.TOP_CENTER` via
     *   `Modifier.gravity`.
     * @param verticalSpacing The amount of vertical spacing in [Dp] between items. Can be negative,
     *   which can be useful for Text if it has plenty of whitespace.
     * @param option A block which describes the content. The integer parameter to the tune function
     *   denotes the index of the option and boolean denotes whether the picker is selected or not.
     */
    @Tunable
    fun PickerGroupItem(
        pickerState: PickerState,
        selected: Boolean,
        onSelected: () -> Unit,
        modifier: Modifier = Modifier,
        contentDescription: (() -> String)? = null,
        focusRequester: HierarchicalFocusRequester? = null,
        readOnlyLabel: (@Tunable BoxScope.() -> Unit)? = null,
        verticalSpacing: Dp = 0.dp,
        option: @Tunable PickerScope.(optionIndex: Int, pickerSelected: Boolean) -> Unit,
    ) {
        val userModifier = modifier
        val autoCentering = selected && autoCenteringEnabled
        val scrollEnabled = !touchExplorationEnabled || selected
        // Upstream's `then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
        // .requestFocusOnHierarchyActive())`: the caller's requester takes the column's focus, or
        // this item asks the hierarchy for it.
        val requester = focusRequester
        val itemFocus: Modifier = if (requester != null) {
            Modifier.hierarchicalFocusRequester(requester)
        } else {
            Modifier.requestFocusOnHierarchyActive()
        }
        Picker(
            state = pickerState,
            contentDescription = contentDescription,
            modifier = userModifier
                .pickerAutoCenteringTarget(autoCentering)
                .pickerSelectOnDown(!touchExplorationEnabled && !selected)
                .hierarchicalFocusGroup(active = selected)
                .then(itemFocus),
            readOnly = !selected,
            readOnlyLabel = readOnlyLabel,
            onSelected = onSelected,
            verticalSpacing = verticalSpacing,
            userScrollEnabled = scrollEnabled,
            option = { optionIndex -> this.option(optionIndex, selected) },
        )
    }

    /** `PickerGroup`'s `autoCenter`, handed to the scope before the content is tuned. */
    internal var autoCenteringEnabled = false

    /** Read once per tune by [PickerGroup]; see the note on the top-level KDoc. */
    internal var touchExplorationEnabled = false
}

/**
 * `Modifier.autoCenteringTarget()`, which upstream passes to `AutoCenteringRow` as parent data.
 * Views carry no parent data on the modifier chain, so the flag lives on the picker view itself and
 * is written on every tune — an attribute that disappears from the chain cannot reset what it set
 * last time, so the value is explicit rather than conditional.
 */
internal fun Modifier.pickerAutoCenteringTarget(target: Boolean): Modifier =
    this.thenViewAttribute<WearPickerView, Boolean>(uniqueKey, target) {
        autoCenteringTarget = it
    }

private fun Modifier.pickerGroupProps(props: PickerGroupProps): Modifier =
    this.thenViewAttribute<WearPickerGroupView, PickerGroupProps>(uniqueKey, props) {
        pickerGroupProps = it
    }

/**
 * `Modifier.pointerInput(...) { awaitEachGesture { awaitFirstDown; onSelected() } }`: the first touch
 * down on an unselected item selects it, before any click. Written as a value rather than applied
 * conditionally, because an attribute that leaves the chain cannot undo what it set on the view.
 */
internal fun Modifier.pickerSelectOnDown(active: Boolean): Modifier =
    this.thenViewAttribute<WearPickerView, Boolean>(uniqueKey, active) {
        pickerSelectOnDown = it
    }

/**
 * `LocalTouchExplorationStateProvider.current.touchExplorationState()`, whose `Listener` in
 * wear's foundation is `accessibilityManager.isEnabled && isTouchExplorationEnabled`. Upstream
 * subscribes to both change listeners and recomposes; this group does not — it samples per tune, so
 * a service toggle while the screen is idle cannot re-tune every column.
 *
 * That is now a deliberate deviation rather than a missing piece: the local and its subscribing
 * helper, [touchExplorationState] (TouchExplorationStateProvider.kt), both exist and return a live
 * `State<Boolean>` — [com.huanli233.hibari.wear.DatePicker] reads it that way. Folding this onto that
 * call is the convergence-list move; it would subscribe the group, which is exactly what the note on
 * [PickerGroup] declines to do.
 */
private fun isPickerTouchExplorationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    return manager?.isEnabled == true && manager?.isTouchExplorationEnabled == true
}

/**
 * `MotionScheme.standard().fastSpatialSpec()` from MotionScheme.kt:
 * `spring(dampingRatio = StandardSpatialDampingRatio, stiffness = StandardFastStiffness)`.
 */
private const val FastSpatialDampingRatio: Float = 1f

/** See [FastSpatialDampingRatio]; `StandardFastStiffness = 1400f`. */
private const val FastSpatialStiffness: Float = 1400f
