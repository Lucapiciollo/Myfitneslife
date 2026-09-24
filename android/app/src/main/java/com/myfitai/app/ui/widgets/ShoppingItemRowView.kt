package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.myfitai.app.R

/** Riga articolo riutilizzabile per la lista della spesa: checkbox, icona, nome, quantità. */
class ShoppingItemRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val checkBox: MaterialCheckBox
    private val iconView: ShapeableImageView
    private val nameView: TextView
    private val quantityView: TextView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val density = resources.displayMetrics.density
        setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())

        checkBox = MaterialCheckBox(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            buttonTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_green))
        }
        addView(checkBox)

        iconView = ShapeableImageView(context).apply {
            val size = (28 * density).toInt()
            layoutParams = LayoutParams(size, size).apply {
                marginStart = (4 * density).toInt()
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder(context, 0, R.style.ShapeAppearance_MyFitAI_Avatar).build()
        }
        addView(iconView)

        nameView = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * density).toInt()
            }
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 14f
        }
        addView(nameView)

        quantityView = TextView(context).apply {
            setTextColor(context.getColor(R.color.text_secondary))
            textSize = 14f
            gravity = android.view.Gravity.END
            textAlignment = TextView.TEXT_ALIGNMENT_VIEW_END
        }
        addView(quantityView)
    }

    fun setName(name: String) {
        nameView.text = name
    }

    fun setQuantity(quantity: String) {
        quantityView.text = quantity
    }

    fun setIcon(resId: Int) {
        iconView.setImageResource(resId)
    }

    fun setChecked(checked: Boolean) {
        checkBox.isChecked = checked
    }

    fun isChecked(): Boolean = checkBox.isChecked

    fun setOnCheckedChangeListener(listener: (Boolean) -> Unit) {
        checkBox.setOnCheckedChangeListener { _, isChecked -> listener(isChecked) }
    }
}
