package com.huanli233.hibari.wear

import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.takeOrElse
import com.huanli233.hibari.ui.text.FontFamily
import com.huanli233.hibari.ui.text.FontWeight
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.unit.TextUnit
import com.huanli233.hibari.ui.unit.isSpecified
import com.huanli233.hibari.ui.unit.takeOrElse
import com.huanli233.hibari.wear.view.WarpOffset

/**
 * Styling configuration for a curved text, ported from
 * androidx.wear.compose.foundation.CurvedTextStyle (`foundation/CurvedTextStyle.kt:85-571`).
 *
 * This is the carrier that [com.huanli233.hibari.wear.Text] and [CurvedText] cannot use implicitly:
 * a Hibari [TextStyle] holds no colour at all (a `TextView`'s colour is a view property, not a span —
 * `Text.kt:24-37`), and the arc roles of this module's [Typography] were degraded to plain
 * [TextStyle]s (`Typography.kt:11-15`), so upstream's `CurvedTextStyle` had no counterpart. Its
 * landed caller is [timeTextStyle] (`material3/TimeText.kt:173-181`), which builds upstream's
 * `arcMedium + CurvedTextStyle(...)` fold with [plus] and is read in turn by [TextSeparator]; the
 * fields are what a curved renderer consumes when it eventually lands, the way upstream's
 * `BasicCurvedText.kt:136` consumes them.
 *
 * What is in upstream's class and not here, with the reason:
 *  - `fontStyle` and `fontSynthesis` (`:91-92`, and the `merge`/`copy`/`equals`/`hashCode`/`toString`
 *    lines that mention them). `com.huanli233.hibari.ui.text` declares no `FontStyle` and no
 *    `FontSynthesis`, and nothing in this module can apply either: the only text renderers are
 *    `Modifier.textStyle` (`attributes/ContainerAttributes.kt:49-76`) and
 *    `WearCurvedTextView`'s own paint path, and both reach a typeface through
 *    `FontFamily.typeface(weight.toAndroidStyle())` plus a `wght`/`wdth` variation string, never
 *    through an italic or synthesis flag. Carrying the two parameters would be a surface that
 *    silently throws away what it is given, so they are absent rather than present-and-ignored.
 *  - The four `@Deprecated(level = HIDDEN)` secondary constructors (`:107-233`) and the four
 *    `@Deprecated(level = HIDDEN)` `copy` overloads (`:293-410`). They exist to keep the JVM
 *    signatures of Wear OS 1.0 / 1.4 / 1.5 callers linkable — upstream even had to make
 *    `WarpOffset.option` a `Byte` to dodge a signature clash it names in a comment (`:496-499`) —
 *    and this module has no such published lineage to stay binary-compatible with. Declaring them
 *    here would only reintroduce the same clash pressure for nobody.
 *  - `DefaultCurvedTextStyles` (`:33-45`), which upstream's `BasicCurvedText.kt:136` seeds a merge
 *    with (`DefaultCurvedTextStyles + style()`). It is `internal`, its only reader is not ported, and
 *    a style value nothing merges into is dead code, so it is left out; the moment a curved renderer
 *    of this module's own seeds a merge, it belongs here verbatim — `color = Color.Black`,
 *    `fontSize = 14.sp`, `background = Color.Transparent`, `letterSpacing = 0f.em`,
 *    `letterSpacingCounterClockwise = 0f.em`, `lineHeight = TextUnit.Unspecified` (`:36-44`).
 *  - `@Stable` on the two inline properties (`:513`, `:518`) — a Compose compiler-stability marker
 *    with no meaning for a retune.
 *  - Upstream's nested `WarpOffset` value class (`:498-570`) is **not** re-declared:
 *    `com.huanli233.hibari.wear.view.WarpOffset` already owns those offsets — five names, because it
 *    folds upstream's `None` and `Baseline` together as the view does (both offset by 0,
 *    `view/WearCurvedTextView.kt:43-49`) — and is what
 *    [CurvedTextSpec.warpOffset] and `WearCurvedTextView.warpOffset` are typed against, and a second
 *    one would mean two warp enums in one module. See the note on [warpOffset] for the one
 *    consequence, and on [letterSpacingCounterClockwise] for the tracking that still does not travel.
 *
 * @param background The background color for the text.
 * @param color The text color.
 * @param fontSize The size of glyphs (in logical pixels) to use when painting the text. This may be
 *   [TextUnit.Unspecified] for inheriting from another [CurvedTextStyle].
 * @param fontFamily The font family to be used when rendering the text.
 * @param fontWeight The thickness of the glyphs, in a range of [1, 1000]. Null means "inherit", as
 *   upstream's does — which a Hibari [TextStyle] cannot express, since its
 *   [TextStyle.fontWeight][com.huanli233.hibari.ui.text.TextStyle] defaults to `FontWeight(400)`
 *   rather than to null, so folding a style in through [CurvedTextStyle] always brings a weight.
 * @param letterSpacing The amount of space (in em or sp) to add between each letter, when text is
 *   going clockwise.
 * @param letterSpacingCounterClockwise The amount of space (in em or sp) to add between each letter,
 *   when text is going counterClockwise. Note that this usually needs to be bigger than
 *   [letterSpacing] to account for the fact that going clockwise, text fans out from the baseline
 *   while going counter clockwise text fans in. If not specified, the value for [letterSpacing] will
 *   be used.
 * @param lineHeight Line height for the text in [TextUnit] unit, e.g. SP or EM. Note that since
 *   curved text only has one line, this used the equivalent of a lineHeightStyle: alignment =
 *   Center, trim = None, mode = Fixed.
 * @param warpOffset Specifies if the text is warped at all and, if so, which horizontal line of the
 *   text keeps its width — upstream's `warpOffset` (`:96`, `:488-553`), whose `Unspecified` sentinel
 *   (`:531`, with `isSpecified`/`isUnspecified`/`takeOrElse` over `:512-527`) is what makes this
 *   property nullable: `null` here is upstream's `WarpOffset.Unspecified`, the "fill this in from the
 *   style I am merged onto" state that [merge] reads at `:144`. The module's own `view.WarpOffset`
 *   enum carries no such member — a second exhaustive `when` over it lives in
 *   `CurvedContainer.kt:798-804`, outside this file — so the sentinel is spelled as absence rather
 *   than as a seventh name. Nothing here computes a warp offset from these values: the arithmetic is
 *   upstream's `determineWarpRadiusOffset` (`:555-569`), and this module currently keeps **two** copies
 *   of it — `WarpOffset.determineRadiusOffset` (`view/WearCurvedTextView.kt:93-99`), which
 *   `WearCurvedTextView.warpRadiusOffset()` (`:394-396`) delegates to behind its API-29 gate, and the
 *   inline `when` at `CurvedContainer.kt:795-804`. Collapsing the second onto the first is open work,
 *   not something this file can claim.
 */
