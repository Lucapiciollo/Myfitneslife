package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.R

/** Riga riutilizzabile per le liste impostazioni/profilo: icona + etichetta + chevron o badge. */
class SettingRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val trailingText: TextView
    private val descriptionView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(R.dimen.control_min_height)
        val rowVerticalPadding = resources.getDimensionPixelSize(R.dimen.space_8)
        setPadding(0, rowVerticalPadding, 0, rowVerticalPadding)
        background = null
        isClickable = true
        isFocusable = true

        val a = context.obtainStyledAttributes(attrs, R.styleable.SettingRowView)
        val iconRes = a.getResourceId(R.styleable.SettingRowView_srIcon, 0)
        val label = a.getString(R.styleable.SettingRowView_srLabel).orEmpty()
        val description = a.getString(R.styleable.SettingRowView_srDescription).orEmpty()
        a.recycle()

        val icon = ImageView(context).apply {
            layoutParams = LayoutParams(resources.getDimensionPixelSize(R.dimen.space_32), resources.getDimensionPixelSize(R.dimen.space_32))
            val iconPadding = resources.getDimensionPixelSize(R.dimen.space_4)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = resources.getDimension(R.dimen.radius_small)
                setColor(context.getColor(R.color.surface_secondary))
            }
            if (iconRes != 0) setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.text_secondary))
        }
        addView(icon)

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.space_16)
            }
        }
        val labelView = TextView(context).apply {
            text = label
            setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
        }
        textColumn.addView(labelView)
        descriptionView = TextView(context).apply {
            text = description
            visibility = if (description.isBlank()) GONE else VISIBLE
            setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_1)
            }
        }
        textColumn.addView(descriptionView)
        addView(textColumn)

        trailingText = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            text = "›"
            setTextColor(context.getColor(R.color.text_muted))
            setTextAppearance(R.style.Text_MyFitAI_CardTitle)
        }
        addView(trailingText)
    }

    fun setTrailingBadge(text: String, colorRes: Int) {
        trailingText.text = text
        trailingText.setTextColor(context.getColor(colorRes))
        trailingText.setTextAppearance(R.style.Text_MyFitAI_BodyEmphasis)
    }

    fun setDescription(value: String?) {
        descriptionView.text = value.orEmpty()
        descriptionView.visibility = if (value.isNullOrBlank()) GONE else VISIBLE
    }
}
