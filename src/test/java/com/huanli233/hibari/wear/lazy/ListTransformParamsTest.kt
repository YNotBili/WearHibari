package com.huanli233.hibari.wear.lazy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the ScalingLazyColumn transform curve. Pure maths, so it runs on the JVM without
 * Robolectric now that `progressFor` has a pixel-space overload.
 *
 * These assertions encode the direction that is easy to get backwards: full size in the middle,
 * shrunk at the edges.
 */
class ListTransformParamsTest {

    private val viewport = 100f
    private val params = ListTransformParams()

    private fun progressAt(top: Float, height: Float = 20f) =
        params.progressFor(top = top, height = height, viewportHeight = viewport)

    @Test
    fun `item straddling the midline is full size`() {
        // top 40 -> adjusted top 35, bottom 55, distance min(65, 55) = 55 -> fraction .55 > line .35
        assertEquals(0f, progressAt(40f), 1e-4f)
        assertEquals(1f, params.scaleFor(progressAt(40f)), 1e-4f)
        assertEquals(1f, params.alphaFor(progressAt(40f)), 1e-4f)
    }

    @Test
    fun `item flush with the top edge reaches edgeScale`() {
        // bottom == 0 after the vertical offset, so distance == 0 and the ramp is fully travelled.
        val top = viewport * params.viewportVerticalOffsetFraction - 20f
        val p = progressAt(top)
        assertEquals(1f, p, 1e-4f)
        assertEquals(params.edgeScale, params.scaleFor(p), 1e-4f)
        assertEquals(params.edgeAlpha, params.alphaFor(p), 1e-4f)
    }

    @Test
    fun `item flush with the bottom edge also reaches edgeScale`() {
        // distance is min(H - adjustedTop, bottom), so it only reaches 0 once the adjusted top has
        // itself crossed the bottom of the viewport.
        val top = viewport + viewport * params.viewportVerticalOffsetFraction
        val p = progressAt(top)
        assertEquals(1f, p, 1e-4f)
        assertEquals(params.edgeScale, params.scaleFor(p), 1e-4f)
    }

    @Test
    fun `scale falls monotonically as an item moves from midline to the top edge`() {
        var previous = Float.MIN_VALUE
        for (top in 0..40) {
            val scale = params.scaleFor(progressAt(top.toFloat()))
            assertTrue("scale rose at top=$top ($previous -> $scale)", scale >= previous)
            previous = scale
        }
    }

    @Test
    fun `transition line grows with item height between the element bounds`() {
        assertEquals(params.minTransitionArea, params.transitionLineFor(0.2f), 1e-4f)
        assertEquals(params.maxTransitionArea, params.transitionLineFor(0.6f), 1e-4f)
        assertEquals(
            (params.minTransitionArea + params.maxTransitionArea) / 2f,
            params.transitionLineFor(0.4f),
            1e-4f,
        )
    }

    @Test
    fun `item heights outside the element bounds clamp to the nearest transition area`() {
        assertEquals(params.minTransitionArea, params.transitionLineFor(0.05f), 1e-4f)
        assertEquals(params.maxTransitionArea, params.transitionLineFor(0.95f), 1e-4f)
    }

    @Test
    fun `a taller item keeps full size further toward the edge`() {
        // Both edges of a short item sit near the top, so it ramps. A tall item starting at the same
        // top reaches past the midline, so its furthest edge is far from the nearest screen edge and
        // it stays full size even though its transition *line* is wider.
        val short = params.progressFor(top = 0f, height = 20f, viewportHeight = viewport)
        val tall = params.progressFor(top = 0f, height = 60f, viewportHeight = viewport)
        assertTrue("expected the short item to ramp at all, got $short", short > 0f)
        assertTrue("short=$short tall=$tall", tall < short)
    }

    @Test
    fun `reduce motion disables the curve entirely`() {
        val still = params.copy(reduceMotion = true)
        assertEquals(0f, still.progressFor(top = 0f, height = 20f, viewportHeight = viewport), 0f)
        assertEquals(0f, still.progressFor(top = 100f, height = 20f, viewportHeight = viewport), 0f)
    }

    @Test
    fun `the viewport pad is a whole pixel, as upstream's Int resolver returns`() {
        // Upstream's default is `viewportVerticalOffsetResolver = { (it.maxHeight / 20f).toInt() }`
        // (`foundation/lazy/ScalingLazyColumn.kt:848`), so on a 193 px viewport the pad is 9 px, not
        // 9.65. That 0.65 px is what decides this item: with the integer pad its `distance` is 68 and
        // the fraction 0.3523 clears the 0.35 transition line, so it is full size; a fractional pad
        // gives 67.35, the fraction 0.3490 sits under the line, and the item starts ramping.
        val oddViewport = params.progressFor(top = 57f, height = 20f, viewportHeight = 193f)
        assertEquals(0f, oddViewport, 0f)
    }

    @Test
    fun `degenerate viewport and inverted element bounds never produce NaN`() {
        assertEquals(0f, params.progressFor(top = 10f, height = 20f, viewportHeight = 0f), 0f)
        val flat = params.copy(minElementHeight = 0.5f, maxElementHeight = 0.5f)
        assertTrue(flat.progressFor(top = 5f, height = 20f, viewportHeight = viewport).isFinite())
    }
}
