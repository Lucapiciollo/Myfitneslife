package com.myfitai.app.ui.widgets

import android.content.Context
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
            setTextAppearance(R.style.Text_MyFitAI_CardTitle)
        }
        header.addView(titleView)
        helpButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.icon_button_size),
                resources.getDimensionPixelSize(R.dimen.icon_button_size),
            )
            setImageResource(R.drawable.ic_help_outline)
            background = null
            val iconPadding = resources.getDimensionPixelSize(R.dimen.space_6)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            setColorFilter(context.getColor(R.color.text_secondary))
        }
        header.addView(helpButton)
        content.addView(header)

        latestText = TextView(context).apply {
            setTextAppearance(R.style.Text_MyFitAI_Caption)
            includeFontPadding = false
            setLineSpacing(0f, 1f)
            minHeight = resources.getDimensionPixelSize(R.dimen.control_min_height)
            gravity = Gravity.CENTER_VERTICAL
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        addButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dimension(R.dimen.icon_button_size), dimension(R.dimen.icon_button_size))
            contentDescription = "Nuova rilevazione"
            tooltipText = "Nuova rilevazione"
            setImageResource(R.drawable.ic_add_circle)
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.white))
            setPadding(0, 0, 0, 0)
            backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_green))
            background = roundedBackground(context.getColor(R.color.accent_green))
        }
        historyButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dimension(R.dimen.icon_button_size), dimension(R.dimen.icon_button_size)).apply { marginStart = dimension(R.dimen.space_8) }
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
            layoutParams = marginTop(R.dimen.space_2)
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

    private fun marginTop(dimenRes: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dimension(dimenRes)
    }
    private fun roundedBackground(fillColor: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fillColor)
        cornerRadius = dimension(R.dimen.radius_medium).toFloat()
        strokeColor?.let { setStroke(dimension(R.dimen.space_1), it) }
    }
    private fun dimension(dimenRes: Int): Int = resources.getDimensionPixelSize(dimenRes)
}
