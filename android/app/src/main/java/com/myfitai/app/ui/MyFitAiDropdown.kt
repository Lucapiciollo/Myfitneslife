package com.myfitai.app.ui

import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.myfitai.app.R

/** Applies the shared MyFitAI menu rows and tap-to-open behavior to exposed dropdowns. */
fun <T> AutoCompleteTextView.setMyFitAiDropdownItems(items: List<T>) {
    // Material exposes the dropdown's outlined box through its parent. Reapply the shared presentation tokens
    // here as well as in XML so every MaterialAutoCompleteTextView receives the same resting/focused outline.
    (parent as? TextInputLayout)?.apply {
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        setBoxBackgroundColor(context.getColor(R.color.white))
        setBoxStrokeColorStateList(context.getColorStateList(R.color.myfitai_input_stroke))
        setBoxStrokeWidth(resources.getDimensionPixelSize(R.dimen.border_width_default))
        setBoxStrokeWidthFocused(resources.getDimensionPixelSize(R.dimen.border_width_focused))
        setBoxCornerRadii(
            resources.getDimension(R.dimen.radius_field),
            resources.getDimension(R.dimen.radius_field),
            resources.getDimension(R.dimen.radius_field),
            resources.getDimension(R.dimen.radius_field),
        )
    }
    setAdapter(ArrayAdapter(context, R.layout.item_dropdown_myfitai, items))
    threshold = 0
    setOnClickListener { showDropDown() }
}
