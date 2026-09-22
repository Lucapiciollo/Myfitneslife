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
        val density = resources.displayMetrics.density
        val paddingV = (12 * density).toInt()
        setPadding(0, paddingV, 0, paddingV)

        val iconCircle = FrameLayout(context).apply {
            val size = (36 * density).toInt()
            layoutParams = LayoutParams(size, size)
            background = context.getDrawable(R.drawable.bg_logo_circle)
        }
        iconView = ImageView(context).apply {
            val size = (18 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER }
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_green))
        }
        iconCircle.addView(iconView)
        addView(iconCircle)

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * density).toInt()
            }
        }
        titleView = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        textColumn.addView(titleView)
        subtitleView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * density).toInt()
            }
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 12f
        }
        textColumn.addView(subtitleView)
        addView(textColumn)

        val chevron = TextView(context).apply {
            text = "›"
            setTextColor(context.getColor(R.color.text_muted))
            textSize = 15f
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
