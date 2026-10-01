package com.huanli233.hibari.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.annotation.PluralsRes
import androidx.annotation.RequiresApi
import com.huanli233.hibari.animation.Animatable
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.Column
import com.huanli233.hibari.foundation.ColumnScope
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.foundation.Spacer
import com.huanli233.hibari.foundation.attributes.height
import com.huanli233.hibari.foundation.attributes.matchParentHeight
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.foundation.attributes.matchParentWidth
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.foundation.attributes.text
import com.huanli233.hibari.foundation.attributes.width
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.TunationLocalProvider
import com.huanli233.hibari.runtime.bindState
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.effects.LaunchedEffect
import com.huanli233.hibari.runtime.getValue
import com.huanli233.hibari.runtime.locals.LocalLayoutDirection
import com.huanli233.hibari.runtime.mutableStateOf
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.runtime.setValue
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.text.createTypeface
import com.huanli233.hibari.ui.text.fontVariationSettings
import com.huanli233.hibari.ui.text.toAndroidStyle
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.DpSize
import com.huanli233.hibari.ui.unit.LayoutDirection
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.tokens.TimePickerTokens
import com.huanli233.hibari.wear.view.TimePickerTextSpec
import com.huanli233.hibari.wear.view.WearTimePickerOptionView
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.math.ceil

