package com.myfitai.app.ui

import java.util.Locale

/** Presentation-only number formatting. It never changes persisted/calculated values. */
object UiNumberFormat {
    fun decimal(value: Double?): String = value?.let { decimal(it) } ?: "—"
    fun decimal(value: Float?): String = value?.let { decimal(it.toDouble()) } ?: "—"
    fun decimal(value: Int?): String = value?.toString() ?: "—"

    fun decimal(value: Double): String = if (value.isFinite() && value % 1.0 == 0.0) {
        value.toInt().toString()
    } else {
        String.format(Locale.ITALIAN, "%.1f", value)
    }

    fun signed(value: Double): String = String.format(Locale.ITALIAN, "%+.1f", value)
    fun signed(value: Float): String = signed(value.toDouble())
}
