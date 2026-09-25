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
        val padding = resources.getDimensionPixelSize(R.dimen.card_content_padding_compact)
        setPadding(padding, padding, padding, padding)
        addMetric(MetricCardView(context).apply { id = R.id.metricWeight; setCompactStyle() })
        addMetric(MetricCardView(context).apply { id = R.id.metricFat; setCompactStyle() })
        addMetric(MetricCardView(context).apply { id = R.id.metricMuscle; setCompactStyle() })
    }

    fun addMetric(metric: MetricCardView) {
        if (childCount > 0) addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.divider))
        }, LayoutParams(LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.space_1)).apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.space_4)
            bottomMargin = resources.getDimensionPixelSize(R.dimen.space_4)
        })
        addView(metric, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}
