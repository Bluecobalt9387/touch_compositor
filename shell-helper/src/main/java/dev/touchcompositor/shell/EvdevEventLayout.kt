package dev.touchcompositor.shell

/** The native helper's ABI controls input_event layout, independently of app_process. */
internal object EvdevEventLayout {
    fun fromReady(line: String): Int {
        check(line.startsWith("EVGRAB_READY ")) { "触摸设备打开失败：$line" }
        val size = Regex("event_size=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
        check(size == 16 || size == 24) { "不支持的触摸事件格式：$line" }
        return size!!
    }
}
