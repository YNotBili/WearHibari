package com.huanli233.hibari.wear.tokens

import com.huanli233.hibari.ui.text.FontFamily
import com.huanli233.hibari.ui.text.FontWeight

internal object TypefaceTokens {
    /** Wear OS ships roboto-flex; off-device the name fails to resolve and the platform default is used. */
    val Brand = FontFamily.named("roboto-flex")
    val Plain = FontFamily.named("roboto-flex")
    val WeightBold = FontWeight.Bold
    val WeightMedium = FontWeight.Medium
    val WeightRegular = FontWeight.Normal
}
