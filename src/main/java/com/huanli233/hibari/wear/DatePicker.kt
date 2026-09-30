/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Port of androidx.wear.compose.material3 DatePicker.kt, from the reference tree
// /home/rj/qmce/app-new/src/main/java/androidx/wear/compose/material3/DatePicker.kt (969 lines).
// Every line number below is from that file.

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
import androidx.annotation.RequiresApi
import com.huanli233.hibari.animation.Animatable
import com.huanli233.hibari.foundation.Box
import com.huanli233.hibari.foundation.Column
import com.huanli233.hibari.foundation.ColumnScope
import com.huanli233.hibari.foundation.Node
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.Spacer
import com.huanli233.hibari.foundation.attributes.alpha
import com.huanli233.hibari.foundation.attributes.height
import com.huanli233.hibari.foundation.attributes.matchParentHeight
import com.huanli233.hibari.foundation.attributes.matchParentSize
import com.huanli233.hibari.foundation.attributes.matchParentWidth
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.size
import com.huanli233.hibari.foundation.attributes.text
import com.huanli233.hibari.foundation.attributes.width
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.effects.LaunchedEffect
import com.huanli233.hibari.runtime.getValue
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
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.ui.viewClass
import com.huanli233.hibari.wear.tokens.DatePickerTokens
import com.huanli233.hibari.wear.view.TimePickerTextSpec
import com.huanli233.hibari.wear.view.WearTimePickerOptionView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.text.format

/* =================================================================================================
 * What this file is
 *
 * The reference tree's `DatePicker` is the material3 1.2 component: one public composable
 * (`DatePicker`, :107-581), a `DatePickerType` value class (:583-602), `DatePickerDefaults`
 * (:605-682), `DatePickerColors` (:698-778) and the private machinery the composable runs on
 * (:780-969). Every one of those is ported here.
 *
 * The entry point is the heading (:284-302), the row of columns (:390-513) and the confirm button
 * (:516-574). The row is composed through `PickerGroup` / `PickerGroupItem` exactly as upstream
 * composes it, so a column keeps this module's ported scroll, snap, rotary and read-only behaviour;
 * what the entry point adds on top of that is the option measurement (:306-388), which is what
 * decides the three column widths, the option height and the one baseline the columns share, and the
 * two corrections that run when a year or a month changes (:174-201).
 *
 * [DatePickerState] (:809-952) is the interesting half: its three columns are already-ported
 * [PickerState]s, built with upstream's own option counts and indices, so the whole selection model
 * — `selectedYear` / `selectedMonth` / `selectedDay`, `isYearValid` / `isMonthValid` / `isDayValid`
 * and the two `adjust*OptionIfInvalid` corrections — is ported without needing anything the picker
 * does not already do. Upstream creates it in a `remember(initialDate)` at :126, which is exactly how
 * [rememberPickerState] hands a [PickerState] to a [Picker].
 *
 * No type here is invented: every declaration in this file exists in the reference tree with the
 * line number cited. The additions are the seven string members on [DatePickerDefaults] (this
 * module ships no resources — see the note in [DatePicker]), the private [DatePickerOptionSpec]
 * that carries what upstream keeps as locals of the composable body, and the visibility changes and
 * hoisted-default workarounds the component's own KDoc lists.
 *
 * Not ported, with the upstream range and the reason:
 *  - `DatePickerDialog`, `WeekDatePicker`, `YearRoundDatePicker`, `YearListDatePicker`,
 *    `RowColumnDatePicker`, `DayPickerLayoutType`, `OnDateChange`, `DatePickerRange`,
 *    `DatePickerScope` and `rememberDatePickerState`: **these are not in the reference tree.** They
 *    belong to a later upstream DatePicker (the 1.3 `DatePickerState` with
 *    `newDayOfMonthColumn` / `newDatePickerColumns` and the `WeekFields` calendar maths), which
 *    `find /home/rj/qmce -name DatePicker.kt` does not contain. Anyone working from a brief that
 *    names them, or that has the invalid option drawn through a `graphicsLayer` alpha rather than
 *    through `colors.invalidPickerContentColor` (`material3/Picker.kt:659-698`), is working from that
 *    other version, not from this tree.
 *  - `LocalInspectionMode` (:118) and the `if (!inspectionMode)` guard it puts on the entry fade
 *    (:578-580): no counterpart here, so [DatePicker] always fades in.
 *  - `Modifier.semantics(mergeDescendants = true) { heading() }` (:296) on the label and
 *    `Modifier.semantics { focused = … }` (:533) on the button: this module has no semantics
 *    surface. `selectableGroup()`, `stateDescription` and `Modifier.onGloballyPositioned` are **not**
 *    in upstream's DatePicker at all, so nothing about them is missing here.
 *  - `FadeLabel` (:285-301, `material3/AnimationSpecUtils.kt:215-253`) cross-fades the heading when
 *    the text changes; it needs `FiniteAnimationSpec.faster(200f)`, which hibari-animation does not
 *    carry, so the heading switches at once. Same gap [TimePicker] documents for the same call.
 *  - `@Immutable` on `DatePickerColors` (:698) and on `DatePickerType` (:584): androidx.compose
 *    .runtime has no Hibari counterpart, so the annotations are dropped. Nothing else about either
 *    class changes; the colours still hold eight immutable [Color]s and compare by all eight.
 *  - `LocalContext.current`, which `datePickerType` reads (:611): replaced by [currentContext], the
 *    tune-local read the sibling `TimePickerDefaults` uses for the same purpose.
 * =================================================================================================
 */

/**
 * Full screen [DatePicker] with day, month, year (:74-581).
 *
 * This component is designed to take most/all of the screen and utilizes large fonts.
 *
 * For custom backgrounds like gradients or images wrap the DatePicker in a MaterialTheme with the
 * colorScheme background set to [Color.Unspecified].
 *
 * Ported from androidx.wear.compose.material3.DatePicker. The three columns are the already-ported
 * [Picker]s composed through [PickerGroup] / [PickerGroupItem] (:397-512), exactly as upstream
 * composes them, so a column's scroll, snap, rotary and read-only behaviour is the one the picker
 * port carries; what this function adds is the heading, the option measurement that sizes the
 * columns, the two invalid-selection corrections and the [EdgeButton] that steps through the columns
 * and then confirms.
 *
 * `@RequiresApi(Build.VERSION_CODES.O)` is upstream's (:107) and stays: the entry point takes and
 * returns a `java.time.LocalDate`, and this module's minSdk is 25, so a caller below API 26 has
 * either to skip the component or re-derive the fields by hand — which is not what the signature
 * promises.
 *
 * Signature deviations: [datePickerType] and [colors] are nullable with `null` standing for
 * upstream's defaults, because a `@Tunable` default expression is hoisted out of the tuner and may
 * not read `MaterialTheme` or a context. Everything else is upstream's parameter list, in its order.
 *
 * Not ported, with the upstream line and the reason:
 *  - `LocalInspectionMode.current` (:118) has no counterpart, so the entry fade (:119, :578-580)
 *    always runs rather than being skipped in a preview.
 *  - `LocalTouchExplorationStateProvider.current.touchExplorationState()` (:128-129) is reached
 *    through this module's port of it ([touchExplorationState]), so the "no column is selected under
 *    TalkBack" rule (:134-144) is upstream's, and it is live: a change in the service re-tunes the
 *    picker the way a recomposition does.
 *  - `semantics { focused = … }` (:533) on the confirm button and the `Modifier.focusable()` (:535)
 *    beside it: this module has no semantics surface, and the focusability is not needed, because
 *    [HierarchicalFocusRequester.requestFocus] raises `isFocusable` / `isFocusableInTouchMode` on the
 *    view it is bound to when it is called. The requester itself (:166), the `requestFocus()` that
 *    closes the column rotation (:256-258) and the `focusRequester` half of the button's modifier
 *    chain (:534) are ported through [hierarchicalFocusRequester]; [PickerGroupItem] needs no
 *    requester here because upstream passes none for a date-picker column, and its default installs
 *    the focus site that carries the rotary stream.
 *  - `FadeLabel` (:285-301) cross-fades the heading when the text changes; see the file comment. The
 *    label's `semantics(mergeDescendants = true) { heading() }` (:296) has no Hibari equivalent.
 *  - Strings (:168-172): this module ships no resources, so the four labels, the instruction heading,
 *    the `"%1$s, %2$d"` content-description template and the two button descriptions are the English
 *    values of `res/values/wear_m3c_strings.xml:8-15,34`, exposed as [DatePickerDefaults] members.
 *    They are not localised.
 *  - `val boxConstraints = this` (:263) is upstream's unused capture of `BoxWithConstraints`; it
 *    disappears with the constraints receiver.
 *
 * @param initialDate The initial value to be displayed in the DatePicker.
 * @param onDatePicked The callback that is called when the user confirms the date selection. It
 *   provides the selected date as [LocalDate]
 * @param modifier Modifier to be applied to the `Box` containing the UI elements.
 * @param minValidDate Optional minimum date that can be selected in the DatePicker (inclusive).
 * @param maxValidDate Optional maximum date that can be selected in the DatePicker (inclusive).
 * @param datePickerType The different [DatePickerType] supported by this [DatePicker]. `null` means
 *   upstream's default, [DatePickerDefaults.datePickerType].
 * @param colors [DatePickerColors] to be applied to the DatePicker. `null` means upstream's default,
 *   [DatePickerDefaults.datePickerColors].
 */