/**
 * A full screen TimePicker with configurable columns that allows users to select a time.
 *
 * This component is designed to take most/all of the screen and utilizes large fonts.
 *
 * For custom backgrounds like gradients or images wrap the TimePicker in a MaterialTheme with the
 * colorScheme background set to [Color.Unspecified].
 *
 * Ported from androidx.wear.compose.material3.TimePicker (`material3/TimePicker.kt:146-390`). The
 * columns are the already-ported [Picker]s composed through [PickerGroup], exactly as upstream
 * composes them, so the scroll, snap, rotary and read-only behaviour of a column is upstream's; what
 * this file adds is the column set, the heading, the separators and the confirm button.
 *
 * `@RequiresApi(Build.VERSION_CODES.O)` is upstream's (`:145`) and stays: the entry point takes and
 * returns a `java.time.LocalTime`, and this module's minSdk is 25, so callers below API 26 have
 * either to skip the component or re-derive the fields by hand — which is not what the signature
 * promises.
 *
 * Not ported, with the upstream line and the reason:
 *  - The `Animatable`-driven `fullyDrawn` fade-in of the whole picker (`:161`, `:387-389`) **is**
 *    ported. What is not is the `LocalInspectionMode` read that upstream uses to skip it in a
 *    preview, so here the fade always runs.
 *  - `LocalTouchExplorationStateProvider.current.touchExplorationState()` (`:164-165`) is sampled
 *    once per tune instead of subscribed to, the same trade [PickerGroup] documents. It is read for
 *    real, though: it decides whether the picker opens with a column selected or with the
 *    [TimePickerSelection.None] heading (`:172-180`, `:1248-1258`).
 *  - The focus story (`:166`, `:280-283`, `:365-369`) is wired through this module's
 *    `HierarchicalFocus*` surface: [HierarchicalFocusRequester] remembered as upstream's
 *    `focusRequesterConfirmButton`, bound to the confirm button with
 *    [hierarchicalFocusRequester] (upstream's `Modifier.focusRequester`, `:368`), and fired from the
 *    last step of the column rotation (`:282`), which is itself ported in full — upstream's
 *    `selectedElement` is plain state, and the pickers report their own selection through
 *    [PickerGroupItem]. Two parts of it are not portable:
 *      - `Modifier.focusable()` (`:369`): `WearEdgeButtonView` raises no focus flags of its own, and
 *        `grantHierarchicalFocus()` only raises them on the view it is called for. So the button
 *        becomes focusable the first time the rotation reaches it, not before — invisible in this
 *        component, where nothing but the rotation requests focus on it, but a difference from
 *        upstream that a caller who focuses the button by hand would see.
 *      - `Modifier.semantics { focused = … }` (`:366-368`): there is no semantics layer to write to,
 *        so TalkBack is not told which element holds focus. See the next bullet.
 *    The blocker this bullet used to record is gone: `EdgeButton` closes its own chain with
 *    `Modifier.clickable(enabled, onClick)` (`EdgeButton.kt:70`), so the confirm step answers both a
 *    tap and a focused press, and `Modifier.clickable` now raises `isFocusable` with it
 *    (`attributes/WearInteractionAttributes.kt:37`), which is what makes the rotation's focus request
 *    land on a view that can take it.
 *  - Semantics: `Modifier.semantics { heading() }`, `clearAndSetSemantics {}` on the label
 *    (`:324-331`) and `clearAndSetSemantics {}` on a separator (`:1176`) have no Hibari equivalent;
 *    `WearPickerView`-level descriptions are the only a11y surface this port can reach.
 *  - `FadeLabel` (`:305-323`, `material3/AnimationSpecUtils.kt:215-253`) cross-fades the heading when
 *    the text changes. It needs `FiniteAnimationSpec.faster(200f)`, which hibari-animation does not
 *    carry, so the heading switches at once.
 *  - `FontScaleIndependent` (`:292`) is reproduced inside [WearTimePickerOptionView] rather than as a
 *    local: every text this component draws converts sp through density only, ignoring
 *    `Configuration.fontScale`, which is exactly what upstream's
 *    `LocalDensity provides Density(density, fontScale = 1f)` does for the whole subtree.
 *  - Strings and plurals (`:218-222`, `:224-250`, `:1184-1195`): the five labels, the instruction
 *    heading, the confirm-button description and the three content-description plurals are read from
 *    this module's `res/values/strings.xml` / `res/values/plurals.xml` under upstream's `wear_m3c_*`
 *    keys — `context.getString(R.string.*)` for the labels and the heading, and
 *    `Resources.getQuantityString(R.plurals.*, value, value)` inside [timePickerCreateDescription],
 *    which is what upstream's `getString(Strings.*)` / `createDescription` do, so the plural category
 *    is chosen per locale. A `@Tunable` default expression may not read the context, so each string is
 *    resolved in the body, where `currentContext` is already in scope.
 *
 * @param initialTime The initial time to be displayed in the TimePicker.
 * @param onTimePicked The callback that is called when the user confirms the time selection. It
 *   provides the selected time as [LocalTime]. Note that any time components not displayed in the
 *   picker (e.g. the hour for [TimePickerType.MinutesSeconds], or the second for
 *   [TimePickerType.HoursMinutes24H]) will have a default value of 0 in the returned [LocalTime].
 * @param modifier [Modifier] to be applied to the `Box` containing the UI elements.
 * @param timePickerType The different [TimePickerType] supported by this time picker. It indicates
 *   whether to show seconds or AM/PM selector as well as hours and minutes. `null` means upstream's
 *   default, [TimePickerDefaults.timePickerType]; a `@Tunable` default expression is hoisted out of
 *   the tuner, so no theme or context read may sit in the signature.
 * @param colors [TimePickerColors] be applied to the TimePicker. `null` means upstream's default,
 *   `TimePickerDefaults.timePickerColors()`.
 * @param initialSelection The initial time component to be selected when the `TimePicker` is first
 *   displayed. By default, this is the first available time component based on the [timePickerType]
 *   and the device's locale (e.g., the hour component for a [TimePickerType.HoursMinutes24H]
 *   picker). If a [TimePickerSelection] is provided that is not applicable to the current
 *   [timePickerType] (such as providing `TimePickerSelection.Second` for a picker that does not
 *   display seconds), the selection will fall back to the first available time component. `null`
 *   means upstream's `TimePickerDefaults.timePickerSelection(timePickerType)`.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Tunable
fun TimePicker(
    initialTime: LocalTime,
    onTimePicked: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    timePickerType: TimePickerType? = null,
    colors: TimePickerColors? = null,
    initialSelection: TimePickerSelection? = null,
) {
    val type = timePickerType ?: TimePickerDefaults.timePickerType
    val pickerColors = colors ?: TimePickerDefaults.timePickerColors()
    val requestedSelection = initialSelection ?: TimePickerDefaults.timePickerSelection(type)

    val context = currentContext
    val fullyDrawn = remember { Animatable(0f) }
    // Upstream's `remember { FocusRequester() }` (`:166`), bound to the confirm button at `:368` and
    // fired from the end of the column rotation below.
    val focusRequesterConfirmButton = remember { HierarchicalFocusRequester() }
    val locale = context.resources.configuration.locales[0]
    val localeConfig = remember(locale, type) { TimePickerLocaleConfig(locale, type) }
    val touchExplorationServicesEnabled = isTimePickerTouchExplorationEnabled(context)

    var selectedElement by remember(touchExplorationServicesEnabled, localeConfig.focusableOrder) {
        mutableStateOf(
            timePickerToInitialSelection(
                localeConfig,
                requestedSelection,
                touchExplorationServicesEnabled,
            )
        )
    }

    val hourState = when {
        type == TimePickerType.MinutesSeconds -> null
        localeConfig.is12hour -> rememberPickerState(
            initialNumberOfOptions = 12,
            initiallySelectedIndex =
                initialTime.get(ChronoField.CLOCK_HOUR_OF_AMPM) - localeConfig.hourValueOffset,
        )
        else -> rememberPickerState(
            initialNumberOfOptions = 24,
            initiallySelectedIndex = initialTime.hour - localeConfig.hourValueOffset,
        )
    }
    val minuteState = rememberPickerState(
        initialNumberOfOptions = 60,
        initiallySelectedIndex = initialTime.minute,
    )
    val secondState = when (type) {
        TimePickerType.HoursMinutesSeconds24H, TimePickerType.MinutesSeconds -> rememberPickerState(
            initialNumberOfOptions = 60,
            initiallySelectedIndex = initialTime.second,
        )
        else -> null
    }
    val periodState = if (type == TimePickerType.HoursMinutesAmPm12H) {
        rememberPickerState(
            initialNumberOfOptions = 2,
            initiallySelectedIndex = initialTime.get(ChronoField.AMPM_OF_DAY),
            shouldRepeatOptions = false,
        )
    } else {
        null
    }

    // Upstream's `getString(Strings.*)` (`:218-222`). A `@Tunable` default expression may not read the
    // context, so the labels and the heading are resolved here, where `currentContext` is already held.
    val instructionHeadingString = context.getString(R.string.wear_m3c_time_picker_heading)
    val hourString = context.getString(R.string.wear_m3c_time_picker_hour)
    val minuteString = context.getString(R.string.wear_m3c_time_picker_minute)
    val secondString = context.getString(R.string.wear_m3c_time_picker_second)
    val periodString = context.getString(R.string.wear_m3c_time_picker_period)

    val hoursContentDescription = {
        timePickerCreateDescription(
            context,
            selectedElement,
            hourState?.run { selectedOptionIndex + localeConfig.hourValueOffset } ?: 0,
            hourString,
            R.plurals.wear_m3c_time_picker_hours_content_description,
        )
    }
    val minutesContentDescription = {
        timePickerCreateDescription(
            context,
            selectedElement,
            minuteState.selectedOptionIndex,
            minuteString,
            R.plurals.wear_m3c_time_picker_minutes_content_description,
        )
    }
    val secondsContentDescription = {
        timePickerCreateDescription(
            context,
            selectedElement,
            secondState?.selectedOptionIndex ?: 0,
            secondString,
            R.plurals.wear_m3c_time_picker_seconds_content_description,
        )
    }
    val periodContentDescription = {
        if (selectedElement == TimePickerSelection.None) {
            periodString
        } else if (periodState?.selectedOptionIndex == 0) {
            localeConfig.localizedAmText
        } else {
            localeConfig.localizedPmText
        }
    }

    val findNextElement = { current: TimePickerSelection ->
        val currentIndex = localeConfig.focusableOrder.indexOf(current)
        localeConfig.focusableOrder.getOrNull(currentIndex + 1) ?: TimePickerSelection.ConfirmButton
    }

    val onPickerSelected = { current: TimePickerSelection ->
        if (selectedElement != current) {
            selectedElement = current
        } else {
            selectedElement = findNextElement(current)
            // Upstream's `:282`. `HierarchicalFocusRequester.requestFocus()` returns false rather
            // than throwing when the target is not attached, which is the same observable moment
            // upstream's `FocusRequester` would have thrown in; the button is attached whenever this
            // rotation can run, so nothing here distinguishes the two.
            if (selectedElement == TimePickerSelection.ConfirmButton) {
                focusRequesterConfirmButton.requestFocus()
            }
        }
    }

    LaunchedEffect(Unit) { fullyDrawn.animateTo(1f) }

    // Built once per colour: a selection change retunes this body, and `image` is an attribute that
    // only lands when it changes.
    val checkIcon = remember(pickerColors.confirmButtonContentColor) {
        timePickerCheckDrawable(pickerColors.confirmButtonContentColor.toArgb())
    }

    // Reading `fullyDrawn.value` here would re-tune the whole picker — every column, separator and
    // heading — once per frame of the entry fade. Alpha is a plain view property, so bind the state.
    Box(
        modifier = modifier
            .matchParentSize()
            .bindState(uniqueKey, fullyDrawn.asState()) { this.alpha = it },
    ) {
        // Upstream's `Column(fillMaxSize, verticalArrangement = Center, horizontalAlignment = Center)`.
        // The weighted row below takes all the leftover height, so the centring arrangement has
        // nothing left to distribute; the children carry their own horizontal gravity instead.
        Column(modifier = Modifier.matchParentSize()) {
            val topPadding = if (selectedElement == TimePickerSelection.None) 0.dp else 14.dp
            val headingHeight = 38.dp - topPadding

            Spacer(Modifier.height(topPadding))

            val layoutConfig = timePickerLayoutConfig(type, localeConfig)
            val heading = when (selectedElement) {
                TimePickerSelection.Hour -> hourString
                TimePickerSelection.Minute -> minuteString
                TimePickerSelection.Second -> secondString
                TimePickerSelection.None ->
                    if (touchExplorationServicesEnabled) instructionHeadingString else ""
                else -> null
            }

            // `FadeLabel(text = heading ?: "", …)`; the null arm's `clearAndSetSemantics` has no
            // counterpart, so an empty string is the whole of that difference here.
            Node(
                modifier = Modifier
                    .matchParentWidth()
                    .height(headingHeight)
                    .gravity(Gravity.CENTER_HORIZONTAL)
                    .padding(horizontal = timePickerHeadingPaddingDp(context))
                    .viewClass(WearTimePickerOptionView::class.java)
                    .text(heading ?: "")
                    .timePickerText(
                        TimePickerTextSpec(
                            style = layoutConfig.labelTextStyle,
                            colorArgb = pickerColors.pickerLabelColor.toArgb(),
                            maxLines = if (selectedElement == TimePickerSelection.None) 2 else 1,
                        ),
                    )
            )

            Spacer(Modifier.height(layoutConfig.sectionVerticalPadding))

            TimePickerContent(
                localeConfig = localeConfig,
                selectedElement = selectedElement,
                onPickerSelected = onPickerSelected,
                hourState = hourState,
                minuteState = minuteState,
                secondState = secondState,
                periodState = periodState,
                hoursContentDescription = hoursContentDescription,
                minutesContentDescription = minutesContentDescription,
                secondsContentDescription = secondsContentDescription,
                periodContentDescription = periodContentDescription,
                colors = pickerColors,
                layoutConfig = layoutConfig,
            )

            Spacer(Modifier.height(layoutConfig.sectionVerticalPadding))

            EdgeButton(
                onClick = {
                    val timeWithoutPeriod = LocalTime.of(
                        hourState?.run { selectedOptionIndex + localeConfig.hourValueOffset } ?: 0,
                        minuteState.selectedOptionIndex,
                        secondState?.selectedOptionIndex ?: 0,
                    )
                    val confirmedTime = if (localeConfig.is12hour) {
                        timeWithoutPeriod.with(
                            ChronoField.AMPM_OF_DAY,
                            (periodState?.selectedOptionIndex ?: 0).toLong(),
                        )
                    } else {
                        timeWithoutPeriod
                    }
                    onTimePicked(confirmedTime)
                },
                // Upstream's `Modifier.semantics { focused = … }.focusRequester(…).focusable()`
                // (`:365-369`). `hierarchicalFocusRequester` is the port of `focusRequester` and
                // binds this view as the rotation's target; it is deliberately not also a focus
                // *site* (`requestFocusOnHierarchyActive()`), which would make the button compete
                // with the picker column upstream's focus tree resolves in favour of. The
                // `semantics { focused = … }` half has no counterpart here.
                modifier = Modifier.hierarchicalFocusRequester(focusRequesterConfirmButton),
                buttonSize = EdgeButtonSize.Small,
                // `buttonColors(contentColor = …, containerColor = …)` (`:371-374`), which upstream
                // implements as `defaultButtonColors.copy(...)` of exactly those two roles:
                // [EdgeButton] reads only `containerColor`/`contentColor` and their disabled twins,
                // so leaving the rest on the theme's values is what upstream renders too.
                colors = ButtonDefaults.buttonColors().copy(
                    containerColor = pickerColors.confirmButtonContainerColor,
                    contentColor = pickerColors.confirmButtonContentColor,
                ),
            ) {
                Icon(
                    image = checkIcon,
                    contentDescription = context.getString(
                        R.string.wear_m3c_picker_confirm_button_content_description,
                    ),
                    modifier = Modifier.size(DpSize(TimePickerIconSize, TimePickerIconSize)),
                    tint = pickerColors.confirmButtonContentColor,
                )
            }
        }
    }
}

/**
 * Ported from the backwards-compatibility overload at `material3/TimePicker.kt:427-445`.
 *
 * `DeprecationLevel.HIDDEN` is upstream's and is load-bearing, not decoration: it is what keeps this
 * five-parameter form out of overload resolution against the six-parameter [TimePicker] above, which
 * would otherwise be ambiguous for every call that passes fewer than six arguments. Upstream needs
 * it because pre-`initialSelection` binaries link against this exact signature; a fresh port has no
 * such caller, and keeping the signature without the level would break the module, so this exists
 * for parity with upstream's surface and is unreachable from Kotlin by design.
 *
 * @param initialTime The initial time to be displayed in the TimePicker.
 * @param onTimePicked The callback that is called when the user confirms the time selection.
 * @param modifier [Modifier] to be applied to the `Box` containing the UI elements.
 * @param timePickerType The different [TimePickerType] supported by this time picker.
 * @param colors [TimePickerColors] be applied to the TimePicker.
 */
