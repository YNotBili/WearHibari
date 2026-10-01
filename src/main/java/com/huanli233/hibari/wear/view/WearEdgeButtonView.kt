package com.huanli233.hibari.wear.view

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.huanli233.hibari.animation.CubicBezierEasing
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.ui.unit.dp
import com.huanli233.hibari.wear.BorderStroke
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What [com.huanli233.hibari.wear.EdgeButton] draws through: the container path, the two height
 * fades and the content window.
 *
 * @param maximumHeight `EdgeButtonSize.maximumHeight`, the height the button wants.
 * @param containerColor already resolved for `enabled`, since upstream's `colors.containerColor(enabled)`
 *   is a plain pick between two fields.
 * @param verticalPadding `EdgeButtonVerticalPadding`, which is both the gap around the button and
 *   the inset the screen circle is measured from.
 * @param contentPaddingTop `6.dp` of the `verticalContentPadding()` pair.
 * @param contentPaddingBottom `8.dp` of that same pair - deliberately unequal, because the shape is
 *   not.
 */
data class EdgeButtonGeometry(
    val maximumHeight: Dp = 56.dp,
    val containerColor: Color = Color.Unspecified,
    val border: BorderStroke? = null,
    val verticalPadding: Dp = 3.dp,
    val contentPaddingTop: Dp = 6.dp,
    val contentPaddingBottom: Dp = 8.dp,
)

/**
 * The container behind [com.huanli233.hibari.wear.EdgeButton]: a button that hugs the top or bottom
 * edge of a dial, drawn as two arcs and an ellipse segment, and faded away as it is squeezed below
 * the extra-small height.
 *
 * Ported from upstream's `ShapeHelper` (`material3/EdgeButton.kt:434-471`), `EdgeButtonShape`
 * (`:472-541`), `sizeAndOffset` (`:406-434`), `scaleAndAlignContent` (`:542-596`) and the two
 * `graphicsLayer` / `drawWithContent` fades (`:153-193`). The arithmetic - every radius, cut angle
 * and tangent - is carried over unchanged; only the carrier moved, from Compose modifier layers to
 * one `ViewGroup`'s measure and draw pass.
 *
 * # Deviations
 *
 *  - **The content mask is `BlendMode.MODULATE` from API 29 and `DST_IN` below it.** Upstream
 *    modulates, which scales alpha without touching colour; `DST_IN` additionally dims what it
 *    keeps. There is no public modulate xfermode before Q, so pre-30 dials get the dimming variant
 *    and API 30+ (Wear OS 3, the only one with the small screens this fade exists for) gets the
 *    faithful one.
 *  - **No pressed indication.** Upstream's press feedback is `indication = ripple()` (`:186`); this
 *    module drops the indication system everywhere, including the colour swap most components use
 *    for it (see [com.huanli233.hibari.wear.MaterialTheme]).
 *  - **The morph is not animated.** Upstream recomputes the outline from the live height, so a
 *    button that grows animates through the arc maths; here the height is settled at measure time,
 *    so the shape moves with it in one step.
 *  - **No `isFocusable` in `init`, deliberately.** A plain `View.requestFocus()` does need it, but
 *    both focus paths of this port — [com.huanli233.hibari.wear.requestFocusOnHierarchyActive] and
 *    [com.huanli233.hibari.wear.HierarchicalFocusRequester.requestFocus] — raise
 *    `isFocusable`/`isFocusableInTouchMode` on the view they are handing focus to. So an edge button
 *    becomes a focus target exactly when its caller declares one, as upstream's picker confirm button
 *    does with `Modifier.focusRequester(…).focusable()` (`material3/TimePicker.kt:365-369`) and no
 *    earlier; setting the flag here would make every edge button on the screen a tab stop.
 */
class WearEdgeButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var geometry: EdgeButtonGeometry = EdgeButtonGeometry()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density: Float = context.resources.displayMetrics.density

    private val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            blendMode = BlendMode.MODULATE
        } else {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
    }

    private val shapePath = Path()

    /** `ShapeHelper.contentWindow`, in px, computed for the last measured size. */
    private val contentWindow = RectF()

    private var measuredBoxWidth = 0f
    private var measuredBoxHeight = 0f
    private var contentMaskAlpha = 1f

    init {
        // The container is painted by this view rather than by a background drawable, and the mask
        // has to land on top of the children.
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun generateDefaultLayoutParams(): LayoutParams = centredLayoutParams()

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(context, attrs).apply { keepGravityIfUnspecified() }

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): LayoutParams =
        (if (p is LayoutParams) p else LayoutParams(p.width, p.height)).apply {
            keepGravityIfUnspecified()
        }

    /**
     * Upstream centres the content with `contentAlignment = Alignment.Center` (`:206-213`); a
     * `FrameLayout` has no container-level gravity, so the default is handed out per child instead.
     * A child that carries its own gravity keeps it.
     */
    private fun LayoutParams.keepGravityIfUnspecified() {
        if (gravity == Gravity.NO_GRAVITY) gravity = Gravity.CENTER
    }

    private fun centredLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val boxWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            // `.layout { ... }` takes `constraints.maxWidth` when the width is bounded and
            // `screenWidthDp().roundToPx()` when it is not (`material3/EdgeButton.kt:110-117`).
            MeasureSpec.UNSPECIFIED -> context.resources.configuration.screenWidthDp * density
            else -> MeasureSpec.getSize(widthMeasureSpec).toFloat()
        }
        val preferredHeight = geometry.maximumHeight.value * density
        val boxHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec).toFloat()
            MeasureSpec.AT_MOST ->
                preferredHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec).toFloat())
            else -> preferredHeight
        }

        updateGeometry(boxWidth, boxHeight)

        // `scaleAndAlignContent` measures its child against `Constraints()` - unbounded on both
        // axes (`:557`) - and the window constraints that `sizeAndOffset` puts on the chain ahead of
        // it never reach the child. So the content is measured exactly once, unbounded, which is
        // also what a `Row` laid out with no width limit would report.
        if (childCount > 0) {
            measureChild(
                getChildAt(0),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
        }

        setMeasuredDimension(
            boxWidth.roundToInt(),
            // `.padding(vertical = EdgeButtonVerticalPadding)` sits outside upstream's `.layout`
            // block (`material3/EdgeButton.kt:184`, the 3 dp constant at `:629`), so the node occupies
            // the button plus 3 dp top and bottom while every fade and radius keys off the button
            // height alone. A Views
            // `padding` would inset the children without shrinking what this view paints, so the
            // gap is folded into the measured height and applied as a draw offset instead.
            (boxHeight + 2f * geometry.verticalPadding.value * density).roundToInt(),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (childCount == 0) return
        val child = getChildAt(0)
        val naturalWidth = max(child.measuredWidth, 1)
        val naturalHeight = child.measuredHeight

        // `scale = (wrapperWidth / placeable.width).coerceAtMost(1f)` (`:563`), where `wrapperWidth`
        // is the content window: the coerced measure upstream's `coerceIn(exact, exact)` always
        // collapses to (`:560-561`).
        val scale = (contentWindow.width() / naturalWidth).coerceAtMost(1f)

        val topPadding = (geometry.contentPaddingTop.value * density).roundToInt()
        val bottomPadding = (geometry.contentPaddingBottom.value * density).roundToInt()

        // Both offsets are computed from the *unscaled* size, because the scale pivots on the
        // content's own horizontal centre and its top edge (`TransformOrigin(0.5f, 0f)`, `:588-591`).
        val x = contentWindow.left + (contentWindow.width() - naturalWidth) / 2f
        val y = contentWindow.top +
            (
                (
                    (
                        contentWindow.height() - naturalHeight * scale +
                            topPadding - bottomPadding
                        ) / 2f
                        )
                    .roundToInt()
                    .coerceAtLeast(topPadding)
                ).toFloat() +

            // The band the container paints in starts below the node's own top padding.
            geometry.verticalPadding.value * density

        child.pivotX = naturalWidth / 2f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
        child.layout(
            x.roundToInt(),
            y.roundToInt(),
            x.roundToInt() + naturalWidth,
            y.roundToInt() + naturalHeight,
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (measuredBoxWidth <= 0f || measuredBoxHeight <= 0f) {
            super.dispatchDraw(canvas)
            return
        }

        val pad = geometry.verticalPadding.value * density
        val canvasSave = canvas.save()
        canvas.translate(0f, pad)

        // Upstream composites the subtree offscreen before masking it (`:181-185`); a save layer is
        // the same thing on Views.
        val layer = canvas.saveLayer(0f, 0f, measuredBoxWidth, measuredBoxHeight, null)

        buildShapePath()
        containerPaint.color = geometry.containerColor.toArgb()
        canvas.drawPath(shapePath, containerPaint)

        geometry.border?.let { stroke ->
            borderPaint.color = stroke.color.toArgb()
            borderPaint.strokeWidth = stroke.width.value * density
            canvas.drawPath(shapePath, borderPaint)
        }

        super.dispatchDraw(canvas)

        val radius = max(measuredBoxWidth, measuredBoxHeight) / 2f
        // `Offset(r, size.height - r)`: the mask's circle is tangent to the bottom edge and centred
        // horizontally on the width axis (`:199-200`).
        val innerColor = (
            ((contentMaskAlpha * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0x00FFFFFF
            )
        maskPaint.shader = RadialGradient(
            radius,
            measuredBoxHeight - radius,
            radius,
            intArrayOf(innerColor, TRANSPARENT_BLACK),
            floatArrayOf(MASK_STOP, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, measuredBoxWidth, measuredBoxHeight, maskPaint)

        canvas.restoreToCount(layer)
        canvas.restoreToCount(canvasSave)
    }

    /**
     * `ShapeHelper.updateIfNeeded` (`material3/EdgeButton.kt:444-467`) plus the two fade alphas
     * (`:153-193`).
     */
    private fun updateGeometry(width: Float, height: Float) {
        measuredBoxWidth = width
        measuredBoxHeight = height

        val extraSmallHeight = EXTRA_SMALL_MAXIMUM_HEIGHT.value * density
        val extraSmallEllipsis = EXTRA_SMALL_ELLIPSIS_HEIGHT.value * density
        val bottomPadding = geometry.verticalPadding.value * density
        val targetSidePadding = TARGET_SIDE_PADDING.value * density

        // 0f at or above the extra-small height, rising to 1f as the button is squeezed to nothing;
        // it drives both the morph toward a plain rounded rectangle and the fades.
        val fadeProgress = (1f - height / extraSmallHeight).coerceAtLeast(0f)

        val ellipsisHeight = lerp(
            extraSmallEllipsis + (height - extraSmallHeight) * BUTTON_TO_ELLIPSIS_RATIO,
            height,
            fadeProgress,
        )

        val halfWidth = width / 2f
        val inset = halfWidth - bottomPadding - ellipsisHeight / 2f
        val localHalfWidth = sqrt(max(squared(halfWidth) - squared(inset), 0f))
        val sidePadding = halfWidth - localHalfWidth + targetSidePadding
        val cornerRadius = height - ellipsisHeight / 2f

        // `Rect(sidePadding, 0f, width - sidePadding, height).inset(r, 0f)` (`:465-466`): the window
        // is centred on the dial and narrowed by the corner radius, never past zero width.
        val windowHalfWidth = max(halfWidth - sidePadding - cornerRadius, 0f)
        contentWindow.set(
            halfWidth - windowHalfWidth,
            0f,
            halfWidth + windowHalfWidth,
            height,
        )

        val containerAlpha = FADE_EASING.transform(
            (height - containerFadeEndPx) / (containerFadeStartPx - containerFadeEndPx),
        ).coerceIn(0f, 1f)
        contentMaskAlpha = FADE_EASING.transform(
            (height - contentFadeEndPx) / (contentFadeStartPx - contentFadeEndPx),
        ).coerceIn(0f, 1f)

        // The whole subtree rides on one alpha, which is what upstream's `graphicsLayer` before the
        // clip and paint does (`:153-161`).
        if (alpha != containerAlpha) alpha = containerAlpha
    }

    /** `EdgeButtonShape.createOutline` (`material3/EdgeButton.kt:472-541`). */
    private fun buildShapePath() {
        val width = measuredBoxWidth
        val height = measuredBoxHeight
        val halfWidth = width / 2f

        val bottomPadding = geometry.verticalPadding.value * density
        val extraSmallHeight = EXTRA_SMALL_MAXIMUM_HEIGHT.value * density
        val extraSmallEllipsis = EXTRA_SMALL_ELLIPSIS_HEIGHT.value * density
        val fadeProgress = (1f - height / extraSmallHeight).coerceAtLeast(0f)
        val ellipsisHeight = lerp(
            extraSmallEllipsis + (height - extraSmallHeight) * BUTTON_TO_ELLIPSIS_RATIO,
            height,
            fadeProgress,
        )

        val screenRadius = halfWidth - bottomPadding
        val ellipsisCenterX = halfWidth
        val ellipsisCenterY = height - ellipsisHeight / 2f
        val ellipsisRadiusY = ellipsisHeight / 2f
        // The widest ellipse that still fits the container shrunk by the padding (`:493-495`).
        val ellipsisRadiusX = sqrt(ellipsisRadiusY * screenRadius).coerceAtMost(screenRadius)

        val cutAngle = fadeProgress * Math.PI.toFloat() / 2f
        val transitionX = ellipsisCenterX + ellipsisRadiusX * cos(cutAngle)
        val transitionY = ellipsisCenterY + ellipsisRadiusY * sin(cutAngle)

        val tangentXRaw = ellipsisRadiusX * sin(cutAngle)
        val tangentYRaw = -ellipsisRadiusY * cos(cutAngle)
        val tangentLength = sqrt(squared(tangentXRaw) + squared(tangentYRaw))
        val tangentX = tangentXRaw / tangentLength
        val tangentY = tangentYRaw / tangentLength

        val circleRadius = transitionY / (1f + tangentX)
        // `transitionPoint + tangent.rotate90ccw() * circleRadius`, and upstream's `rotate90ccw` is
        // `Offset(y, -x)` (`:543`).
        val circleCenterX = transitionX + tangentY * circleRadius
        val circleCenterY = transitionY - tangentX * circleRadius

        val sweep =
            Math.toDegrees(
                atan2((transitionY - circleCenterY).toDouble(), (transitionX - circleCenterX).toDouble()),
            ).toFloat() + 90f

        shapePath.reset()
        shapePath.moveTo(circleCenterX, circleCenterY - circleRadius)

        shapePath.arcTo(circumscribedOval(circleCenterX, circleCenterY, circleRadius), 270f, sweep, false)

        val ellipsisAngle = Math.toDegrees(cutAngle.toDouble()).toFloat()
        shapePath.arcTo(
            RectF(
                ellipsisCenterX - ellipsisRadiusX,
                height - ellipsisHeight,
                ellipsisCenterX + ellipsisRadiusX,
                height - ellipsisHeight + 2f * ellipsisRadiusY,
            ),
            ellipsisAngle,
            180f - 2f * ellipsisAngle,
            false,
        )

        // The left arc is the right one mirrored about the vertical centre line (`:529-535`).
        shapePath.arcTo(
            circumscribedOval(width - circleCenterX, circleCenterY, circleRadius),
            270f - sweep,
            sweep,
            false,
        )

        shapePath.close()
    }

    /** Compose's `Rect(center: Offset, radius: Float)` (`:516`, `:530`). */
    private fun circumscribedOval(centerX: Float, centerY: Float, radius: Float): RectF =
        RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

    private val containerFadeStartPx: Float get() = CONTAINER_FADE_START.value * density
    private val containerFadeEndPx: Float get() = CONTAINER_FADE_END.value * density
    private val contentFadeStartPx: Float get() = CONTENT_FADE_START.value * density
    private val contentFadeEndPx: Float get() = CONTENT_FADE_END.value * density

    private companion object {
        /** `EdgeButtonSize.ExtraSmall`'s `maximumHeight` (`material3/EdgeButton.kt:236`). */
        val EXTRA_SMALL_MAXIMUM_HEIGHT: Dp = 46.dp

        /** `EXTRA_SMALL_ELLIPSIS_HEIGHT` (`material3/EdgeButton.kt:489`). */
        val EXTRA_SMALL_ELLIPSIS_HEIGHT: Dp = 58.dp

        /** `TARGET_SIDE_PADDING` (`material3/EdgeButton.kt:493`). */
        val TARGET_SIDE_PADDING: Dp = 20.dp

        /** `BUTTON_TO_ELLIPSIS_RATIO` (`material3/EdgeButton.kt:495`). */
        const val BUTTON_TO_ELLIPSIS_RATIO = 1.42f

        val CONTAINER_FADE_START: Dp = 30.dp
        val CONTAINER_FADE_END: Dp = 4.dp
        val CONTENT_FADE_START: Dp = 38.dp
        val CONTENT_FADE_END: Dp = 30.dp

        /** The inner stop of upstream's radial mask, and the fully transparent outer one (`:196-203`). */
        const val MASK_STOP = 0.875f
        const val TRANSPARENT_BLACK = 0x00000000

        /** `CubicBezierEasing(0.25f, 0f, 0.75f, 1.0f)` (`material3/EdgeButton.kt:71`). */
        val FADE_EASING = CubicBezierEasing(0.25f, 0f, 0.75f, 1.0f)

        fun lerp(start: Float, stop: Float, fraction: Float): Float =
            start + (stop - start) * fraction

        fun squared(x: Float): Float = x * x
    }
}
