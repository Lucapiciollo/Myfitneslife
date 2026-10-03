package com.myfitai.app.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
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
        setBackgroundResource(R.drawable.bg_panel_tonal)
        val padding = resources.getDimensionPixelSize(R.dimen.space_12)
        setPadding(padding, padding, padding, padding)
        val icon = ImageView(context).apply {
            layoutParams = LayoutParams(resources.getDimensionPixelSize(R.dimen.vector_icon_size), resources.getDimensionPixelSize(R.dimen.vector_icon_size))
            setImageResource(R.drawable.ic_workout)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.accent_green))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(icon)

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.space_12)
                marginEnd = resources.getDimensionPixelSize(R.dimen.space_12)
            }
        }
        timeView = TextView(context).apply {
            setTextAppearance(R.style.Text_MyFitAI_CardTitle)
            setTextColor(context.getColor(R.color.accent_green_dark))
        }
        textColumn.addView(timeView)
        titleView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_4)
            }
            setTextAppearance(R.style.Text_MyFitAI_BodyEmphasis)
            gravity = android.view.Gravity.START
            textAlignment = TEXT_ALIGNMENT_TEXT_START
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