@Deprecated(
    "This overload is provided for backwards compatibility with Compose for Wear OS 1.5. " +
        "A newer overload is available with an additional initialSelection parameter.",
    level = DeprecationLevel.HIDDEN,
)
@RequiresApi(Build.VERSION_CODES.O)
@Tunable
fun TimePicker(
    initialTime: LocalTime,
    onTimePicked: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    timePickerType: TimePickerType? = null,
    colors: TimePickerColors? = null,
): Unit = TimePicker(
    initialTime = initialTime,
    onTimePicked = onTimePicked,
    modifier = modifier,
    timePickerType = timePickerType,
    colors = colors,
    initialSelection = timePickerType?.let { TimePickerDefaults.timePickerSelection(it) },
)

/**
 * Specifies the types of columns to display in the [TimePicker]
 * (`material3/TimePicker.kt:229-262`). Upstream's `@Immutable` is a Compose compiler-stability
 * marker; a value class is already stable for a Views retune, so nothing replaces it.
 */
@JvmInline
value class TimePickerType internal constructor(internal val value: Int) {
    companion object {
        /** Displays two columns for hours (24-hour format) and minutes. */
        val HoursMinutes24H: TimePickerType = TimePickerType(0)

        /** Displays three columns for hours (24-hour format), minutes and seconds. */
        val HoursMinutesSeconds24H: TimePickerType = TimePickerType(1)

        /** Displays three columns for hours (12-hour format), minutes and AM/PM label. */
        val HoursMinutesAmPm12H: TimePickerType = TimePickerType(2)

        /** Displays two columns for minutes and seconds */
        val MinutesSeconds: TimePickerType = TimePickerType(3)
    }

    override fun toString(): String = when (this) {
        HoursMinutes24H -> "HoursMinutes24H"
        HoursMinutesSeconds24H -> "HoursMinutesSeconds24H"
        HoursMinutesAmPm12H -> "HoursMinutesAmPm12H"
        MinutesSeconds -> "MinutesSeconds"
        else -> "Unknown"
    }
}

/**
 * Which time component a [TimePicker] is currently editing (`material3/TimePicker.kt:263-296`).
 * [None] is upstream's accessibility resting state: `toInitialSelection` (`:1248-1258`) picks it
 * whenever touch exploration is on, so the picker opens showing the heading rather than an editable
 * column. That is reproducible here, because [PickerGroup] already samples the same
 * `AccessibilityManager` state — see [TimePicker] for the one part of upstream's focus story that
 * is not.
 */
@JvmInline
value class TimePickerSelection internal constructor(internal val value: Int) {
    companion object {
        /** Represents the hour component of the time. */
        val Hour: TimePickerSelection = TimePickerSelection(0)

        /** Represents the minute component of the time. */
        val Minute: TimePickerSelection = TimePickerSelection(1)

        /** Represents the second component of the time. */
        val Second: TimePickerSelection = TimePickerSelection(2)

        /** Represents the AM/PM period component of the time for 12-hour formats. */
        val Period: TimePickerSelection = TimePickerSelection(3)

        /** Represents the confirmation button component used to confirm the selected time. */
        val ConfirmButton: TimePickerSelection = TimePickerSelection(4)

        /** Indicates that no specific component is selected. Used primarily for accessibility. */
        val None: TimePickerSelection = TimePickerSelection(5)
    }

    override fun toString(): String = when (this) {
        Hour -> "Hour"
        Minute -> "Minute"
        Second -> "Second"
        Period -> "Period"
        ConfirmButton -> "ConfirmButton"
        None -> "None"
        else -> "Unknown"
    }
}

/** Contains the default values used by [TimePicker] (`material3/TimePicker.kt:498-573`). */
object TimePickerDefaults {
    /**
     * The default [TimePickerType] for [TimePicker] aligns with the current system time format
     * (`:501-509`), i.e. `DateFormat.is24HourFormat`, which is what upstream's
     * `materialcore.is24HourFormat()` calls (`materialcore/Resources.kt:60`).
     */
    val timePickerType: TimePickerType
        @Tunable @RequiresApi(Build.VERSION_CODES.O) get() =
            if (DateFormat.is24HourFormat(currentContext)) {
                TimePickerType.HoursMinutes24H
            } else {
                TimePickerType.HoursMinutesAmPm12H
            }

    /**
     * The default [TimePickerSelection] for [TimePicker] is set to the first available time
     * component based on the provided [TimePickerType] and current system time format
     * (`:511-521`).
     */
    @RequiresApi(Build.VERSION_CODES.O)
    @Tunable
    fun timePickerSelection(timePickerType: TimePickerType): TimePickerSelection {
        val locale = currentContext.resources.configuration.locales[0]
        return timePickerDefaultSelection(TimePickerLocaleConfig(locale, timePickerType))
    }

    /** Creates a [TimePickerColors] for a [TimePicker] (`:523-525`). */
    @Tunable
    fun timePickerColors(): TimePickerColors = MaterialTheme.colorScheme.defaultTimePickerColors

    /**
     * Creates a [TimePickerColors] for a [TimePicker] (`:527-552`). Every default is upstream's
     * [Color.Unspecified], which its `copy` treats as "keep the theme's value" — and, unlike a
     * theme-reading default, an `Unspecified` constant is legal in a hoisted `$default`.
     *
     * @param selectedPickerContentColor The content color of selected picker.
     * @param unselectedPickerContentColor The content color of unselected pickers.
     * @param separatorColor The color of separator between the pickers.
     * @param pickerLabelColor The color of the picker label.
     * @param confirmButtonContentColor The content color of the confirm button.
     * @param confirmButtonContainerColor The container color of the confirm button.
     */
    @Tunable
    fun timePickerColors(
        selectedPickerContentColor: Color = Color.Unspecified,
        unselectedPickerContentColor: Color = Color.Unspecified,
        separatorColor: Color = Color.Unspecified,
        pickerLabelColor: Color = Color.Unspecified,
        confirmButtonContentColor: Color = Color.Unspecified,
        confirmButtonContainerColor: Color = Color.Unspecified,
    ): TimePickerColors =
        MaterialTheme.colorScheme.defaultTimePickerColors.copy(
            selectedPickerContentColor = selectedPickerContentColor,
            unselectedPickerContentColor = unselectedPickerContentColor,
            separatorColor = separatorColor,
            pickerLabelColor = pickerLabelColor,
            confirmButtonContentColor = confirmButtonContentColor,
            confirmButtonContainerColor = confirmButtonContainerColor,
        )

    // Upstream's `TimePickerDefaults` (`:549-573`) carries no string members: the labels, the heading,
    // the confirm-button description and the plural content descriptions are read from resources in the
    // component body (`getString(Strings.*)` / `createDescription`, `:218-222`, `:224-250`,
    // `:1184-1195`), so this port reads the same `R.string.wear_m3c_*` / `R.plurals.wear_m3c_*` keys
    // from [TimePicker] rather than exposing text here.
    private val ColorScheme.defaultTimePickerColors: TimePickerColors
        get() = TimePickerColors(
            selectedPickerContentColor = TimePickerTokens.SelectedContentColor.resolve(this),
            unselectedPickerContentColor = TimePickerTokens.UnselectedContentColor.resolve(this),
            separatorColor = TimePickerTokens.SeparatorColor.resolve(this),
            pickerLabelColor = TimePickerTokens.LabelColor.resolve(this),
            confirmButtonContentColor = TimePickerTokens.ConfirmButtonContentColor.resolve(this),
            confirmButtonContainerColor = TimePickerTokens.ConfirmButtonContainerColor.resolve(this),
        )
}

