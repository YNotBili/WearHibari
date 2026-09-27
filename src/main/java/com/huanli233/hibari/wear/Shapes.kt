package com.huanli233.hibari.wear

import com.huanli233.hibari.ui.geometry.CircleShape
import com.huanli233.hibari.ui.geometry.RectangleShape
import com.huanli233.hibari.ui.geometry.RoundedCornerShape
import com.huanli233.hibari.ui.geometry.Shape
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.tokens.ShapeKeyTokens

/**
 * Ported from androidx.wear.compose.material3.Shapes.
 *
 * Upstream stores a `CornerBasedShape` per role, seeded from `ShapeTokens.Corner*`, so a corner can
 * animate toward a cut corner; nothing here animates, so the roles stay rounded shapes over the same
 * five radii. Note that Hibari's `CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart)`
 * defaults the other three corners to `0.dp`, so a role must be built through [RoundedCornerShape]
 * — `CornerBasedShape(radius)` alone rounds only the top-start corner.
 */
class Shapes(
    val extraSmall: Shape = RoundedCornerShape(ShapeDefaults.ExtraSmall),
    val small: Shape = RoundedCornerShape(ShapeDefaults.Small),
    val medium: Shape = RoundedCornerShape(ShapeDefaults.Medium),
    val large: Shape = RoundedCornerShape(ShapeDefaults.Large),
    val extraLarge: Shape = RoundedCornerShape(ShapeDefaults.ExtraLarge),
) {

    fun copy(
        extraSmall: Shape = this.extraSmall,
        small: Shape = this.small,
        medium: Shape = this.medium,
        large: Shape = this.large,
        extraLarge: Shape = this.extraLarge,
    ): Shapes = Shapes(extraSmall, small, medium, large, extraLarge)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Shapes) return false
        return extraSmall == other.extraSmall &&
            small == other.small &&
            medium == other.medium &&
            large == other.large &&
            extraLarge == other.extraLarge
    }

    override fun hashCode(): Int {
        var result = extraSmall.hashCode()
        result = 31 * result + small.hashCode()
        result = 31 * result + medium.hashCode()
        result = 31 * result + large.hashCode()
        result = 31 * result + extraLarge.hashCode()
        return result
    }

    internal fun fromToken(token: ShapeKeyTokens): Shape = when (token) {
        ShapeKeyTokens.CornerNone -> RectangleShape
        ShapeKeyTokens.CornerExtraSmall -> extraSmall
        ShapeKeyTokens.CornerSmall -> small
        ShapeKeyTokens.CornerExtraLarge -> extraLarge
        ShapeKeyTokens.CornerFull -> CircleShape
        ShapeKeyTokens.CornerLarge -> large
        ShapeKeyTokens.CornerMedium -> medium
    }
}

/**
 * Contains the default values used by [Shapes]. Upstream's members are `RoundedCornerShape`s taken
 * straight from `ShapeTokens.Corner*` (material3/Shapes.kt:129-145); the bare [Dp]s here are the
 * same five radii, kept separable because callers such as `ButtonGroup` need the radius on its own.
 */
object ShapeDefaults {
    val ExtraSmall: Dp = 4.dp
    val Small: Dp = 8.dp
    val Medium: Dp = 18.dp
    val Large: Dp = 26.dp
    val ExtraLarge: Dp = 36.dp
}
