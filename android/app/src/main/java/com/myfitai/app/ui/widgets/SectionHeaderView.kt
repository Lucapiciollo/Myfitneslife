package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.textview.MaterialTextView
import com.myfitai.app.R

/** Titolo di sezione riutilizzabile con gerarchia tipografica condivisa. */
class SectionHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialTextView(context, attrs) {
    init {
        setTextAppearance(R.style.Text_MyFitAI_Section)
    }
}
