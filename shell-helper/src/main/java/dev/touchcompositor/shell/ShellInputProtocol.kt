package dev.touchcompositor.shell

/** Commands are deliberately typed; there is no execute-arbitrary-shell command. */
sealed interface ShellInputCommand {
    data object Ping : ShellInputCommand
    data object StartInputMonitor : ShellInputCommand
    data object StopInputMonitor : ShellInputCommand
    data class TouchDown(val pointerId: Int, val x: Float, val y: Float) : ShellInputCommand
    data class TouchMove(val pointerId: Int, val x: Float, val y: Float) : ShellInputCommand
    data class TouchUp(val pointerId: Int, val x: Float, val y: Float) : ShellInputCommand
}
