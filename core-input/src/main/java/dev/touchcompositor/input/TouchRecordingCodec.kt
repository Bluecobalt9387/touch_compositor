package dev.touchcompositor.input

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Compact deterministic format intended for local recording files. */
object TouchRecordingCodec {
    private const val MAGIC = 0x54435231 // TCR1
    const val FORMAT_VERSION = 2
    private const val MAX_FRAMES = 10_000_000
    private const val MAX_POINTERS = 32

    fun isSupported(input: InputStream): Boolean = runCatching {
        val data = DataInputStream(input)
        data.readInt() == MAGIC && data.readInt() == FORMAT_VERSION
    }.getOrDefault(false)

    fun write(recording: TouchRecording, output: OutputStream) {
        val out = DataOutputStream(BufferedOutputStream(output))
        out.writeInt(MAGIC)
        out.writeInt(recording.metadata.formatVersion)
        out.writeInt(recording.metadata.displayWidth)
        out.writeInt(recording.metadata.displayHeight)
        out.writeInt(recording.metadata.rotation)
        out.writeLong(recording.durationNanos)
        out.writeInt(recording.frames.size)
        for (frame in recording.frames) {
            out.writeLong(frame.offsetNanos)
            out.writeByte(frame.action.ordinal)
            out.writeByte(frame.changedPointerId)
            out.writeByte(frame.pointers.size)
            for (p in frame.pointers) {
                out.writeByte(p.id)
                out.writeByte(p.origin.ordinal)
                out.writeFloat(p.x)
                out.writeFloat(p.y)
                out.writeFloat(p.pressure)
                out.writeFloat(p.size)
                out.writeFloat(p.touchMajor)
                out.writeFloat(p.touchMinor)
                out.writeFloat(p.toolMajor)
                out.writeFloat(p.toolMinor)
            }
        }
        out.flush()
    }

    fun read(input: InputStream): TouchRecording {
        val data = DataInputStream(BufferedInputStream(input))
        require(data.readInt() == MAGIC) { "not a Touch Compositor recording" }
        val version = data.readInt()
        require(version == FORMAT_VERSION) { "unsupported recording version $version" }
        val metadata = RecordingMetadata(
            displayWidth = data.readInt(),
            displayHeight = data.readInt(),
            rotation = data.readInt(),
            formatVersion = version,
        )
        val duration = data.readLong()
        val frameCount = data.readInt()
        require(frameCount in 0..MAX_FRAMES) { "invalid frame count" }
        val frames = ArrayList<RecordedFrame>(frameCount)
        repeat(frameCount) {
            val offset = data.readLong()
            val actionOrdinal = data.readUnsignedByte()
            require(actionOrdinal < TouchAction.entries.size) { "invalid action" }
            val changed = data.readUnsignedByte()
            val pointerCount = data.readUnsignedByte()
            require(pointerCount in 0..MAX_POINTERS) { "invalid pointer count" }
            val pointers = ArrayList<RecordedPointer>(pointerCount)
            repeat(pointerCount) {
                val id = data.readUnsignedByte()
                val originOrdinal = data.readUnsignedByte()
                require(originOrdinal < PointerOrigin.entries.size) { "invalid pointer origin" }
                pointers += RecordedPointer(
                    id = id,
                    origin = PointerOrigin.entries[originOrdinal],
                    x = data.readFloat(),
                    y = data.readFloat(),
                    pressure = data.readFloat(),
                    size = data.readFloat(),
                    touchMajor = data.readFloat(),
                    touchMinor = data.readFloat(),
                    toolMajor = data.readFloat(),
                    toolMinor = data.readFloat(),
                )
            }
            frames += RecordedFrame(offset, TouchAction.entries[actionOrdinal], changed, pointers)
        }
        return TouchRecording(metadata, duration, frames)
    }
}
