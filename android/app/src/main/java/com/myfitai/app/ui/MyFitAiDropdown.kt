package com.myfitai.app.ui

import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.myfitai.app.R

/** Applies the shared MyFitAI menu rows and tap-to-open behavior to exposed dropdowns. */
fun <T> AutoCompleteTextView.setMyFitAiDropdownItems(items: List<T>) {
    setAdapter(ArrayAdapter(context, R.layout.item_dropdown_myfitai, items))
    threshold = 0
    setOnClickListener { showDropDown() }
}
