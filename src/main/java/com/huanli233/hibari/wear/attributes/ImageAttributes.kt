package com.huanli233.hibari.wear.attributes

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.thenViewAttributeIfNotNull
import com.huanli233.hibari.ui.uniqueKey

/** A drawable, resource id or bitmap, resolved the way Hibari's other image slots are. */
fun Modifier.image(image: Any?): Modifier =
    this.thenViewAttributeIfNotNull<ImageView, Any>(uniqueKey, image) {
        when (it) {
            is Int -> setImageResource(it)
            is Drawable -> setImageDrawable(it)
            is Bitmap -> setImageBitmap(it)
        }
    }

fun Modifier.scaleType(scaleType: ImageView.ScaleType): Modifier =
    this.thenViewAttribute<ImageView, ImageView.ScaleType>(uniqueKey, scaleType) {
        this.scaleType = it
    }

fun Modifier.imageTint(color: Color): Modifier =
    this.thenViewAttribute<ImageView, Color>(uniqueKey, color) { setColorFilter(it.toArgb()) }

/** Null clears the description, which is how a decorative image is hidden from accessibility. */
fun Modifier.contentDescription(description: String?): Modifier =
    this.thenViewAttribute<ImageView, String?>(uniqueKey, description) {
        contentDescription = it
    }