/**
 * Represents the colors used by a [TimePicker] (`material3/TimePicker.kt:575-660`), `copy`, `equals`
 * and `hashCode` included.
 *
 * Upstream's `copy` routes every argument through `takeOrElse { this.x }` so that its
 * `Color.Unspecified` default means "unchanged"; that resolution is what the [TimePickerColors]
 * constructor call below does, so the plain data-class-style copy is the same function.
 *
 * @param selectedPickerContentColor The content color of selected picker.
 * @param unselectedPickerContentColor The content color of unselected pickers.
 * @param separatorColor The color of separator between the pickers.
 * @param pickerLabelColor The color of the picker label.
 * @param confirmButtonContentColor The content color of the confirm button.
 * @param confirmButtonContainerColor The container color of the confirm button.
 */
class TimePickerColors(
    val selectedPickerContentColor: Color,
    val unselectedPickerContentColor: Color,
    val separatorColor: Color,
    val pickerLabelColor: Color,
    val confirmButtonContentColor: Color,
    val confirmButtonContainerColor: Color,
) {

    /** Returns a copy of this [TimePickerColors], optionally overriding some of the values. */
    fun copy(
        selectedPickerContentColor: Color = this.selectedPickerContentColor,
        unselectedPickerContentColor: Color = this.unselectedPickerContentColor,
        separatorColor: Color = this.separatorColor,
        pickerLabelColor: Color = this.pickerLabelColor,
        confirmButtonContentColor: Color = this.confirmButtonContentColor,
        confirmButtonContainerColor: Color = this.confirmButtonContainerColor,
    ): TimePickerColors = TimePickerColors(
        selectedPickerContentColor =
            selectedPickerContentColor.takeOrElse { this.selectedPickerContentColor },
        unselectedPickerContentColor =
            unselectedPickerContentColor.takeOrElse { this.unselectedPickerContentColor },
        separatorColor = separatorColor.takeOrElse { this.separatorColor },
        pickerLabelColor = pickerLabelColor.takeOrElse { this.pickerLabelColor },
        confirmButtonContentColor =
            confirmButtonContentColor.takeOrElse { this.confirmButtonContentColor },
        confirmButtonContainerColor =
            confirmButtonContainerColor.takeOrElse { this.confirmButtonContainerColor },
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is TimePickerColors) return false

        if (selectedPickerContentColor != other.selectedPickerContentColor) return false
        if (unselectedPickerContentColor != other.unselectedPickerContentColor) return false
        if (separatorColor != other.separatorColor) return false
        if (pickerLabelColor != other.pickerLabelColor) return false
        if (confirmButtonContentColor != other.confirmButtonContentColor) return false
        if (confirmButtonContainerColor != other.confirmButtonContainerColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = selectedPickerContentColor.hashCode()
        result = 31 * result + unselectedPickerContentColor.hashCode()
        result = 31 * result + separatorColor.hashCode()
        result = 31 * result + pickerLabelColor.hashCode()
        result = 31 * result + confirmButtonContentColor.hashCode()
        result = 31 * result + confirmButtonContainerColor.hashCode()
        return result
    }
}

/** `material3/TimePicker.kt:658-659` */
private val TimePickerType.isTwoColumnPicker: Boolean
    get() = this == TimePickerType.HoursMinutes24H || this == TimePickerType.MinutesSeconds

/**
 * The row of columns (`material3/TimePicker.kt:661-826`).
 *
 * Upstream's `Row(fillMaxWidth().weight(1f))` becomes [Modifier.weight] plus
 * `height(0.dp)`: a `wrap_content` child of a vertical `LinearLayout` would take the leftover
 * height in the Compose sense but not in the Views one, where the leftover only reaches a child
 * whose main-axis size is 0. Its `verticalAlignment = CenterVertically` and
 * `horizontalArrangement = Center` need no counterpart because the single child fills it.
 *
 * The `PickerGroup` carries `Modifier.matchParentSize()` rather than upstream's `fillMaxWidth()`:
 * `WearPickerGroupView` answers an unspecified height with the tallest child, and with the columns
 * set to `fillMaxHeight` upstream that answer is circular — here the group is given the height the
 * weighted row has already been allocated, which is the value upstream's row converges on, and each
 * column then fills it as upstream's `fillMaxHeight()` says.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Tunable
private fun ColumnScope.TimePickerContent(
    selectedElement: TimePickerSelection,
    onPickerSelected: (TimePickerSelection) -> Unit,
    hourState: PickerState?,
    minuteState: PickerState,
    secondState: PickerState?,
    periodState: PickerState?,
    hoursContentDescription: () -> String,
    minutesContentDescription: () -> String,
    secondsContentDescription: () -> String,
    periodContentDescription: () -> String,
    colors: TimePickerColors,
    localeConfig: TimePickerLocaleConfig,
    layoutConfig: TimePickerLayoutConfig,
) {
    Row(
        modifier = Modifier.matchParentWidth().height(0.dp).weight(1f),
    ) {
        PickerGroup(
            selectedPickerState = when (selectedElement) {
                TimePickerSelection.Hour -> hourState
                TimePickerSelection.Minute -> minuteState
                TimePickerSelection.Second -> secondState
                TimePickerSelection.Period -> periodState
                else -> null
            },
            modifier = Modifier.matchParentSize(),
            autoCenter = false,
        ) {
            localeConfig.layoutElements.forEach { element ->
                when (element) {
                    is TimePickerLayoutElement.Standalone -> when (val part = element.part) {
                        is TimePickerPatternPart.ComponentPart ->
                            if (part.component == TimePickerSelection.Period && periodState != null) {
                                PeriodPicker(
                                    periodState = periodState,
                                    selected = selectedElement == TimePickerSelection.Period,
                                    onSelected = { onPickerSelected(TimePickerSelection.Period) },
                                    contentDescription = periodContentDescription,
                                    layoutConfig = layoutConfig,
                                    colors = colors,
                                )
                            }
                        is TimePickerPatternPart.SeparatorPart -> TimePickerSeparator(
                            textStyle = layoutConfig.optionTextStyle,
                            color = colors.separatorColor,
                            separatorPadding = layoutConfig.separatorPadding,
                            text = part.separatorText,
                            optionHeight = layoutConfig.optionHeight,
                            optionBaseline = layoutConfig.optionBaseline,
                        )
                    }
                    is TimePickerLayoutElement.TimeGroup -> {
                        // Upstream's comment, kept because it is the reason the nested row exists:
                        // the top-level row follows the global direction, which is what orders the
                        // group against an AM/PM column, but the digits inside it must stay in the
                        // logical h:m:s order, so this subtree is forced to LTR.
                        TimePickerLtrRow(
                            modifier =
                                Modifier.width(timePickerGroupWidth(element.parts, layoutConfig))
                                    .matchParentHeight(),
                        ) {
                            element.parts.forEach { part ->
                                when (part) {
                                    is TimePickerPatternPart.ComponentPart -> when (part.component) {
                                        TimePickerSelection.Hour -> if (hourState != null) {
                                            HourPicker(
                                                hourState = hourState,
                                                selected =
                                                    selectedElement == TimePickerSelection.Hour,
                                                onSelected = {
                                                    onPickerSelected(TimePickerSelection.Hour)
                                                },
                                                contentDescription = hoursContentDescription,
                                                hourValueOffset = localeConfig.hourValueOffset,
                                                layoutConfig = layoutConfig,
                                                colors = colors,
                                                locale = localeConfig.locale,
                                            )
                                        }
                                        TimePickerSelection.Minute -> MinutePicker(
                                            minuteState = minuteState,
                                            selected =
                                                selectedElement == TimePickerSelection.Minute,
                                            onSelected = {
                                                onPickerSelected(TimePickerSelection.Minute)
                                            },
                                            contentDescription = minutesContentDescription,
                                            layoutConfig = layoutConfig,
                                            colors = colors,
                                            locale = localeConfig.locale,
                                        )
                                        TimePickerSelection.Second -> if (secondState != null) {
                                            SecondPicker(
                                                secondState = secondState,
                                                selected =
                                                    selectedElement == TimePickerSelection.Second,
                                                onSelected = {
                                                    onPickerSelected(TimePickerSelection.Second)
                                                },
                                                contentDescription = secondsContentDescription,
                                                layoutConfig = layoutConfig,
                                                colors = colors,
                                                locale = localeConfig.locale,
                                            )
                                        }
                                        else -> {}
                                    }
                                    is TimePickerPatternPart.SeparatorPart -> TimePickerSeparator(
                                        textStyle = layoutConfig.optionTextStyle,
                                        color = colors.separatorColor,
                                        // The nested row centres on the shared baseline exactly as
                                        // upstream's `verticalAlignment = CenterVertically` does;
                                        // a standalone separator needs nothing, because
                                        // `WearPickerGroupView` already centres its children.
                                        modifier = Modifier.gravity(Gravity.CENTER_VERTICAL),
                                        separatorPadding = layoutConfig.separatorPadding,
                                        text = part.separatorText,
                                        optionHeight = layoutConfig.optionHeight,
                                        optionBaseline = layoutConfig.optionBaseline,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * `PickerGroupScope.HourPicker` / `MinutePicker` / `SecondPicker` / `PeriodPicker`
 * (`material3/TimePicker.kt:827-945`), private upstream and private here. Upstream builds each
 * column's content with `pickerTextOption(...)` (`material3/Picker.kt:659-698`), a function that
 * *returns* a composable lambda; that shape would put a tuned lambda in a non-tuned function, so the
 * same content is written inline and handed to [TimePickerOptionText].
 */
