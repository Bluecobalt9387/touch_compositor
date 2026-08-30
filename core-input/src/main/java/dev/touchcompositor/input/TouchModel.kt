package dev.touchcompositor.input

enum class TouchPhase { DOWN, MOVE, UP }

data class TouchPoint(
    val id: Int,
    val x: Float,
    val y: Float,
    val phase: TouchPhase,
)

enum class RouteDecision { PASS, CONSUME }

fun interface TouchRule {
    fun onTouch(point: TouchPoint): RouteDecision
}
