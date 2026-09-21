package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.view.View
import com.myfitai.app.R

/** Compact dashboard surface that keeps the existing metric view IDs discoverable. */
class DashboardMetricsPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        val density = resources.displayMetrics.density
        val padding = (12 * density).toInt()
        setPadding(padding, padding, padding, padding)
        addMetric(MetricCardView(context).apply { id = R.id.metricWeight; setCompactStyle() })
        addMetric(MetricCardView(context).apply { id = R.id.metricFat; setCompactStyle() })
        addMetric(MetricCardView(context).apply { id = R.id.metricMuscle; setCompactStyle() })
    }

    fun addMetric(metric: MetricCardView) {
        if (childCount > 0) addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.divider))
        }, LayoutParams(LayoutParams.MATCH_PARENT, (1 * resources.displayMetrics.density).toInt()).apply {
            topMargin = (4 * resources.displayMetrics.density).toInt()
            bottomMargin = (4 * resources.displayMetrics.density).toInt()
        })
        addView(metric, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}