@Tunable
private fun PickerGroupScope.HourPicker(
    hourState: PickerState,
    selected: Boolean,
    onSelected: () -> Unit,
    contentDescription: () -> String,
    hourValueOffset: Int,
    layoutConfig: TimePickerLayoutConfig,
    colors: TimePickerColors,
    locale: Locale,
) {
    PickerGroupItem(
        pickerState = hourState,
        modifier = Modifier.width(layoutConfig.twoDigitsOptionWidth).matchParentHeight(),
        selected = selected,
        onSelected = onSelected,
        contentDescription = contentDescription,
        verticalSpacing = layoutConfig.optionSpacing,
        option = { optionIndex, pickerSelected ->
            TimePickerOptionText(
                text = "%02d".format(locale, optionIndex + hourValueOffset),
                selected = pickerSelected,
                layoutConfig = layoutConfig,
                colors = colors,
            )
        },
    )
}

/** See [HourPicker]; upstream's `:876-903`. */
@Tunable
private fun PickerGroupScope.MinutePicker(
    minuteState: PickerState,
    selected: Boolean,
    onSelected: () -> Unit,
    contentDescription: () -> String,
    layoutConfig: TimePickerLayoutConfig,
    colors: TimePickerColors,
    locale: Locale,
) {
    PickerGroupItem(
        pickerState = minuteState,
        modifier = Modifier.width(layoutConfig.twoDigitsOptionWidth).matchParentHeight(),
        selected = selected,
        onSelected = onSelected,
        contentDescription = contentDescription,
        verticalSpacing = layoutConfig.optionSpacing,
        option = { optionIndex, pickerSelected ->
            TimePickerOptionText(
                text = "%02d".format(locale, optionIndex),
                selected = pickerSelected,
                layoutConfig = layoutConfig,
                colors = colors,
            )
        },
    )
}

/** See [HourPicker]; upstream's `:904-931`. */
@Tunable
private fun PickerGroupScope.SecondPicker(
    secondState: PickerState,
    selected: Boolean,
    onSelected: () -> Unit,
    contentDescription: () -> String,
    layoutConfig: TimePickerLayoutConfig,
    colors: TimePickerColors,
    locale: Locale,
) {
    PickerGroupItem(
        pickerState = secondState,
        modifier = Modifier.width(layoutConfig.twoDigitsOptionWidth).matchParentHeight(),
        selected = selected,
        onSelected = onSelected,
        contentDescription = contentDescription,
        verticalSpacing = layoutConfig.optionSpacing,
        option = { optionIndex, pickerSelected ->
            TimePickerOptionText(
                text = "%02d".format(locale, optionIndex),
                selected = pickerSelected,
                layoutConfig = layoutConfig,
                colors = colors,
            )
        },
    )
}

/** See [HourPicker]; upstream's `:932-945`, whose `indexToText` is the AM/PM pair. */
@Tunable
private fun PickerGroupScope.PeriodPicker(
    periodState: PickerState,
    selected: Boolean,
    onSelected: () -> Unit,
    contentDescription: () -> String,
    layoutConfig: TimePickerLayoutConfig,
    colors: TimePickerColors,
) {
    PickerGroupItem(
        pickerState = periodState,
        modifier = Modifier.width(layoutConfig.periodOptionWidth).matchParentHeight(),
        selected = selected,
        onSelected = onSelected,
        contentDescription = contentDescription,
        verticalSpacing = layoutConfig.optionSpacing,
        option = { optionIndex, pickerSelected ->
            TimePickerOptionText(
                text = if (optionIndex == 0) layoutConfig.displayAmText else layoutConfig.displayPmText,
                selected = pickerSelected,
                layoutConfig = layoutConfig,
                colors = colors,
            )
        },
    )
}

/**
 * One option of a column: `pickerTextOption`'s `Box(fillMaxWidth().height(optionHeight))` with a
 * single-line `Text` inside it (`material3/Picker.kt:659-698`). Upstream's `overflow =
 * TextOverflow.Visible` / `softWrap = false` pair is `maxLines = 1` with wrapping off in
 * [WearTimePickerOptionView].
 */
@Tunable
private fun TimePickerOptionText(
    text: String,
    selected: Boolean,
    layoutConfig: TimePickerLayoutConfig,
    colors: TimePickerColors,
) {
    Node(
        modifier = Modifier
            .viewClass(WearTimePickerOptionView::class.java)
            .matchParentWidth()
            .height(layoutConfig.optionHeight)
            .text(text)
            .timePickerText(
                TimePickerTextSpec(
                    style = layoutConfig.optionTextStyle,
                    colorArgb = (
                        if (selected) colors.selectedPickerContentColor
                        else colors.unselectedPickerContentColor
                        ).toArgb(),
                    baselineOffsetPx = layoutConfig.optionBaseline,
                    maxLines = 1,
                ),
            ),
    )
}

/**
 * `Separator` (`material3/TimePicker.kt:1146-1182`): the padded box is the view's own width and
 * horizontal padding, and the `layout { placeRelative(y = optionBaseline - baseline) }` that lifts
 * the colon onto the digits' baseline is [TimePickerTextSpec.baselineOffsetPx]. Upstream's
 * `clearAndSetSemantics {}` has no counterpart here.
 *
 * @param modifier Upstream's parameter of the same name, which reaches the `Text` inside the box.
 *   Here it reaches the box itself, which is the one view: it carries the vertical centring the
 *   nested LTR row needs, and nothing else is put in it.
 */
@Tunable
private fun TimePickerSeparator(
    textStyle: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    separatorPadding: Dp,
    text: String,
    optionHeight: Dp,
    optionBaseline: Int,
) {
    Node(
        modifier = modifier
            .viewClass(WearTimePickerOptionView::class.java)
            .width(TimePickerSeparatorWidth + separatorPadding * 2)
            .height(optionHeight)
            .padding(horizontal = separatorPadding)
            .text(text)
            .timePickerText(
                TimePickerTextSpec(
                    style = textStyle,
                    colorArgb = color.toArgb(),
                    baselineOffsetPx = optionBaseline,
                    maxLines = 1,
                ),
            ),
    )
}

/**
 * Upstream's `Row` that only exists to force LTR (`material3/TimePicker.kt:762-767`). Both halves of
 * the forcing are needed: the [LocalLayoutDirection] provider for whatever Hibari reads from the
 * tuner, and the view's own `layoutDirection`, which is what a `LinearLayout` actually reverses on
 * and what `Modifier.padding` resolves its edges against.
 */
@Tunable
private fun TimePickerLtrRow(modifier: Modifier, content: @Tunable RowScope.() -> Unit) {
    TunationLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Ltr,
        content = {
            Row(modifier = modifier.timePickerViewLayoutDirection(View.LAYOUT_DIRECTION_LTR)) {
                content()
            }
        },
    )
}

