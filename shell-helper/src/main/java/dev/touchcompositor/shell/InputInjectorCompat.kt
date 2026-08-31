package dev.touchcompositor.shell

import android.os.Build
import android.view.InputEvent

/** Shell-side InputManager injection used by both compositor and isolated macro streams. */
class InputInjectorCompat {
    private val manager: Any
    private val injectMethod: java.lang.reflect.Method

    init {
        val cls = if (Build.VERSION.SDK_INT <= 33) {
            Class.forName("android.hardware.input.InputManager")
        } else {
            Class.forName("android.hardware.input.InputManagerGlobal")
        }
        manager = cls.getDeclaredMethod("getInstance").invoke(null)
            ?: error("${cls.simpleName}.getInstance() returned null")
        injectMethod = cls.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType,
        )
    }

    fun inject(event: InputEvent, mode: Int = 1): Boolean =
        injectMethod.invoke(manager, event, mode) as Boolean
}
