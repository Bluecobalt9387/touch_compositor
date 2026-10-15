package dev.touchcompositor.shell

import android.os.Build
import android.view.InputEvent

/** Probe API availability as vendor builds may backport framework changes. */
class InputInjectorCompat {
    private val binding: Pair<Any, java.lang.reflect.Method> = run {
        val names = if (Build.VERSION.SDK_INT >= 34) {
            listOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager")
        } else {
            listOf("android.hardware.input.InputManager", "android.hardware.input.InputManagerGlobal")
        }
        var lastError: Throwable? = null
        names.firstNotNullOfOrNull { name ->
            runCatching {
                val cls = Class.forName(name)
                val manager = cls.getDeclaredMethod("getInstance").invoke(null)
                    ?: error("$name.getInstance() returned null")
                manager to cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            }.onFailure { lastError = it }.getOrNull()
        } ?: throw IllegalStateException("当前系统未提供可用的输入注入 API", lastError)
    }

    fun inject(event: InputEvent, mode: Int = 1): Boolean =
        binding.second.invoke(binding.first, event, mode) as Boolean
}
