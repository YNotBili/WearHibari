package com.huanli233.hibari.wear

import com.huanli233.hibari.ui.text.FontFamily
import com.huanli233.hibari.ui.text.TextStyle
import com.huanli233.hibari.ui.text.takeOrElse
import com.huanli233.hibari.wear.tokens.ArcTypographyKeyTokens
import com.huanli233.hibari.wear.tokens.TypographyKeyTokens
import com.huanli233.hibari.wear.tokens.TypographyTokens

/**
 * Ported from androidx.wear.compose.material3.Typography.
 *
 * `CurvedTextStyle` is not reproduced: arc roles degrade to plain [TextStyle]s, so a caller that
 * passes [arcLarge] to [Text] gets straight text unless it uses the curved components explicitly.
 */
class Typography(
    val arcLarge: TextStyle = TypographyTokens.ArcLarge,
    val arcMedium: TextStyle = TypographyTokens.ArcMedium,
    val arcSmall: TextStyle = TypographyTokens.ArcSmall,
    val displayLarge: TextStyle = TypographyTokens.DisplayLarge,
    val displayMedium: TextStyle = TypographyTokens.DisplayMedium,
    val displaySmall: TextStyle = TypographyTokens.DisplaySmall,
    val titleLarge: TextStyle = TypographyTokens.TitleLarge,
    val titleMedium: TextStyle = TypographyTokens.TitleMedium,
    val titleSmall: TextStyle = TypographyTokens.TitleSmall,
    val labelLarge: TextStyle = TypographyTokens.LabelLarge,
    val labelMedium: TextStyle = TypographyTokens.LabelMedium,
    val labelSmall: TextStyle = TypographyTokens.LabelSmall,
    val bodyLarge: TextStyle = TypographyTokens.BodyLarge,
    val bodyMedium: TextStyle = TypographyTokens.BodyMedium,
    val bodySmall: TextStyle = TypographyTokens.BodySmall,
    val bodyExtraSmall: TextStyle = TypographyTokens.BodyExtraSmall,
    val numeralExtraLarge: TextStyle = TypographyTokens.NumeralExtraLarge,
    val numeralLarge: TextStyle = TypographyTokens.NumeralLarge,
    val numeralMedium: TextStyle = TypographyTokens.NumeralMedium,
    val numeralSmall: TextStyle = TypographyTokens.NumeralSmall,
    val numeralExtraSmall: TextStyle = TypographyTokens.NumeralExtraSmall,
) {

    fun copy(
        defaultFontFamily: FontFamily = FontFamily.Default,
        arcLarge: TextStyle = this.arcLarge,
        arcMedium: TextStyle = this.arcMedium,
        arcSmall: TextStyle = this.arcSmall,
        displayLarge: TextStyle = this.displayLarge,
        displayMedium: TextStyle = this.displayMedium,
        displaySmall: TextStyle = this.displaySmall,
        titleLarge: TextStyle = this.titleLarge,
        titleMedium: TextStyle = this.titleMedium,
        titleSmall: TextStyle = this.titleSmall,
        labelLarge: TextStyle = this.labelLarge,
        labelMedium: TextStyle = this.labelMedium,
        labelSmall: TextStyle = this.labelSmall,
        bodyLarge: TextStyle = this.bodyLarge,
        bodyMedium: TextStyle = this.bodyMedium,
        bodySmall: TextStyle = this.bodySmall,
        bodyExtraSmall: TextStyle = this.bodyExtraSmall,
        numeralExtraLarge: TextStyle = this.numeralExtraLarge,
        numeralLarge: TextStyle = this.numeralLarge,
        numeralMedium: TextStyle = this.numeralMedium,
        numeralSmall: TextStyle = this.numeralSmall,
        numeralExtraSmall: TextStyle = this.numeralExtraSmall,
    ): Typography = Typography(
        arcLarge.withDefaultFontFamily(defaultFontFamily),
        arcMedium.withDefaultFontFamily(defaultFontFamily),
        arcSmall.withDefaultFontFamily(defaultFontFamily),
        displayLarge.withDefaultFontFamily(defaultFontFamily),
        displayMedium.withDefaultFontFamily(defaultFontFamily),
        displaySmall.withDefaultFontFamily(defaultFontFamily),
        titleLarge.withDefaultFontFamily(defaultFontFamily),
        titleMedium.withDefaultFontFamily(defaultFontFamily),
        titleSmall.withDefaultFontFamily(defaultFontFamily),
        labelLarge.withDefaultFontFamily(defaultFontFamily),
        labelMedium.withDefaultFontFamily(defaultFontFamily),
        labelSmall.withDefaultFontFamily(defaultFontFamily),
        bodyLarge.withDefaultFontFamily(defaultFontFamily),
        bodyMedium.withDefaultFontFamily(defaultFontFamily),
        bodySmall.withDefaultFontFamily(defaultFontFamily),
        bodyExtraSmall.withDefaultFontFamily(defaultFontFamily),
        numeralExtraLarge.withDefaultFontFamily(defaultFontFamily),
        numeralLarge.withDefaultFontFamily(defaultFontFamily),
        numeralMedium.withDefaultFontFamily(defaultFontFamily),
        numeralSmall.withDefaultFontFamily(defaultFontFamily),
        numeralExtraSmall.withDefaultFontFamily(defaultFontFamily),
    )

    internal fun fromToken(token: TypographyKeyTokens): TextStyle = when (token) {
        TypographyKeyTokens.BodyExtraSmall -> bodyExtraSmall
        TypographyKeyTokens.BodyLarge -> bodyLarge
        TypographyKeyTokens.BodyMedium -> bodyMedium
        TypographyKeyTokens.BodySmall -> bodySmall
        TypographyKeyTokens.DisplayLarge -> displayLarge
        TypographyKeyTokens.DisplayMedium -> displayMedium
        TypographyKeyTokens.DisplaySmall -> displaySmall
        TypographyKeyTokens.LabelLarge -> labelLarge
        TypographyKeyTokens.LabelMedium -> labelMedium
        TypographyKeyTokens.LabelSmall -> labelSmall
        TypographyKeyTokens.NumeralExtraLarge -> numeralExtraLarge
        TypographyKeyTokens.NumeralExtraSmall -> numeralExtraSmall
        TypographyKeyTokens.NumeralLarge -> numeralLarge
        TypographyKeyTokens.NumeralMedium -> numeralMedium
        TypographyKeyTokens.NumeralSmall -> numeralSmall
        TypographyKeyTokens.TitleLarge -> titleLarge
        TypographyKeyTokens.TitleMedium -> titleMedium
        TypographyKeyTokens.TitleSmall -> titleSmall
    }

    /**
     * Arc counterpart of [fromToken]. Upstream returns a `CurvedTextStyle` here; the arc roles are
     * plain [TextStyle]s in this port, so an arc key resolves to the same style the curved
     * components consume.
     */
    internal fun fromToken(token: ArcTypographyKeyTokens): TextStyle = when (token) {
        ArcTypographyKeyTokens.ArcLarge -> arcLarge
        ArcTypographyKeyTokens.ArcMedium -> arcMedium
        ArcTypographyKeyTokens.ArcSmall -> arcSmall
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Typography) return false
        return arcLarge == other.arcLarge &&
            arcMedium == other.arcMedium &&
            arcSmall == other.arcSmall &&
            displayLarge == other.displayLarge &&
            displayMedium == other.displayMedium &&
            displaySmall == other.displaySmall &&
            titleLarge == other.titleLarge &&
            titleMedium == other.titleMedium &&
            titleSmall == other.titleSmall &&
            labelLarge == other.labelLarge &&
            labelMedium == other.labelMedium &&
            labelSmall == other.labelSmall &&
            bodyLarge == other.bodyLarge &&
            bodyMedium == other.bodyMedium &&
            bodySmall == other.bodySmall &&
            bodyExtraSmall == other.bodyExtraSmall &&
            numeralExtraLarge == other.numeralExtraLarge &&
            numeralLarge == other.numeralLarge &&
            numeralMedium == other.numeralMedium &&
            numeralSmall == other.numeralSmall &&
            numeralExtraSmall == other.numeralExtraSmall
    }

    override fun hashCode(): Int {
        var result = arcLarge.hashCode()
        result = 31 * result + bodyLarge.hashCode()
        result = 31 * result + titleLarge.hashCode()
        result = 31 * result + numeralExtraLarge.hashCode()
        return result
    }
}

private fun TextStyle.withDefaultFontFamily(default: FontFamily): TextStyle =
    if (default != FontFamily.Default) copy(fontFamily = fontFamily.takeOrElse { default }) else this
