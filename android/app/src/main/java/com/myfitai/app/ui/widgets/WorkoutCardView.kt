package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.myfitai.app.R

/** Card riutilizzabile per il prossimo allenamento: icona, orario, titolo, miniatura foto. */
class WorkoutCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val timeView: TextView
    private val titleView: TextView
    private val thumbnail: ShapeableImageView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val density = resources.displayMetrics.density

        val icon = ImageView(context).apply {
            layoutParams = LayoutParams((36 * density).toInt(), (36 * density).toInt())
            setImageResource(R.drawable.ic_workout)
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
            setTextAppearance(R.style.Text_MyFitAI_ScreenTitle)
        }
        textColumn.addView(timeView)
        titleView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * density).toInt()
            }
            setTextAppearance(R.style.Text_MyFitAI_Body)
        }
        textColumn.addView(titleView)
        addView(textColumn)

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

    fun setImage(resId: Int) {
        thumbnail.setImageResource(resId)
    }
}
