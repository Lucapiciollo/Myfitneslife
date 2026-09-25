package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.R

/** Riga riutilizzabile misura/valore: icona opzionale, label a sinistra, valore a destra. */
class MeasurementRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val iconView: ImageView
    private val labelView: TextView
    private val valueView: TextView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val rowPadding = resources.getDimensionPixelSize(R.dimen.space_8)
        setPadding(0, rowPadding, 0, rowPadding)

        iconView = ImageView(context).apply {
            layoutParams = LayoutParams(resources.getDimensionPixelSize(R.dimen.space_2), resources.getDimensionPixelSize(R.dimen.space_2)).apply {
                marginEnd = resources.getDimensionPixelSize(R.dimen.space_8)
            }
            setBackgroundResource(R.drawable.bg_body_marker)
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(iconView)

        labelView = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextAppearance(R.style.Text_MyFitAI_KeyValueKey)
        }
        addView(labelView)

        valueView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setTextAppearance(R.style.Text_MyFitAI_KeyValueValue)
        }
        addView(valueView)
    }

    fun showIcon() {
        iconView.visibility = VISIBLE
    }

    fun setLabel(label: String) {
        labelView.text = label
    }

    fun setValue(value: String) {
        valueView.text = value
    }
}
