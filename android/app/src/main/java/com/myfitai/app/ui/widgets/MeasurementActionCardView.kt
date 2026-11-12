package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
    private val analysisButton: MaterialButton
    private val titleView: TextView

    init {
        setCardBackgroundColor(context.getColor(R.color.white))
        radius = context.resources.getDimension(R.dimen.radius_card)
        strokeWidth = context.resources.getDimensionPixelSize(R.dimen.border_width_default)
        setStrokeColor(context.getColor(R.color.divider))
        cardElevation = context.resources.getDimension(R.dimen.elevation_none)

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
            setLineSpacing(
                resources.getDimension(R.dimen.line_spacing_zero_extra),
                resources.getFraction(R.fraction.text_line_spacing_default, 1, 1),
            )
            minHeight = resources.getDimensionPixelSize(R.dimen.control_min_height)
            gravity = Gravity.CENTER_VERTICAL
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = marginTop(R.dimen.space_8)
        }
        // K3d: tre azioni con etichetta visibile, stessa larghezza, altezza >= 48dp e testo a capo a font grande.
        addButton = actionButton(
            label = R.string.measurement_action_new,
            iconRes = R.drawable.ic_add_circle,
            contentDescription = "Nuova rilevazione",
            tint = R.color.white,
            fill = R.color.accent_green,
            stroke = null,
            startMargin = false,
        )
        historyButton = actionButton(
            label = R.string.measurement_action_history,
            iconRes = R.drawable.ic_history,
            contentDescription = "Storico rilevazioni",
            tint = R.color.accent_green_dark,
            fill = R.color.surface_primary,
            stroke = R.color.accent_green,
            startMargin = true,
        )
        analysisButton = actionButton(
            label = R.string.measurement_action_analyze,
            iconRes = R.drawable.ic_nutrition_ai,
            contentDescription = "Analizza dati",
            tint = R.color.accent_purple,
            fill = R.color.surface_primary,
            stroke = R.color.accent_purple,
            startMargin = true,
            textColor = R.color.text_primary,
        )
        actions.addView(addButton)
        actions.addView(historyButton)
        actions.addView(analysisButton)

        latestText.layoutParams = marginTop(R.dimen.space_2)
        content.addView(latestText)
        content.addView(actions)
    }

    private fun actionButton(
        label: Int,
        iconRes: Int,
        contentDescription: String,
        tint: Int,
        fill: Int,
        stroke: Int?,
        startMargin: Boolean,
        textColor: Int = tint,
    ) = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (startMargin) marginStart = dimension(R.dimen.space_8)
        }
        text = context.getString(label)
        this.contentDescription = contentDescription
        tooltipText = contentDescription
        setTextAppearance(R.style.Text_MyFitAI_Supporting)
        setTextColor(context.getColor(textColor))
        isAllCaps = false
        maxLines = 2
        gravity = Gravity.CENTER
        icon = context.getDrawable(iconRes)
        iconTint = android.content.res.ColorStateList.valueOf(context.getColor(tint))
        iconSize = dimension(R.dimen.inline_icon_size)
        iconPadding = dimension(R.dimen.space_4)
        insetTop = 0
        insetBottom = 0
        minHeight = dimension(R.dimen.control_min_height)
        minimumHeight = dimension(R.dimen.control_min_height)
        setPadding(dimension(R.dimen.space_8), 0, dimension(R.dimen.space_8), 0)
        cornerRadius = dimension(R.dimen.radius_medium)
        backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(fill))
        strokeWidth = stroke?.let { dimension(R.dimen.border_width_default) } ?: 0
        stroke?.let { strokeColor = android.content.res.ColorStateList.valueOf(context.getColor(it)) }
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
    fun setAnalysisAction(contentDescription: String, listener: OnClickListener) {
        analysisButton.contentDescription = contentDescription
        analysisButton.tooltipText = contentDescription
        analysisButton.setOnClickListener(listener)
    }

    private fun marginTop(dimenRes: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dimension(dimenRes)
    }
    private fun dimension(dimenRes: Int): Int = resources.getDimensionPixelSize(dimenRes)
}
