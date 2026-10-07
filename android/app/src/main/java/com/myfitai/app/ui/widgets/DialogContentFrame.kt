package com.myfitai.app.ui.widgets

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.R as AppCompatR

/**
 * Hosts custom dialog content and keeps room for the dialog action bar.
 *
 * The stock dialog layout measures custom content with all the height that is left, without reserving the action bar:
 * long content then squeezes the button panel, which turns into a clipped scroller and the buttons move or are cut.
 * This host offers the content the available height minus the real height of the button panel, so the content scrolls
 * inside itself, the fields stay reachable and the actions always keep their full size. Short content keeps its size.
 */
class DialogContentFrame(context: Context) : FrameLayout(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val limit = maxOf(MeasureSpec.getSize(heightMeasureSpec) - actionBarHeight(widthMeasureSpec), 0)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }

    /** Natural height of the dialog button panel (0 when the dialog has no buttons). */
    private fun actionBarHeight(widthMeasureSpec: Int): Int {
        val panel = rootView.findViewById<View>(AppCompatR.id.buttonPanel) ?: return 0
        if (panel.visibility == GONE) return 0
        panel.measure(
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return panel.measuredHeight
    }
}
