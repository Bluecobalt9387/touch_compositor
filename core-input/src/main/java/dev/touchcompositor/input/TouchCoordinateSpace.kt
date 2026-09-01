package dev.touchcompositor.input

/** Inclusive raw axis range reported by Linux evdev. */
data class RawAxisRange(val min: Int, val max: Int) {
    init { require(max > min) { "raw axis max must be greater than min" } }

    fun normalized(value: Int): Float =
        ((value - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
}

data class RawTouchGeometry(
    val x: RawAxisRange,
    val y: RawAxisRange,
)

data class DisplayGeometry(
    val width: Int,
    val height: Int,
    /** Clockwise rotation from the touchscreen's natural orientation. */
    val rotation: Int,
) {
    init {
        require(width > 0 && height > 0) { "display size must be positive" }
        require(rotation in setOf(0, 90, 180, 270)) { "rotation must be 0/90/180/270" }
    }

    data class Point(val x: Float, val y: Float)
    data class NormalizedPoint(val x: Float, val y: Float)

    /** Convert evdev coordinates directly into current Display 0 logical-screen pixels. */
    fun rawToScreen(rawX: Int, rawY: Int, raw: RawTouchGeometry): Point {
        val u = raw.x.normalized(rawX)
        val v = raw.y.normalized(rawY)
        val normalized = when (rotation) {
            0 -> NormalizedPoint(u, v)
            90 -> NormalizedPoint(v, 1f - u)
            180 -> NormalizedPoint(1f - u, 1f - v)
            270 -> NormalizedPoint(1f - v, u)
            else -> error("unreachable")
        }
        return normalizedToScreen(normalized.x, normalized.y)
    }

    /** Normalized coordinates use the full logical display: (0,0)=top-left, (1,1)=bottom-right. */
    fun normalizedToScreen(x: Float, y: Float): Point {
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        return Point(
            (nx * width.toFloat()).coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat()),
            (ny * height.toFloat()).coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat()),
        )
    }

    /** Inverse used for recordings and persisted user geometry. */
    fun screenToNormalized(x: Float, y: Float): NormalizedPoint = NormalizedPoint(
        (x / width.toFloat()).coerceIn(0f, 1f),
        (y / height.toFloat()).coerceIn(0f, 1f),
    )
}
