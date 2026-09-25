package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.myfitai.app.R

/** Riga allenamento riutilizzabile: foto (o icona per il riposo), titolo, sottotitolo. */
class WorkoutRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val photoThumbnail: ShapeableImageView
    private val restIconCircle: FrameLayout
    private val titleView: TextView
    private val subtitleView: TextView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(R.dimen.control_min_height)
        val rowPadding = resources.getDimensionPixelSize(R.dimen.space_8)
        setPadding(0, rowPadding, 0, rowPadding)
        background = null

        val thumbnailSize = resources.getDimensionPixelSize(R.dimen.dashboard_thumbnail_size)
        photoThumbnail = ShapeableImageView(context).apply {
            layoutParams = LayoutParams(thumbnailSize, thumbnailSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder(context, 0, R.style.ShapeAppearance_MyFitAI_Avatar).build()
        }
        addView(photoThumbnail)

        restIconCircle = FrameLayout(context).apply {
            layoutParams = LayoutParams(thumbnailSize, thumbnailSize)
            background = context.getDrawable(R.drawable.bg_logo_circle)
            visibility = GONE
        }
        val restIcon = ImageView(context).apply {
            val size = resources.getDimensionPixelSize(R.dimen.space_20)
            layoutParams = FrameLayout.LayoutParams(size, size).apply { gravity = android.view.Gravity.CENTER }
            setImageResource(R.drawable.ic_pause)
        }
        restIconCircle.addView(restIcon)
        addView(restIconCircle)

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
    }

    fun setTitle(title: String) {
        titleView.text = title
    }

    fun setSubtitle(subtitle: String) {
        subtitleView.text = subtitle
    }

    fun setPhoto(resId: Int) {
        photoThumbnail.visibility = VISIBLE
        restIconCircle.visibility = GONE
        photoThumbnail.setImageResource(resId)
    }

    fun setRestDay() {
        photoThumbnail.visibility = GONE
        restIconCircle.visibility = VISIBLE
    }
}