/**
 * `rememberPickerLayoutConfig` (`material3/TimePicker.kt:946-1064`) without the `remember`: a tune
 * body has no `@Composable` `TextMeasurer` to hold, so the measuring is done by the plain
 * [timePickerLayoutConfig] below, which caches the finished config on the same five keys upstream
 * lists (`:992-998`) — `LocalTypography.current` included, since a different theme must re-measure.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Tunable
private fun timePickerLayoutConfig(
    timePickerType: TimePickerType,
    localeConfig: TimePickerLocaleConfig,
): TimePickerLayoutConfig {
    val context = currentContext
    val density = context.resources.displayMetrics.density
    val screenWidthDp = context.resources.configuration.screenWidthDp
    val typography = MaterialTheme.typography
    val isLargeScreen = screenWidthDp >= WearScreen.LargeScreenWidthDp

    val labelTextStyle = typography.fromToken(
        if (isLargeScreen) TimePickerTokens.LabelLargeTypography else TimePickerTokens.LabelTypography,
    )
    // Upstream's `.copy(textAlign = TextAlign.Center, fontFeatureSettings = "tnum")` (`:961-968`):
    // Hibari's `TextStyle` carries no alignment, so the centring is the view's gravity and only the
    // tabular-figures override travels through the style.
    val optionTextStyle = typography.fromToken(
        if (isLargeScreen) TimePickerTokens.ContentLargeTypography else TimePickerTokens.ContentTypography,
    ).copy(fontFeatureSettings = "tnum")

    return remember(timePickerType, localeConfig, screenWidthDp, density, typography) {
        val minimumOptionHeight: Dp = if (isLargeScreen) 46.dp else 36.dp
        val maximumOptionHeight: Dp = if (isLargeScreen) 58.dp else 48.dp
        val optionSpacing = if (isLargeScreen) 6.dp else 4.dp
        val separatorPadding = when {
            timePickerType.isTwoColumnPicker && isLargeScreen -> 12.dp
            timePickerType.isTwoColumnPicker && !isLargeScreen -> 8.dp
            timePickerType == TimePickerType.HoursMinutesAmPm12H && isLargeScreen -> 0.dp
            isLargeScreen -> 6.dp
            else -> 2.dp
        }

        val measuredMetrics = timePickerMeasureMetrics(optionTextStyle, density, localeConfig)
        // Upstream's `with(density) { px.toDp() } + 1.dp` buffer on the three widths (`:1013-1029`).
        val twoDigitsOptionWidth = (measuredMetrics.twoDigitsWidthPx / density).dp + 1.dp
        val measuredPeriodOptionWidth = (measuredMetrics.periodTextWidthPx / density).dp + 1.dp
        val fallbackPeriodOptionWidth = (measuredMetrics.fallbackPeriodWidthPx / density).dp + 1.dp
        val measuredOptionHeight = (measuredMetrics.optionHeightPx / density).dp
        val optionHeight = measuredOptionHeight.coerceIn(minimumOptionHeight, maximumOptionHeight)
        val optionBaseline = timePickerCalculateBaseline(
            measuredOptionBaselinePx = measuredMetrics.optionBaselinePx,
            measuredOptionHeight = measuredOptionHeight,
            maximumOptionHeight = maximumOptionHeight,
            minimumOptionHeight = minimumOptionHeight,
            density = density,
        )

        val separatorTotalWidth = TimePickerSeparatorWidth + (separatorPadding * 2)
        val useFallbackPeriodText = measuredPeriodOptionWidth > screenWidthDp.dp -
            separatorTotalWidth * 2 - twoDigitsOptionWidth * 2
        val periodOptionWidth =
            if (useFallbackPeriodText) fallbackPeriodOptionWidth else measuredPeriodOptionWidth
        val displayAmText =
            if (useFallbackPeriodText) TimePickerFallbackAmText else localeConfig.localizedAmText
        val displayPmText =
            if (useFallbackPeriodText) TimePickerFallbackPmText else localeConfig.localizedPmText

        TimePickerLayoutConfig(
            labelTextStyle = labelTextStyle,
            optionTextStyle = optionTextStyle,
            // `max(size, minimumInteractiveComponentSize)` (`material3/TimePicker.kt:1054-1055`), the
            // number from `InteractiveComponentSize.kt:99`.
            twoDigitsOptionWidth = maxOf(twoDigitsOptionWidth, MinimumInteractiveComponentSize),
            periodOptionWidth = maxOf(periodOptionWidth, MinimumInteractiveComponentSize),
            optionHeight = optionHeight,
            optionBaseline = optionBaseline,
            optionSpacing = optionSpacing,
            separatorPadding = separatorPadding,
            separatorTotalWidth = separatorTotalWidth,
            sectionVerticalPadding = if (isLargeScreen) 6.dp else 4.dp,
            displayAmText = displayAmText,
            displayPmText = displayPmText,
        )
    }
}

/**
 * `measurePickerMetrics` (`material3/TimePicker.kt:1068-1108`). Upstream hands both strings to
 * `TextMeasurer.measure`, whose `TextLayoutResult` on Android *is* a `StaticLayout`, so the five
 * numbers are read off two `StaticLayout`s the same way: the multi-line one for the three widths
 * (`getBoundingBox(char).width` is a character's advance, which is what `TextPaint.getTextWidths`
 * returns), the single line one for the height and the baseline — a line of its own so that the
 * first-line font padding upstream measures is added exactly once.
 *
 * `getLineWidth(i)` is upstream's `getLineRight(i) - getLineLeft(i)`: with `TextDirection.Ltr`
 * (upstream's default) Compose takes `max(lineLeft, lineRight)` and `min(...)`, which is the whole
 * painted extent of the line, and `StaticLayout.getLineWidth` is the same extent.
 *
 * Deviation, and it is a measurement one: a `TextStyle.lineHeight` is honoured by Compose's
 * `TextMeasurer` as an exact line box, while `StaticLayout` here falls back on the font's own
 * ascent-to-descent, so upstream's `optionHeightPx` comes out shorter (by roughly the font padding)
 * and `optionBaselinePx` sits correspondingly higher in the line. Where the digits end up is decided
 * by the same measured pair in both, and both then coerce into the same 46/58 dp (36/48 dp) range,
 * so the visible effect is a column pitch and baseline a few pixels apart — but a pixel diff against
 * upstream will show it. Closing it would mean reproducing how Compose distributes the shortfall
 * inside the line box, and `TextMeasurer`/`TextLayoutAndroid` are `compose.ui:ui-text`, outside the
 * reference tree: the numbers are not available to copy, so they are not guessed.
 */
private fun timePickerMeasureMetrics(
    style: TextStyle,
    density: Float,
    localeConfig: TimePickerLocaleConfig,
): TimePickerMeasuredMetrics {
    // `applyTimePickerTextStyle` is a Unit-returning extension, so building and configuring it in one
    // expression would type `paint` as Unit.
    val paint = TextPaint()
    paint.applyTimePickerTextStyle(style, density)
    val digits = localeConfig.localizedDigits
    val digitWidths = FloatArray(digits.length)
    paint.getTextWidths(digits, digitWidths)
    val am = localeConfig.localizedAmText
    val pm = localeConfig.localizedPmText

    val widthMeasureResult =
        timePickerStaticLayout(paint, "$digits\n$am\n$pm\n$TimePickerFallbackAmText\n$TimePickerFallbackPmText")
    val singleLineHeightMeasureResult = timePickerStaticLayout(
        paint,
        "$digits$am$pm$TimePickerFallbackAmText$TimePickerFallbackPmText",
    )

    return TimePickerMeasuredMetrics(
        twoDigitsWidthPx = digitWidths.max() * 2f,
        periodTextWidthPx = maxOf(
            widthMeasureResult.getLineWidth(1),
            widthMeasureResult.getLineWidth(2),
        ),
        fallbackPeriodWidthPx = maxOf(
            widthMeasureResult.getLineWidth(3),
            widthMeasureResult.getLineWidth(4),
        ),
        optionHeightPx = (
            singleLineHeightMeasureResult.getLineBottom(0) -
                singleLineHeightMeasureResult.getLineTop(0)
            ).toFloat(),
        optionBaselinePx = singleLineHeightMeasureResult.getLineBaseline(0).toFloat(),
    )
}

/**
 * `calculateBaseline` (`material3/TimePicker.kt:1110-1144`), the two branches and their comments
 * included: an option taller than the cap is shifted up by half the overflow so it is optically
 * centred in the clipped box, and a shorter one is pushed down by half the slack left by the
 * minimum height, so the touch target grows around the digits instead of moving them.
 */
private fun timePickerCalculateBaseline(
    measuredOptionBaselinePx: Float,
    measuredOptionHeight: Dp,
    maximumOptionHeight: Dp,
    minimumOptionHeight: Dp,
    density: Float,
): Int {
    return if (measuredOptionHeight > maximumOptionHeight) {
        val offset = minOf(0.dp, (maximumOptionHeight - measuredOptionHeight) / 2)
        (measuredOptionBaselinePx + offset.value * density).toInt()
    } else {
        val offset = maxOf(0.dp, (minimumOptionHeight - measuredOptionHeight) / 2)
        (measuredOptionBaselinePx + offset.value * density).toInt()
    }
}

