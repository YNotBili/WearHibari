package com.huanli233.hibari.wear.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.animation.TimeInterpolator
import android.view.View
import com.huanli233.hibari.animation.AnimationSpec
import com.huanli233.hibari.animation.SnapSpec
import com.huanli233.hibari.animation.TweenSpec
import com.huanli233.hibari.ui.graphics.Color
import com.huanli233.hibari.ui.unit.Dp
import com.huanli233.hibari.wear.PageIndicatorSpec
import com.huanli233.hibari.wear.ScrollIndicatorSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The dots of `PageIndicatorImpl`, laid out on the same circular arc Compose uses.
 *
 * Deviations, mechanical rather than visual:
 *  - Upstream's `Modifier.padding(edgePadding).size(boundsSize)` shrinks a Canvas inside a bigger
 *    box. A `View` cannot inset its own drawing area that way without clipping the dots, so
 *    [PageIndicatorSpec.boxSize] carries `boundsSize + 2 * edgePadding` and the arc is centred in
 *    the view; with symmetric padding the two centres are the same point.
 *  - [PagesState] is rebuilt when `pageCount` changes, which is upstream's `remember(pageCount)` key.
 *  - Nothing animates here on purpose: upstream animates nothing either, it re-reads
 *    `currentPageOffsetFraction` from the pager every frame while the user drags.
 *  - Two guards upstream does not have: an empty pager and a non-positive arc radius draw nothing.
 *    With `pageCount == 0` Compose still leaves a stroke-width dot of background colour on screen
 *    (a zero-length arc with a round cap); that is not a state worth reproducing, and the box it
 *    would land in is zero-sized anyway.
 */
class WearPageIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcBounds = RectF()

    private var pagesState: PagesState? = null

    var spec: PageIndicatorSpec? = null
        set(value) {
            field = value
            val s = value
            if (s != null) {
                val pages = pagesState
                if (pages == null || pages.totalPages != s.pageCount) {
                    pagesState = PagesState(
                        totalPages = s.pageCount,
                        pagesOnScreen = s.pagesOnScreen,
                        smallIndicatorSizeFraction = SmallIndicatorSizeFraction,
                        shrinkThresholdStart = s.spacing / (s.spacing + s.indicatorSize) / 4,
                        shrinkThresholdEnd =
                            (s.spacing / 2 + s.indicatorSize) / (s.spacing + s.indicatorSize) / 2,
                    )
                }
            }
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val box = spec?.boxSize
        val w = box?.width?.let { it.indicatorPx(density).roundToInt() } ?: 0
        val h = box?.height?.let { it.indicatorPx(density).roundToInt() } ?: 0
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val pages = pagesState ?: return
        if (s.pagesOnScreen <= 0) return

        val density = resources.displayMetrics.density
        val isRtl = layoutDirection == View.LAYOUT_DIRECTION_RTL

        val screenWidthPx = s.screenWidth.indicatorPx(density)
        // Upstream rounds the dot size but not the pitch, copied exactly.
        val indicatorSizePx = s.indicatorSize.indicatorPx(density).roundToInt().toFloat()
        val spacerSizePx =
            if (s.pagesOnScreen > 1) (s.indicatorSize + s.spacing).indicatorPx(density) else 0f
        val backgroundStrokeWidthPx = s.backgroundRadius.indicatorPx(density) * 2 + indicatorSizePx
        val arcRadius =
            (screenWidthPx - backgroundStrokeWidthPx) / 2 - s.edgePadding.indicatorPx(density)
        if (arcRadius <= 0f) return

        // The offset fraction is folded into page space, and a value within 0.001 of the last page
        // counts as being on it: the index drops by one so the fraction lands on 1 rather than on
        // the non-existent next page.
        val currentPageOffsetWithFraction = s.currentPage + s.currentPageOffsetFraction
        val isLastPage = withinTolerance(currentPageOffsetWithFraction, s.pageCount - 1f, 0.001f)
        val selectedPage =
            if (isLastPage) currentPageOffsetWithFraction.toInt() - 1
            else currentPageOffsetWithFraction.toInt()
        val offset = currentPageOffsetWithFraction - selectedPage

        if (pages.totalPages > 1) pages.recalculateState(selectedPage, offset)

        // The dots sit on a circle of `arcRadius`; where its centre falls depends on the axis and,
        // vertically, on the layout direction.
        val centerX = width / 2f
        val centerY = height / 2f
        val arcCenterX: Float
        val arcCenterY: Float
        if (s.isHorizontal) {
            arcCenterX = centerX
            arcCenterY = centerY - arcRadius
        } else {
            arcCenterX = if (isRtl) backgroundStrokeWidthPx / 2 + arcRadius else centerX - arcRadius
            arcCenterY = centerY
        }

        val indicatorLength = spacerSizePx * (s.pagesOnScreen - 1)
        val indicatorLengthAngle = indicatorLength / arcRadius

        val anchor = when {
            s.isHorizontal -> HorizontalPagerAnchor
            !isRtl -> VerticalPagerAnchor
            else -> VerticalPagerRtlAnchor
        }

        // The run is centred on the anchor, so it starts half its own angle before it.
        val startAngle = rotateBy(anchor, -indicatorLengthAngle / 2, s.isHorizontal, isRtl)
        val endAngle = rotateBy(anchor, indicatorLengthAngle / 2, s.isHorizontal, isRtl)

        if (s.pagesOnScreen == 1) {
            // A single page is drawn as plain circles: the arc degenerates into b/291753164 artefacts.
            drawCircleAtAngle(
                canvas, startAngle, arcRadius, backgroundStrokeWidthPx / 2, s.backgroundColor, arcCenterX, arcCenterY,
            )
            drawCircleAtAngle(
                canvas, startAngle, arcRadius, indicatorSizePx / 2, s.selectedColor, arcCenterX, arcCenterY,
            )
        } else {
            drawIndicatorArcBackground(
                canvas, arcRadius, startAngle, endAngle, backgroundStrokeWidthPx, s.backgroundColor, arcCenterX, arcCenterY,
            )
            drawIndicators(
                canvas, s, pages, arcRadius, startAngle, indicatorSizePx, spacerSizePx, offset, isRtl, arcCenterX, arcCenterY,
            )
        }
    }

    private fun drawIndicators(
        canvas: Canvas,
        s: PageIndicatorSpec,
        pages: PagesState,
        arcRadius: Float,
        startAngle: Float,
        indicatorSizePx: Float,
        spacerSizePx: Float,
        offset: Float,
        isRtl: Boolean,
        arcCenterX: Float,
        arcCenterY: Float,
    ) {
        val spacerAngle = spacerSizePx / arcRadius
        var angle = rotateBy(startAngle, -spacerAngle, s.isHorizontal, isRtl)
        for (page in 0 until pages.pagesOnScreen + 1) {
            if (page == pages.visibleDotIndex) {
                drawSelectedIndicatorArc(
                    canvas, arcRadius, angle, spacerAngle, offset, s.selectedColor, indicatorSizePx,
                    s.isHorizontal, isRtl, arcCenterX, arcCenterY,
                )
            }
            // The spacer ratio shrinks or grows the pitch, which is what makes the run slide.
            angle = rotateBy(angle, spacerAngle * pages.spacersSizeRatio[page], s.isHorizontal, isRtl)
            drawIndicator(
                canvas, angle, page, arcRadius, pages, s.unselectedColor, indicatorSizePx, arcCenterX, arcCenterY,
            )
        }
    }

    /**
     * The selected page as a short arc reaching from the dot being left to the one being entered,
     * weighted by how far into the swipe [offset] has got.
     */
    private fun drawSelectedIndicatorArc(
        canvas: Canvas,
        arcRadius: Float,
        angle: Float,
        spacerAngle: Float,
        offset: Float,
        color: Color,
        indicatorSizePx: Float,
        isHorizontal: Boolean,
        isRtl: Boolean,
        arcCenterX: Float,
        arcCenterY: Float,
    ) {
        val startWeight = (1 - offset * 2).coerceAtLeast(0f)
        val endWeight = (offset * 2 - 1).coerceAtLeast(0f)
        val blurbWeight = (1 - startWeight - endWeight).coerceAtLeast(0.01f)

        val arcStart = rotateBy(
            rotateBy(angle, spacerAngle, isHorizontal, isRtl),
            spacerAngle * endWeight,
            isHorizontal,
            isRtl,
        )
        val sweep = rotateBy(0f, spacerAngle * blurbWeight, isHorizontal, isRtl)

        strokePaint.color = color.toArgb()
        strokePaint.strokeWidth = indicatorSizePx
        arcBounds.set(
            arcCenterX - arcRadius, arcCenterY - arcRadius, arcCenterX + arcRadius, arcCenterY + arcRadius,
        )
        // A negative sweep, which the horizontal direction produces, runs counter-clockwise in
        // Canvas.drawArc exactly as it does in Compose's drawArc.
        canvas.drawArc(arcBounds, arcStart.toDegreesLocal(), sweep.toDegreesLocal(), false, strokePaint)
    }

    private fun drawIndicatorArcBackground(
        canvas: Canvas,
        radius: Float,
        startAngle: Float,
        endAngle: Float,
        strokeWidthPx: Float,
        color: Color,
        arcCenterX: Float,
        arcCenterY: Float,
    ) {
        val sweepAngle = endAngle - startAngle
        val signValue = if (sweepAngle < 0f) -1f else 1f
        // Magnitudes below 0.2f artefact b/291753164, so the value is floored and the sign kept.
        val sweepAngleDeg = signValue * abs(sweepAngle.toDegreesLocal()).coerceAtLeast(0.2f)

        strokePaint.color = color.toArgb()
        strokePaint.strokeWidth = strokeWidthPx
        arcBounds.set(arcCenterX - radius, arcCenterY - radius, arcCenterX + radius, arcCenterY + radius)
        canvas.drawArc(arcBounds, startAngle.toDegreesLocal(), sweepAngleDeg, false, strokePaint)
    }

    private fun drawIndicator(
        canvas: Canvas,
        angle: Float,
        page: Int,
        arcRadius: Float,
        pages: PagesState,
        color: Color,
        indicatorSizePx: Float,
        arcCenterX: Float,
        arcCenterY: Float,
    ) {
        drawCircleAtAngle(
            canvas = canvas,
            angle = angle,
            arcRadius = arcRadius,
            circleRadius = indicatorSizePx / 2f * pages.indicatorsSizeRatio[page],
            color = color.copy(alpha = color.alpha * pages.indicatorsAlpha[page]),
            arcCenterX = arcCenterX,
            arcCenterY = arcCenterY,
        )
    }

    /** A dot of [circleRadius] on the circumference of the arc, at [angle] radians. */
    private fun drawCircleAtAngle(
        canvas: Canvas,
        angle: Float,
        arcRadius: Float,
        circleRadius: Float,
        color: Color,
        arcCenterX: Float,
        arcCenterY: Float,
    ) {
        if (circleRadius <= 0f) return
        fillPaint.color = color.toArgb()
        canvas.drawCircle(
            arcCenterX + arcRadius * cos(angle),
            arcCenterY + arcRadius * sin(angle),
            circleRadius,
            fillPaint,
        )
    }

    /**
     * Alpha and size of every dot, and of the spacers between them, for a page and swipe offset.
     * Copied from upstream's private `PagesState`.
     */
    private class PagesState(
        val totalPages: Int,
        val pagesOnScreen: Int,
        val smallIndicatorSizeFraction: Float,
        val shrinkThresholdStart: Float,
        val shrinkThresholdEnd: Float,
    ) {
        private val dotsCount = pagesOnScreen + 1
        private val spacersCount = pagesOnScreen + 2

        private var smoothProgress = 0f

        // How many pages are hidden to the left.
        private var hiddenPagesToTheLeft = 0

        // The dot currently under the selection.
        var visibleDotIndex = 0
            private set

        val indicatorsAlpha = FloatArray(dotsCount)
        val indicatorsSizeRatio = FloatArray(dotsCount)
        val spacersSizeRatio = FloatArray(spacersCount)

        fun recalculateState(selectedPage: Int, offset: Float) {
            val pageWithOffset = selectedPage + offset

            // The window of dots slides once the selection passes the second-to-last visible slot,
            // and never slides past the last page.
            if (selectedPage > hiddenPagesToTheLeft + pagesOnScreen - 2) {
                hiddenPagesToTheLeft =
                    (selectedPage - (pagesOnScreen - 2)).coerceAtMost(totalPages - pagesOnScreen)
            } else if (pageWithOffset <= hiddenPagesToTheLeft) {
                hiddenPagesToTheLeft = (selectedPage - 1).coerceAtLeast(0)
            }

            // A smooth slide only starts when more than two pages remain beyond the edge dot:
            // o O O O X o
            val scrolledToTheRight =
                pageWithOffset > hiddenPagesToTheLeft + pagesOnScreen - 2 &&
                    pageWithOffset < totalPages - 2

            val scrolledToTheLeft = pageWithOffset > 1 && pageWithOffset < hiddenPagesToTheLeft + 1

            smoothProgress = if (scrolledToTheLeft || scrolledToTheRight) offset else 0f

            for (i in indicatorsAlpha.indices) {
                indicatorsAlpha[i] = when (i) {
                    0 -> 1 - smoothProgress
                    dotsCount - 1 -> smoothProgress
                    else -> 1f
                }
            }

            for (i in spacersSizeRatio.indices) {
                spacersSizeRatio[i] = when (i) {
                    0 -> 1 - smoothProgress
                    spacersCount - 1 -> smoothProgress
                    else -> 1f
                }
            }

            for (i in indicatorsSizeRatio.indices) {
                indicatorsSizeRatio[i] = when (i) {
                    0 -> {
                        if (
                            hiddenPagesToTheLeft == 0 ||
                            hiddenPagesToTheLeft == 1 && scrolledToTheLeft
                        ) {
                            1 - smoothProgress
                        } else {
                            smallIndicatorSizeFraction * (1 - smoothProgress)
                        }
                    }
                    1 -> 1 - (1 - smallIndicatorSizeFraction) * smoothProgress
                    dotsCount - 2 -> {
                        if (scrolledToTheRight || scrolledToTheLeft) {
                            indicatorLerp(smallIndicatorSizeFraction, 1f, smoothProgress)
                        } else if (hiddenPagesToTheLeft < totalPages - pagesOnScreen) {
                            smallIndicatorSizeFraction
                        } else {
                            1f
                        }
                    }
                    dotsCount - 1 -> {
                        if (
                            hiddenPagesToTheLeft == totalPages - pagesOnScreen - 1 &&
                            scrolledToTheRight ||
                            hiddenPagesToTheLeft == totalPages - pagesOnScreen &&
                            scrolledToTheLeft
                        ) {
                            smoothProgress
                        } else {
                            smallIndicatorSizeFraction * smoothProgress
                        }
                    }
                    else -> 1f
                }
            }

            // Sliding left inserts a dot at the front, which pins the selection at index 1 while it
            // happens.
            visibleDotIndex =
                (if (scrolledToTheLeft) 1 else selectedPage - hiddenPagesToTheLeft).coerceAtLeast(0)

            calculateAdjacentDotParameters(offset)
        }

        /** Size, spacer and alpha of the two dots either side of the selected one. */
        private fun calculateAdjacentDotParameters(offset: Float) {
            val shrinkFractionPrev =
                indicatorInverseLerp(1 - shrinkThresholdStart, 1 - shrinkThresholdEnd, offset)
            val shrinkFractionNext = indicatorInverseLerp(shrinkThresholdStart, shrinkThresholdEnd, offset)

            indicatorsSizeRatio[visibleDotIndex] *= (1 - shrinkFractionPrev)
            indicatorsSizeRatio[visibleDotIndex + 1] *= (1 - shrinkFractionNext)

            // One more spacer than dots, so [visibleDotIndex] is the spacer before the selected dot
            // and [visibleDotIndex + 2] the one after it.
            spacersSizeRatio[visibleDotIndex] = 1 - shrinkFractionPrev / 3
            spacersSizeRatio[visibleDotIndex + 1] = 1 + shrinkFractionNext / 3 + shrinkFractionPrev / 3
            spacersSizeRatio[visibleDotIndex + 2] = 1 - shrinkFractionNext / 3

            indicatorsAlpha[visibleDotIndex] *=
                indicatorInverseLerp(0f, 0.5f, indicatorsSizeRatio[visibleDotIndex])
            indicatorsAlpha[visibleDotIndex + 1] *=
                indicatorInverseLerp(0f, 0.5f, indicatorsSizeRatio[visibleDotIndex + 1])
        }
    }

    private companion object {
        const val SmallIndicatorSizeFraction = 0.66f

        // 0 degrees is the 3 o'clock position, at the right of the screen.
        val VerticalPagerAnchor: Float = 0f.toRadiansLocal()

        // 180 degrees is the 9 o'clock position, at the left of the screen.
        val VerticalPagerRtlAnchor: Float = 180f.toRadiansLocal()

        // 90 degrees is the 6 o'clock position, at the bottom of the screen.
        val HorizontalPagerAnchor: Float = 90f.toRadiansLocal()

        /**
         * The direction the run of dots is laid out in: horizontally it moves counter-clockwise in
         * LTR and clockwise in RTL, vertically the two swap. Upstream builds one of four closures
         * for this; the branch is the same rule.
         */
        fun rotateBy(base: Float, angle: Float, isHorizontal: Boolean, isRtl: Boolean): Float =
            if (isHorizontal != isRtl) base - angle else base + angle
    }
}

