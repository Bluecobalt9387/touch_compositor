package dev.touchcompositor.shell

import android.os.Build
import android.view.InputEvent

/** Matches the proven shell-side injection strategy used by the reference APK. */
class InputInjectorCompat {
    private val manager: Any
    private val injectMethod: java.lang.reflect.Method

    init {
        if (Build.VERSION.SDK_INT <= 33) {
            val cls = Class.forName("android.hardware.input.InputManager")
            manager = cls.getDeclaredMethod("getInstance").invoke(null)
                ?: error("InputManager.getInstance() returned null")
            injectMethod = cls.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
        } else {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            manager = cls.getDeclaredMethod("getInstance").invoke(null)
                ?: error("InputManagerGlobal.getInstance() returned null")
            injectMethod = cls.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
        }
    }

    fun inject(event: InputEvent, mode: Int = 1): Boolean =
        injectMethod.invoke(manager, event, mode) as Boolean
}