/**
 * `createDescription` (`material3/TimePicker.kt:1184-1195`): the bare [label] while no column is
 * selected, otherwise the plural [plurals] resolved for `selectedValue` through
 * `Resources.getQuantityString(plurals, value, value)` — the per-locale plural selection upstream
 * performs, so `wear_m3c_time_picker_{hours,minutes,seconds}_content_description` picks its own
 * `one`/`other` form rather than this module applying English's `one`-at-1 rule. Upstream hands in its
 * `Plurals` value class; Hibari has no such type, so the `@PluralsRes` id is passed directly. See the
 * note on [TimePicker].
 */
private fun timePickerCreateDescription(
    context: Context,
    selectedElement: TimePickerSelection,
    selectedValue: Int,
    label: String,
    @PluralsRes plurals: Int,
): String = if (selectedElement == TimePickerSelection.None) {
    label
} else {
    context.resources.getQuantityString(plurals, selectedValue, selectedValue)
}

/**
 * `PickerLocaleConfig` (`material3/TimePicker.kt:1196-1245`), private upstream and private here. Upstream
 * marks it `@Immutable`; equality is hand-written below, because a `remember` key on a plain `Any`
 * would re-measure the whole layout on every tune.
 */
@RequiresApi(Build.VERSION_CODES.O)
private class TimePickerLocaleConfig(val locale: Locale, val timePickerType: TimePickerType) {
    val is12hour: Boolean = timePickerType == TimePickerType.HoursMinutesAmPm12H

    val skeleton: String = when (timePickerType) {
        TimePickerType.HoursMinutesAmPm12H -> "h:mm a"
        TimePickerType.HoursMinutesSeconds24H -> "H:mm:ss"
        TimePickerType.MinutesSeconds -> "mm:ss"
        else -> "H:mm"
    }

    val pattern: String = DateFormat.getBestDateTimePattern(locale, skeleton)

    val layoutElements: List<TimePickerLayoutElement> =
        timePickerGroupTimeParts(timePickerParsePattern(pattern))

    val focusableOrder: List<TimePickerSelection> = layoutElements
        .flatMap { element ->
            when (element) {
                is TimePickerLayoutElement.Standalone -> listOf(element.part)
                is TimePickerLayoutElement.TimeGroup -> element.parts
            }
        }
        .mapNotNull { part -> (part as? TimePickerPatternPart.ComponentPart)?.component }

    // The hour value offset is used to map the picker's 0-based index to the correct hour
    // value. Hour format patterns can be 0-based (e.g., H for 0-23, K for 0-11) or 1-based
    // (e.g., k for 1-24, h for 1-12). This offset accounts for that difference.
    val hourValueOffset: Int = if (pattern.contains('H') || pattern.contains('K')) 0 else 1

    val localizedDigits: String = buildString { (0..9).forEach { append("%d".format(locale, it)) } }

    val localizedAmText: String
    val localizedPmText: String

    init {
        if (is12hour) {
            val formatter = DateTimeFormatter.ofPattern("a", locale)
            localizedAmText = formatter.format(LocalTime.of(0, 0))
            localizedPmText = formatter.format(LocalTime.of(12, 0))
        } else {
            localizedAmText = ""
            localizedPmText = ""
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TimePickerLocaleConfig) return false
        // The two constructor properties are everything every field above is derived from.
        return locale == other.locale && timePickerType == other.timePickerType
    }

    override fun hashCode(): Int = 31 * locale.hashCode() + timePickerType.hashCode()
}

/** `PickerLocaleConfig.toInitialSelection` (`material3/TimePicker.kt:1248-1258`). */
@RequiresApi(Build.VERSION_CODES.O)
private fun timePickerToInitialSelection(
    localeConfig: TimePickerLocaleConfig,
    initialSelection: TimePickerSelection,
    touchExplorationServicesEnabled: Boolean,
): TimePickerSelection = when {
    touchExplorationServicesEnabled -> TimePickerSelection.None
    initialSelection == TimePickerSelection.ConfirmButton -> TimePickerSelection.ConfirmButton
    initialSelection in localeConfig.focusableOrder -> initialSelection
    else -> localeConfig.focusableOrder.firstOrNull() ?: TimePickerSelection.None
}

/** `PickerLocaleConfig.toDefaultSelection` (`material3/TimePicker.kt:1261-1262`). */
private fun timePickerDefaultSelection(
    localeConfig: TimePickerLocaleConfig,
): TimePickerSelection = localeConfig.focusableOrder.firstOrNull() ?: TimePickerSelection.None

/** `PickerLayoutConfig` (`material3/TimePicker.kt:1264-1276`). */
private class TimePickerLayoutConfig(
    val labelTextStyle: TextStyle,
    val optionTextStyle: TextStyle,
    val twoDigitsOptionWidth: Dp,
    val periodOptionWidth: Dp,
    val optionHeight: Dp,
    val optionBaseline: Int,
    val optionSpacing: Dp,
    val separatorPadding: Dp,
    /** Upstream's local `separatorTotalWidth` (`:1044`), kept because the group width needs it. */
    val separatorTotalWidth: Dp,
    val sectionVerticalPadding: Dp,
    val displayAmText: String,
    val displayPmText: String,
)

/** `PickerMeasuredMetrics` (`material3/TimePicker.kt:1278-1285`), raw pixels. */
private data class TimePickerMeasuredMetrics(
    val twoDigitsWidthPx: Float,
    val periodTextWidthPx: Float,
    val fallbackPeriodWidthPx: Float,
    val optionHeightPx: Float,
    val optionBaselinePx: Float,
)

/**
 * `TimeLayoutElement` (`material3/TimePicker.kt:1287-1297`). Upstream's is `internal` — checked
 * against the reference tree, nothing outside `TimePicker.kt` uses it, not even `DatePicker.kt` — so
 * it is `private` and `TimePicker`-prefixed here, which is also what keeps it out of the way of the
 * components being ported alongside this one.
 */
private sealed interface TimePickerLayoutElement {
    /** A group of components that must maintain a fixed LTR order (h:m:s). */
    data class TimeGroup(val parts: List<TimePickerPatternPart>) : TimePickerLayoutElement

    /** A standalone part that can be reordered by the parent layout direction. */
    data class Standalone(val part: TimePickerPatternPart) : TimePickerLayoutElement
}

/** `TimePatternPart` (`material3/TimePicker.kt:1300-1306`). */
private sealed interface TimePickerPatternPart {
    data class ComponentPart(val component: TimePickerSelection) : TimePickerPatternPart

    data class SeparatorPart(val separatorText: String) : TimePickerPatternPart
}

/** `TimePatternPart.isTimeGroupComponent()` (`material3/TimePicker.kt:1308-1317`). */
private fun TimePickerPatternPart.isTimeGroupComponent(): Boolean =
    this is TimePickerPatternPart.ComponentPart &&
        (component == TimePickerSelection.Hour ||
            component == TimePickerSelection.Minute ||
            component == TimePickerSelection.Second)

/** `groupTimeParts` (`material3/TimePicker.kt:1319-1360`), verbatim. */
private fun timePickerGroupTimeParts(
    parts: List<TimePickerPatternPart>,
): List<TimePickerLayoutElement> {
    val elements = mutableListOf<TimePickerLayoutElement>()
    var timeGroupParts = mutableListOf<TimePickerPatternPart>()

    for (i in parts.indices) {
        val part = parts[i]

        // An internal separator is one that is followed by a time component.
        val isInternalSeparator =
            part is TimePickerPatternPart.SeparatorPart &&
                (parts.getOrNull(i + 1)?.isTimeGroupComponent() ?: false)

        // A part should be added to the group if it's a time component itself,
        // OR if it's an internal separator AND the group has already been started.
        val shouldAddToGroup =
            part.isTimeGroupComponent() || (isInternalSeparator && timeGroupParts.isNotEmpty())

        if (shouldAddToGroup) {
            timeGroupParts.add(part)
        } else {
            // This part does not belong to the group. First, flush the existing group if it's
            // not empty.
            if (timeGroupParts.isNotEmpty()) {
                elements.add(TimePickerLayoutElement.TimeGroup(timeGroupParts))
                timeGroupParts = mutableListOf()
            }
            // Then, add the current part as a standalone element.
            elements.add(TimePickerLayoutElement.Standalone(part))
        }
    }

    // Flush any remaining group at the end of the loop.
    if (timeGroupParts.isNotEmpty()) {
        elements.add(TimePickerLayoutElement.TimeGroup(timeGroupParts))
    }
    return elements
}

