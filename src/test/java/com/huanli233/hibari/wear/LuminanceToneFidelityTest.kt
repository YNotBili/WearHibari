package com.huanli233.hibari.wear

import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.graphics.setLuminance
import com.huanli233.hibari.ui.graphics.srgbToLinear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two `setLuminance`-shaped functions this module keeps apart, and which it previously confused
 * (see `ColorAppearanceModel.kt:26-44` for the history). They read the same argument as two
 * different scales:
 *
 *  - [setLuminanceTone] (`ColorAppearanceModel.kt:1631-1640`) is upstream's
 *    `internal fun Color.setLuminance` (`material3/DynamicColorScheme.kt:116-125`) renamed, and takes
 *    a CAM16/L* tone on a 0..100 scale, solved through `Cam`/`HctSolver`;
 *  - `com.huanli233.hibari.ui.graphics.setLuminance` (`hibari-ui Color.kt:172-186`) is the public
 *    one, takes a 0..1 *relative luminance*, and `coerceIn`s its argument to that range.
 *
 * Hand-computed expectations only: the 0..1 side is arithmetic on the sRGB curve, and the 0..100
 * side is only pinned where the tone argument has zero chroma to solve for, i.e. where
 * `HctSolver.solveToInt` short circuits to `CamUtils.argbFromLstar`
 * (`ColorAppearanceModel.kt:1581-1583`, `:757-768`) and the answer is the CIE curve applied to a
 * neutral.
 */
class LuminanceToneFidelityTest {

    private val red = Color(0xFFFF0000)
    private val black = Color(0xFF000000)

    /** sRGB relative luminance, the quantity the 0..1 function targets. */
    private fun luminance(color: Color): Float =
        0.2126f * srgbToLinear(color.red) + 0.7152f * srgbToLinear(color.green) +
            0.0722f * srgbToLinear(color.blue)

    @Test
    fun toneEightyIsNotLuminanceEighty() {
        // The 0..1 function coerces 80f up to its ceiling of 1f and then cannot reach it: pure red is
        // already the brightest red sRGB has, so every channel clips back to where it started.
        assertEquals(red, red.setLuminance(80f))
        assertEquals(0.2126f, luminance(red.setLuminance(80f)), 0.001f)
        // The tone function reads the same 80f as L* 80, i.e. Y = ((80 + 16) / 116)^3 = 0.5668
        // (`CamUtils.yFromLstar`, ColorAppearanceModel.kt:902-909), which no red is - the solve has to
        // desaturate it to get there.
        val lifted = red.setLuminanceTone(80f)
        assertNotEquals(red, lifted)
        assertEquals(0.5668f, luminance(lifted), 0.05f)
    }

    @Test
    fun toneScaleOrdersRequestsTheCoerceInCannotEvenSee() {
        val dark = red.setLuminanceTone(20f)
        val light = red.setLuminanceTone(80f)
        assertTrue("L* 20 should be darker than L* 80", luminance(dark) < luminance(light))
        // All three of those requests are the same one to the 0..1 function, because they all
        // coerce up to its ceiling.
        assertEquals(red, red.setLuminance(20f))
        assertEquals(red, red.setLuminance(80f))
        assertEquals(red, red.setLuminance(1f))
    }

    @Test
    fun outOfRangeTonesAreCorrectedToBlackOrWhite() {
        // Upstream's doc comment says "invalid values are corrected" (`DynamicColorScheme.kt:114`) and
        // the short circuit at `ColorAppearanceModel.kt:1632-1634` means `argbFromLstar`, which
        // `clampInt(0, 255, ...)` (`:809-817`) flattens to a neutral. Nothing coerces the argument
        // into 0..1 on the way.
        assertEquals(Color(0xFF000000), Color(0xFF3366CC).setLuminanceTone(0f))
        assertEquals(Color(0xFF000000), Color(0xFF3366CC).setLuminanceTone(-5f))
        assertEquals(Color(0xFFFFFFFF), Color(0xFF3366CC).setLuminanceTone(100f))
        assertEquals(Color(0xFFFFFFFF), Color(0xFF3366CC).setLuminanceTone(105f))
        // The 0..1 function cannot express "invalid" at all: 0f means black and -5f coerces to it.
        assertEquals(Color(0xFF000000), red.setLuminance(0f))
        assertEquals(Color(0xFF000000), red.setLuminance(-5f))
    }

    @Test
    fun theZeroChromaGreysOfTheTwoScalesDiffer() {
        // A black input has exactly zero CAM16 chroma, so the tone solve short circuits to a neutral
        // at the requested L*: L* 50 is sRGB 119, not sRGB 128. Worked from `argbFromLstar`:
        // fy = 66/116, Y = fy^3 = 0.184187, encoded = 1.055 * Y^(1/2.4) - 0.055 = 0.46633, * 255 = 119.
        assertEquals(Color(0xFF777777), black.setLuminanceTone(50f))
        assertEquals(Color(0xFFC6C6C6), black.setLuminanceTone(80f))
        assertEquals(Color(0xFF303030), black.setLuminanceTone(20f))
        // The 0..1 function's own zero-luminance shortcut (`hibari-ui Color.kt:178`) hands back the
        // plain fraction as a grey instead, so half brightness is 128.
        assertEquals(Color(0xFF808080), black.setLuminance(0.5f))
    }

    @Test
    fun onlyTheRelativeLuminanceVariantKeepsTheAlpha() {
        // Both are faithful to upstream here: the tone path round trips through an ARGB int that
        // `argbFromRgb` always stamps 0xFF onto (`ColorAppearanceModel.kt:820-822`), while the 0..1
        // one passes `alpha` through to the Float factory (`hibari-ui Color.kt:178, 180-185`).
        val translucent = Color(0x80FF8033)
        assertEquals(0xFF, translucent.setLuminanceTone(0f).toArgb() ushr 24)
        assertEquals(0x80, translucent.setLuminance(0f).toArgb() ushr 24)
        assertEquals(0x00, translucent.setLuminance(0f).toArgb() and 0xFF)
    }

    @Test
    fun theCallSitesInTheModuleAskForTheToneScale() {
        // `ScrollIndicatorDefaults.colors()` (`ScrollIndicator.kt:399-400`) derives both its colours
        // from `onBackground` at tone 80 / tone 20, and `CurvedText.kt:120` and `TimeText.kt:115` both
        // use tone 80. On white that is a desaturated grey pair; run through the 0..1 function both
        // would collapse to `onBackground` itself and the track would be invisible against it.
        val onBackground = ColorScheme().onBackground
        assertEquals(Color(0xFFFFFFFF), onBackground)
        val indicator = onBackground.setLuminanceTone(80f)
        val track = onBackground.setLuminanceTone(20f)
        assertNotEquals("the two indicator colours must differ", indicator, track)
        assertTrue("track luminance was ${luminance(track)}", luminance(track) < 0.1f)
        assertTrue("indicator luminance was ${luminance(indicator)}", luminance(indicator) > 0.45f)
    }
}
