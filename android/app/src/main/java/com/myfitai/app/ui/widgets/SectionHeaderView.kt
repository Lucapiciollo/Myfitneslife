package com.myfitai.app.ui.widgets

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import com.google.android.material.textview.MaterialTextView
import com.myfitai.app.R

/** Titolo di sezione riutilizzabile con gerarchia tipografica condivisa. */
class SectionHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialTextView(context, attrs) {
    init {
        setTextColor(context.getColor(R.color.text_primary))
        setTypeface(typeface, Typeface.BOLD)
        textSize = 16f
        includeFontPadding = true
    }
}
