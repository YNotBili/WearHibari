package com.huanli233.hibari.wear

import com.huanli233.hibari.ui.unit.LayoutDirection
import com.huanli233.hibari.ui.unit.PaddingValues
import com.huanli233.hibari.ui.unit.calculateStartPadding
import com.huanli233.hibari.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The screen-padding percentages are the reason a Wear layout looks right on a round screen, and
 * this is the arithmetic that replaced `androidx.wear.widget.BoxInsetLayout`. The expectations below
 * are computed from upstream's formulas by hand rather than read back out of the implementation:
 * vertical is `ceil(screenHeightDp * 10%)` and horizontal is `ceil(screenWidthDp * 5.2%)`, both in
 * dp, so a screen of 192 dp costs 20 dp vertically and 10 dp horizontally.
 */
class WearInsetsTest {

    @Test
    fun ceilRoundsUpAwayFromZeroOnly() {
        assertEquals(21f, WearInsets.ceilDp(20.2f), 0f)
        assertEquals(20f, WearInsets.ceilDp(20f), 0f)
        assertEquals(-3f, WearInsets.ceilDp(-3.7f), 0f)
    }

    @Test
    fun contentPaddingFollowsScreenPercentages() {
        assertEquals(20f, WearInsets.verticalContentPaddingDp(192), 0f)
        assertEquals(20f, WearInsets.verticalContentPaddingDp(199), 0f)
        assertEquals(21f, WearInsets.verticalContentPaddingDp(201), 0f)
        assertEquals(10f, WearInsets.horizontalContentPaddingDp(192), 0f)
        // 5.2% of 200 dp is 10.4, which ceils up.
        assertEquals(11f, WearInsets.horizontalContentPaddingDp(200), 0f)
    }

    @Test
    fun paddingValuesCarriesVerticalAndHorizontally() {
        val padding = WearInsets.contentPaddingValues(screenWidthDp = 192, screenHeightDp = 192)
        assertEquals(20f, padding.calculateTopPadding().value, 0f)
        assertEquals(20f, padding.calculateBottomPadding().value, 0f)
        assertEquals(10f, padding.calculateStartPadding(LayoutDirection.Ltr).value, 0f)
    }

    @Test
    fun insetsConvertToPixels() {
        assertEquals(WearEdgeInsets(left = 20, top = 40, right = 20, bottom = 40),
            WearInsets.contentInsetsPx(screenWidthDp = 192, screenHeightDp = 192, density = 2f))
    }

    @Test
    fun boxedEdgesKeepOnlySelectedSides() {
        val insets = WearEdgeInsets(20, 40, 20, 40)
        assertEquals(WearEdgeInsets(20, 40, 0, 0),
            WearInsets.boxedInsetsPx(insets, WearBoxedEdges.LEFT or WearBoxedEdges.TOP))
        assertEquals(WearEdgeInsets(0, 0, 0, 0),
            WearInsets.boxedInsetsPx(insets, WearBoxedEdges.NONE))
        assertEquals(insets, WearInsets.boxedInsetsPx(insets, WearBoxedEdges.ALL))
    }

    @Test
    fun asymmetricPaddingFlipsWithDirection() {
        val padding = PaddingValues(start = 4.dp, top = 0.dp, end = 8.dp, bottom = 0.dp)
        assertEquals(WearEdgeInsets(8, 0, 16, 0),
            WearInsets.insetsPx(padding, 2f, LayoutDirection.Ltr))
        assertEquals(WearEdgeInsets(16, 0, 8, 0),
            WearInsets.insetsPx(padding, 2f, LayoutDirection.Rtl))
    }

    @Test
    fun inscribedRectOnlyAppliesToRoundScreens() {
        assertEquals(WearEdgeInsets(56, 56, 56, 56),
            WearInsets.inscribedRectInsetsPx(384, 384, isRound = true))
        assertEquals(WearEdgeInsets.NONE,
            WearInsets.inscribedRectInsetsPx(384, 384, isRound = false))
        assertEquals(WearEdgeInsets.NONE, WearInsets.inscribedRectInsetsPx(0, 384, isRound = true))
    }

    /**
     * The legacy widget this replaced used a single number for all four edges, which is exactly why
     * it is wrong for Wear Compose's per-edge model: on a 384 px screen it pads 56 px everywhere,
     * where upstream wants 40 px vertically and 20 px horizontally at density 2.
     */
    @Test
    fun legacyBoxInsetIsOneSidedValue() {
        assertEquals(56, WearInsets.legacyBoxInsetPx(384))
        val legacy = WearInsets.legacyBoxInsetPx(384).toFloat()
        val upstream = WearInsets.contentInsetsPx(192, 192, 2f)
        assertEquals(16, legacy.toInt() - upstream.top)
        assertEquals(36, legacy.toInt() - upstream.left)
    }
}
