package com.huanli233.hibari.wear

import android.view.Gravity
import android.widget.LinearLayout
import com.huanli233.hibari.foundation.Row
import com.huanli233.hibari.foundation.RowScope
import com.huanli233.hibari.foundation.Spacer
import com.huanli233.hibari.foundation.attributes.matchParentWidth
import com.huanli233.hibari.foundation.attributes.minHeight
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.foundation.attributes.width
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.RectangleShape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.text.TextAlign
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.tokens.ListHeaderTokens
import com.huanli233.hibari.wear.tokens.ListSubHeaderTokens
import com.huanli233.hibari.wear.tokens.TypographyKeyTokens

/**
 * Ported from androidx.wear.compose.material3.ListHeader / ListSubHeader.
 *
 * Upstream animates the header's padding away while the list scrolls beneath it (`ScrollAway`);
 * this port keeps a fixed padding because nothing here re-tunes the header per scroll frame, not
 * because the scroll facts are unavailable — the module has [ScrollInfoProvider]
 * (`ScrollAway.kt:76`) and [ScreenStage] (`ScrollAway.kt:35`), and upstream's `scrollAway` is ported.
 *
 * Both headers hand their children three things through locals (material3/ListHeader.kt:84-92 and
 * :141-149): `LocalContentColor`, `LocalTextStyle` from the role's own token, and
 * `LocalTextConfiguration(textAlign, Ellipsis, maxLines = 3)` — `Center` for the header, `Start` for
 * the sub-header. All three ride down through [provideContentColorAndStyle], so a child [Text] that
 * leaves `textAlign` / `overflow` / `maxLines` unset inherits them.
 *
 * The two roles also differ in ways the delegation this file used to have between them hid: the
 * header is `Arrangement.Center` over a `wrapContentSize()` box (:76-80), the sub-header is
 * `Arrangement.Start` over a `fillMaxWidth()` one (:129-135). The sub-header also puts a 6.dp gap
 * between its icon and its label (`material3/ListHeader.kt:155-158`), which a caller cannot add from
 * outside the slot pair, so it is applied here.
 *
 * @param contentColor Defaults to null and resolves in the body: the real default reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot reach it.
 */
@Tunable
fun ListHeader(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    contentColor: Color? = null,
    contentPadding: PaddingValues = ListHeaderDefaults.ContentPadding,
    content: @Tunable RowScope.() -> Unit,
) {
    val resolvedColor = contentColor ?: ListHeaderDefaults.contentColor
    val scope = content
    Row(
        modifier = modifier
            .rowGravity(Gravity.CENTER_HORIZONTAL)
            .container(ContainerSpec(shape = RectangleShape, containerColor = backgroundColor))
            .padding(contentPadding)
            .minHeight(ListHeaderTokens.Height),
    ) {
        // Upstream hands the token straight to `LocalTextStyle` (:86); the read is inline here for
        // the same reason, so `ListHeaderDefaults` carries only the members upstream gives it.
        provideContentColorAndStyle(
            resolvedColor,
            MaterialTheme.typography.fromToken(ListHeaderTokens.ContentTypography),
            TextConfiguration(TextAlign.Center, TextOverflow.Ellipsis, maxLines = 3),
        ) { scope() }
    }
}

/**
 * Upstream's `icon` slot is followed by a `Spacer(Modifier.width(6.dp))`
 * (`material3/ListHeader.kt:151-157`), applied here between the two slots: a caller cannot add it,
 * because it sits between two slots the caller does not own.
 *
 * @param contentColor Defaults to null and resolves in the body, for the same reason as in [ListHeader].
 */
