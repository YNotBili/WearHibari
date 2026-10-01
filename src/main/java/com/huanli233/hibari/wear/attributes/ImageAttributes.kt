package com.huanli233.hibari.wear.attributes

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey

/** A drawable, resource id or bitmap, resolved the way Hibari's other image slots are. */
fun Modifier.image(image: Any?): Modifier =
    this.thenViewAttribute<ImageView, Any>(uniqueKey, image ?: NoImage) {
        // The attribute stays in the chain while there is no image. Dropping it instead would take
        // the value with it while the view still shows the old one, which is the exact case
        // `HibariDiffCallback` (`hibari-runtime/.../HibariDiffCallback.kt:167-173`) answers with a null
        // payload, costing this node and its subtree a recreation every time the image turns off.
        when (it) {
            is Int -> setImageResource(it)
            is Drawable -> setImageDrawable(it)
            is Bitmap -> setImageBitmap(it)

            // `setImageDrawable(null)` leaves the view in the state a freshly-created `ImageView` is
            // in: the drawable, `mResource` and `mUri` cleared and the intrinsic size back to -1.
            // Anything `image` accepts but does not handle here still writes nothing, as before.
            NoImage -> setImageDrawable(null)
        }
    }

/** The always-present chain's stand-in for "no image". Identity-equal, so a no-change retune diffs away. */
private object NoImage

fun Modifier.scaleType(scaleType: ImageView.ScaleType): Modifier =
    this.thenViewAttribute<ImageView, ImageView.ScaleType>(uniqueKey, scaleType) {
        this.scaleType = it
    }

/**
 * The icon tint, or `null`/`Color.Unspecified` for "no colour filter at all".
 *
 * Always emitted rather than applied conditionally: an attribute that leaves the chain at all makes
 * [com.huanli233.hibari.runtime.HibariDiffCallback] hand back a null payload (`:167-173`, "an
 * attribute that left the chain took its value with it while the view still shows it, so the view has
 * to be rebuilt"), and `Patcher` then answers with `removeView` + `render` + a re-patch of the
 * subtree (`:161-174`). A tint that resolves to unspecified for one tune would rebuild the image and
 * everything under it. Clearing is exact here — a freshly created `ImageView` has no colour filter —
 * so writing `null` reproduces the created state instead of approximating it, which is what the
 * `Modifier.image` sentinel does for the drawable itself.
 */
fun Modifier.imageTint(color: Color?): Modifier =
    this.thenViewAttribute<ImageView, Color?>(uniqueKey, color) {
        if (it == null || it == Color.Unspecified) clearColorFilter() else setColorFilter(it.toArgb())
    }

/** Null clears the description, which is how a decorative image is hidden from accessibility. */
fun Modifier.contentDescription(description: String?): Modifier =
    this.thenViewAttribute<ImageView, String?>(uniqueKey, description) {
        contentDescription = it
    }
