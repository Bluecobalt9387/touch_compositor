package dev.touchcompositor.shell

/** Commands are deliberately typed; there is no execute-arbitrary-shell command. */
sealed interface ShellInputCommand {
    data object Ping : ShellInputCommand
    data object StartInputMonitor : ShellInputCommand
    data object StopInputMonitor : ShellInputCommand
    data object StartRecording : ShellInputCommand
    data object StopRecording : ShellInputCommand
    data class PlayRecording(val speed: Double = 1.0, val repeatCount: Int = 1) : ShellInputCommand
    data object PausePlayback : ShellInputCommand
    data object ResumePlayback : ShellInputCommand
    data object StopPlayback : ShellInputCommand
    data class TouchDown(val pointerId: Int, val x: Float, val y: Float) : ShellInputCommand
    data class TouchMove(val pointerId: Int, val x: Float, val y: Float) : ShellInputCommand
    data class TouchUp(val pointerId: Int, val x: Float, val y: Float) : ShellInputCommand
}