/**
 * The scroll thumb of `ScrollIndicator`: three arcs — leading track, thumb, trailing track — on the
 * circle a watch bezel describes. On a square screen the radius is the screen width, so the same
 * maths reads as a straight bar.
 *
 * Deviations:
 *  - Upstream pipes `positionFraction`/`sizeFraction` through two `Animatable`s driven by a
 *    `snapshotFlow`. Here [spec] carries the targets and a [ValueAnimator] chases them, following
 *    the `ContainerDrawable` precedent in this module. Only [TweenSpec] and [SnapSpec] are honoured;
 *    any other spec snaps, because a `ValueAnimator` needs a fixed duration to schedule against.
 *  - The `DisplayState` throttle (one redraw per travelled pixel) is gone: there is no snapshot flow
 *    to coalesce, and the animator already runs per frame.
 *  - `jiggleAmount` is absent. Upstream only ever adds it to the position target from its overscroll
 *    effect, which Hibari does not have, so it is a constant 0f there.
 */
class WearScrollIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arcBounds = RectF()
    private var animator: ValueAnimator? = null

    /** On screen right now; it chases [ScrollIndicatorSpec]'s fractions through the animation spec. */
    private var drawnPosition = 0f
    private var drawnSize = 0f

    // Upstream skips the first position animation, because both animatables start at 0, and ignores
    // an all-zero first reading, which is an uninitialised layout rather than a real position.
    private var skipFirstPositionAnimation = true
    private var skipUninitialisedData = true

    var spec: ScrollIndicatorSpec? = null
        set(value) {
            field = value
            if (value == null) {
                invalidate()
            } else {
                // Clamped on the way in: upstream only documents the 0..1 range, it does not enforce
                // it, and an out-of-range fraction would draw a mirrored thumb.
                animateToFractions(
                    position = value.positionFraction.coerceIn(0f, 1f),
                    size = value.sizeFraction.coerceIn(0f, 1f),
                    animationSpec = value.positionAnimationSpec,
                )
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val box = spec?.boxSize
        val w = box?.width?.let { it.indicatorPx(density).roundToInt() } ?: 0
        val h = box?.height?.let { it.indicatorPx(density).roundToInt() } ?: 0
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val density = resources.displayMetrics.density

        val indicatorHeightPx = s.indicatorHeight.indicatorPx(density)
        val diameterPx = s.screenWidth.indicatorPx(density)
        val paddingHorizontalPx = s.paddingHorizontal.indicatorPx(density)
        val indicatorWidthPx = s.indicatorWidth.indicatorPx(density)
        val gapHeightPx = s.gapHeight.indicatorPx(density)

        val usableRadius = diameterPx / 2f - paddingHorizontalPx
        val arcRadius = usableRadius - indicatorWidthPx / 2f
        if (usableRadius <= 0f) return

        val gapPadding = pixelsHeightToDegrees(indicatorWidthPx + gapHeightPx, usableRadius)
        val sweepDegrees = pixelsHeightToDegrees(indicatorHeightPx, usableRadius) + gapPadding

        // TODO(b/360358568) upstream: lefty mode is not taken into account either.
        val indicatorOnTheRight = layoutDirection != View.LAYOUT_DIRECTION_RTL

        // On the left of a round screen the arc is mirrored about the centre, so the direction flips.
        val actualReverseDirection =
            if (!indicatorOnTheRight) !s.reverseDirection else s.reverseDirection
        val indicatorPosition =
            if (actualReverseDirection) 1 - drawnPosition else drawnPosition
        // Position 0 aligns the thumb with the start of the track, 1 with the end.
        val indicatorStart = indicatorPosition * (1 - drawnSize)

        drawCurvedIndicator(
            canvas = canvas,
            diameter = diameterPx,
            color = s.indicatorColor,
            background = s.trackColor,
            paddingHorizontalPx = paddingHorizontalPx,
            indicatorOnTheRight = indicatorOnTheRight,
            indicatorWidthPx = indicatorWidthPx,
            indicatorStart = indicatorStart,
            indicatorSize = drawnSize,
            gapPadding = gapPadding,
            arcRadius = arcRadius,
            sweepDegrees = sweepDegrees,
        )
    }

    private fun drawCurvedIndicator(
        canvas: Canvas,
        diameter: Float,
        color: Color,
        background: Color,
        paddingHorizontalPx: Float,
        indicatorOnTheRight: Boolean,
        indicatorWidthPx: Float,
        indicatorStart: Float,
        indicatorSize: Float,
        gapPadding: Float,
        arcRadius: Float,
        sweepDegrees: Float,
    ) {
        val arcSize = diameter - 2 * paddingHorizontalPx - indicatorWidthPx
        val arcLeft = indicatorWidthPx / 2f +
            if (indicatorOnTheRight) width - diameter + paddingHorizontalPx else paddingHorizontalPx
        val arcTop = (height - diameter) / 2f + paddingHorizontalPx + indicatorWidthPx / 2f

        val startAngleOffset = if (indicatorOnTheRight) 0f else 180f

        val startTopArc = startAngleOffset - sweepDegrees / 2
        val sweepTopArc = sweepDegrees * indicatorStart
        val startMidArc = startTopArc + sweepTopArc
        val sweepMidArc = sweepDegrees * indicatorSize
        val startBottomArc = startMidArc + sweepMidArc
        val sweepBottomArc = sweepDegrees * (1 - indicatorSize - indicatorStart)

        drawCurvedIndicatorSegment(
            canvas, startTopArc, max(sweepTopArc, 0f), arcRadius, background,
            arcLeft, arcTop, arcSize, indicatorWidthPx, gapPadding,
        )
        drawCurvedIndicatorSegment(
            canvas, startMidArc, max(sweepMidArc, 0f), arcRadius, color,
            arcLeft, arcTop, arcSize, indicatorWidthPx, gapPadding,
        )
        drawCurvedIndicatorSegment(
            canvas, startBottomArc, max(sweepBottomArc, 0f), arcRadius, background,
            arcLeft, arcTop, arcSize, indicatorWidthPx, gapPadding,
        )
    }

    /** A segment narrower than its own end gap collapses into a dot that shrinks and fades. */
    private fun drawCurvedIndicatorSegment(
        canvas: Canvas,
        startAngle: Float,
        sweep: Float,
        radius: Float,
        color: Color,
        arcLeft: Float,
        arcTop: Float,
        arcSize: Float,
        indicatorWidthPx: Float,
        gapSweep: Float,
    ) {
        if (sweep <= gapSweep) {
            val angle = (startAngle + sweep / 2f).toRadiansLocal()
            val indicatorRadiusFraction = indicatorInverseLerp(0f, gapSweep, sweep)
            val indicatorRadius = indicatorLerp(0f, indicatorWidthPx / 2, indicatorRadiusFraction)
            fillPaint.color = color.copy(alpha = color.alpha * indicatorRadiusFraction).toArgb()
            canvas.drawCircle(
                arcLeft + radius + radius * cos(angle),
                arcTop + radius + radius * sin(angle),
                indicatorRadius,
                fillPaint,
            )
        } else {
            strokePaint.color = color.toArgb()
            strokePaint.strokeWidth = indicatorWidthPx
            arcBounds.set(arcLeft, arcTop, arcLeft + arcSize, arcTop + arcSize)
            canvas.drawArc(arcBounds, startAngle + gapSweep / 2, max(sweep - gapSweep, 0f), false, strokePaint)
        }
    }

    /**
     * What upstream's `snapshotFlow` collection does per emission: skip the uninitialised pair, snap
     * the first real one, animate every one after that under [animationSpec].
     */
    private fun animateToFractions(position: Float, size: Float, animationSpec: AnimationSpec<Float>) {
        if (skipUninitialisedData && size == 0f && position == 0f) {
            skipUninitialisedData = false
            return
        }

        if (drawnPosition == position && drawnSize == size) {
            animator?.cancel()
            animator = null
            invalidate()
            return
        }

        // Anything but a tween has no duration a ValueAnimator can schedule against.
        val tween = animationSpec as? TweenSpec<*>
        if (animationSpec is SnapSpec<*> || tween == null || tween.durationMillis <= 0 ||
            skipFirstPositionAnimation
        ) {
            skipFirstPositionAnimation = false
            animator?.cancel()
            animator = null
            drawnPosition = position
            drawnSize = size
            invalidate()
            return
        }

        val fromPosition = drawnPosition
        val fromSize = drawnSize
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = tween.durationMillis.toLong()
            startDelay = tween.delay.toLong()
            interpolator = TimeInterpolator { input -> tween.easing.transform(input) }
            addUpdateListener {
                val f = it.animatedValue as Float
                drawnPosition = fromPosition + (position - fromPosition) * f
                drawnSize = fromSize + (size - fromSize) * f
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}

/** `Dp.toPx` against an explicit density; the views already hold one. */
private fun Dp.indicatorPx(density: Float): Float = value * density

private fun Float.toRadiansLocal(): Float = this * PI.toFloat() / 180f

private fun Float.toDegreesLocal(): Float = this * 180f / PI.toFloat()

/** `Float.equalsWithTolerance` from material3's ProgressIndicator.kt. */
private fun withinTolerance(value: Float, number: Float, tolerance: Float): Boolean =
    abs(value - number) < tolerance

/** The angle, in degrees, that a [heightInPixels] tall chord subtends at [radius]. */
private fun pixelsHeightToDegrees(heightInPixels: Float, radius: Float): Float =
    (2 * asin(heightInPixels / 2 / radius)).toDegreesLocal()

private fun indicatorLerp(from: Float, to: Float, fraction: Float): Float =
    from + (to - from) * fraction

/** `inverseLerp` as androidx.wear.compose.foundation.lazy defines it. */
private fun indicatorInverseLerp(start: Float, stop: Float, value: Float): Float =
    ((value - start) / (stop - start)).coerceIn(0f, 1f)
