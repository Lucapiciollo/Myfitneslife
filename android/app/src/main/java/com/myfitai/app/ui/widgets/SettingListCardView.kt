package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import com.myfitai.app.R

/** Home-style white list card: compact rows, fine dividers, no colored row surfaces. */
class SettingListCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs) {
    private val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(4))
    }

    init {
        setCardBackgroundColor(context.getColor(R.color.surface_primary))
        radius = dp(16).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        setStrokeColor(context.getColor(R.color.divider))
        addView(rows)
    }

    fun addRow(row: SettingRowView) {
        if (rows.childCount > 0) {
            rows.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.divider)) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        rows.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
