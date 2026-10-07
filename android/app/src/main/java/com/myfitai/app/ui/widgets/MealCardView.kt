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

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_panel_tonal)
        val padding = resources.getDimensionPixelSize(R.dimen.space_12)
        setPadding(padding, padding, padding, padding)
        val icon = ImageView(context).apply {
            layoutParams = LayoutParams(resources.getDimensionPixelSize(R.dimen.vector_icon_size), resources.getDimensionPixelSize(R.dimen.vector_icon_size))
            setImageResource(R.drawable.ic_clock)
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
        kcalView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_2)
            }
            setTextAppearance(R.style.Text_MyFitAI_Supporting)
            gravity = android.view.Gravity.START
            textAlignment = TEXT_ALIGNMENT_TEXT_START
        }
        textColumn.addView(kcalView)
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

    fun setKcal(kcal: String) {
        kcalView.text = kcal
    }

    /** Shows the icon of the moment of the day (breakfast, lunch, dinner, snacks) instead of a photo. */
    fun setMeal(type: String?, timeMinutes: Int?, describe: Boolean) {
        thumbnail.setBackgroundResource(R.drawable.bg_meal_icon_tile)
        thumbnail.scaleType = ImageView.ScaleType.CENTER
        thumbnail.setImageResource(MealSlotIcons.iconRes(type, timeMinutes))
        if (describe) {
            thumbnail.contentDescription = MealSlotIcons.label(type, timeMinutes)
            thumbnail.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            thumbnail.contentDescription = null
            thumbnail.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    fun setImage(resId: Int) {
        thumbnail.setImageResource(resId)
    }
}
