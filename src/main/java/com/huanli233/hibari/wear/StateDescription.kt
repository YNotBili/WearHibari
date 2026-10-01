package com.huanli233.hibari.wear

import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.huanli233.hibari.ui.Modifier
import com.huanli233.hibari.ui.thenViewAttribute
import com.huanli233.hibari.ui.uniqueKey

/**
 * The `semantics { stateDescription = … }` that upstream puts on its labelled selection controls,
 * shared by the two components that carry one: [CheckboxButton]'s rows — root at
 * `material3/CheckboxButton.kt:187-190`, toggle section at `:412`, values picked at `:145-150` and
 * `:312-317` — and [SwitchButton]'s rows — root at `material3/SwitchButton.kt:199`, toggle section at
 * `:434`, values picked at `:147-151` and `:333-337`. Both read the string through the four keys
 * `internal/Strings.kt:110-119` name, which this module ships verbatim (`res/values/strings.xml`).
 *
 * The node is reached through a [View.AccessibilityDelegate] rather than a setter on the view because
 * the hosts differ per component — `WearCheckboxButtonRow`, a plain `FrameLayout` for a split section,
 * the box that holds a `WearSwitchButtonView` — and only the delegate fits all of them;
 * [cardLongClickable] reaches its action label the same way. The write itself is
 * `AccessibilityNodeInfo.setStateDescription`, which the platform's own `data/api-versions.xml`
 * records as `since="30"` — so on this module's minSdk of 25 there is no channel for the string at
 * all. The gate is that fact, not a preference: below API 30 the node keeps whatever the platform
 * derives from the row. A pre-R device is the honest gap; on R and later the value is upstream's.
 *
 * The compared value is the boolean, so the delegate is replaced exactly when the state moves and
 * never on a frame — upstream re-runs the `getString` on every recomposition, which is also why a
 * locale change alone does not re-resolve the text here: it takes a state flip or a recreated view.
 * Emitted unconditionally, including below API 30, because an attribute that left the chain on those
 * devices would leave a delegate installed on the one view that ever had it.
 */
internal fun Modifier.wearStateDescription(
    checked: Boolean,
    checkedDescription: Int,
    uncheckedDescription: Int,
): Modifier = this.thenViewAttribute<View, Boolean>(uniqueKey, checked) { isChecked ->
    accessibilityDelegate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        StateDescriptionDelegate(
            context.getString(if (isChecked) checkedDescription else uncheckedDescription)
        )
    } else {
        null
    }
}

/**
 * Writes [AccessibilityNodeInfo.stateDescription]; see [wearStateDescription]. Annotated because the
 * only construction site is inside an `SDK_INT >= R` branch, which is what lets the write through the
 * platform check.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class StateDescriptionDelegate(private val description: String) :
    View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.stateDescription = description
    }
}
