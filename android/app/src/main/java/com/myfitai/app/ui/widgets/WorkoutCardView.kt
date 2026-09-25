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
        val icon = ImageView(context).apply {
            layoutParams = LayoutParams(resources.getDimensionPixelSize(R.dimen.icon_button_size), resources.getDimensionPixelSize(R.dimen.icon_button_size))
            setImageResource(R.drawable.ic_workout)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(icon)

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.space_12)
            }
        }
        timeView = TextView(context).apply {
            setTextAppearance(R.style.Text_MyFitAI_CardValue)
        }
        textColumn.addView(timeView)
        titleView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_2)
            }
            setTextAppearance(R.style.Text_MyFitAI_Body)
        }
        textColumn.addView(titleView)
        addView(textColumn)

        thumbnail = ShapeableImageView(context).apply {
            val size = resources.getDimensionPixelSize(R.dimen.dashboard_thumbnail_size)
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
