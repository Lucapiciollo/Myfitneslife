package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.R

/** Riga riutilizzabile per lo storico: icona in cerchio, titolo, sottotitolo, chevron. */
class HistoryRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val iconView: ImageView
    private val titleView: TextView
    private val subtitleView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        val paddingV = resources.getDimensionPixelSize(R.dimen.space_12)
        minimumHeight = resources.getDimensionPixelSize(R.dimen.control_min_height) + resources.getDimensionPixelSize(R.dimen.space_8)
        setPadding(0, paddingV, 0, paddingV)

        val iconCircle = FrameLayout(context).apply {
            val size = resources.getDimensionPixelSize(R.dimen.icon_button_size)
            layoutParams = LayoutParams(size, size)
            background = context.getDrawable(R.drawable.bg_logo_circle)
        }
        iconView = ImageView(context).apply {
            val size = resources.getDimensionPixelSize(R.dimen.space_20)
            layoutParams = FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER }
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_green))
        }
        iconCircle.addView(iconView)
        addView(iconCircle)

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.space_12)
            }
        }
        titleView = TextView(context).apply {
            setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
        }
        textColumn.addView(titleView)
        subtitleView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_2)
            }
            setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
        }
        textColumn.addView(subtitleView)
        addView(textColumn)

        val chevron = TextView(context).apply {
            text = "›"
            setTextColor(context.getColor(R.color.text_muted))
            setTextAppearance(R.style.Text_MyFitAI_Caption)
        }
        addView(chevron)
    }

    fun setIcon(resId: Int) {
        iconView.setImageResource(resId)
    }

    fun setTitle(title: String) {
        titleView.text = title
    }

    fun setSubtitle(subtitle: String) {
        subtitleView.text = subtitle
    }
}
