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
        setPadding(
            context.resources.getDimensionPixelSize(R.dimen.card_content_padding_compact),
            context.resources.getDimensionPixelSize(R.dimen.space_4),
            context.resources.getDimensionPixelSize(R.dimen.card_content_padding_compact),
            context.resources.getDimensionPixelSize(R.dimen.space_4),
        )
    }

    init {
        setCardBackgroundColor(context.getColor(R.color.surface_primary))
        radius = context.resources.getDimension(R.dimen.radius_card)
        cardElevation = 0f
        strokeWidth = context.resources.getDimensionPixelSize(R.dimen.space_1)
        setStrokeColor(context.getColor(R.color.divider))
        addView(rows)
    }

    fun addRow(row: SettingRowView) {
        if (rows.childCount > 0) {
            rows.addView(
                View(context).apply { setBackgroundColor(context.getColor(R.color.divider)) },
                LinearLayout.LayoutParams(-1, context.resources.getDimensionPixelSize(R.dimen.space_1)),
            )
        }
        rows.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
}
