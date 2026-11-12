package com.myfitai.app.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import com.myfitai.app.R

/** Applica a card e campi delle schermate impostazioni la stessa superficie bianca con bordo sottile. */
internal object SettingsSurfaces {
    fun normalize(activity: Activity) {
        fun visit(view: View) {
            when (view) {
                is MaterialCardView -> {
                    view.setCardBackgroundColor(activity.getColor(R.color.white))
                    view.strokeColor = activity.getColor(R.color.divider)
                    view.strokeWidth = activity.resources.getDimensionPixelSize(R.dimen.border_width_default)
                    view.cardElevation = activity.resources.getDimension(R.dimen.elevation_none)
                }
                is TextInputLayout -> {
                    view.boxBackgroundColor = activity.getColor(R.color.white)
                    view.boxStrokeColor = activity.getColor(R.color.myfitai_input_stroke)
                    view.boxStrokeWidth = activity.resources.getDimensionPixelSize(R.dimen.border_width_default)
                    view.boxStrokeWidthFocused = activity.resources.getDimensionPixelSize(R.dimen.space_2)
                    view.hintTextColor = ColorStateList.valueOf(activity.getColor(R.color.myfitai_input_hint))
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(activity.findViewById(android.R.id.content))
    }
}
