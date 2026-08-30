package dev.touchcompositor.input

/** Routes physical touch independently from overlay rendering. */
class TouchRouter {
    private val rules = mutableListOf<TouchRule>()

    fun addRule(rule: TouchRule) { rules += rule }
    fun clear() = rules.clear()

    fun route(point: TouchPoint): RouteDecision =
        if (rules.any { it.onTouch(point) == RouteDecision.CONSUME }) {
            RouteDecision.CONSUME
        } else {
            RouteDecision.PASS
        }
}
