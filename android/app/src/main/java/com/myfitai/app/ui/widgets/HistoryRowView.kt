package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import android.widget.TextView
import com.myfitai.app.R

/** Riga riutilizzabile per lo storico: icona in cerchio, titolo, sottotitolo, chevron. */
class HistoryRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs) {

    private val iconView: ImageView
    private val titleView: TextView
    private val subtitleView: TextView
    private val rowContainer: LinearLayout

    init {
        rowContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(rowContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        isClickable = true
        isFocusable = true
        radius = (16 * resources.displayMetrics.density)
        cardElevation = 0f
        setCardBackgroundColor(context.getColor(R.color.surface_primary))
        strokeColor = context.getColor(R.color.divider)
        strokeWidth = (resources.displayMetrics.density).toInt()
        val density = resources.displayMetrics.density
        setPadding((16 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())

        val iconCircle = FrameLayout(context).apply {
            val size = (44 * density).toInt()
            layoutParams = LayoutParams(size, size)
            background = context.getDrawable(R.drawable.bg_logo_circle)
        }
        iconView = ImageView(context).apply {
            val size = (20 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER }
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_green))
        }
        iconCircle.addView(iconView)
        rowContainer.addView(iconCircle)

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * density).toInt()
            }
        }
        titleView = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        textColumn.addView(titleView)
        subtitleView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * density).toInt()
            }
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 13f
        }
        textColumn.addView(subtitleView)
        rowContainer.addView(textColumn)

        val chevron = TextView(context).apply {
            text = "›"
            setTextColor(context.getColor(R.color.text_muted))
            textSize = 18f
        }
        rowContainer.addView(chevron)
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
