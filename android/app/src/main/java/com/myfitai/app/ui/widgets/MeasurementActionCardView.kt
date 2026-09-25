package com.myfitai.app.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.myfitai.app.R

/** Shared BIA/body-measurement card. Data and navigation remain owned by the Activity. */
class MeasurementActionCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs) {
    private val latestText: TextView
    private val helpButton: ImageButton
    private val addButton: ImageButton
    private val historyButton: ImageButton
    private val titleView: TextView

    init {
        setCardBackgroundColor(context.getColor(R.color.white))
        radius = context.resources.getDimension(R.dimen.radius_card)
        strokeWidth = context.resources.getDimensionPixelSize(R.dimen.space_1)
        setStrokeColor(context.getColor(R.color.divider))
        cardElevation = 0f

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.resources.getDimensionPixelSize(R.dimen.space_12),
                context.resources.getDimensionPixelSize(R.dimen.space_12),
                context.resources.getDimensionPixelSize(R.dimen.space_12),
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
            setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(titleView)
        helpButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setImageResource(R.drawable.ic_help_outline)
            background = null
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setColorFilter(context.getColor(R.color.text_secondary))
        }
        header.addView(helpButton)
        content.addView(header)

        latestText = TextView(context).apply {
            setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
            textSize = 12f
            includeFontPadding = false
            setLineSpacing(0f, 1f)
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        addButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(44))
            contentDescription = "Nuova rilevazione"
            tooltipText = "Nuova rilevazione"
            setImageResource(R.drawable.ic_add_circle)
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.white))
            setPadding(0, 0, 0, 0)
            backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_green))
            background = roundedBackground(context.getColor(R.color.accent_green))
        }
        historyButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(44)).apply { marginStart = dp(8) }
            contentDescription = "Storico rilevazioni"
            tooltipText = "Storico rilevazioni"
            setImageResource(R.drawable.ic_history)
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_green_dark))
            setPadding(0, 0, 0, 0)
            background = roundedBackground(
                context.getColor(R.color.surface_primary),
                context.getColor(R.color.accent_green),
            )
        }
        actions.addView(addButton)
        actions.addView(historyButton)

        val details = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = marginTop(2)
        }
        latestText.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        details.addView(latestText)
        details.addView(actions)
        content.addView(details)
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
    private fun roundedBackground(fillColor: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fillColor)
        cornerRadius = dp(13).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
