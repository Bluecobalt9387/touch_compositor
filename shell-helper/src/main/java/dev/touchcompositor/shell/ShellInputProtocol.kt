package dev.touchcompositor.shell

import dev.touchcompositor.input.TouchAttributes

/**
 * Typed model of the fixed touch-runtime-v1 command set.
 * Transport implementations may serialize these commands, but cannot execute
 * arbitrary shell text through this API.
 */
sealed interface ShellInputCommand {
    data object Ping : ShellInputCommand
    data object Status : ShellInputCommand
    data object Capabilities : ShellInputCommand

    data class TouchDown(
        val pointerId: Int,
        val x: Float,
        val y: Float,
        val attributes: TouchAttributes = TouchAttributes(),
    ) : ShellInputCommand

    data class TouchMove(
        val pointerId: Int,
        val x: Float,
        val y: Float,
        val attributes: TouchAttributes = TouchAttributes(),
    ) : ShellInputCommand

    data class TouchUp(
        val pointerId: Int,
        val x: Float,
        val y: Float,
        val attributes: TouchAttributes = TouchAttributes(),
    ) : ShellInputCommand

    data object ReleaseAllSynthetic : ShellInputCommand
    data class StartRecording(val name: String = "latest") : ShellInputCommand
    data object StopRecording : ShellInputCommand
    data class PlayRecording(
        val name: String,
        val speed: Double = 1.0,
        val repeatCount: Int = 1,
    ) : ShellInputCommand
    data object PausePlayback : ShellInputCommand
    data object ResumePlayback : ShellInputCommand
    data object StopPlayback : ShellInputCommand
    data object Shutdown : ShellInputCommand
}
