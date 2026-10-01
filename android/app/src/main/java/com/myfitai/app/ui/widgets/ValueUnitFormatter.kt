package com.myfitai.app.ui.widgets

import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.TextView
import com.myfitai.app.R

/**
 * Distingue visivamente l'unità finale (kcal, g, oltre) dal valore numerico di un [TextView].
 * Il testo non cambia: viene applicato solo uno span di dimensione/colore all'unità finale, quindi
 * valori non disponibili ("—") e qualsiasi altro testo restano invariati.
 */
object ValueUnitFormatter {
    private val units = listOf("kcal", "oltre", "g")

    fun apply(view: TextView) {
        val text = view.text?.toString().orEmpty()
        val unit = units.firstOrNull { text.endsWith(" $it") } ?: return
        val valuePart = text.dropLast(unit.length + 1)
        if (valuePart.lastOrNull()?.isDigit() != true) return
        val resources = view.resources
        val spannable = SpannableString(text)
        val start = text.length - unit.length
        spannable.setSpan(
            RelativeSizeSpan(resources.getFraction(R.fraction.value_unit_text_scale, 1, 1)),
            start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        spannable.setSpan(
            ForegroundColorSpan(view.context.getColor(R.color.text_secondary)),
            start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        view.text = spannable
    }
}
