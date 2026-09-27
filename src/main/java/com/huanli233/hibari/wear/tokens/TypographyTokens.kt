package com.huanli233.hibari.wear.tokens

import com.huanli233.hibari.ui.text.FontWeight
import com.huanli233.hibari.ui.text.TextStyle

/**
 * Ported from androidx.wear.compose.material3.tokens TypographyTokens (v0_108 + manual changes).
 *
 * Every upstream role is built from `DefaultTextStyle`; its only field with a Hibari `TextStyle`
 * equivalent is `fontFeatureSettings = "pnum"` (proportional numerals), reproduced on all roles
 * below. `platformStyle` (includeFontPadding = false), `lineHeightStyle` (centered, untrimmed) and
 * `textMotion = Animated` have no Hibari field, so they are not reproduced.
 *
 * Upstream reaches the wdth/wght axes through `TypographyVariableFontsTokens.*VariationSettings`;
 * [TextStyle.widthAxis] plus [FontWeight] carry the same two values here. Curved styles collapse to
 * plain [TextStyle]s and `letterSpacingCounterClockwise` (`TypeScaleTokens.Arc*TrackingBottom`) has
 * no `TextView` equivalent, so arc roles carry only the clockwise tracking.
 */
internal object TypographyTokens {
    val ArcLarge = TextStyle(
        fontFamily = TypeScaleTokens.ArcLargeFont,
        fontWeight = FontWeight(TypeScaleTokens.ArcLargeWeight.toInt()),
        fontSize = TypeScaleTokens.ArcLargeSize,
        lineHeight = TypeScaleTokens.ArcLargeLineHeight,
        letterSpacing = TypeScaleTokens.ArcLargeTrackingTop,
        widthAxis = TypeScaleTokens.ArcLargeWidth,
        fontFeatureSettings = "pnum",
    )
    val ArcMedium = TextStyle(
        fontFamily = TypeScaleTokens.ArcMediumFont,
        fontWeight = FontWeight(TypeScaleTokens.ArcMediumWeight.toInt()),
        fontSize = TypeScaleTokens.ArcMediumSize,
        lineHeight = TypeScaleTokens.ArcMediumLineHeight,
        letterSpacing = TypeScaleTokens.ArcMediumTrackingTop,
        widthAxis = TypeScaleTokens.ArcMediumWidth,
        fontFeatureSettings = "pnum",
    )
    val ArcSmall = TextStyle(
        fontFamily = TypeScaleTokens.ArcSmallFont,
        fontWeight = FontWeight(TypeScaleTokens.ArcSmallWeight.toInt()),
        fontSize = TypeScaleTokens.ArcSmallSize,
        lineHeight = TypeScaleTokens.ArcSmallLineHeight,
        letterSpacing = TypeScaleTokens.ArcSmallTrackingTop,
        widthAxis = TypeScaleTokens.ArcSmallWidth,
        fontFeatureSettings = "pnum",
    )
    val BodyExtraSmall = TextStyle(
        fontFamily = TypeScaleTokens.BodyExtraSmallFont,
        fontWeight = FontWeight(TypeScaleTokens.BodyExtraSmallWeight.toInt()),
        fontSize = TypeScaleTokens.BodyExtraSmallSize,
        lineHeight = TypeScaleTokens.BodyExtraSmallLineHeight,
        letterSpacing = TypeScaleTokens.BodyExtraSmallTracking,
        widthAxis = TypeScaleTokens.BodyExtraSmallWidth,
        fontFeatureSettings = "pnum",
    )
    val BodyLarge = TextStyle(
        fontFamily = TypeScaleTokens.BodyLargeFont,
        fontWeight = FontWeight(TypeScaleTokens.BodyLargeWeight.toInt()),
        fontSize = TypeScaleTokens.BodyLargeSize,
        lineHeight = TypeScaleTokens.BodyLargeLineHeight,
        letterSpacing = TypeScaleTokens.BodyLargeTracking,
        widthAxis = TypeScaleTokens.BodyLargeWidth,
        fontFeatureSettings = "pnum",
    )
    val BodyMedium = TextStyle(
        fontFamily = TypeScaleTokens.BodyMediumFont,
        fontWeight = FontWeight(TypeScaleTokens.BodyMediumWeight.toInt()),
        fontSize = TypeScaleTokens.BodyMediumSize,
        lineHeight = TypeScaleTokens.BodyMediumLineHeight,
        letterSpacing = TypeScaleTokens.BodyMediumTracking,
        widthAxis = TypeScaleTokens.BodyMediumWidth,
        fontFeatureSettings = "pnum",
    )
    val BodySmall = TextStyle(
        fontFamily = TypeScaleTokens.BodySmallFont,
        fontWeight = FontWeight(TypeScaleTokens.BodySmallWeight.toInt()),
        fontSize = TypeScaleTokens.BodySmallSize,
        lineHeight = TypeScaleTokens.BodySmallLineHeight,
        letterSpacing = TypeScaleTokens.BodySmallTracking,
        widthAxis = TypeScaleTokens.BodySmallWidth,
        fontFeatureSettings = "pnum",
    )
    val DisplayLarge = TextStyle(
        fontFamily = TypeScaleTokens.DisplayLargeFont,
        fontWeight = FontWeight(TypeScaleTokens.DisplayLargeWeight.toInt()),
        fontSize = TypeScaleTokens.DisplayLargeSize,
        lineHeight = TypeScaleTokens.DisplayLargeLineHeight,
        letterSpacing = TypeScaleTokens.DisplayLargeTracking,
        widthAxis = TypeScaleTokens.DisplayLargeWidth,
        fontFeatureSettings = "pnum",
    )
    val DisplayMedium = TextStyle(
        fontFamily = TypeScaleTokens.DisplayMediumFont,
        fontWeight = FontWeight(TypeScaleTokens.DisplayMediumWeight.toInt()),
        fontSize = TypeScaleTokens.DisplayMediumSize,
        lineHeight = TypeScaleTokens.DisplayMediumLineHeight,
        letterSpacing = TypeScaleTokens.DisplayMediumTracking,
        widthAxis = TypeScaleTokens.DisplayMediumWidth,
        fontFeatureSettings = "pnum",
    )
    val DisplaySmall = TextStyle(
        fontFamily = TypeScaleTokens.DisplaySmallFont,
        fontWeight = FontWeight(TypeScaleTokens.DisplaySmallWeight.toInt()),
        fontSize = TypeScaleTokens.DisplaySmallSize,
        lineHeight = TypeScaleTokens.DisplaySmallLineHeight,
        letterSpacing = TypeScaleTokens.DisplaySmallTracking,
        widthAxis = TypeScaleTokens.DisplaySmallWidth,
        fontFeatureSettings = "pnum",
    )
    val LabelLarge = TextStyle(
        fontFamily = TypeScaleTokens.LabelLargeFont,
        fontWeight = FontWeight(TypeScaleTokens.LabelLargeWeight.toInt()),
        fontSize = TypeScaleTokens.LabelLargeSize,
        lineHeight = TypeScaleTokens.LabelLargeLineHeight,
        letterSpacing = TypeScaleTokens.LabelLargeTracking,
        widthAxis = TypeScaleTokens.LabelLargeWidth,
        fontFeatureSettings = "pnum",
    )
    val LabelMedium = TextStyle(
        fontFamily = TypeScaleTokens.LabelMediumFont,
        fontWeight = FontWeight(TypeScaleTokens.LabelMediumWeight.toInt()),
        fontSize = TypeScaleTokens.LabelMediumSize,
        lineHeight = TypeScaleTokens.LabelMediumLineHeight,
        letterSpacing = TypeScaleTokens.LabelMediumTracking,
        widthAxis = TypeScaleTokens.LabelMediumWidth,
        fontFeatureSettings = "pnum",
    )
    val LabelSmall = TextStyle(
        fontFamily = TypeScaleTokens.LabelSmallFont,
        fontWeight = FontWeight(TypeScaleTokens.LabelSmallWeight.toInt()),
        fontSize = TypeScaleTokens.LabelSmallSize,
        lineHeight = TypeScaleTokens.LabelSmallLineHeight,
        letterSpacing = TypeScaleTokens.LabelSmallTracking,
        widthAxis = TypeScaleTokens.LabelSmallWidth,
        fontFeatureSettings = "pnum",
    )
    val NumeralExtraLarge = TextStyle(
        fontFamily = TypeScaleTokens.NumeralExtraLargeFont,
        fontWeight = FontWeight(TypeScaleTokens.NumeralExtraLargeWeight.toInt()),
        fontSize = TypeScaleTokens.NumeralExtraLargeSize,
        lineHeight = TypeScaleTokens.NumeralExtraLargeLineHeight,
        letterSpacing = TypeScaleTokens.NumeralExtraLargeTracking,
        widthAxis = TypeScaleTokens.NumeralExtraLargeWidth,
        fontFeatureSettings = "pnum",
    )
    val NumeralExtraSmall = TextStyle(
        fontFamily = TypeScaleTokens.NumeralExtraSmallFont,
        fontWeight = FontWeight(TypeScaleTokens.NumeralExtraSmallWeight.toInt()),
        fontSize = TypeScaleTokens.NumeralExtraSmallSize,
        lineHeight = TypeScaleTokens.NumeralExtraSmallLineHeight,
        letterSpacing = TypeScaleTokens.NumeralExtraSmallTracking,
        widthAxis = TypeScaleTokens.NumeralExtraSmallWidth,
        fontFeatureSettings = "pnum",
    )
    val NumeralLarge = TextStyle(
        fontFamily = TypeScaleTokens.NumeralLargeFont,
        fontWeight = FontWeight(TypeScaleTokens.NumeralLargeWeight.toInt()),
        fontSize = TypeScaleTokens.NumeralLargeSize,
        lineHeight = TypeScaleTokens.NumeralLargeLineHeight,
        letterSpacing = TypeScaleTokens.NumeralLargeTracking,
        widthAxis = TypeScaleTokens.NumeralLargeWidth,
        fontFeatureSettings = "pnum",
    )
    val NumeralMedium = TextStyle(
        fontFamily = TypeScaleTokens.NumeralMediumFont,
        fontWeight = FontWeight(TypeScaleTokens.NumeralMediumWeight.toInt()),
        fontSize = TypeScaleTokens.NumeralMediumSize,
        lineHeight = TypeScaleTokens.NumeralMediumLineHeight,
        letterSpacing = TypeScaleTokens.NumeralMediumTracking,
        widthAxis = TypeScaleTokens.NumeralMediumWidth,
        fontFeatureSettings = "pnum",
    )
    val NumeralSmall = TextStyle(
        fontFamily = TypeScaleTokens.NumeralSmallFont,
        fontWeight = FontWeight(TypeScaleTokens.NumeralSmallWeight.toInt()),
        fontSize = TypeScaleTokens.NumeralSmallSize,
        lineHeight = TypeScaleTokens.NumeralSmallLineHeight,
        letterSpacing = TypeScaleTokens.NumeralSmallTracking,
        widthAxis = TypeScaleTokens.NumeralSmallWidth,
        fontFeatureSettings = "pnum",
    )
    val TitleLarge = TextStyle(
        fontFamily = TypeScaleTokens.TitleLargeFont,
        fontWeight = FontWeight(TypeScaleTokens.TitleLargeWeight.toInt()),
        fontSize = TypeScaleTokens.TitleLargeSize,
        lineHeight = TypeScaleTokens.TitleLargeLineHeight,
        letterSpacing = TypeScaleTokens.TitleLargeTracking,
        widthAxis = TypeScaleTokens.TitleLargeWidth,
        fontFeatureSettings = "pnum",
    )
    val TitleMedium = TextStyle(
        fontFamily = TypeScaleTokens.TitleMediumFont,
        fontWeight = FontWeight(TypeScaleTokens.TitleMediumWeight.toInt()),
        fontSize = TypeScaleTokens.TitleMediumSize,
        lineHeight = TypeScaleTokens.TitleMediumLineHeight,
        letterSpacing = TypeScaleTokens.TitleMediumTracking,
        widthAxis = TypeScaleTokens.TitleMediumWidth,
        fontFeatureSettings = "pnum",
    )
    val TitleSmall = TextStyle(
        fontFamily = TypeScaleTokens.TitleSmallFont,
        fontWeight = FontWeight(TypeScaleTokens.TitleSmallWeight.toInt()),
        fontSize = TypeScaleTokens.TitleSmallSize,
        lineHeight = TypeScaleTokens.TitleSmallLineHeight,
        letterSpacing = TypeScaleTokens.TitleSmallTracking,
        widthAxis = TypeScaleTokens.TitleSmallWidth,
        fontFeatureSettings = "pnum",
    )
}
