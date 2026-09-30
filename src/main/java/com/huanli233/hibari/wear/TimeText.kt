package com.huanli233.hibari.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.text.format.DateFormat
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.runtime.MutableState
import com.huanli233.hibari.runtime.RememberObserver
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.runtime.currentContext
import com.huanli233.hibari.runtime.mutableStateOf
import com.huanli233.hibari.runtime.remember
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.TextUnit
import com.huanli233.hibari.ui.unit.dp
import java.util.Calendar
import java.util.Locale

/*
 * The helpers behind `TimeText`'s clock — the time-source plumbing of
 * androidx.wear.compose.material3.TimeText (`material3/TimeText.kt:131-204`, `:237-330`) plus the
 * linear separator of androidx.wear.compose.material.TimeText
 * (`material/TimeText.kt:207-213`).
 *
 * The `TimeText` composable itself already lives in `CurvedText.kt`, and so does
 * [TimeTextDefaults]; this file adds only what was missing, which is why the four helpers upstream
 * holds as members of `TimeTextDefaults` are top-level here — see the note on each.
 *
 * Upstream's shape and how it maps:
 *  - `TimeTextDefaults.timeFormat()` / `.timeTextStyle()` / `.rememberTimeSource()`
 *    (`material3/TimeText.kt:157-197`) are object members upstream; this module's `TimeTextDefaults`
 *    is in another file, which this port may not edit, so they are top-level functions of the same
 *    names. `TimeText` does not consume them: its landed form drives `WearTimeTextView` from a
 *    `BroadcastReceiver` inside the view (`view/WearTimeTextView.kt:68-95`) and takes no `timeSource`
 *    parameter at all (upstream's is `material3/TimeText.kt:111`). They are for a caller composing
 *    their own time text, which is what upstream's `timeSource` parameter enables.
 *  - `TimeTextDefaults.TimeFormat24Hours` / `TimeFormat12Hours` (`:137`, `:140`) likewise become
 *    top-level [TimeFormat24Hours] / [TimeFormat12Hours] rather than new members of the existing
 *    object. Its `ContentPadding` (`:151`) and `MaxSweepAngle` (`:148`, already ported) are not
 *    repeated here.
 *  - Upstream's `currentTimeMillis()` (`materialcore/Resources.kt:62`) has no Hibari counterpart, so
 *    the tick callback reads `System.currentTimeMillis()` directly, which is what that one-line
 *    helper does.
 *  - `Locale.current.platformLocale` (`:160`) becomes [Locale.getDefault], the same source
 *    `WearTimeTextView` formats its own pattern from.
 */
/**
 * `TimeTextDefaults.TimeFormat24Hours` (`material3/TimeText.kt:137`): the skeleton handed to
 * `DateFormat.getBestDateTimePattern` on a 24-hour device.
 */
val TimeFormat24Hours: String = "HH:mm"

/** `TimeTextDefaults.TimeFormat12Hours` (`material3/TimeText.kt:140`). */
val TimeFormat12Hours: String = "h:mm"

/**
 * `TimeTextDefaults.timeFormat()` (`material3/TimeText.kt:157-163`): the locale's own pattern for the
 * device's 12- or 24-hour setting, with the am/pm marker stripped — Wear shows no marker in
 * [TimeText], and the `h`/`H` skeleton choice is upstream's `is24HourFormat()`, i.e.
 * `DateFormat.is24HourFormat` (`materialcore/Resources.kt:60`).
 */
@Tunable
fun timeFormat(): String {
    val context = currentContext
    val format =
        if (DateFormat.is24HourFormat(context)) TimeFormat24Hours else TimeFormat12Hours
    return DateFormat.getBestDateTimePattern(Locale.getDefault(), format)
        .replace("a", "")
        .trim()
}

/**
 * `TimeTextDefaults.timeTextStyle()` (`material3/TimeText.kt:173-181`): upstream's
 * [CurvedTextStyle] for the clock, built the same way upstream builds it — `arcMedium` folded with a
 * fresh style through [CurvedTextStyle.plus], so the values a caller passes win and `arcMedium` fills
 * what is missing (`:179-180`).
 *
 * Two adjustments, both forced by this module rather than chosen:
 *  - Upstream's `arcMedium` is already a `CurvedTextStyle` (`material3/Typography.kt:118`); this
 *    module's is a plain [TextStyle] (`Typography.kt:11-15`), so it enters the fold through
 *    [CurvedTextStyle]'s `constructor(style: TextStyle)` (`foundation/CurvedTextStyle.kt:235-256`),
 *    which is upstream's own bridge for exactly that.
 *  - [color] is `Color?` resolved in the body, not `Color = MaterialTheme...`: a `@Tunable` default
 *    expression is hoisted into the non-`@Tunable` `$default` method, which cannot read the theme.
 *
 * The other two parameters keep upstream's defaults and types, so nothing that looks like
 * `timeTextStyle(color = red)` fails to compile.
 *
 * @param background Upstream's `Color.Unspecified` (`:175`). This module's [Text] has no background
 *   slot (a `TextView`'s background is a drawable), so a caller who wants one puts
 *   `Modifier.background(...)` on the modifier instead; [CurvedText] reads the field directly.
 * @param color Upstream reads `MaterialTheme.colorScheme.onBackground.setLuminance(80f)` (`:176`),
 *   which is the 0..100 Oklab-tone `setLuminance` of `material3/DynamicColorScheme.kt:116-125`; this
 *   package's port of that one is [setLuminanceTone] (`ColorAppearanceModel.kt:1622-1631`), and it is
 *   what the body uses. The publicly resolving `com.huanli233.hibari.ui.graphics.setLuminance` speaks
 *   0..1 relative luminance (`hibari-ui/src/main/java/com/huanli233/hibari/ui/graphics/Color.kt:162-173`),
 *   so feeding it `80f` coerces to `1f` and returns white — which is why this call does not use it.
 *   `null` means "upstream's default", as it does for every other theme-read parameter here.
 * @param fontSize Defaults to [TextUnit.Unspecified] — i.e. keep `arcMedium`'s — as upstream's does.
 */
