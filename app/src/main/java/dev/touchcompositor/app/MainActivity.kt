package dev.touchcompositor.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Touch Compositor"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "ADB  ·  Input Router  ·  Multi-touch Compositor"
            textSize = 15f
        })
        root.addView(TextView(this).apply {
            text = "Milestone 0: project skeleton"
            textSize = 14f
            setPadding(0, pad, 0, 0)
        })
        setContentView(root)
    }
}
