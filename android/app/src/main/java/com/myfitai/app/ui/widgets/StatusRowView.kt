package com.myfitai.app.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.R

/** Shared status/check row with a semantic indicator on the left. */
class StatusRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val indicator: TextView
    private val labelView: TextView
    private val stateView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        indicator = TextView(context).apply {
            layoutParams = LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            gravity = Gravity.CENTER
            text = ""
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(R.color.surface_soft))
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        labelView = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextAppearance(R.style.Text_MyFitAI_StatusLabel)
        }
        stateView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
            setTextAppearance(R.style.Text_MyFitAI_StatusValue)
            gravity = Gravity.END
            textAlignment = TextView.TEXT_ALIGNMENT_VIEW_END
        }
        addView(indicator)
        addView(labelView)
        addView(stateView)
    }

    fun setLabel(value: String) { labelView.text = value; refreshDescription() }
    fun setState(value: String) { stateView.text = value; refreshDescription() }

    fun setStatus(status: Status) {
        val color = when (status) {
            Status.POSITIVE -> R.color.semantic_positive
            Status.WARNING -> R.color.semantic_warning
            Status.ERROR -> R.color.semantic_error
            Status.NEUTRAL -> R.color.text_muted
        }
        indicator.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(context.getColor(color))
        }
        stateView.setTextColor(context.getColor(color))
        refreshDescription()
    }

    private fun refreshDescription() {
        val state = stateView.text.toString()
        contentDescription = if (state.isBlank()) labelView.text else "${labelView.text}: $state"
    }

    enum class Status { POSITIVE, WARNING, ERROR, NEUTRAL }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
