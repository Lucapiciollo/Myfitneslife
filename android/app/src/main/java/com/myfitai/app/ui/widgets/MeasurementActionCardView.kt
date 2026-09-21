package com.myfitai.app.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.myfitai.app.R

/** Shared BIA/body-measurement card. Data and navigation remain owned by the Activity. */
class MeasurementActionCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs) {
    private val latestText: TextView
    private val helpButton: ImageButton
    private val addButton: MaterialButton
    private val historyButton: MaterialButton
    private val titleView: TextView

    init {
        setCardBackgroundColor(context.getColor(R.color.white))
        radius = context.resources.getDimension(R.dimen.radius_card)
        strokeWidth = context.resources.getDimensionPixelSize(R.dimen.space_1)
        setStrokeColor(context.getColor(R.color.divider))
        cardElevation = context.resources.getDimension(R.dimen.elevation_card)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.resources.getDimensionPixelSize(R.dimen.space_16),
                context.resources.getDimensionPixelSize(R.dimen.space_12),
                context.resources.getDimensionPixelSize(R.dimen.space_16),
                context.resources.getDimensionPixelSize(R.dimen.space_16),
            )
        }
        addView(content)

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        titleView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(titleView)
        helpButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setImageResource(R.drawable.ic_help_outline)
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setColorFilter(context.getColor(R.color.text_secondary))
        }
        header.addView(helpButton)
        content.addView(header)

        latestText = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 13f
        }
        content.addView(latestText, marginTop(4))

        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        addButton = MaterialButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            text = ""
            contentDescription = "Nuova rilevazione"
            tooltipText = "Nuova rilevazione"
            icon = context.getDrawable(R.drawable.ic_add_circle)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconTint = android.content.res.ColorStateList.valueOf(context.getColor(R.color.white))
            gravity = Gravity.CENTER
            backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_green))
            cornerRadius = dp(14)
        }
        historyButton = MaterialButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) }
            text = ""
            contentDescription = "Storico rilevazioni"
            tooltipText = "Storico rilevazioni"
            icon = context.getDrawable(R.drawable.ic_history)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            gravity = Gravity.CENTER
            backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.surface_primary))
            strokeColor = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_green))
            strokeWidth = dp(1)
            cornerRadius = dp(14)
        }
        actions.addView(addButton)
        actions.addView(historyButton)
        content.addView(actions, marginTop(12))
    }

    fun setTitle(value: String) {
        titleView.text = value
    }

    fun setLatest(value: String) { latestText.text = value }
    fun setHelp(contentDescription: String, listener: OnClickListener) {
        helpButton.contentDescription = contentDescription
        helpButton.setOnClickListener(listener)
    }
    fun setAddAction(contentDescription: String, listener: OnClickListener) {
        addButton.contentDescription = contentDescription
        addButton.tooltipText = contentDescription
        addButton.setOnClickListener(listener)
    }
    fun setHistoryAction(contentDescription: String, listener: OnClickListener) {
        historyButton.contentDescription = contentDescription
        historyButton.tooltipText = contentDescription
        historyButton.setOnClickListener(listener)
    }

    private fun marginTop(value: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(value) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