@Tunable
fun timeTextStyle(
    background: Color = Color.Unspecified,
    color: Color? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
): CurvedTextStyle =
    CurvedTextStyle(MaterialTheme.typography.arcMedium) +
        CurvedTextStyle(
            color = color ?: MaterialTheme.colorScheme.onBackground.setLuminanceTone(80f),
            background = background,
            fontSize = fontSize,
        )

/**
 * A source of the time string [TimeText] shows (`material3/TimeText.kt:237-245`).
 *
 * Upstream's member is `@Composable public fun currentTime(): String`; the Hibari equivalent of that
 * context is [Tunable], which is applied to the declaration and to the override in
 * [DefaultTimeSource] both — this plugin decides tunability from the annotation on the symbol it is
 * looking at (`k2/FirUtils.kt:66` reads only that symbol's own annotations), so an unannotated
 * override would be a plain function whose body may not call [currentTime], and whose JVM signature
 * would be missing the `$tuner` parameter the transformed interface method declares
 * (`transformer/TunerParamTransformer.kt:569-620`).
 */
interface TimeSource {

    /**
     * A method responsible for returning updated time string.
     *
     * @return Formatted time string.
     */
    @Tunable
    fun currentTime(): String
}

/**
 * `DefaultTimeSource` (`material3/TimeText.kt:247-250`), internal there and here. Build one through
 * [rememberTimeSource] rather than by hand: the ticker only lives as long as something remembers it.
 */
internal class DefaultTimeSource(val timeFormat: String) : TimeSource {

    @Tunable
    override fun currentTime(): String =
        currentTime(
            time = { System.currentTimeMillis() },
            timeFormat = timeFormat,
        ).value
}

/**
 * `TimeTextDefaults.rememberTimeSource(timeFormat)` (`material3/TimeText.kt:183-197`): a
 * [DefaultTimeSource] kept across retunes for as long as the pattern holds.
 */
@Tunable
fun rememberTimeSource(timeFormat: String): TimeSource =
    remember(timeFormat) { DefaultTimeSource(timeFormat) }

/**
 * `currentTime(time, timeFormat)` (`material3/TimeText.kt:252-278`): the state the clock reads, and
 * the thing that keeps it current. Upstream marks it `@VisibleForTesting internal`, so it is internal
 * here too — the gap list calls it public, which it is not upstream.
 *
 * Upstream's body is `remember(context) { callbackFlow { … receiver.register(context) … }
 * .flowOn(Dispatchers.Main) }.collectAsState(Unit)`; there is no `callbackFlow`/`StateFlow` plumbing
 * in Hibari's runtime for a tune to collect, so the receiver lives in a [TimeTicker] held by
 * [remember]. That is the same lifecycle upstream's flow has — registered while the slot is
 * remembered, unregistered when the key changes or the node leaves — and it is the mechanism
 * `LaunchedEffect` itself uses (`runtime/effects/Effects.kt:15-33`).
 *
 * `DisposableEffect` would have been the closer spelling, but it is `remember` plus a
 * `DisposableEffectView` *node injected into the layout*
 * (`runtime/effects/DisposableEffect.kt:32-44`) — a phantom child in whatever container the clock is
 * read from, which in a curved row is a layout participant. `KeepScreenOn.kt:41-44` rejects it for
 * the same reason.
 *
 * @return The state, not its value: [DefaultTimeSource.currentTime] reads `.value`, which is what
 *   makes the write from a tick re-tune the caller. Upstream returns a `State<String>`;
 *   `MutableState` is what `mutableStateOf` hands back here, and the write happens inside the ticker
 *   rather than through a flow.
 */
