package com.huanli233.hibari.wear

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.ColorRes
import com.huanli233.hibari.ui.graphics.Color

/**
 * Ported from androidx.wear.compose.material3.DynamicColorScheme
 * (material3/DynamicColorScheme.kt:27-127).
 *
 * Upstream's `dynamicColorScheme` is a **separate public entry point**, not something
 * `MaterialTheme` calls internally: wear's `MaterialTheme` takes a [ColorScheme] the caller built
 * (material3/MaterialTheme.kt:59) and the sample/app is expected to write
 * `dynamicColorScheme(context) ?: ColorScheme(...)`. That contract is preserved here, including the
 * nullable return — do not fold it into `MaterialTheme`.
 *
 * The 29 role-by-role mapping, the `_dark` resource suffixes, and the `errorDim` tone override are
 * copied verbatim from material3/DynamicColorScheme.kt:41-96; the role names line up exactly with
 * Hibari's [ColorScheme] constructor (29 slots, ColorScheme.kt:14-43), so no role is dropped.
 *
 * Deviations, both forced:
 * - `errorDim` is built with [setLuminanceTone] rather than `setLuminance`, because the 0..100
 *   Oklab-tone function upstream uses (material3/DynamicColorScheme.kt:116-125) is already taken in
 *   Hibari by a different public `Color.setLuminance` that speaks 0..1 linear-sRGB luminance
 *   (hibari-ui Color.kt:172); passing `68f` to that would clamp to 1 and paint a near-white error
 *   dim. The tone maths is ported in `ColorAppearanceModel.kt`.
 * - Upstream's `ResourceHelper` object (material3/DynamicColorScheme.kt:104-108) stays private and
 *   inline here for the same reason it is private upstream — it is not API.
 *
 * Requires API 35 (`VANILLA_ICE_CREAM`): both the `android.R.color.system_*_fixed` /
 * `system_*_dark` resource ids and the `dynamic_color_theme_enabled` global setting are newer than
 * this module's minSdk 25 floor, so the [Build.VERSION.SDK_INT] gate below is load-bearing and the
 * function returns null on anything older, exactly as upstream intends.
 */
fun dynamicColorScheme(context: Context): ColorScheme? =
    if (!isDynamicColorSchemeEnabled(context)) {
        null
    } else {
        ColorScheme(
            primary = ResourceHelper.getColor(context, android.R.color.system_primary_fixed),
            primaryDim = ResourceHelper.getColor(context, android.R.color.system_primary_fixed_dim),
            primaryContainer =
                ResourceHelper.getColor(context, android.R.color.system_primary_container_dark),
            onPrimary = ResourceHelper.getColor(context, android.R.color.system_on_primary_fixed),
            onPrimaryContainer =
                ResourceHelper.getColor(context, android.R.color.system_on_primary_container_dark),
            secondary = ResourceHelper.getColor(context, android.R.color.system_secondary_fixed),
            secondaryDim =
                ResourceHelper.getColor(context, android.R.color.system_secondary_fixed_dim),
            secondaryContainer =
                ResourceHelper.getColor(context, android.R.color.system_secondary_container_dark),
            onSecondary =
                ResourceHelper.getColor(context, android.R.color.system_on_secondary_fixed),
            onSecondaryContainer =
                ResourceHelper.getColor(
                    context,
                    android.R.color.system_on_secondary_container_dark,
                ),
            tertiary = ResourceHelper.getColor(context, android.R.color.system_tertiary_fixed),
            tertiaryDim =
                ResourceHelper.getColor(context, android.R.color.system_tertiary_fixed_dim),
            tertiaryContainer =
                ResourceHelper.getColor(context, android.R.color.system_tertiary_container_dark),
            onTertiary = ResourceHelper.getColor(context, android.R.color.system_on_tertiary_fixed),
            onTertiaryContainer =
                ResourceHelper.getColor(context, android.R.color.system_on_tertiary_container_dark),
            surfaceContainerLow =
                ResourceHelper.getColor(context, android.R.color.system_surface_container_low_dark),
            surfaceContainer =
                ResourceHelper.getColor(context, android.R.color.system_surface_container_dark),
            surfaceContainerHigh =
                ResourceHelper.getColor(
                    context,
                    android.R.color.system_surface_container_high_dark,
                ),
            onSurface = ResourceHelper.getColor(context, android.R.color.system_on_surface_dark),
            onSurfaceVariant =
                ResourceHelper.getColor(context, android.R.color.system_on_surface_variant_dark),
            outline = ResourceHelper.getColor(context, android.R.color.system_outline_dark),
            outlineVariant =
                ResourceHelper.getColor(context, android.R.color.system_outline_variant_dark),
            background = ResourceHelper.getColor(context, android.R.color.system_background_dark),
            onBackground =
                ResourceHelper.getColor(context, android.R.color.system_on_background_dark),
            error = ResourceHelper.getColor(context, android.R.color.system_error_dark),
            errorContainer =
                ResourceHelper.getColor(context, android.R.color.system_error_container_dark),
            errorDim =
                ResourceHelper.getColor(context, android.R.color.system_error_container_dark)
                    .setLuminanceTone(68f),
            onError = ResourceHelper.getColor(context, android.R.color.system_on_error_dark),
            onErrorContainer =
                ResourceHelper.getColor(context, android.R.color.system_on_error_container_dark),
        )
    }

/** Returns whether dynamic color is currently enabled on this device. Upstream: :99-102. */
private fun isDynamicColorSchemeEnabled(context: Context): Boolean =
    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) &&
        (Settings.Global.getInt(context.contentResolver, DYNAMIC_THEMING_SETTING_NAME, 0) == 1)

private object ResourceHelper {
    fun getColor(context: Context, @ColorRes id: Int): Color {
        return Color(context.resources.getColor(id, context.theme))
    }
}

private const val DYNAMIC_THEMING_SETTING_NAME = "dynamic_color_theme_enabled"
