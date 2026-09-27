package com.huanli233.hibari.wear

import com.huanli233.hibari.foundation.Column
import com.huanli233.hibari.foundation.ColumnScope
import com.huanli233.hibari.foundation.attributes.matchParentWidth
import com.huanli233.hibari.foundation.attributes.minHeight
import com.huanli233.hibari.foundation.attributes.padding
import com.huanli233.hibari.runtime.Tunable
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.attributes.clickable
import com.huanli233.hibari.wear.attributes.container
import com.huanli233.hibari.wear.tokens.CardTokens
import com.huanli233.hibari.wear.tokens.ColorSchemeKeyTokens
import com.huanli233.hibari.wear.tokens.OutlinedCardTokens
import com.huanli233.hibari.wear.tokens.ShapeKeyTokens

/**
 * Ported from androidx.wear.compose.material3.CardColors. The `*WithApp` variants only differ in
 * the app-name slot colour, so they collapse into [appNameColor].
 *
 * There are no disabled variants here, and that is upstream's, not this port's, choice: its KDoc
 * states "Unlike other Material 3 components, Cards do not change their color appearance when they
 * are disabled. All colors remain the same in enabled and disabled states"
 * (material3/Card.kt:1137-1139). The border is likewise not a colour — it is a card parameter
 * (material3/Card.kt:125, and :660 for `OutlinedCard`), so it is a parameter on [Card] /
 * [OutlinedCard] below rather than a field of this class.
 */
data class CardColors(
    val containerColor: Color,
    val contentColor: Color,
    val titleColor: Color,
    val appNameColor: Color,
    val timeColor: Color,
    val subtitleColor: Color,
) {

    fun containerSpec(shape: Shape, border: BorderStroke?): ContainerSpec = ContainerSpec(
        shape = shape,
        containerColor = containerColor,
        border = border,
    )
}

/**
 * Ported from androidx.wear.compose.material3.Card / NonClickableCard.
 *
 * `containerPainter` and `transformation` overloads are not ported, same reason as in Button.
 *
 * @param shape Defaults to null and resolves in the body, for the same reason as `colors`:
 *   `CardDefaults.shape` reads `MaterialTheme.shapes`.
 * @param colors Defaults to null and resolves in the body: `CardDefaults.cardColors()` reads
 *   `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable` `$default`
 *   method that cannot.
 */
@Tunable
fun Card(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: CardColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CardDefaults.ContentPadding,
    minHeight: Dp = CardDefaults.Height,
    content: @Tunable ColumnScope.() -> Unit,
) {
    val resolved = colors ?: CardDefaults.cardColors()
    val resolvedShape = shape ?: CardDefaults.shape
    val titleStyle = MaterialTheme.typography.fromToken(CardTokens.TitleTypography)
    val scope = content
    Column(
        // `matchParentWidth()` is this port's `fillMaxWidth()`, which upstream applies between
        // `cardSizeModifier(minHeight)` and `surface(...)` (material3/Card.kt:1279-1283). Without it
        // a card shrink-wraps its content and sits flush-left instead of spanning the dial.
        modifier = modifier
            .matchParentWidth()
            .container(resolved.containerSpec(resolvedShape, border))
            .padding(contentPadding)
            .minHeight(minHeight),
    ) {
        // The non-clickable wrapper provides the title pair, not `contentColor`:
        // `LocalContentColor provides colors.titleColor` plus `LocalTextStyle provides
        // CardTokens.TitleTypography` (material3/NonClickableCard.kt:90-93).
        provideContentColorAndStyle(resolved.titleColor, titleStyle) { scope() }
    }
}

@Tunable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: CardColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CardDefaults.ContentPadding,
    minHeight: Dp = CardDefaults.Height,
    content: @Tunable ColumnScope.() -> Unit,
) {
    val resolved = colors ?: CardDefaults.cardColors()
    val resolvedShape = shape ?: CardDefaults.shape
    val titleStyle = MaterialTheme.typography.fromToken(CardTokens.TitleTypography)
    val scope = content
    Column(
        modifier = modifier
            .matchParentWidth()
            .container(resolved.containerSpec(resolvedShape, border))
            .clickable(enabled, onClick)
            .padding(contentPadding)
            .minHeight(minHeight),
    ) {
        // Same title pair as the non-clickable form: material3/Card.kt:147-150 provides
        // `colors.titleColor` and `CardTokens.TitleTypography` around this overload's content.
        provideContentColorAndStyle(resolved.titleColor, titleStyle) { scope() }
    }
}

