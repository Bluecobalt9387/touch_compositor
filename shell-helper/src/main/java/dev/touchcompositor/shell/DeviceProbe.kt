package dev.touchcompositor.shell

/** Parses platform probe output independently of the privilege transport. */
object DeviceProbe {
    data class TouchscreenProbe(
        val devicePath: String,
        val rawMinX: Int,
        val rawMaxX: Int,
        val rawMinY: Int,
        val rawMaxY: Int,
        val hasPressure: Boolean,
    )

        fun parseTouchscreen(getevent: String): TouchscreenProbe? {
            data class Candidate(
                var path: String = "",
                var minX: Int? = null,
                var maxX: Int? = null,
                var minY: Int? = null,
                var maxY: Int? = null,
                var slot: Boolean = false,
                var tracking: Boolean = false,
                var direct: Boolean = false,
                var pressure: Boolean = false,
            )
            val candidates = mutableListOf<Candidate>()
            var current: Candidate? = null
            val add = Regex("^add device \\d+: (\\S+)")
            val range = Regex("min (-?\\d+), max (-?\\d+)")
            for (line in getevent.lineSequence()) {
                val match = add.find(line.trimStart())
                if (match != null) {
                    current = Candidate(path = match.groupValues[1]).also(candidates::add)
                    continue
                }
                val c = current ?: continue
                when {
                    "ABS_MT_SLOT" in line -> c.slot = true
                    "ABS_MT_TRACKING_ID" in line -> c.tracking = true
                    "ABS_MT_POSITION_X" in line -> range.find(line)?.let { m ->
                        c.minX = m.groupValues[1].toIntOrNull(); c.maxX = m.groupValues[2].toIntOrNull()
                    }
                    "ABS_MT_POSITION_Y" in line -> range.find(line)?.let { m ->
                        c.minY = m.groupValues[1].toIntOrNull(); c.maxY = m.groupValues[2].toIntOrNull()
                    }
                    "ABS_MT_PRESSURE" in line -> c.pressure = true
                    "INPUT_PROP_DIRECT" in line -> c.direct = true
                }
            }
            return candidates
                .filter {
                    it.path.startsWith("/dev/input/event") && it.slot && it.tracking && it.direct &&
                        it.minX != null && it.maxX != null && it.minY != null && it.maxY != null &&
                        it.maxX!! > it.minX!! && it.maxY!! > it.minY!!
                }
                .maxByOrNull { (it.maxX!! - it.minX!!).toLong() * (it.maxY!! - it.minY!!).toLong() }
                ?.let { TouchscreenProbe(it.path, it.minX!!, it.maxX!!, it.minY!!, it.maxY!!, it.pressure) }
        }

        fun parseWmSize(output: String): Pair<Int, Int>? {
            val regex = Regex("(?:Override|Physical) size: (\\d+)x(\\d+)")
            val values = regex.findAll(output).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
            return values.lastOrNull()
        }

        fun parseRotation(viewport: String): Int {
            val orientation = Regex("orientation\\s*=\\s*([0-3])").find(viewport)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return when (orientation and 3) { 1 -> 90; 2 -> 180; 3 -> 270; else -> 0 }
        }

}
