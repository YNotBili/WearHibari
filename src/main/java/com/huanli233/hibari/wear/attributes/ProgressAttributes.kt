package com.huanli233.hibari.wear.attributes

import android.view.View
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.ProgressSpec
import com.huanli233.hibari.wear.view.WearCircularProgressView
import com.huanli233.hibari.wear.view.WearLinearProgressView

/**
 * Push a [ProgressSpec] onto either progress view. The three colours are resolved once through
 * [com.huanli233.hibari.wear.ProgressIndicatorColors.indicatorColorFor] and its siblings, which are
 * upstream's `indicatorBrush(enabled)` / `trackBrush(enabled)` / `overflowTrackBrush(enabled)` calls
 * (`ProgressIndicator.kt:231-261`) moved out of the draw routine; the gap, the arc angles and the
 * overflow flag only exist on the circular view and the X mirror only on the linear one, so they go
 * through a type test rather than a wider interface that [WearLinearProgressView] would have to stub
 * out.
 */
fun Modifier.progressIndicatorAttrs(spec: ProgressSpec): Modifier =
    this.thenViewAttribute<View, ProgressSpec>(uniqueKey, spec) {
        val indicator = it.colors.indicatorColorFor(it.enabled)
        val track = it.colors.trackColorFor(it.enabled)
        when (this) {
            is WearCircularProgressView -> {
                progress = it.progress
                indicatorColor = indicator
                trackColor = track
                overflowTrackColor = it.colors.overflowTrackColorFor(it.enabled)
                allowProgressOverflow = it.allowProgressOverflow
                strokeWidth = it.strokeWidth
                gapSize = it.gapSize
                startAngle = it.startAngle
                endAngle = it.endAngle
                indeterminate = it.indeterminate
            }

            is WearLinearProgressView -> {
                progress = it.progress
                indicatorColor = indicator
                trackColor = track
                strokeWidth = it.strokeWidth
                flipHorizontal = it.flipHorizontal
            }
        }
    }
