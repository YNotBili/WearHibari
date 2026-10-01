package com.huanli233.hibari.wear

import com.huanli233.hibari.runtime.ProvidableTunationLocal
import com.huanli233.hibari.runtime.tunationLocalOf
import com.huanli233.hibari.ui.text.TextAlign

/**
 * TunationLocal containing the preferred [TextConfiguration] that will be used by [Text] components
 * by default, consisting of text alignment, overflow specification and max lines. Material3
 * components related to text such as `Button`, `CheckboxButton`, `SwitchButton`, `RadioButton` use
 * [LocalTextConfiguration] to set values with which to style child text components.
 *
 * Ported from `LocalTextConfiguration` (`material3/Text.kt:261-268`). The only substitution is the
 * carrier: `ProvidableCompositionLocal` + `compositionLocalOf(structuralEqualityPolicy())` becomes
 * [ProvidableTunationLocal] + [tunationLocalOf], whose default policy already *is* structural
 * equality (`runtime/TunationLocal.kt:127-131`), so a reader subscribes exactly as it does
 * upstream. [TextOverflow] is this module's own (`Text.kt:104`) rather than compose-ui's, which is
 * what [Text] already maps onto a `TextView`'s `ellipsize`.
 *
 * Unlike [LocalContentColor] and [LocalTextStyle], which this module made `internal`, this one
 * stays public because upstream declares it public.
 */
val LocalTextConfiguration: ProvidableTunationLocal<TextConfiguration> = tunationLocalOf {
    TextConfiguration(
        TextConfigurationDefaults.TextAlign,
        TextConfigurationDefaults.Overflow,
        TextConfigurationDefaults.MaxLines,
    )
}

/**
 * Class representing aspects of [Text] that can be configured with [LocalTextConfiguration].
 *
 * @param textAlign The alignment of the text within the lines of the paragraph.
 * @param overflow How visual overflow should be handled.
 * @param maxLines The maximum number of lines for the text to span, wrapping if necessary.
 */
class TextConfiguration(
    val textAlign: TextAlign?,
    val overflow: TextOverflow,
    val maxLines: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is TextConfiguration) return false

        if (textAlign != other.textAlign) return false
        if (overflow != other.overflow) return false
        if (maxLines != other.maxLines) return false

        return true
    }

    override fun hashCode(): Int {
        var result = textAlign.hashCode()
        result = 31 * result + overflow.hashCode()
        result = 31 * result + maxLines.hashCode()
        return result
    }
}

/** Default values for [TextConfiguration] */
object TextConfigurationDefaults {
    /** Default text alignment for [Text] */
    val TextAlign: TextAlign? = null

    /** Default visual text overflow for [Text] */
    val Overflow: TextOverflow = TextOverflow.Clip

    /** Default max lines for [Text] */
    val MaxLines: Int = Int.MAX_VALUE
}
