package dev.touchcompositor.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import java.util.Locale

/** Debug-only target for verifying injected Display 0 coordinates on real devices. */
class CoordinateProbeActivity : Activity() {
    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this).apply {
            text = "READY"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 32, 36))
            gravity = android.view.Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = text
        }
        setContentView(label)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val value = String.format(
            Locale.US,
            "TOUCH action=%d raw=%.2f,%.2f local=%.2f,%.2f",
            event.actionMasked,
            event.rawX,
            event.rawY,
            event.x,
            event.y,
        )
        label.text = value
        label.contentDescription = value
        return true
    }
}
