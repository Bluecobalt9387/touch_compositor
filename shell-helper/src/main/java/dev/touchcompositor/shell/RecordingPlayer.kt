package dev.touchcompositor.shell

import dev.touchcompositor.input.PlaybackOptions
import dev.touchcompositor.input.PlaybackTarget
import dev.touchcompositor.input.TouchPlayer
import dev.touchcompositor.input.TouchRecordingCodec
import java.io.FileInputStream

object RecordingPlayer {
    fun play(
        inputPath: String,
        displayWidth: Int,
        displayHeight: Int,
        rotation: Int,
        speed: Double = 1.0,
        repeatCount: Int = 1,
        pointerIdOffset: Int = 0,
    ) = FileInputStream(inputPath).use { input ->
        val recording = TouchRecordingCodec.read(input)
        TouchPlayer().play(
            recording = recording,
            target = PlaybackTarget(displayWidth, displayHeight, rotation),
            sink = AndroidTouchFrameSink(),
            options = PlaybackOptions(speed, repeatCount, pointerIdOffset),
        )
    }
}