/** `parsePattern` (`material3/TimePicker.kt:1362-1408`), verbatim. */
private fun timePickerParsePattern(originalPattern: String): List<TimePickerPatternPart> {
    // Sanitize the pattern by removing all quoted literals.
    // This handles edge cases like fr-CA ("HH 'h' mm") by turning them into "HHmm",
    // preventing the 'h' from being parsed as an Hour component.
    val pattern: String = originalPattern.replace(Regex("\\s*'.*?'\\s*"), "")
    val parts = mutableListOf<TimePickerPatternPart>()
    val separatorText = StringBuilder()
    pattern.forEach { char ->
        val component = when (char) {
            'h', 'H', 'k', 'K' -> TimePickerSelection.Hour
            'm' -> TimePickerSelection.Minute
            's' -> TimePickerSelection.Second
            'a' -> TimePickerSelection.Period
            else -> null
        }

        if (component != null) {
            // Found a component, first flush any pending literal
            if (separatorText.isNotEmpty()) {
                // Sanitize long, unquoted literals.
                // If the separator is longer than 1 char, replace it with a blank string.
                // This allows the heuristic below to ensure a simple space for a clean UI.
                // Otherwise, keep simple separators like ":" or ".".
                val sanitizedSeparator =
                    if (separatorText.length > 1) " " else separatorText.toString()
                if (parts.isNotEmpty()) {
                    parts.add(TimePickerPatternPart.SeparatorPart(sanitizedSeparator))
                }
                separatorText.clear()
            }
            // Add the component, avoiding duplicates
            if (parts.lastOrNull() != TimePickerPatternPart.ComponentPart(component)) {
                // Heuristic: Add a space if two components are adjacent (e.g., "aK")
                if (parts.isNotEmpty() && parts.last() is TimePickerPatternPart.ComponentPart) {
                    parts.add(TimePickerPatternPart.SeparatorPart(" "))
                }
                parts.add(TimePickerPatternPart.ComponentPart(component))
            }
        } else {
            // It's a literal character
            separatorText.append(char)
        }
    }
    // Trim trailing separators.
    // This handling of a literal at the start or end of the original pattern ensures our UI only
    // displays separators *between* components.
    return parts.dropLastWhile { it is TimePickerPatternPart.SeparatorPart }
}

/**
 * The width upstream's nested LTR row ends up with: each component at its column width, each
 * separator at [TimePickerLayoutConfig.separatorTotalWidth]. Upstream lets the row wrap, and
 * `WearPickerGroupView` would measure an undeclared child at the full width of the group, which
 * would push a standalone AM/PM column off the screen, so the width is stated. The arithmetic is
 * upstream's own (`:1044-1048`), where the same three terms decide whether the AM/PM text has to
 * fall back.
 */
private fun timePickerGroupWidth(
    parts: List<TimePickerPatternPart>,
    layoutConfig: TimePickerLayoutConfig,
): Dp {
    var width = 0.dp
    parts.forEach { part ->
        width += when (part) {
            is TimePickerPatternPart.ComponentPart ->
                if (part.component == TimePickerSelection.Period) {
                    layoutConfig.periodOptionWidth
                } else {
                    layoutConfig.twoDigitsOptionWidth
                }
            is TimePickerPatternPart.SeparatorPart -> layoutConfig.separatorTotalWidth
        }
    }
    return width
}

/**
 * The `textStyle` attribute's work, aimed at a [TextPaint] instead of a `TextView` and with one
 * change: the size is resolved through density only, never `scaledDensity`, which is the whole of
 * `FontScaleIndependent` for whatever this paint measures.
 */
private fun TextPaint.applyTimePickerTextStyle(style: TextStyle, density: Float) {
    isAntiAlias = true
    if (style.fontSize.isSp) {
        textSize = style.fontSize.value * density
    }
    val tracking = style.letterSpacing
    if (tracking.isSp && style.fontSize.isSp && style.fontSize.value > 0f) {
        letterSpacing = tracking.value / style.fontSize.value
    }
    var typeface: Typeface? = style.fontFamily.typeface(style.fontWeight.toAndroidStyle())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        typeface = style.fontWeight.createTypeface(typeface)
    }
    typeface?.let { this.typeface = it }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        style.fontWeight.fontVariationSettings(style.widthAxis)?.let { this.fontVariationSettings = it }
        style.fontFeatureSettings?.let { this.fontFeatureSettings = it }
    }
}

/** `StaticLayout` with upstream's measurement constraints: unbounded width, no wrapping. */
private fun timePickerStaticLayout(paint: TextPaint, text: String): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, TimePickerMeasureWidthPx)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(true)
        .setLineSpacing(0f, 1f)
        .build()

/**
 * `LocalTouchExplorationStateProvider.current.touchExplorationState()`: wear's listener is
 * `accessibilityManager.isEnabled && isTouchExplorationEnabled`. Sampled per tune, the same
 * approximation [PickerGroup] documents.
 */
private fun isTimePickerTouchExplorationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    return manager?.isEnabled == true && manager.isTouchExplorationEnabled
}

/**
 * Upstream's `Icons.Check` (`internal/Icons.kt:95-124`) as a [Drawable]: upstream builds it as an
 * `ImageVector` through compose-ui's icon DSL, which has no counterpart here, so the one glyph
 * [TimePicker] needs is drawn from the same path data on a 960-unit viewport
 * (`internal/Icons.kt:222-223`). The colour is carried by the drawable rather than by the
 * `ImageView`'s colour filter, which a hand-built [Drawable] would ignore.
 */
private fun timePickerCheckDrawable(colorArgb: Int): Drawable = TimePickerCheckDrawable(colorArgb)

private class TimePickerCheckDrawable(private val colorArgb: Int) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun draw(canvas: Canvas) {
        val bounds: Rect = bounds
        if (bounds.isEmpty) return
        fill.color = colorArgb
        val scale = bounds.width() / TimePickerIconViewport
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(scale, bounds.height() / TimePickerIconViewport)
        canvas.drawPath(CheckPath, fill)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val TimePickerIconViewport = 960f

        val CheckPath = Path().apply {
            moveTo(382f, 597.87f)
            lineTo(716.7f, 263.17f)
            quadTo(730.37f, 249.5f, 748.76f, 249.5f)
            quadTo(767.15f, 249.5f, 780.83f, 263.17f)
            quadTo(794.5f, 276.85f, 794.5f, 295.62f)
            quadTo(794.5f, 314.39f, 780.83f, 328.07f)
            lineTo(414.07f, 695.59f)
            quadTo(400.39f, 709.26f, 382f, 709.26f)
            quadTo(363.61f, 709.26f, 349.93f, 695.59f)
            lineTo(178.41f, 524.07f)
            quadTo(164.74f, 510.39f, 165.12f, 491.62f)
            quadTo(165.5f, 472.85f, 179.17f, 459.17f)
            quadTo(192.85f, 445.5f, 211.62f, 445.5f)
            quadTo(230.39f, 445.5f, 244.07f, 459.17f)
            lineTo(382f, 597.87f)
            close()
        }
    }
}

/** `Modifier.timePickerText` — the one attribute [WearTimePickerOptionView] needs. */
private fun Modifier.timePickerText(spec: TimePickerTextSpec): Modifier =
    this.thenViewAttribute<WearTimePickerOptionView, TimePickerTextSpec>(uniqueKey, spec) {
        timePickerTextSpec = it
    }

/** The view-level half of upstream's forced-LTR `CompositionLocalProvider`; see [TimePickerLtrRow]. */
private fun Modifier.timePickerViewLayoutDirection(value: Int): Modifier =
    this.thenViewAttribute<View, Int>(uniqueKey, value) { layoutDirection = it }

/** `TimePicker.kt:300`: the heading's own horizontal inset, as a percentage of the screen width. */
private const val TimePickerTextPaddingPercentage = 30f

/** `PaddingDefaults.horizontalContentPadding(30f)` — material3/Padding.kt:57-60 — ceil'd to whole dp. */
private fun timePickerHeadingPaddingDp(context: Context): Dp =
    ceil(context.resources.configuration.screenWidthDp * TimePickerTextPaddingPercentage / 100f).dp

/** `private val SeparatorWidth = 12.dp` (`material3/TimePicker.kt:1413`). */
private val TimePickerSeparatorWidth = 12.dp

/** `private const val FallbackAmText = "AM"` / `FallbackPmText = "PM"` (`:1411-1412`). */
private const val TimePickerFallbackAmText = "AM"
private const val TimePickerFallbackPmText = "PM"

/** `Modifier.size(24.dp)` around the confirm icon (`material3/TimePicker.kt:379`). */
private val TimePickerIconSize = 24.dp

/** The width upstream measures at: Compose's `Constraints.MaxWidth`, wide enough never to wrap. */
private const val TimePickerMeasureWidthPx = 1 shl 30
