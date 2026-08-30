package dev.touchcompositor.shell

/**
 * Entry point planned for app_process under uid=2000(shell).
 * InputMonitor and InputManager injection will be implemented here.
 */
object ShellInputService {
    @JvmStatic
    fun main(args: Array<String>) {
        println("touch_compositor shell helper: ready")
    }
}