@RequiresApi(Build.VERSION_CODES.O)
@Tunable
public fun DatePicker(
    initialDate: LocalDate,
    onDatePicked: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    minValidDate: LocalDate? = null,
    maxValidDate: LocalDate? = null,
    datePickerType: DatePickerType? = null,
    colors: DatePickerColors? = null,
) {
    val type = datePickerType ?: DatePickerDefaults.datePickerType
    val pickerColors = colors ?: DatePickerDefaults.datePickerColors()

    val context = currentContext
    val fullyDrawn = remember { Animatable(0f) }

    if (minValidDate != null && maxValidDate != null) {
        verifyDates(initialDate, minValidDate, maxValidDate)
    }

    val datePickerState =
        remember(initialDate) { DatePickerState(initialDate, minValidDate, maxValidDate) }

    val touchExplorationServicesEnabled by touchExplorationState(context)

    /** The current selected [Picker] index. */
    var selectedIndex: Int? by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(touchExplorationServicesEnabled) {
        // When the date picker loads, none of the individual pickers are selected in talkback mode,
        // otherwise first picker should be focused (depends on the picker ordering given by
        // datePickerType)
        selectedIndex = if (touchExplorationServicesEnabled) null else 0
    }

    val isLargeScreen = WearScreen.isLargeScreen(context)
    val typography = MaterialTheme.typography
    val labelTextStyle = typography.fromToken(
        if (isLargeScreen) DatePickerTokens.LabelLargeTypography else DatePickerTokens.LabelTypography,
    )
    // Upstream's `DatePickerTokens.Content*Typography.value.copy(textAlign = Center,
    // fontFeatureSettings = "tnum")` (:153-164): `*Tokens.*.value` is the internal
    // `TypographyKeyTokens.value` extension (`material3/Typography.kt:384-389`), i.e.
    // `MaterialTheme.typography.fromToken(it)`. Hibari's `TextStyle` carries no alignment, so the
    // centring is the option view's own gravity and only the tabular-figures override travels through
    // the style — the same split [TimePicker] documents at its `optionTextStyle`.
    val optionTextStyle = typography.fromToken(
        if (isLargeScreen) DatePickerTokens.ContentLargeTypography
        else DatePickerTokens.ContentTypography,
    ).copy(fontFeatureSettings = "tnum")

    val yearString = DatePickerDefaults.yearText
    val monthString = DatePickerDefaults.monthText
    val dayString = DatePickerDefaults.dayText
    val contentDescriptionTemplate = DatePickerDefaults.contentDescriptionTemplate

    val focusRequesterConfirmButton = remember { HierarchicalFocusRequester() }

    LaunchedEffect(
        datePickerState.isMinYearSelected,
        datePickerState.isMaxYearSelected,
        datePickerState.yearState.isScrollInProgress,
        datePickerState.monthState.isScrollInProgress,
    ) {
        if (
            (datePickerState.isMinYearSelected || datePickerState.isMaxYearSelected) &&
            !datePickerState.yearState.isScrollInProgress &&
            !datePickerState.monthState.isScrollInProgress
        ) {
            datePickerState.adjustMonthOptionIfInvalid()
        }
    }
    LaunchedEffect(
        datePickerState.yearState.isScrollInProgress,
        datePickerState.monthState.isScrollInProgress,
        datePickerState.dayState.isScrollInProgress,
    ) {
        if (
            !datePickerState.yearState.isScrollInProgress &&
            !datePickerState.monthState.isScrollInProgress &&
            !datePickerState.dayState.isScrollInProgress &&
            datePickerState.isSelectedMonthValid
        ) {
            datePickerState.adjustDayOptionIfInvalid()
        }
    }

    val locale = context.resources.configuration.locales[0]
    val monthPattern = remember(locale) {
        val yearPattern = DateFormat.getBestDateTimePattern(locale, "y")
        // Upstream's heuristic (:207-217): a pattern that carries any letter besides 'y' — a
        // linguistic marker like '年', '년', 'г' — is one whose month names would not fit a narrow
        // column, so the column falls back to two digits.
        val useNumericMonth = yearPattern.any { it.isLetter() && it != 'y' }
        if (useNumericMonth) "MM" else "MMM"
    }

    val shortMonthNames = remember(monthPattern) { getMonthNames(monthPattern) }
    val fullMonthNames = remember { getMonthNames("MMMM") }
    val yearContentDescription = {
        createDescriptionDatePicker(
            locale,
            contentDescriptionTemplate,
            selectedIndex,
            datePickerState.selectedYear,
            yearString,
        )
    }
    val monthContentDescription = {
        if (selectedIndex == null) {
            monthString
        } else {
            fullMonthNames[(datePickerState.selectedMonth - 1) % 12]
        }
    }
    val dayContentDescription = {
        createDescriptionDatePicker(
            locale,
            contentDescriptionTemplate,
            selectedIndex,
            datePickerState.selectedDay,
            dayString,
        )
    }

    val datePickerOptions = type.toDatePickerOptions()
    val confirmButtonIndex = datePickerOptions.size

    val onPickerSelected = { current: Int, next: Int ->
        if (selectedIndex != current) {
            selectedIndex = current
        } else {
            selectedIndex = next
            if (next == confirmButtonIndex) {
                // Upstream's `focusRequesterConfirmButton.requestFocus()` (:257): the ring moves onto
                // the confirm button, so a rotary click or a D-pad press confirms the date.
                focusRequesterConfirmButton.requestFocus()
            }
        }
    }

    Box(modifier = modifier.matchParentSize().alpha(fullyDrawn.value)) {
        val heading =
            selectedIndex?.let {
                when (datePickerOptions.getOrNull(it)) {
                    DatePickerOption.Day -> dayString
                    DatePickerOption.Month -> monthString
                    DatePickerOption.Year -> yearString
                    else -> ""
                }
            } ?: if (touchExplorationServicesEnabled) DatePickerDefaults.headingText else ""

        // Allow more room for the initial instruction heading under TalkBck (:274-278).
        val maxTextLines = if (selectedIndex == null) 2 else 1
        val textPaddingPercentage = 30f
        val topPadding = if (selectedIndex == null) 0.dp else 14.dp
        val headingHeight = 38.dp - topPadding

        // Upstream wraps only the row of columns in `FontScaleIndependent` (:303); here the whole
        // `Column` sits inside it, because `Modifier.weight` — what makes the row take the leftover
        // height upstream (:391) — is a `ColumnScope` member, and the ported surface reaches scope
        // members through the scope's own body rather than a receiver captured two lambdas deep. The
        // wider scope is inert: the heading and the options are drawn by
        // [WearTimePickerOptionView], which sizes `sp` from raw density alone, and the only `sp` read
        // inside is [currentSpToPx] in [datePickerOptionSpec]. See `FontScaleIndependent` for what
        // the wrapper can and cannot neutralise on Views.
        FontScaleIndependent {
            // Upstream's `Column(verticalArrangement = Center, horizontalAlignment = Center)`
            // (:280-283): the weighted row below takes all the leftover height, so the centring
            // arrangement has nothing left to distribute, and each child carries its own horizontal
            // gravity.
            Column(modifier = Modifier.matchParentSize()) {
                Spacer(Modifier.height(topPadding))

                // `FadeLabel` (:285-301), without the cross-fade or the heading semantics.
                Node(
                    modifier = Modifier
                        .matchParentWidth()
                        .height(headingHeight)
                        .gravity(Gravity.CENTER_HORIZONTAL)
                        .padding(
                            horizontal = datePickerHeadingPaddingDp(context, textPaddingPercentage),
                        )
                        .viewClass(WearTimePickerOptionView::class.java)
                        .text(heading)
                        .datePickerText(
                            TimePickerTextSpec(
                                style = labelTextStyle,
                                colorArgb = pickerColors.pickerLabelColor.toArgb(),
                                maxLines = maxTextLines,
                            ),
                        ),
                )

                Spacer(Modifier.height(if (isLargeScreen) 6.dp else 4.dp))

                val optionSpec = datePickerOptionSpec(isLargeScreen, optionTextStyle, shortMonthNames)

                datePickerColumns(
                    datePickerState = datePickerState,
                    datePickerOptions = datePickerOptions,
                    selectedIndex = selectedIndex,
                    onPickerSelected = onPickerSelected,
                    dayContentDescription = dayContentDescription,
                    monthContentDescription = monthContentDescription,
                    yearContentDescription = yearContentDescription,
                    colors = pickerColors,
                    optionSpec = optionSpec,
                    locale = locale,
                )

                Spacer(Modifier.height(if (isLargeScreen) 6.dp else 4.dp))

                // If none is selected (selectedIndex == null) we show 'next' instead of 'confirm'.
                val showConfirm = selectedIndex?.let { it >= 2 } == true
                // Built once per colour: the entry fade retunes this body every frame, and `image` is
                // an attribute that only lands when it changes.
                val confirmIcon = remember(pickerColors.confirmButtonContentColor) {
                    DatePickerIconDrawable(
                        pickerColors.confirmButtonContentColor.toArgb(),
                        DatePickerCheckPath,
                    )
                }
                val nextIcon = remember(pickerColors.nextButtonContentColor) {
                    DatePickerIconDrawable(
                        pickerColors.nextButtonContentColor.toArgb(),
                        DatePickerArrowRightPath,
                    )
                }

                EdgeButton(
                    onClick = {
                        selectedIndex?.let { selectedIndex ->
                            if (selectedIndex >= 2) {
                                val pickedDate = LocalDate.of(
                                    datePickerState.selectedYear,
                                    datePickerState.selectedMonth,
                                    datePickerState.selectedDay,
                                )
                                onDatePicked(pickedDate)
                            } else {
                                onPickerSelected(selectedIndex, selectedIndex + 1)
                            }
                        }
                    },
                    // Upstream's `Modifier.semantics { focused = … }.focusRequester(…)
                    // .focusable()` (:532-535); only the `focusRequester` half is portable here, and
                    // [HierarchicalFocusRequester.requestFocus] is what makes the view focusable when
                    // the rotation reaches it. `EdgeButtonSize.Small` is upstream's default, so the
                    // size is not passed.
                    modifier = Modifier.hierarchicalFocusRequester(focusRequesterConfirmButton),
                    colors = if (showConfirm) {
                        // Upstream's `buttonColors(contentColor = …, containerColor = …)` (:538-541),
                        // which this module has as the same two-named-argument factory. [EdgeButton]
                        // reads only `containerColor`/`contentColor` and their disabled twins, so the
                        // roles left on the theme's defaults are the ones upstream renders too.
                        ButtonDefaults.buttonColors(
                            contentColor = pickerColors.confirmButtonContentColor,
                            containerColor = pickerColors.confirmButtonContainerColor,
                        )
                    } else {
                        // Upstream's `filledTonalButtonColors(contentColor = …, containerColor = …)`
                        // (:543-546): [ButtonDefaults] carries that factory with no colour arguments,
                        // so the two roles it would replace are replaced by hand — the same
                        // substitution [TimePicker] makes for its confirm colour.
                        ButtonDefaults.filledTonalButtonColors().copy(
                            contentColor = pickerColors.nextButtonContentColor,
                            containerColor = pickerColors.nextButtonContainerColor,
                        )
                    },
                    enabled = if (showConfirm) {
                        datePickerState.isSelectedDayValid
                    } else {
                        // Disable the 'next' button under TalkBack until a Picker is selected.
                        selectedIndex != null
                    },
                ) {
                    Icon(
                        image = if (showConfirm) confirmIcon else nextIcon,
                        contentDescription = if (showConfirm) {
                            DatePickerDefaults.confirmButtonContentDescription
                        } else {
                            // If none is selected, return the 'next' content description.
                            DatePickerDefaults.nextButtonContentDescription
                        },
                        modifier = Modifier.size(DpSize(DatePickerIconSize, DatePickerIconSize)),
                        // Upstream's `Icon` tints an `ImageVector`; these hand-built drawables carry
                        // their own colour because `setColorFilter` cannot reach them — see
                        // [DatePickerIconDrawable]. The `wrapContentSize(Center)` upstream adds
                        // around the 24.dp box is the view's own centring here.
                        tint = if (showConfirm) {
                            pickerColors.confirmButtonContentColor
                        } else {
                            pickerColors.nextButtonContentColor
                        },
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) { fullyDrawn.animateTo(1f) }
}

/**
 * The row of columns (:390-513): upstream's weighted `Row` around a
 * `PickerGroup(selectedPickerState = …, autoCenter = true)` whose content is the
 * `datePickerOptions.forEachIndexed` at :409-511.
 *
 * Upstream's `Row(fillMaxWidth().weight(1f))` becomes [Modifier.weight] plus `height(0.dp)`: a
 * `wrap_content` child of a vertical `LinearLayout` would take the leftover height in the Compose
 * sense but not in the Views one, where the leftover only reaches a child whose main-axis size is 0.
 * Its `verticalAlignment = CenterVertically` and `horizontalArrangement = Center` need no
 * counterpart because the [PickerGroup] fills the row and centres its own children. The group carries
 * [Modifier.matchParentSize] rather than upstream's bare `Modifier` for the reason [TimePicker]
 * gives: `WearPickerGroupView` answers an unspecified height with the tallest child, and with the
 * columns set to fill the height that answer is circular.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Tunable
private fun ColumnScope.datePickerColumns(
    datePickerState: DatePickerState,
    datePickerOptions: Array<DatePickerOption>,
    selectedIndex: Int?,
    onPickerSelected: (Int, Int) -> Unit,
    dayContentDescription: () -> String,
    monthContentDescription: () -> String,
    yearContentDescription: () -> String,
    colors: DatePickerColors,
    optionSpec: DatePickerOptionSpec,
    locale: Locale,
) {
    Row(modifier = Modifier.matchParentWidth().height(0.dp).weight(1f)) {
        PickerGroup(
            modifier = Modifier.matchParentSize(),
            // Pass a negative value as the selected picker index when none is selected.
            selectedPickerState = selectedIndex?.let {
                when (datePickerOptions.getOrNull(it)) {
                    DatePickerOption.Day -> datePickerState.dayState
                    DatePickerOption.Month -> datePickerState.monthState
                    DatePickerOption.Year -> datePickerState.yearState
                    else -> null
                }
            },
            autoCenter = true,
        ) {
            datePickerOptions.forEachIndexed { index, datePickerOption ->
                val selected = index == selectedIndex
                when (datePickerOption) {
                    DatePickerOption.Day -> datePickerDayColumn(
                        datePickerState = datePickerState,
                        selected = selected,
                        onSelected = { onPickerSelected(index, index + 1) },
                        contentDescription = dayContentDescription,
                        colors = colors,
                        optionSpec = optionSpec,
                        locale = locale,
                    )

                    DatePickerOption.Month -> datePickerMonthColumn(
                        datePickerState = datePickerState,
                        selected = selected,
                        onSelected = { onPickerSelected(index, index + 1) },
                        contentDescription = monthContentDescription,
                        colors = colors,
                        optionSpec = optionSpec,
                        locale = locale,
                    )

                    DatePickerOption.Year -> datePickerYearColumn(
                        datePickerState = datePickerState,
                        selected = selected,
                        onSelected = { onPickerSelected(index, index + 1) },
                        contentDescription = yearContentDescription,
                        colors = colors,
                        optionSpec = optionSpec,
                        locale = locale,
                    )
                }
                if (index < datePickerOptions.size - 1) {
                    Spacer(Modifier.width(optionSpec.columnSpacing))
                }
            }
        }
    }
}

/**
 * `PickerGroupItem` for the day column (:412-443). Upstream writes all three inline in the
 * `forEachIndexed`; they are one function each here because [PickerGroupItem]'s `option` slot is a
 * tuned lambda, which cannot be built by a function that returns one — the same reason
 * [TimePicker] spells its columns out rather than porting `pickerTextOption`'s shape.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Tunable
private fun PickerGroupScope.datePickerDayColumn(
    datePickerState: DatePickerState,
    selected: Boolean,
    onSelected: () -> Unit,
    contentDescription: () -> String,
    colors: DatePickerColors,
    optionSpec: DatePickerOptionSpec,
    locale: Locale,
) {
    PickerGroupItem(
        pickerState = datePickerState.dayState,
        modifier = Modifier.width(optionSpec.dayWidth).matchParentHeight(),
        selected = selected,
        onSelected = onSelected,
        contentDescription = contentDescription,
        verticalSpacing = optionSpec.verticalSpacing,
        option = { optionIndex, pickerSelected ->
            DatePickerOptionText(
                text = "%02d".format(locale, datePickerState.dayValue(optionIndex)),
                selected = pickerSelected,
                valid = datePickerState.isDayValid(datePickerState.dayValue(optionIndex)),
                colors = colors,
                optionSpec = optionSpec,
            )
        },
    )
}

/** See [datePickerDayColumn]; upstream's `:444-473`, whose `indexToText` is the short month name. */
@RequiresApi(Build.VERSION_CODES.O)
@Tunable
private fun PickerGroupScope.datePickerMonthColumn(
    datePickerState: DatePickerState,
    selected: Boolean,
    onSelected: () -> Unit,
    contentDescription: () -> String,
    colors: DatePickerColors,
    optionSpec: DatePickerOptionSpec,
    locale: Locale,
) {
    PickerGroupItem(
        pickerState = datePickerState.monthState,
        modifier = Modifier.width(optionSpec.monthWidth).matchParentHeight(),
        selected = selected,
        onSelected = onSelected,
        contentDescription = contentDescription,
        verticalSpacing = optionSpec.verticalSpacing,
        option = { optionIndex, pickerSelected ->
            DatePickerOptionText(
                text = optionSpec.shortMonthNames[
                    (datePickerState.monthValue(optionIndex) - 1) % 12
                ],
                selected = pickerSelected,
                valid = datePickerState.isMonthValid(datePickerState.monthValue(optionIndex)),
                colors = colors,
                optionSpec = optionSpec,
            )
        },
    )
}

/** See [datePickerDayColumn]; upstream's `:474-506`, whose `indexToText` is `"%4d"`. */
@RequiresApi(Build.VERSION_CODES.O)
@Tunable
private fun PickerGroupScope.datePickerYearColumn(
    datePickerState: DatePickerState,
    selected: Boolean,
    onSelected: () -> Unit,
    contentDescription: () -> String,
    colors: DatePickerColors,
    optionSpec: DatePickerOptionSpec,
    locale: Locale,
) {
    PickerGroupItem(
        pickerState = datePickerState.yearState,
        modifier = Modifier.width(optionSpec.yearWidth).matchParentHeight(),
        selected = selected,
        onSelected = onSelected,
        contentDescription = contentDescription,
        verticalSpacing = optionSpec.verticalSpacing,
        option = { optionIndex, pickerSelected ->
            DatePickerOptionText(
                text = "%4d".format(locale, datePickerState.yearValue(optionIndex)),
                selected = pickerSelected,
                valid = datePickerState.isYearValid(datePickerState.yearValue(optionIndex)),
                colors = colors,
                optionSpec = optionSpec,
            )
        },
    )
}

/**
 * One option of a column: `pickerTextOption`'s `Box(fillMaxWidth().height(optionHeight))` with a
 * single-line `Text` inside it, laid out on the shared baseline
 * (`material3/Picker.kt:659-698`, called at :418, :452 and :482).
 *
 * The colour rule is upstream's, and note that an invalid option is *not* faded through a
 * `graphicsLayer` alpha in this version: `!isValid(value) -> invalidContentColor` (:688-694), which
 * is [DatePickerColors.invalidPickerContentColor] — the `OnSurface` token at
 * `DatePickerTokens.InvalidContentOpacity` — winning over the selected/inactive pair. That colour is
 * what carries the "invalid" alpha here.
 */
@Tunable
private fun DatePickerOptionText(
    text: String,
    selected: Boolean,
    valid: Boolean,
    colors: DatePickerColors,
    optionSpec: DatePickerOptionSpec,
) {
    val color = when {
        !valid -> colors.invalidPickerContentColor
        selected -> colors.activePickerContentColor
        else -> colors.inactivePickerContentColor
    }
    Node(
        modifier = Modifier
            .viewClass(WearTimePickerOptionView::class.java)
            .matchParentWidth()
            .height(optionSpec.optionHeight)
            .text(text)
            .datePickerText(
                TimePickerTextSpec(
                    style = optionSpec.optionTextStyle,
                    colorArgb = color.toArgb(),
                    baselineOffsetPx = optionSpec.optionBaseline,
                    maxLines = 1,
                ),
            ),
    )
}

/**
 * The sizes upstream works out inside `FontScaleIndependent` (:306-395): the measured metrics, the
 * three column widths, the coerced option height and the one baseline the columns share.
 *
 * Deviation in the measurement, and it is a real one: upstream gets the four numbers from a
 * `TextMeasurer` pass (:307-352) and this module cannot reach `compose.ui:ui-text`, so
 * [datePickerMeasureMetrics] reads them off two `StaticLayout`s the way [TimePicker] does for the
 * same reason, and inherits that file's caveat about `lineHeight` — `StaticLayout` falls back on the
 * font's own ascent-to-descent, so `optionHeightPx` comes out a little shorter than upstream's and
 * `optionBaselinePx` sits correspondingly higher in the line. The 1.dp buffers, the
 * `minimumInteractiveComponentSize` floors, the 46/36.dp minimum and the half-slack baseline
 * arithmetic are upstream's unchanged. Upstream caps nothing here either: unlike
 * `rememberPickerLayoutConfig`, DatePicker's `optionHeight` is a plain `max` of the measured height
 * and the minimum (:379-382), so no maximum is applied.
 *
 * `remember` keys: upstream's are `density.density`, `screenWidthDp` and `optionTextStyle` (:307-311).
 * [shortMonthNames] is added, because the widest month name is one of the four numbers being cached
 * and a locale whose month names differ but whose `ContentTypography` does not would otherwise keep
 * stale widths.
 */
@Tunable
private fun datePickerOptionSpec(
    isLargeScreen: Boolean,
    optionTextStyle: TextStyle,
    shortMonthNames: List<String>,
): DatePickerOptionSpec {
    val context = currentContext
    val density = context.resources.displayMetrics.density
    val screenWidthDp = context.resources.configuration.screenWidthDp
    // Inside [FontScaleIndependent] this is `density`; outside it would be the scaled factor, which
    // is exactly what upstream's wrapper exists to keep out of these numbers.
    val spToPx = currentSpToPx(1f)
    val measuredMetrics = remember(
        density,
        spToPx,
        screenWidthDp,
        optionTextStyle,
        shortMonthNames,
    ) {
        datePickerMeasureMetrics(optionTextStyle, spToPx, shortMonthNames)
    }

    // Add spaces on to allow room to grow
    val dayWidth = maxOf(
        // Add 1dp buffer to compensate for potential conversion loss
        (measuredMetrics.digitWidthPx * 2f / density).dp + 1.dp,
        MinimumInteractiveComponentSize,
    )
    val monthWidth = maxOf(
        (measuredMetrics.maxMonthWidthPx / density).dp + 1.dp,
        MinimumInteractiveComponentSize,
    )
    val yearWidth = maxOf(
        (measuredMetrics.digitWidthPx * 4f / density).dp + 1.dp,
        MinimumInteractiveComponentSize,
    )
    val measuredOptionHeight = (measuredMetrics.optionHeightPx / density).dp
    val minimumOptionHeight = if (isLargeScreen) 46.dp else 36.dp
    val optionHeight = maxOf(measuredOptionHeight, minimumOptionHeight)
    val minimumOptionHeightPx = minimumOptionHeight.value * density
    val optionBaseline = (
        measuredMetrics.optionBaselinePx +
            maxOf(0f, (minimumOptionHeightPx - measuredMetrics.optionHeightPx) / 2f)
        ).toInt()

    return DatePickerOptionSpec(
        optionTextStyle = optionTextStyle,
        shortMonthNames = shortMonthNames,
        dayWidth = dayWidth,
        monthWidth = monthWidth,
        yearWidth = yearWidth,
        optionHeight = optionHeight,
        optionBaseline = optionBaseline,
        // Upstream's `spacing` (:395) and the `verticalSpacing` every column is given.
        verticalSpacing = if (isLargeScreen) 6.dp else 4.dp,
        // The gap between two columns (:509).
        columnSpacing = if (isLargeScreen) 12.dp else 8.dp,
    )
}

/**
 * The values every column needs, gathered into one object so that the three
 * [PickerGroupItem] calls take the same shape as upstream's arms of its `when`. Not an upstream type:
 * upstream keeps these as locals of the composable body (:355-395), and no behaviour changes in
 * moving them.
 */
private class DatePickerOptionSpec(
    val optionTextStyle: TextStyle,
    /** Upstream reads `shortMonthNames` from the enclosing body at :455. */
    val shortMonthNames: List<String>,
    val dayWidth: Dp,
    val monthWidth: Dp,
    val yearWidth: Dp,
    val optionHeight: Dp,
    val optionBaseline: Int,
    val verticalSpacing: Dp,
    val columnSpacing: Dp,
)

/**
 * The four numbers of upstream's `remember(density.density, screenWidthDp, optionTextStyle)`
 * measurement block (:307-352): the two strings handed to the measurer are upstream's, built the same
 * way — one line per month name for the widths, everything on a single line for the height and the
 * baseline, so the first-line font padding upstream measures is added exactly once.
 *
 * `getLineRight(i) - getLineLeft(i)` (:344-345) is `StaticLayout.getLineWidth(i)` for a left-to-right
 * layout, and the widest of ten numerals is `TextPaint.getTextWidths` rather than ten bounding boxes;
 * both equivalences are the ones [TimePicker] documents for its own measurement.
 */
private fun datePickerMeasureMetrics(
    style: TextStyle,
    spToPx: Float,
    shortMonthNames: List<String>,
): DatePickerMeasuredMetrics {
    // `applyDatePickerTextStyle` is a Unit-returning extension, so building and configuring it in one
    // expression would type `paint` as Unit.
    val paint = TextPaint()
    paint.applyDatePickerTextStyle(style, spToPx)
    val widthMeasureResult = datePickerStaticLayout(
        paint,
        buildString {
            append(DatePickerDigits)
            for (i in shortMonthNames.indices) {
                append("\n")
                append(shortMonthNames[i])
            }
        },
    )
    val singleLineHeightMeasureResult = datePickerStaticLayout(
        paint,
        buildString {
            append(DatePickerDigits)
            for (i in shortMonthNames.indices) {
                append(shortMonthNames[i])
            }
        },
    )
    val digitWidths = FloatArray(DatePickerDigits.length)
    paint.getTextWidths(DatePickerDigits, digitWidths)

    return DatePickerMeasuredMetrics(
        digitWidthPx = digitWidths.max(),
        maxMonthWidthPx = (1..12).maxOf { widthMeasureResult.getLineWidth(it) },
        optionHeightPx = (
            singleLineHeightMeasureResult.getLineBottom(0) -
                singleLineHeightMeasureResult.getLineTop(0)
            ).toFloat(),
        optionBaselinePx = singleLineHeightMeasureResult.getLineBaseline(0).toFloat(),
    )
}

/**
 * The `textStyle` attribute's work, aimed at a [TextPaint] instead of a `TextView`, with the size
 * resolved through [spToPx] rather than `scaledDensity`.
 *
 * Duplication: this is `TimePicker.kt`'s private `applyTimePickerTextStyle` with the density argument
 * renamed, and the same is true of [datePickerStaticLayout], [datePickerText],
 * [datePickerHeadingPaddingDp] and [DatePickerIconDrawable]'s glyph geometry. Those helpers are
 * private to that file and it is not editable from here; all five should be lifted to one shared
 * home in the consolidation pass.
 */
private fun TextPaint.applyDatePickerTextStyle(style: TextStyle, spToPx: Float) {
    isAntiAlias = true
    if (style.fontSize.isSp) {
        textSize = style.fontSize.value * spToPx
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
        style.fontWeight.fontVariationSettings(style.widthAxis)?.let {
            this.fontVariationSettings = it
        }
        style.fontFeatureSettings?.let { this.fontFeatureSettings = it }
    }
}

/** `StaticLayout` with upstream's measurement constraints: unbounded width, no wrapping. */
private fun datePickerStaticLayout(paint: TextPaint, text: String): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, DatePickerMeasureWidthPx)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(true)
        .setLineSpacing(0f, 1f)
        .build()

/**
 * `PaddingDefaults.horizontalContentPadding(textPaddingPercentage)` (`material3/Padding.kt:57-60`),
 * ceil'd to whole dp — the same reduction [TimePicker] makes for its label.
 */
private fun datePickerHeadingPaddingDp(context: Context, percentage: Float): Dp =
    ceil(context.resources.configuration.screenWidthDp * percentage / 100f).dp

/**
 * The one attribute [WearTimePickerOptionView] needs. See [applyDatePickerTextStyle] for why this is
 * a copy rather than a call.
 */
private fun Modifier.datePickerText(spec: TimePickerTextSpec): Modifier =
    this.thenViewAttribute<WearTimePickerOptionView, TimePickerTextSpec>(uniqueKey, spec) {
        timePickerTextSpec = it
    }

/**
 * Upstream's `Icons.Check` (:561) and `Icons.AutoMirrored.KeyboardArrowRight` (:563) as one
 * [Drawable]: `Icon` can only tint what the framework hands back, and this module ships no
 * resources, so the two glyphs are drawn from the same path data on a 960-unit viewport
 * (`internal/Icons.kt:95-122` and `:168-194`). The colour is carried by the drawable rather than by
 * the `ImageView`'s colour filter, which a hand-built [Drawable] would ignore.
 */
private class DatePickerIconDrawable(
    private val colorArgb: Int,
    private val glyphPath: Path,
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL }

    override fun draw(canvas: Canvas) {
        val bounds: Rect = bounds
        if (bounds.isEmpty) return
        fill.color = colorArgb
        val scale = bounds.width() / DatePickerIconViewport
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(scale, bounds.height() / DatePickerIconViewport)
        canvas.drawPath(glyphPath, fill)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** `Icons.Check` (`internal/Icons.kt:100-118`). */
private val DatePickerCheckPath = Path().apply {
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

/** `Icons.AutoMirrored.KeyboardArrowRight` (`internal/Icons.kt:175-191`). */
private val DatePickerArrowRightPath = Path().apply {
    moveTo(496.35f, 480f)
    lineTo(344.17f, 327.83f)
    quadTo(331.5f, 315.15f, 331.5f, 296f)
    quadTo(331.5f, 276.85f, 344.17f, 264.17f)
    quadTo(356.85f, 251.5f, 376f, 251.5f)
    quadTo(395.15f, 251.5f, 407.83f, 264.17f)
    lineTo(591.59f, 447.93f)
    quadTo(598.3f, 454.65f, 601.4f, 462.85f)
    quadTo(604.5f, 471.04f, 604.5f, 480f)
    quadTo(604.5f, 488.96f, 601.4f, 497.15f)
    quadTo(598.3f, 505.35f, 591.59f, 512.07f)
    lineTo(407.83f, 695.83f)
    quadTo(395.15f, 708.5f, 376f, 708.5f)
    quadTo(356.85f, 708.5f, 344.17f, 695.83f)
    quadTo(331.5f, 683.15f, 331.5f, 664f)
    quadTo(331.5f, 644.85f, 344.17f, 632.17f)
    lineTo(496.35f, 480f)
    close()
}

/** Upstream's `append("0123456789")` (:316, :330). */
private const val DatePickerDigits = "0123456789"

/** `Modifier.size(24.dp)` around the button icon (:572). */
private val DatePickerIconSize = 24.dp

/** `MaterialIconViewPortDimension` (`internal/Icons.kt:223`). */
private const val DatePickerIconViewport = 960f

/** The width upstream measures at: Compose's `Constraints.MaxWidth`, wide enough never to wrap. */
private const val DatePickerMeasureWidthPx = 1 shl 30

/**
 * The order the picker's three columns are shown in: day-month-year, month-day-year or
 * year-month-day. Ported from upstream `DatePickerType` (:583-602).
 *
 * Upstream's `@Immutable` (:584) is dropped — see the file comment — and the `@JvmInline` (:585) is
 * kept: this module's [TimePickerType] and [TimePickerSelection] are inline classes over the same
 * shape, so the boxed-`value` equality a caller's `==` and this class's own `when (this)` need comes
 * from the compiler exactly as it does upstream, and no hand-written `equals` / `hashCode` is needed
 * to stand in for it.
 *
 * @param value the ordinal upstream packs into the inline class, 0 / 1 / 2
 */
@JvmInline
public value class DatePickerType internal constructor(
    internal val value: Int,
) {

    public companion object {
        /** Day, month, year — upstream's `DayMonthYear`, `DatePickerType(0)`. */
        public val DayMonthYear: DatePickerType = DatePickerType(0)

        /** Month, day, year — upstream's `MonthDayYear`, `DatePickerType(1)`. */
        public val MonthDayYear: DatePickerType = DatePickerType(1)

        /** Year, month, day — upstream's `YearMonthDay`, `DatePickerType(2)`. */
        public val YearMonthDay: DatePickerType = DatePickerType(2)
    }

    override fun toString(): String {
        return when (this) {
            DayMonthYear -> "DayMonthYear"
            MonthDayYear -> "MonthDayYear"
            YearMonthDay -> "YearMonthDay"
            else -> "Unknown"
        }
    }
}

/** Contains the default values used by [DatePicker] (:605-682). */
public object DatePickerDefaults {

    /**
     * The default [DatePickerType] for [DatePicker] aligns with the current system date format
     * (:607-617), read from `DateFormat.getDateFormatOrder`.
     *
     * Upstream's is a `@Composable` getter over `LocalContext.current`; the tune-local read is
     * [currentContext], which is what the sibling `TimePickerDefaults.timePickerType` does for the
     * same reason. The three-way branch and the `'M'` / `'y'` / else cases are upstream's, including
     * its fall-through of any other order (most notably `'D'`) to [DatePickerType.DayMonthYear].
     */
    public val datePickerType: DatePickerType
        @Tunable get() = when (DateFormat.getDateFormatOrder(currentContext)[0]) {
            'M' -> DatePickerType.MonthDayYear
            'y' -> DatePickerType.YearMonthDay
            else -> DatePickerType.DayMonthYear
        }

    /** Creates a [DatePickerColors] for a [DatePicker] (:619-622). */
    @Tunable
    public fun datePickerColors(): DatePickerColors =
        MaterialTheme.colorScheme.defaultDatePickerColors

    /**
     * Creates a [DatePickerColors] for a [DatePicker], overriding only the colours that are not
     * [Color.Unspecified] (:624-657).
     *
     * @param activePickerContentColor The content color of the currently active picker.
     * @param inactivePickerContentColor The content color of an inactive picker.
     * @param invalidPickerContentColor The content color of invalid picker options. Picker options
     *   can be invalid when minValidDate or maxValidDate are specified for the [DatePicker].
     * @param pickerLabelColor The color of the picker label.
     * @param nextButtonContentColor The content color of the next button.
     * @param nextButtonContainerColor The container color of the next button.
     * @param confirmButtonContentColor The content color of the confirm button.
     * @param confirmButtonContainerColor The container color of the confirm button.
     */
    @Tunable
    public fun datePickerColors(
        activePickerContentColor: Color = Color.Unspecified,
        inactivePickerContentColor: Color = Color.Unspecified,
        invalidPickerContentColor: Color = Color.Unspecified,
        pickerLabelColor: Color = Color.Unspecified,
        nextButtonContentColor: Color = Color.Unspecified,
        nextButtonContainerColor: Color = Color.Unspecified,
        confirmButtonContentColor: Color = Color.Unspecified,
        confirmButtonContainerColor: Color = Color.Unspecified,
    ): DatePickerColors =
        MaterialTheme.colorScheme.defaultDatePickerColors.copy(
            activePickerContentColor = activePickerContentColor,
            inactivePickerContentColor = inactivePickerContentColor,
            invalidPickerContentColor = invalidPickerContentColor,
            pickerLabelColor = pickerLabelColor,
            nextButtonContentColor = nextButtonContentColor,
            nextButtonContainerColor = nextButtonContainerColor,
            confirmButtonContentColor = confirmButtonContentColor,
            confirmButtonContainerColor = confirmButtonContainerColor,
        )

    /**
     * The tokens' colours for this scheme, upstream's `ColorScheme.defaultDatePickerColors`
     * (:659-681), including the cache upstream keeps so that a recomposition does not rebuild the
     * palette. The cache is not keyed on the scheme, exactly as upstream's is not: a scheme change
     * has to come with a new [DatePickerColors] instance from somewhere else, or the cached one is
     * returned. Hibari's retune path always re-reads the theme, so the same trap applies one layer
     * up, and it is upstream's.
     */
    private val ColorScheme.defaultDatePickerColors: DatePickerColors
        get() {
            return defaultDatePickerColorsCached
                ?: DatePickerColors(
                        activePickerContentColor = fromToken(DatePickerTokens.SelectedContentColor),
                        inactivePickerContentColor =
                            fromToken(DatePickerTokens.UnselectedContentColor),
                        invalidPickerContentColor =
                            fromToken(DatePickerTokens.InvalidContentColor)
                                .toDisabledColor(DatePickerTokens.InvalidContentOpacity),
                        pickerLabelColor = fromToken(DatePickerTokens.LabelColor),
                        nextButtonContentColor = fromToken(DatePickerTokens.NextButtonContentColor),
                        nextButtonContainerColor =
                            fromToken(DatePickerTokens.NextButtonContainerColor),
                        confirmButtonContentColor =
                            fromToken(DatePickerTokens.ConfirmButtonContentColor),
                        confirmButtonContainerColor =
                            fromToken(DatePickerTokens.ConfirmButtonContainerColor),
                    )
                    .also { defaultDatePickerColorsCached = it }
        }

    private var defaultDatePickerColorsCached: DatePickerColors? = null

    /**
     * `R.string.wear_m3c_date_picker_heading` (`res/values/wear_m3c_strings.xml:11`), shown while no
     * column is selected under TalkBack (`DatePicker.kt:168, :272`). This module ships no resources,
     * so the English value is carried as text and is not translated — the same reduction
     * [TimePickerDefaults.headingText] documents.
     */
    public val headingText: String = "Scroll to set date"

    /** `R.string.wear_m3c_date_picker_day` (`wear_m3c_strings.xml:8`), read at `DatePicker.kt:171`. */
    public val dayText: String = "Day"

    /** `R.string.wear_m3c_date_picker_month` (`wear_m3c_strings.xml:9`), read at `DatePicker.kt:170`. */
    public val monthText: String = "Month"

    /** `R.string.wear_m3c_date_picker_year` (`wear_m3c_strings.xml:10`), read at `DatePicker.kt:169`. */
    public val yearText: String = "Year"

    /**
     * `R.string.wear_m3c_date_picker_content_description` (`wear_m3c_strings.xml:34`), the template
     * [createDescriptionDatePicker] formats the day and year descriptions with (`DatePicker.kt:172`).
     */
    public val contentDescriptionTemplate: String = "%1\$s, %2\$d"

    /**
     * `R.string.wear_m3c_picker_confirm_button_content_description` (`wear_m3c_strings.xml:14`), on
     * the confirm button once the last column has been stepped through (`DatePicker.kt:567`).
     */
    public val confirmButtonContentDescription: String = "Confirm"

    /**
     * `R.string.wear_m3c_picker_next_button_content_description` (`wear_m3c_strings.xml:15`), while
     * the button is still advancing through the columns (`DatePicker.kt:570`).
     */
    public val nextButtonContentDescription: String = "Next"
}

/**
 * Colors for [DatePicker] (:698-778).
 *
 * `@Immutable` (:698) is dropped; see the file comment. [copy] keeps upstream's
 * `takeOrElse { this.x }` behaviour, so a [Color.Unspecified] override leaves the default in place.
 *
 * @param activePickerContentColor The content color of the currently active picker, that is, the
 *   picker currently being changed, such as the day, month or year.
 * @param inactivePickerContentColor The content color of an inactive picker.
 * @param invalidPickerContentColor The content color of invalid picker options. Picker options can
 *   be invalid when minValidDate or maxValidDate are specified for the [DatePicker].
 * @param pickerLabelColor The color of the picker label.
 * @param nextButtonContentColor The content color of the next button.
 * @param nextButtonContainerColor The container color of the next button.
 * @param confirmButtonContentColor The content color of the confirm button.
 * @param confirmButtonContainerColor The container color of the confirm button.
 */
public class DatePickerColors(
    public val activePickerContentColor: Color,
    public val inactivePickerContentColor: Color,
    public val invalidPickerContentColor: Color,
    public val pickerLabelColor: Color,
    public val nextButtonContentColor: Color,
    public val nextButtonContainerColor: Color,
    public val confirmButtonContentColor: Color,
    public val confirmButtonContainerColor: Color,
) {
    /**
     * Returns a copy of this DatePickerColors, optionally overriding some of the values (:709-748).
     *
     * @param activePickerContentColor The content color of the currently active picker, that is,
     *   the picker currently being changed, such as the day, month or year.
     * @param inactivePickerContentColor The content color of an inactive picker.
     * @param invalidPickerContentColor The content color of invalid picker options.
     * @param pickerLabelColor The color of the picker label.
     * @param nextButtonContentColor The content color of the next button.
     * @param nextButtonContainerColor The container color of the next button.
     * @param confirmButtonContentColor The content color of the confirm button.
     * @param confirmButtonContainerColor The container color of the confirm button.
     */
    public fun copy(
        activePickerContentColor: Color = this.activePickerContentColor,
        inactivePickerContentColor: Color = this.inactivePickerContentColor,
        invalidPickerContentColor: Color = this.invalidPickerContentColor,
        pickerLabelColor: Color = this.pickerLabelColor,
        nextButtonContentColor: Color = this.nextButtonContentColor,
        nextButtonContainerColor: Color = this.nextButtonContainerColor,
        confirmButtonContentColor: Color = this.confirmButtonContentColor,
        confirmButtonContainerColor: Color = this.confirmButtonContainerColor,
    ): DatePickerColors =
        DatePickerColors(
            activePickerContentColor =
                activePickerContentColor.takeOrElse { this.activePickerContentColor },
            inactivePickerContentColor =
                inactivePickerContentColor.takeOrElse { this.inactivePickerContentColor },
            invalidPickerContentColor =
                invalidPickerContentColor.takeOrElse { this.invalidPickerContentColor },
            pickerLabelColor = pickerLabelColor.takeOrElse { this.pickerLabelColor },
            nextButtonContentColor =
                nextButtonContentColor.takeOrElse { this.nextButtonContentColor },
            nextButtonContainerColor =
                nextButtonContainerColor.takeOrElse { this.nextButtonContainerColor },
            confirmButtonContentColor =
                confirmButtonContentColor.takeOrElse { this.confirmButtonContentColor },
            confirmButtonContainerColor =
                confirmButtonContainerColor.takeOrElse { this.confirmButtonContainerColor },
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is DatePickerColors) return false

        if (activePickerContentColor != other.activePickerContentColor) return false
        if (inactivePickerContentColor != other.inactivePickerContentColor) return false
        if (invalidPickerContentColor != other.invalidPickerContentColor) return false
        if (pickerLabelColor != other.pickerLabelColor) return false
        if (nextButtonContentColor != other.nextButtonContentColor) return false
        if (nextButtonContainerColor != other.nextButtonContainerColor) return false
        if (confirmButtonContentColor != other.confirmButtonContentColor) return false
        if (confirmButtonContainerColor != other.confirmButtonContainerColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = activePickerContentColor.hashCode()
        result = 31 * result + inactivePickerContentColor.hashCode()
        result = 31 * result + invalidPickerContentColor.hashCode()
        result = 31 * result + pickerLabelColor.hashCode()
        result = 31 * result + nextButtonContentColor.hashCode()
        result = 31 * result + nextButtonContainerColor.hashCode()
        result = 31 * result + confirmButtonContentColor.hashCode()
        result = 31 * result + confirmButtonContainerColor.hashCode()

        return result
    }
}

/**
 * Represents the possible column options for the DatePicker (:780-785).
 *
 * `private` upstream. `internal` here rather than `private` because [toDatePickerOptions] is the
 * column table [DatePicker] reads at :248 and the two travel together; nothing outside this file
 * uses either today, so the integration pass can drop both back to `private` if no second caller
 * turns up.
 */
internal enum class DatePickerOption {
    Day,
    Month,
    Year,
}

/**
 * The columns to show, in the order [type] says (:787-794). Upstream's `else` arm covers
 * [DatePickerType.DayMonthYear] and anything else the ordinal could hold. Called from the composable
 * at :248.
 */
internal fun DatePickerType.toDatePickerOptions() =
    when (value) {
        DatePickerType.YearMonthDay.value ->
            arrayOf(DatePickerOption.Year, DatePickerOption.Month, DatePickerOption.Day)
        DatePickerType.MonthDayYear.value ->
            arrayOf(DatePickerOption.Month, DatePickerOption.Day, DatePickerOption.Year)
        else -> arrayOf(DatePickerOption.Day, DatePickerOption.Month, DatePickerOption.Year)
    }

/**
 * Reject a construction that cannot show a date, upstream's `verifyDates` (:796-800), which the
 * `DatePicker` composable calls at :122 before it reads any of its arguments.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal fun verifyDates(date: LocalDate, minDate: LocalDate, maxDate: LocalDate) {
    require(maxDate >= minDate) { "maxDate should be greater than or equal to minDate" }
    require(date in minDate..maxDate) { "date should lie between minDate and maxDate" }
}

/**
 * The twelve month names under [pattern], upstream's `getMonthNames` (:802-807), which the
 * composable calls at :220 for the short names a month wheel shows and at :221 for the full names
 * its content descriptions use. 2022 is the year upstream picks because no month name depends on it.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal fun getMonthNames(pattern: String): List<String> {
    val monthFormatter = DateTimeFormatter.ofPattern(pattern)
    val months = 1..12
    return months.map { LocalDate.of(2022, it, 1).format(monthFormatter) }
}

/**
 * The state of one date picker: three [PickerState] columns and the rules that keep the date they
 * spell out valid, upstream's private `DatePickerState` (:809-952) in full — `selectedYear` /
 * `selectedMonth` / `selectedDay`, the three `*Value` mappings, `isYearValid` / `isMonthValid` /
 * `isDayValid`, the two `adjust*OptionIfInvalid` corrections and the 1900-2100 default window from
 * b/277885199.
 *
 * Hibari's [PickerState] takes the same three arguments upstream's does and reports
 * `selectedOptionIndex`, so the option-to-value maths is byte-for-byte upstream's. Two deviations:
 *  - upstream's `adjust*OptionIfInvalid` are `suspend`, because `animateScrollToOption` is; Hibari's
 *    [PickerState.animateScrollToOption] is not (a `@Tunable` body is already on the main thread), so
 *    neither is the correction here.
 *  - upstream is `private` and its only consumer, [DatePicker], is in the same file; it is `internal`
 *    here because Hibari splits what upstream keeps in one tuning body across several `@Tunable`
 *    functions — [datePickerColumns] and the three column builders take it — and its members keep
 *    upstream's visibilities.
 *
 * @param initialDate the date the picker opens on
 * @param initialDateMinYear the earliest selectable date, or null for 1 January 1900
 * @param initialDateMaxYear the latest selectable date, or null for 31 December 2100
 */
@RequiresApi(Build.VERSION_CODES.O)
internal class DatePickerState(
    initialDate: LocalDate,
    initialDateMinYear: LocalDate?,
    initialDateMaxYear: LocalDate?,
) {
    // Year range 1900 - 2100 was suggested in b/277885199
    internal val minDate = initialDateMinYear ?: LocalDate.of(1900, 1, 1)
    internal val maxDate = initialDateMaxYear ?: LocalDate.of(2100, 12, 31)

    internal val yearState =
        PickerState(
            initialNumberOfOptions = (maxDate.year - minDate.year + 1),
            initiallySelectedIndex = initialDate.year - minDate.year,
            shouldRepeatOptions = false,
        )

    internal val monthState: PickerState =
        PickerState(
            initialNumberOfOptions = 12,
            initiallySelectedIndex = initialDate.monthValue - 1,
        )

    internal val dayState =
        PickerState(
            initialNumberOfOptions = initialDate.lengthOfMonth(),
            initiallySelectedIndex = initialDate.dayOfMonth - 1,
        )

    internal val selectedYear: Int
        get() = yearValue(yearState.selectedOptionIndex)

    internal val selectedMonth: Int
        get() = monthValue(monthState.selectedOptionIndex)

    internal val selectedDay: Int
        get() = dayValue(dayState.selectedOptionIndex)

    internal fun yearValue(yearOptionIndex: Int): Int = yearOptionIndex + minDate.year

    internal fun monthValue(monthOptionIndex: Int): Int = monthOptionIndex + 1

    internal fun dayValue(dayOptionIndex: Int): Int = dayOptionIndex + 1

    internal val isMinYearSelected: Boolean
        get() = minDate.year == selectedYear

    internal val isMaxYearSelected: Boolean
        get() = maxDate.year == selectedYear

    private val isMinMonthSelected: Boolean
        get() = isMinYearSelected && selectedMonth == minDate.monthValue

    private val isMaxMonthSelected: Boolean
        get() = isMaxYearSelected && selectedMonth == maxDate.monthValue

    internal fun isYearValid(year: Int) = year >= minDate.year && year <= maxDate.year

    internal val isSelectedMonthValid
        get() = isMonthValid(selectedMonth)

    internal fun isMonthValid(month: Int): Boolean =
        when {
            !isYearValid(selectedYear) -> false
            isMinYearSelected && month < minDate.monthValue -> false
            isMaxYearSelected && month > maxDate.monthValue -> false
            else -> true
        }

    internal val isSelectedDayValid: Boolean
        get() = isDayValid(selectedDay)

    internal fun isDayValid(day: Int): Boolean =
        when {
            !isSelectedMonthValid -> false
            isMinMonthSelected && day < minDate.dayOfMonth -> false
            isMaxMonthSelected && day > maxDate.dayOfMonth -> false
            else -> true
        }

    private fun lengthOfMonth(year: Int, month: Int): Int =
        LocalDate.of(year, month, 1).lengthOfMonth()

    /**
     * Adjusts the month options and scrolls to the appropriate month when the selected year
     * changes (:892-918).
     */
    internal fun adjustMonthOptionIfInvalid() {
        if (isSelectedMonthValid) return
        when {
            isMinYearSelected -> {
                val scrollToMonth =
                    if (minDate.monthValue - selectedMonth <= selectedMonth) {
                        minDate.monthValue
                    } else {
                        12
                    }
                monthState.animateScrollToOption(scrollToMonth - 1)
            }
            isMaxYearSelected -> {
                val scrollToMonth =
                    if (selectedMonth - maxDate.monthValue <= 12 - selectedMonth) {
                        maxDate.monthValue
                    } else {
                        1
                    }
                monthState.animateScrollToOption(scrollToMonth - 1)
            }
        }
    }

    /**
     * Adjusts the day options and scrolls to the appropriate day when the selected year or month
     * changes (:920-951). The `numberOfOptions` write is what resizes the day column when February
     * comes into or goes out of view.
     */
    internal fun adjustDayOptionIfInvalid() {
        val updatedNumberOfOptions = lengthOfMonth(selectedYear, selectedMonth)
        val scrollToDay =
            when {
                !isSelectedDayValid && isMinMonthSelected -> {
                    if (minDate.dayOfMonth - selectedDay <= selectedDay) {
                        minDate.dayOfMonth
                    } else {
                        updatedNumberOfOptions
                    }
                }
                !isSelectedDayValid && isMaxMonthSelected -> {
                    if (selectedDay - maxDate.dayOfMonth <= updatedNumberOfOptions - selectedDay) {
                        maxDate.dayOfMonth
                    } else {
                        1
                    }
                }
                selectedDay > updatedNumberOfOptions -> {
                    updatedNumberOfOptions
                }
                else -> null
            }
        scrollToDay?.let { dayState.animateScrollToOption(it - 1) }
        if (updatedNumberOfOptions != dayState.numberOfOptions) {
            dayState.numberOfOptions = updatedNumberOfOptions
        }
    }
}

/**
 * The content description of a column, upstream's `createDescriptionDatePicker` (:954-961), which
 * the composable calls at :223 and :239: the bare label while no option index is selected, the
 * formatted "label, value" once one is.
 *
 * @param locale the locale `String.format` localises [template] for
 * @param template e.g. "%1$s, %2$d"
 * @param selectedIndex the column's selected option index, or null when nothing is selected
 * @param selectedValue the value at [selectedIndex]
 * @param label the column's label, e.g. "Day"
 */
internal fun createDescriptionDatePicker(
    locale: Locale,
    template: String,
    selectedIndex: Int?,
    selectedValue: Int,
    label: String,
): String =
    if (selectedIndex == null) label else String.format(locale, template, label, selectedValue)

/**
 * A data class to hold the measured raw pixel metrics for picker options (:963-969): the width of
 * one numeral, the widest month name and the option height and baseline the columns are laid out on.
 * Upstream builds one at :339 inside the composable's `remember`, from two `TextMeasurer` passes;
 * here [datePickerMeasureMetrics] builds it from two `StaticLayout`s. It is `internal` rather than
 * `private` for the same reason as [DatePickerOption]: the measurer sits beside it, outside the
 * composable's body, and the pair is meant to be lifted together in the consolidation pass.
 */
internal data class DatePickerMeasuredMetrics(
    internal val digitWidthPx: Float,
    internal val maxMonthWidthPx: Float,
    internal val optionHeightPx: Float,
    internal val optionBaselinePx: Float,
)
