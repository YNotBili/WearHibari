package com.huanli233.hibari.wear.tokens

/**
 * Duration constants ported verbatim from androidx.wear.compose.material3.tokens.MotionTokens.
 * The easing entries in that file (`CubicBezierEasing`, `PathEasing` with SVG path strings) are not
 * ported: Hibari's easing lives in hibari-animation's own `Easing` hierarchy, which has no path-easing
 * equivalent, so mapping them here would silently change curves.
 */
internal object MotionDurationTokens {
    const val DurationExtraLong1 = 700
    const val DurationExtraLong2 = 800
    const val DurationExtraLong3 = 900
    const val DurationExtraLong4 = 1000
    const val DurationLong1 = 450
    const val DurationLong2 = 500
    const val DurationLong3 = 550
    const val DurationLong4 = 600
    const val DurationMedium1 = 250
    const val DurationMedium2 = 300
    const val DurationMedium3 = 350
    const val DurationMedium4 = 400
    const val DurationShort1 = 50
    const val DurationShort2 = 100
    const val DurationShort3 = 150
    const val DurationShort4 = 200
}
