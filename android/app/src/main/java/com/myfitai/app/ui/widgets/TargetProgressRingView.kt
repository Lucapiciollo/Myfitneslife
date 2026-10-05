package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.myfitai.app.R

/** Reusable determinate ring for values that already have an explicit target/range. */
class TargetProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val indicator: CircularProgressIndicator

    init {
        inflate(context, R.layout.view_target_progress_ring, this)
        indicator = findViewById(R.id.targetProgressIndicator)
    }

    /** `maximum` is the caller's explicit target; [CircularProgressIndicator] renders a normalized percent. */
    fun setProgress(maximum: Int, progress: Int, available: Boolean) {
        indicator.max = 100
        indicator.setIndicatorColor(context.getColor(if (available && maximum > 0) R.color.accent_green else R.color.divider))
        val percent = if (maximum > 0) ((progress.toLong() * 100L) / maximum).toInt().coerceIn(0, 100) else 0
        indicator.setProgressCompat(percent, false)
        // Keep the neutral target track visible when consumption is unavailable, while the explicit —
        // value and accessibility description prevent it from being interpreted as zero intake.
        indicator.visibility = if (maximum > 0) VISIBLE else INVISIBLE
    }

    fun setRingDimensions(sizePx: Int, thicknessPx: Int) {
        layoutParams = layoutParams.apply {
            width = sizePx
            height = sizePx
        }
        indicator.setIndicatorSize(sizePx)
        indicator.setTrackThickness(thicknessPx)
        requestLayout()
    }

}
