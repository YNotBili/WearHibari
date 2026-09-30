package com.huanli233.hibari.wear

import com.huanli233.hibari.ui.geometry.CircleShape
import com.huanli233.hibari.ui.geometry.RoundedCornerShape
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The parts of the icon and text button surface that are plain functions, and whose failure would
 * otherwise only be visible as a button that is the wrong size or has quietly lost a colour.
 */
class IconButtonSurfaceTest {

    @Test
    fun iconSizeForFollowsUpstreamsTwoBranches() {
        // `if (buttonSize >= LargeButtonSize) LargeIconSize else max(SmallIconSize, size / 2)`
        // (material3/IconButton.kt:499-506).
        assertEquals(
            IconButtonDefaults.LargeIconSize,
            IconButtonDefaults.iconSizeFor(IconButtonDefaults.LargeButtonSize),
        )
        assertEquals(
            IconButtonDefaults.LargeIconSize,
            IconButtonDefaults.iconSizeFor(IconButtonDefaults.LargeButtonSize + 8.dp),
        )
        assertEquals(
            IconButtonDefaults.DefaultIconSize,
            IconButtonDefaults.iconSizeFor(IconButtonDefaults.DefaultButtonSize),
        )
        // Half of the extra-small container is below the small-icon floor, so the floor answers.
        assertEquals(
            IconButtonDefaults.SmallIconSize,
            IconButtonDefaults.iconSizeFor(IconButtonDefaults.ExtraSmallButtonSize),
        )
    }

    @Test
    fun copyKeepsEveryFieldLeftUnspecified() {
        val base = IconButtonColors(
            containerColor = Color(0xFF112233),
            contentColor = Color(0xFF445566),
            disabledContainerColor = Color(0xFF778899),
            disabledContentColor = Color(0xFFAABBCC),
        )
        val untouched = base.copy()
        assertEquals(base, untouched)
        assertEquals(base.contentColor, untouched.contentColor)

        val oneMoved = base.copy(contentColor = Color(0xFF00FF00))
        assertEquals(Color(0xFF00FF00), oneMoved.contentColor)
        assertEquals(base.containerColor, oneMoved.containerColor)
        assertNotEquals(base, oneMoved)

        // Same rule on the text button set, which is a separate copy rather than a shared base.
        val text = TextButtonColors(
            containerColor = Color(0xFF112233),
            contentColor = Color(0xFF445566),
            disabledContainerColor = Color(0xFF778899),
            disabledContentColor = Color(0xFFAABBCC),
        )
        assertEquals(text, text.copy(disabledContentColor = Color.Unspecified))
        assertEquals(text, text.copy())
    }

    @Test
    fun pressedShapeDefaultsToShapeSoAPlainButtonNeverMorphs() {
        val static = IconButtonShapes(shape = CircleShape)
        assertSame(static.shape, static.pressedShape)

        // The morph case: [ContainerSpec.pressedShape] is only populated when the two differ, which
        // is what makes an animated button's squircle reach the drawable at all.
        val morph = static.copy(pressedShape = RoundedCornerShape(8.dp))
        assertNotEquals(static, morph)
        assertEquals(CircleShape, morph.shape)

        val textStatic = TextButtonShapes(shape = CircleShape)
        assertSame(textStatic.shape, textStatic.pressedShape)
    }
}