class CurvedTextStyle(
    val background: Color = Color.Unspecified,
    val color: Color = Color.Unspecified,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val fontFamily: FontFamily? = null,
    val fontWeight: FontWeight? = null,
    val letterSpacing: TextUnit = TextUnit.Unspecified,
    val letterSpacingCounterClockwise: TextUnit = TextUnit.Unspecified,
    val lineHeight: TextUnit = TextUnit.Unspecified,
    val warpOffset: WarpOffset? = null,
) {

    /**
     * Create a curved text style from the given text style
     * (`:235-256`). Upstream's fold passes **ten arguments covering nine roles** — its own note at
     * `:238-241` lists `color`, `fontSize`, `background`, `fontFamily`, `fontWeight`, `fontStyle`,
     * `fontSynthesis`, `letterSpacing` and `lineHeight`, and `letterSpacing` is passed twice, filling
     * both directions (a plain text style has no direction-sensitive tracking, so a style made from
     * one asks for the same tracking either way). This fold carries the five of those a Hibari
     * [TextStyle] actually has: `fontSize`, `fontFamily`, `fontWeight`, `letterSpacing` (into both
     * spacings, as upstream) and `lineHeight`. Upstream's `color`, `background`, `fontStyle` and
     * `fontSynthesis` are not dropped silently — a Hibari [TextStyle] carries none of the four.
     *
     * Two of the Hibari style's own roles are dropped by this fold because upstream's class has no
     * slot for them: [TextStyle.widthAxis] rides on the weight's variation string rather than on the
     * style, and [TextStyle.fontFeatureSettings] has no curved counterpart upstream.
     */
    constructor(style: TextStyle) : this(
        fontSize = style.fontSize,
        fontFamily = style.fontFamily,
        fontWeight = style.fontWeight,
        letterSpacing = style.letterSpacing,
        letterSpacingCounterClockwise = style.letterSpacing,
        lineHeight = style.lineHeight,
    )

    /**
     * Returns a new curved text style that is a combination of this style and the given [other]
     * style (`:258-288`).
     *
     * [other] curved text style's null or inherit properties are replaced with the non-null
     * properties of this curved text style. Another way to think of it is that the "missing"
     * properties of the [other] style are _filled_ by the properties of this style.
     *
     * If the given curved text style is null, returns this curved text style.
     */
    fun merge(other: CurvedTextStyle? = null): CurvedTextStyle {
        if (other == null) return this

        return CurvedTextStyle(
            color = other.color.takeOrElse { this.color },
            fontSize = if (other.fontSize.isSpecified) other.fontSize else this.fontSize,
            background = other.background.takeOrElse { this.background },
            fontFamily = other.fontFamily ?: this.fontFamily,
            fontWeight = other.fontWeight ?: this.fontWeight,
            letterSpacing =
                if (other.letterSpacing.isSpecified) other.letterSpacing else this.letterSpacing,
            letterSpacingCounterClockwise =
                if (other.letterSpacingCounterClockwise.isSpecified)
                    other.letterSpacingCounterClockwise
                else this.letterSpacingCounterClockwise,
            lineHeight = other.lineHeight.takeOrElse { this.lineHeight },
            warpOffset = other.warpOffset ?: this.warpOffset,
        )
    }

    /** Plus operator overload that applies a [merge] (`:290-291`). */
    operator fun plus(other: CurvedTextStyle): CurvedTextStyle = merge(other)

    /**
     * The current `copy` (`:412-438`), every parameter kept at its existing value by default.
     *
     * Upstream's `fontStyle`/`fontSynthesis` slots are absent for the reason given at the top of this
     * file; nothing else about the method's behaviour differs.
     */
    fun copy(
        background: Color = this.background,
        color: Color = this.color,
        fontSize: TextUnit = this.fontSize,
        fontFamily: FontFamily? = this.fontFamily,
        fontWeight: FontWeight? = this.fontWeight,
        letterSpacing: TextUnit = this.letterSpacing,
        letterSpacingCounterClockwise: TextUnit = this.letterSpacingCounterClockwise,
        lineHeight: TextUnit = this.lineHeight,
        warpOffset: WarpOffset? = this.warpOffset,
    ): CurvedTextStyle = CurvedTextStyle(
        background = background,
        color = color,
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        letterSpacingCounterClockwise = letterSpacingCounterClockwise,
        lineHeight = lineHeight,
        warpOffset = warpOffset,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true

        return other is CurvedTextStyle &&
            color == other.color &&
            fontSize == other.fontSize &&
            background == other.background &&
            fontFamily == other.fontFamily &&
            fontWeight == other.fontWeight &&
            letterSpacing == other.letterSpacing &&
            letterSpacingCounterClockwise == other.letterSpacingCounterClockwise &&
            lineHeight == other.lineHeight &&
            warpOffset == other.warpOffset
    }

    override fun hashCode(): Int {
        var result = color.hashCode()
        result = 31 * result + fontSize.hashCode()
        result = 31 * result + background.hashCode()
        result = 31 * result + fontFamily.hashCode()
        result = 31 * result + fontWeight.hashCode()
        result = 31 * result + letterSpacing.hashCode()
        result = 31 * result + letterSpacingCounterClockwise.hashCode()
        result = 31 * result + lineHeight.hashCode()
        result = 31 * result + warpOffset.hashCode()
        return result
    }

    // Upstream's own string (`:472-486`), missing comma after `background` included, so a log
    // comparison against an AndroidX build reads the same on both sides.
    override fun toString(): String =
        "CurvedTextStyle(" +
            "background=$background" +
            "color=$color, " +
            "fontSize=$fontSize, " +
            "fontFamily=$fontFamily, " +
            "fontWeight=$fontWeight, " +
            "letterSpacing=$letterSpacing, " +
            "letterSpacingCounterClockwise=$letterSpacingCounterClockwise, " +
            "lineHeight=$lineHeight, " +
            "warpOffset=$warpOffset, " +
            ")"
}
