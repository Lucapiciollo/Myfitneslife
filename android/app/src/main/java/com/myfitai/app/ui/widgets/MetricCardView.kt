package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.R

/** Card metrica riutilizzabile: label + valore + delta con icona di tendenza. */
class MetricCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    enum class DeltaState { POSITIVE, NEGATIVE, NEUTRAL }

    private val labelView: TextView
    private val valueView: TextView
    private val deltaIcon: ImageView
    private val deltaView: TextView

    init {
        orientation = VERTICAL

        labelView = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 12f
        }
        addView(labelView)

        valueView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * resources.displayMetrics.density).toInt()
            }
            setTextColor(context.getColor(R.color.metric_value))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addView(valueView)

        val deltaRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * resources.displayMetrics.density).toInt()
            }
        }
        val iconSize = (10 * resources.displayMetrics.density).toInt()
        deltaIcon = ImageView(context).apply {
            layoutParams = LayoutParams(iconSize, iconSize)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        deltaRow.addView(deltaIcon)
        deltaView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = (2 * resources.displayMetrics.density).toInt()
            }
            textSize = 12f
        }
        deltaRow.addView(deltaView)
        addView(deltaRow)
    }

    fun setLabel(label: String) {
        labelView.text = label
    }

    fun setValue(value: String) {
        valueView.text = value
    }

    fun setDelta(delta: String, state: DeltaState) {
        deltaView.text = delta
        val color = when (state) {
            DeltaState.POSITIVE -> context.getColor(R.color.accent_green)
            DeltaState.NEGATIVE -> context.getColor(R.color.semantic_error)
            DeltaState.NEUTRAL -> context.getColor(R.color.text_muted)
        }
        deltaView.setTextColor(color)
        deltaIcon.imageTintList = android.content.res.ColorStateList.valueOf(color)
        deltaIcon.setImageResource(
            when (state) {
                DeltaState.POSITIVE -> R.drawable.ic_trend_up
                DeltaState.NEGATIVE -> R.drawable.ic_trend_down
                DeltaState.NEUTRAL -> R.drawable.ic_trend_down
            }
        )
    }
}
