package dev.touchcompositor.app

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TouchLabView())
    }

    private inner class TouchLabView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 38f }
        private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f }
        private var last = "Touch the left area"
        private var cancelCount = 0
        private var targetDownCount = 0
        private var eventCount = 0
        private var activePointers = 0

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val mid = width / 2f
            canvas.drawText("REAL TOUCH / HOLD", 70f, 140f, paint)
            canvas.drawText("INJECT TARGET", mid + 70f, 140f, paint)
            canvas.drawLine(mid, 0f, mid, height.toFloat(), paint)
            canvas.drawText("last: $last", 70f, 240f, smallPaint)
            canvas.drawText("events=$eventCount  active=$activePointers", 70f, 290f, smallPaint)
            canvas.drawText("CANCEL=$cancelCount", 70f, 340f, smallPaint)
            canvas.drawText("targetDown=$targetDownCount", mid + 70f, 240f, smallPaint)
            canvas.drawText("Keep one finger moving on LEFT", 70f, height - 120f, smallPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            eventCount++
            activePointers = when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0
                MotionEvent.ACTION_POINTER_UP -> event.pointerCount - 1
                else -> event.pointerCount
            }
            val action = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> "DOWN"
                MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
                MotionEvent.ACTION_MOVE -> "MOVE"
                MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
                MotionEvent.ACTION_UP -> "UP"
                MotionEvent.ACTION_CANCEL -> "CANCEL"
                else -> event.actionMasked.toString()
            }
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelCount++
            if ((event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) &&
                event.getX(event.actionIndex) >= width / 2f
            ) targetDownCount++
            last = "$action dev=${event.deviceId} p=${event.pointerCount} x=${event.getX(event.actionIndex).toInt()} y=${event.getY(event.actionIndex).toInt()}"
            Log.i("TC_TOUCH_LAB", last + " targetDown=$targetDownCount cancel=$cancelCount")
            invalidate()
            return true
        }
    }
}
