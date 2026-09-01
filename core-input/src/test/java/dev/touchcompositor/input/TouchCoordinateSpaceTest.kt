package dev.touchcompositor.input

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchCoordinateSpaceTest {
    private val raw = RawTouchGeometry(
        RawAxisRange(100, 1099),
        RawAxisRange(200, 2199),
    )

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: DisplayGeometry.Point) {
        assertEquals(expectedX, actual.x, 1.1f)
        assertEquals(expectedY, actual.y, 1.1f)
    }

    @Test
    fun rawRangesHonorNonZeroMinimums() {
        val display = DisplayGeometry(1000, 2000, 0)
        assertPoint(0f, 0f, display.rawToScreen(100, 200, raw))
        assertPoint(500.0f, 1000.0f, display.rawToScreen(599, 1199, raw))
        assertPoint(999f, 1999f, display.rawToScreen(1099, 2199, raw))
    }

    @Test
    fun rotationMapsNaturalTouchIntoCurrentDisplay() {
        val centerX = 599
        val centerY = 1199
        assertPoint(500f, 1000f, DisplayGeometry(1000, 2000, 0).rawToScreen(centerX, centerY, raw))
        assertPoint(1000f, 500f, DisplayGeometry(2000, 1000, 90).rawToScreen(centerX, centerY, raw))
        assertPoint(500f, 1000f, DisplayGeometry(1000, 2000, 180).rawToScreen(centerX, centerY, raw))
        assertPoint(1000f, 500f, DisplayGeometry(2000, 1000, 270).rawToScreen(centerX, centerY, raw))
    }

    @Test
    fun rotationCornersAreUnambiguous() {
        assertPoint(0f, 0f, DisplayGeometry(1000, 2000, 0).rawToScreen(100, 200, raw))
        assertPoint(0f, 999f, DisplayGeometry(2000, 1000, 90).rawToScreen(100, 200, raw))
        assertPoint(999f, 1999f, DisplayGeometry(1000, 2000, 180).rawToScreen(100, 200, raw))
        assertPoint(1999f, 0f, DisplayGeometry(2000, 1000, 270).rawToScreen(100, 200, raw))
    }

    @Test
    fun normalizedEndpointsStayInsideDisplay() {
        val display = DisplayGeometry(1080, 2400, 0)
        assertPoint(0f, 0f, display.normalizedToScreen(0f, 0f))
        assertPoint(1079f, 2399f, display.normalizedToScreen(1f, 1f))
    }

    @Test
    fun recordingNormalizationRoundTripsScreenPixels() {
        val display = DisplayGeometry(1080, 2400, 0)
        val n = display.screenToNormalized(817f, 1333f)
        assertPoint(817f, 1333f, display.normalizedToScreen(n.x, n.y))
    }
}
