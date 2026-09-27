package com.huanli233.hibari.wear.attributes

import android.view.View
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey
import com.huanli233.hibari.wear.ProgressSpec
import com.huanli233.hibari.wear.view.WearCircularProgressView
import com.huanli233.hibari.wear.view.WearLinearProgressView

/**
 * Push a [ProgressSpec] onto either progress view. The common four properties are set through the
 * shared [applyCommon]; the arc-only ones need the circular view, so they go through a type test
 * rather than a wider interface that [WearLinearProgressView] would have to stub out.
 */
fun Modifier.progressIndicatorAttrs(spec: ProgressSpec): Modifier =
    this.thenViewAttribute<View, ProgressSpec>(uniqueKey, spec) {
        val indicator = if (it.enabled) it.colors.indicatorColor else it.colors.disabledIndicatorColor
        val track = if (it.enabled) it.colors.trackColor else it.colors.disabledTrackColor
        when (this) {
            is WearCircularProgressView -> {
                progress = it.progress
                indicatorColor = indicator
                trackColor = track
                strokeWidth = it.strokeWidth
                startAngle = it.startAngle
                endAngle = it.endAngle
                indeterminate = it.indeterminate
            }

            is WearLinearProgressView -> {
                progress = it.progress
                indicatorColor = indicator
                trackColor = track
                strokeWidth = it.strokeWidth
            }
        }
    }
