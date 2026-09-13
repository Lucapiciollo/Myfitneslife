package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.myfitai.app.R

/** Riga pasto riutilizzabile per il Piano alimentare: foto, titolo+kcal, descrizione e cambio IA. */
class MealPlanRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val thumbnail: ShapeableImageView
    private val titleView: TextView
    private val kcalView: TextView
    private val descriptionView: TextView
    private val changeButton: ImageView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        val density = resources.displayMetrics.density
        setPadding((12 * density).toInt(), (12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt())
        background = context.getDrawable(R.drawable.bg_card)

        thumbnail = ShapeableImageView(context).apply {
            val size = (56 * density).toInt()
            layoutParams = LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder(context, 0, R.style.ShapeAppearance_MyFitAI_Thumbnail).build()
        }
        addView(thumbnail)

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * density).toInt()
            }
        }
        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        titleView = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        titleRow.addView(titleView)
        kcalView = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 13f
        }
        titleRow.addView(kcalView)
        textColumn.addView(titleRow)

        descriptionView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * density).toInt()
            }
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 13f
        }
        textColumn.addView(descriptionView)
        addView(textColumn)

        changeButton = ImageView(context).apply {
            val size = (40 * density).toInt()
            layoutParams = LayoutParams(size, size).apply { marginStart = (6 * density).toInt() }
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(context.getColor(R.color.accent_green_dark))
            setPadding((9 * density).toInt(), (9 * density).toInt(), (9 * density).toInt(), (9 * density).toInt())
            contentDescription = "Cambia pasto con IA"
            isClickable = true
            isFocusable = true
        }
        addView(changeButton)
    }

    fun setTitle(title: String) {
        titleView.text = title
    }

    fun setKcal(kcal: String) {
        kcalView.text = kcal
    }

    fun setDescription(description: String) {
        descriptionView.text = description
    }

    fun setImage(resId: Int) {
        thumbnail.setImageResource(resId)
    }

    fun setOnChangeClickListener(listener: (() -> Unit)?) {
        changeButton.setOnClickListener { listener?.invoke() }
    }

    fun setChangeEnabled(enabled: Boolean) {
        changeButton.isEnabled = enabled
        changeButton.alpha = if (enabled) 1f else 0.35f
    }
}