@Tunable
fun OutlinedCard(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: CardColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = CardDefaults.ContentPadding,
    minHeight: Dp = CardDefaults.Height,
    content: @Tunable ColumnScope.() -> Unit,
) {
    val resolved = colors ?: CardDefaults.outlinedCardColors()
    // Upstream's default here is the non-null `CardDefaults.outlinedCardBorder()`
    // (material3/Card.kt:660). It is `null` + resolved-in-body instead because that factory reads
    // `MaterialTheme`, and a `@Tunable` default expression is hoisted into a non-`@Tunable`
    // `$default` method that cannot.
    val resolvedBorder = border ?: CardDefaults.outlinedCardBorder()
    val resolvedShape = shape ?: CardDefaults.shape
    val contentStyle = MaterialTheme.typography.fromToken(OutlinedCardTokens.ContentTypography)
    val scope = content
    Column(
        modifier = modifier
            .matchParentWidth()
            .container(resolved.containerSpec(resolvedShape, resolvedBorder))
            .padding(contentPadding)
            .minHeight(minHeight),
    ) {
        // The one wrapper that keeps the content pair instead of the title pair:
        // material3/Card.kt:680-683 provides `colors.contentColor` and
        // `OutlinedCardTokens.ContentTypography`. It can no longer delegate to [Card] now that
        // [Card] provides the title pair.
        provideContentColorAndStyle(resolved.contentColor, contentStyle) { scope() }
    }
}

object CardDefaults {
    /**
     * `CardDefaults.shape` (material3/Card.kt:1081-1082) is `CardTokens.Shape.value`, which upstream
     * resolves through `MaterialTheme.shapes` inside a `@Composable` getter — so a theme that
     * overrides its corner radii moves the card with it. Same here, as a `@Tunable` getter; that is
     * also why `Card`'s `shape` parameter is `Shape? = null` rather than defaulting to this property,
     * since a `@Tunable` default expression is hoisted into a method that cannot read the theme.
     */
    val shape: Shape
        @Tunable get() = MaterialTheme.shapes.fromToken(CardTokens.Shape)

    /**
     * `CardDefaults.Height` (material3/Card.kt:1088), whose KDoc there says the card grows past it to
     * fit its content — so the value is a floor, which is what this module can express through
     * `Modifier.minHeight`. Upstream's own parameter for it is also named `minHeight`.
     */
    val Height: Dp = CardTokens.ContainerMinHeight

    val CardHorizontalPadding: Dp = 12.dp
    val CardVerticalPadding: Dp = 12.dp

    val ContentPadding: PaddingValues = PaddingValues(
        start = CardHorizontalPadding,
        top = CardVerticalPadding,
        end = CardHorizontalPadding,
        bottom = CardVerticalPadding,
    )

    @Tunable
    fun cardColors(): CardColors = MaterialTheme.colorScheme.filledCardColors()

    @Tunable
    fun outlinedCardColors(): CardColors = MaterialTheme.colorScheme.outlinedCardColors()

    /**
     * `CardDefaults.outlinedCardBorder()` (material3/Card.kt:1019-1024): `OutlinedCardTokens`'
     * border width and outline colour. Upstream exposes it as a two-parameter function with token
     * defaults; the parameters are dropped because the resolved values need `MaterialTheme`, which a
     * `@Tunable` default expression cannot read.
     */
    @Tunable
    fun outlinedCardBorder(): BorderStroke = BorderStroke(
        OutlinedCardTokens.BorderWidth,
        OutlinedCardTokens.ContainerBorderColor.resolve(MaterialTheme.colorScheme),
    )
}

private fun ColorScheme.filledCardColors(): CardColors = CardColors(
    containerColor = CardTokens.ContainerColor.resolve(this),
    contentColor = CardTokens.ContentColor.resolve(this),
    titleColor = CardTokens.TitleColor.resolve(this),
    appNameColor = CardTokens.AppNameColor.resolve(this),
    timeColor = CardTokens.TimeColor.resolve(this),
    subtitleColor = CardTokens.SubtitleColor.resolve(this),
)

private fun ColorScheme.outlinedCardColors(): CardColors = CardColors(
    containerColor = Color.Transparent,
    contentColor = OutlinedCardTokens.ContentColor.resolve(this),
    titleColor = OutlinedCardTokens.TitleColor.resolve(this),
    appNameColor = OutlinedCardTokens.AppNameColor.resolve(this),
    timeColor = OutlinedCardTokens.TimeColor.resolve(this),
    subtitleColor = OutlinedCardTokens.SubtitleColor.resolve(this),
)

internal fun ColorSchemeKeyTokens.resolve(scheme: ColorScheme): Color = scheme.fromToken(this)