@Tunable
internal fun currentTime(time: () -> Long, timeFormat: String): MutableState<String> {
    val context = currentContext
    val timeText = remember {
        mutableStateOf(formatTime(Calendar.getInstance(), time(), timeFormat))
    }
    // Upstream keys the receiver on the context and re-registers when it changes; `time` is read
    // through the callback rather than being a key of its own, because upstream's M3 flow does the
    // same (its `remember(context)` ignores the lambda's identity, unlike material v1's
    // `rememberUpdatedState(time)`, `material/TimeText.kt:299`).
    //
    // The value is deliberately not bound: being a `RememberObserver`, the ticker is started and
    // stopped by the slot `remember` puts it in (`runtime/Tuner.kt:214-217`), which is also how
    // `KeepScreenOn.kt:55` holds its flag object.
    remember(context) {
        TimeTicker(context) {
            timeText.value = formatTime(Calendar.getInstance(), time(), timeFormat)
        }
    }
    return timeText
}

/**
 * `TimeTextDefaults.TextSeparator` from androidx.wear.compose.material (`material/TimeText.kt:206-213`):
 * the interpunct shown between the status text and the time on a square screen, `·` at
 * `timeTextStyle()` with 4.dp either side.
 *
 * Upstream v1 defaults its style to v1's `timeTextStyle()`, which is `caption1`-based
 * (`material/TimeText.kt:189-196`). This module has one typography, and the role [TimeText] renders
 * its own time with is `arcMedium` (`CurvedText.kt:144`), so the separator matches the text it
 * separates via [timeTextStyle] instead.
 *
 * Upstream's one `TextStyle` carries colour and typography together, so the default flows to `Text`
 * as a single argument (`material/TimeText.kt:212`); a Hibari [TextStyle] has no colour slot at all
 * (`Text.kt:24-37`), so the two are routed separately here: the colour off the [CurvedTextStyle]
 * [timeTextStyle] builds, the rest off [textStyle] or off `arcMedium`. The consequence is stated
 * rather than papered over — a caller who passes [textStyle] cannot set its colour through that
 * object, because the object has no such field.
 *
 * Hidden from TalkBack in one upstream and not in the other, and not here at all: m3 wraps its
 * separator in `clearAndSetSemantics {}` (`material3/TimeText.kt:225-234`, the call at `:233`) and the
 * time itself the same way (`:212-217`, at `:214`), because `TimeText` is decorative, while v1's
 * `TextSeparator` asks for no such clearing (`material/TimeText.kt:212`). Neither is possible in this
 * module — there is no semantics layer to clear, the omission every other ported component documents
 * (`TimePicker.kt:115-116`, `AlertDialog.kt:74`).
 *
 * @param textStyle Upstream's default is `timeTextStyle()` itself (`material/TimeText.kt:209`). Here
 *   it defaults to null and resolves in the body to `MaterialTheme.typography.arcMedium` — [timeTextStyle]
 *   is a `@Tunable` call that reads the theme, and a default expression is hoisted into the
 *   non-`@Tunable` `$default` method, which cannot.
 */
@Tunable
fun TextSeparator(
    modifier: Modifier = Modifier,
    textStyle: TextStyle? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 4.dp),
) {
    Text(
        text = TimeTextSeparatorGlyph,
        modifier = modifier.padding(contentPadding),
        color = timeTextStyle().color,
        style = textStyle ?: MaterialTheme.typography.arcMedium,
    )
}

/** `TimeTextDefaults.TextSeparator`'s glyph (`material/TimeText.kt:212`). */
private const val TimeTextSeparatorGlyph = "·"

/**
 * Upstream's `TimeBroadcastReceiver` (`material3/TimeText.kt:300-325`) wearing the [RememberObserver]
 * hat that `callbackFlow`/`awaitClose` plays there: registered from [onRemembered], unregistered from
 * [onForgotten], so it follows the slot that [remember] holds it in.
 */
private class TimeTicker(
    private val context: Context,
    private val onChange: () -> Unit,
) : BroadcastReceiver(), RememberObserver {

    private var registered = false

    override fun onReceive(context: Context?, intent: Intent?) {
        // Either time or timezone changed, or we got the tick sent every minute. Upstream's M3
        // receiver does not branch between them; v1's does (`material/TimeText.kt:314-323`), to keep
        // a Calendar per event kind, and the M3 shape is the one ported.
        onChange()
    }

    override fun onRemembered() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // minSdk 25, and on 33+ a receiver without an explicit export flag throws
        // SecurityException. `ACTION_TIME_*` are protected system broadcasts, so not exported is both
        // what upstream asks for and what the platform accepts; same guard as `WearTimeTextView`.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(this, filter)
        }
        registered = true
    }

    override fun onForgotten() {
        if (!registered) return
        runCatching { context.unregisterReceiver(this) }
        registered = false
    }
}

/** `formatTime` (`material3/TimeText.kt:327-330`), verbatim. */
private fun formatTime(calendar: Calendar, currentTime: Long, timeFormat: String): String {
    calendar.timeInMillis = currentTime
    return DateFormat.format(timeFormat, calendar).toString()
}
