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
    private val statusView: TextView
    private val changeButton: ImageView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = resources.getDimensionPixelSize(R.dimen.food_meal_row_min_height)
        setPadding(
            resources.getDimensionPixelSize(R.dimen.card_content_padding_compact),
            resources.getDimensionPixelSize(R.dimen.space_12),
            resources.getDimensionPixelSize(R.dimen.space_8),
            resources.getDimensionPixelSize(R.dimen.space_12),
        )
        background = context.getDrawable(R.drawable.bg_card)

        thumbnail = ShapeableImageView(context).apply {
            val size = resources.getDimensionPixelSize(R.dimen.dashboard_thumbnail_size)
            layoutParams = LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder(context, 0, R.style.ShapeAppearance_MyFitAI_Thumbnail).build()
        }
        addView(thumbnail)

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.space_12)
            }
        }
        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        titleView = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
            gravity = android.view.Gravity.START
            textAlignment = TEXT_ALIGNMENT_TEXT_START
        }
        titleRow.addView(titleView)
        kcalView = TextView(context).apply {
            setTextAppearance(R.style.Text_MyFitAI_KeyValueValue)
            gravity = android.view.Gravity.END
            textAlignment = TEXT_ALIGNMENT_TEXT_END
        }
        titleRow.addView(kcalView)
        textColumn.addView(titleRow)

        descriptionView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_4)
            }
            setTextAppearance(R.style.Text_MyFitAI_Body)
            gravity = android.view.Gravity.START
            textAlignment = TEXT_ALIGNMENT_TEXT_START
        }
        textColumn.addView(descriptionView)
        statusView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_4)
            }
            setTextAppearance(R.style.Text_MyFitAI_StatusValue)
            setTextColor(context.getColor(R.color.accent_green_dark))
        }
        textColumn.addView(statusView)
        addView(textColumn)

        changeButton = ImageView(context).apply {
            val size = resources.getDimensionPixelSize(R.dimen.icon_button_size)
            layoutParams = LayoutParams(size, size).apply { marginStart = resources.getDimensionPixelSize(R.dimen.space_4) }
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(context.getColor(R.color.accent_green_dark))
            val iconPadding = resources.getDimensionPixelSize(R.dimen.space_8)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
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

    fun setStatus(status: String?) {
        statusView.text = status.orEmpty()
        statusView.visibility = if (status.isNullOrBlank()) GONE else VISIBLE
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
