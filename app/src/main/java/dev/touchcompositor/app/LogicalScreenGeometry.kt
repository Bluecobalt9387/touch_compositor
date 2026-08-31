package dev.touchcompositor.app

import android.view.View

/**
 * One coordinate contract for all user-authored touch geometry.
 *
 * Persisted coordinates are normalized against the full logical display. Android
 * windows and raw-trigger hit testing use global logical-screen pixels. Overlay
 * parent-frame coordinates are deliberately kept out of persisted/runtime data.
 */
class LogicalScreenGeometry(
    private val sizeProvider: () -> Pair<Int, Int>,
) {
    data class Point(val x: Float, val y: Float)
    data class NormalizedPoint(val x: Float, val y: Float)
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX: Float get() = (left + right) * 0.5f
        val centerY: Float get() = (top + bottom) * 0.5f
    }

    fun size(): Pair<Int, Int> = sizeProvider().also { (w, h) ->
        require(w > 0 && h > 0) { "logical screen size must be positive" }
    }

    fun toGlobal(x: Float, y: Float): Point {
        val (w, h) = size()
        return Point(x.coerceIn(0f, 1f) * w, y.coerceIn(0f, 1f) * h)
    }

    fun toNormalized(x: Float, y: Float): NormalizedPoint {
        val (w, h) = size()
        return NormalizedPoint(
            (x / w.toFloat()).coerceIn(0f, 1f),
            (y / h.toFloat()).coerceIn(0f, 1f),
        )
    }

    fun globalOrigin(view: View): Point? {
        if (!view.isAttachedToWindow) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Point(location[0].toFloat(), location[1].toFloat())
    }

    fun globalBounds(view: View): Rect? {
        if (view.width <= 0 || view.height <= 0) return null
        val origin = globalOrigin(view) ?: return null
        return Rect(
            origin.x,
            origin.y,
            origin.x + view.width.toFloat(),
            origin.y + view.height.toFloat(),
        )
    }

    fun normalizedCenter(view: View): NormalizedPoint? {
        val bounds = globalBounds(view) ?: return null
        return toNormalized(bounds.centerX, bounds.centerY)
    }
}
