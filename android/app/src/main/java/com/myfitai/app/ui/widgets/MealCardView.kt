package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.myfitai.app.R

/** Card riutilizzabile per il prossimo pasto: icona, orario, titolo, kcal, miniatura foto. */
class MealCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val timeView: TextView
    private val titleView: TextView
    private val kcalView: TextView
    private val thumbnail: ShapeableImageView
    private val changeButton: ImageView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val density = resources.displayMetrics.density

        val icon = ImageView(context).apply {
            layoutParams = LayoutParams((36 * density).toInt(), (36 * density).toInt())
            setImageResource(R.drawable.ic_clock)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(icon)

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * density).toInt()
            }
        }
        timeView = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        textColumn.addView(timeView)
        titleView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * density).toInt()
            }
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 13f
        }
        textColumn.addView(titleView)
        kcalView = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 13f
        }
        textColumn.addView(kcalView)
        addView(textColumn)

        changeButton = ImageView(context).apply {
            val size = (40 * density).toInt()
            layoutParams = LayoutParams(size, size).apply { marginEnd = (4 * density).toInt() }
            setImageResource(R.drawable.ic_refresh)
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            contentDescription = "Cambia pasto con IA"
            visibility = GONE
        }
        addView(changeButton)

        thumbnail = ShapeableImageView(context).apply {
            val size = (72 * density).toInt()
            layoutParams = LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder(context, 0, R.style.ShapeAppearance_MyFitAI_Thumbnail).build()
        }
        addView(thumbnail)
    }

    fun setTime(time: String) {
        timeView.text = time
    }

    fun setTitle(title: String) {
        titleView.text = title
    }

    fun setKcal(kcal: String) {
        kcalView.text = kcal
    }

    fun setImage(resId: Int) {
        thumbnail.setImageResource(resId)
    }

    fun setChangeEnabled(enabled: Boolean) {
        changeButton.visibility = if (enabled) VISIBLE else GONE
        changeButton.isEnabled = enabled
    }

    fun setOnChangeClickListener(listener: () -> Unit) {
        changeButton.setOnClickListener { listener() }
    }
}
