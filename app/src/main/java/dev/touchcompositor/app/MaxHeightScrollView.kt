package dev.touchcompositor.app

import android.content.Context
import android.view.View
import android.widget.ScrollView

class MaxHeightScrollView(context: Context) : ScrollView(context) {
    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = View.MeasureSpec.makeMeasureSpec(maxHeightPx.coerceAtLeast(1), View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}