@Tunable
fun ListSubHeader(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    contentColor: Color? = null,
    contentPadding: PaddingValues = ListHeaderDefaults.SubHeaderContentPadding,
    icon: (@Tunable RowScope.() -> Unit)? = null,
    label: @Tunable RowScope.() -> Unit,
) {
    val resolvedColor = contentColor ?: ListHeaderDefaults.subHeaderContentColor
    val labelSlot = label
    val iconSlot = icon
    Row(
        modifier = modifier
            .matchParentWidth()
            .container(ContainerSpec(shape = RectangleShape, containerColor = backgroundColor))
            .padding(contentPadding)
            .minHeight(ListSubHeaderTokens.Height),
    ) {
        provideContentColorAndStyle(
            resolvedColor,
            MaterialTheme.typography.fromToken(ListSubHeaderTokens.ContentTypography),
            TextConfiguration(TextAlign.Start, TextOverflow.Ellipsis, maxLines = 3),
        ) {
            iconSlot?.invoke(this)
            if (iconSlot != null) Spacer(Modifier.width(IconLabelGap))
            labelSlot()
        }
    }
}

/**
 * The Views stand-in for `Row(horizontalArrangement = ...)`: Hibari's `RowScope` only carries
 * per-child gravity and weight, so the container's own alignment has to be written on the
 * `LinearLayout` this `Row` renders to.
 */
private fun Modifier.rowGravity(gravity: Int): Modifier =
    this.thenViewAttribute<LinearLayout, Int>(uniqueKey, gravity) { this.gravity = gravity }

/** `material3/ListHeader.kt:157`'s `Spacer(Modifier.width(6.dp))`, the icon-to-label gap. */
private val IconLabelGap = 6.dp

/**
 * The defaults of both header roles live in this one object, as upstream keeps them
 * (`material3/ListHeader.kt:163-210`): `SubHeaderContentPadding` and `subHeaderContentColor` are
 * members of `ListHeaderDefaults`, not of a second `ListSubHeaderDefaults`.
 */
object ListHeaderDefaults {
    private val TopPadding: Dp = 16.dp
    private val SubHeaderBottomPadding: Dp = 8.dp
    private val HeaderBottomPadding: Dp = 12.dp
    private val HorizontalPadding: Dp = 14.dp

    /** The default content padding for ListHeader — `material3/ListHeader.kt:170-171`. */
    val ContentPadding: PaddingValues = PaddingValues(
        start = HorizontalPadding,
        top = TopPadding,
        end = HorizontalPadding,
        bottom = HeaderBottomPadding,
    )

    /** The default content padding for ListSubHeader — `material3/ListHeader.kt:174-175`. */
    val SubHeaderContentPadding: PaddingValues = PaddingValues(
        start = HorizontalPadding,
        top = TopPadding,
        end = HorizontalPadding,
        bottom = SubHeaderBottomPadding,
    )

    /**
     * The minimum top content padding for the list when a [ListHeader] sits at the top
     * (`material3/ListHeader.kt:188-189`). Upstream's consumer is
     * `TransformingLazyColumnItemScope.minimumVerticalContentPadding`, which the `wear.lazy` port has
     * no counterpart for (see `ButtonGroup.kt:48-50`), so this is the number without the hook.
     */
    val minimumTopListContentPadding: Dp
        @Tunable get() = screenHeightFraction(SMALL_VERTICAL_CONTENT_PADDING_FRACTION)

    /**
     * The minimum bottom content padding for the list when a [ListHeader] sits at the bottom
     * (`material3/ListHeader.kt:200-201`), with [minimumTopListContentPadding]'s same missing
     * consumer.
     */
    val minimumBottomListContentPadding: Dp
        @Tunable get() = screenHeightFraction(LARGE_VERTICAL_CONTENT_PADDING_FRACTION)

    /** The default color for ListHeader — `material3/ListHeader.kt:204-205`. */
    val contentColor: Color
        @Tunable get() = ListHeaderTokens.ContentColor.resolve(MaterialTheme.colorScheme)

    /** The default color for ListSubHeader — `material3/ListHeader.kt:208-209`. */
    val subHeaderContentColor: Color
        @Tunable get() = ListSubHeaderTokens.ContentColor.resolve(MaterialTheme.colorScheme)
}
