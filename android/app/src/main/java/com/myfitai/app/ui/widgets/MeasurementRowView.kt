package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.R

/** Riga riutilizzabile misura/valore: label a sinistra, valore a destra. */
class MeasurementRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val labelView: TextView
    private val valueView: TextView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val paddingV = (11 * resources.displayMetrics.density).toInt()
        setPadding(0, paddingV, 0, paddingV)

        labelView = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 14f
        }
        addView(labelView)

        valueView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addView(valueView)
    }

    fun setLabel(label: String) {
        labelView.text = label
    }

    fun setValue(value: String) {
        valueView.text = value
    }
}
