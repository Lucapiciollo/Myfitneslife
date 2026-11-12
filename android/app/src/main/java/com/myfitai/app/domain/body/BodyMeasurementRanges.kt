package com.myfitai.app.domain.body

import java.time.LocalDate
import java.util.Locale

/**
 * Unico punto in cui sono dichiarati gli intervalli plausibili delle misure corporee (kg e cm).
 * Sono controlli di buon senso contro refusi (es. 785 al posto di 78,5), non valutazioni di salute:
 * valori fuori intervallo non vengono salvati ma l'utente puo' sempre correggerli.
 */
object BodyMeasurementRanges {

    enum class Field(val min: Float, val max: Float, val unit: String) {
        WEIGHT(30f, 300f, "kg"),
        CHEST(50f, 200f, "cm"),
        WAIST(40f, 200f, "cm"),
        ABDOMEN(40f, 200f, "cm"),
        SHOULDERS(60f, 200f, "cm"),
        HIPS(50f, 200f, "cm"),
        GLUTES(50f, 200f, "cm"),
        ARM_LEFT(12f, 70f, "cm"),
        ARM_RIGHT(12f, 70f, "cm"),
        THIGH_LEFT(25f, 120f, "cm"),
        THIGH_RIGHT(25f, 120f, "cm"),
        CALF_LEFT(18f, 70f, "cm"),
        CALF_RIGHT(18f, 70f, "cm"),
    }

    sealed interface Check {
        data object Empty : Check
        data object NotANumber : Check
        data class Valid(val value: Float) : Check
        data class OutOfRange(val min: Float, val max: Float) : Check
    }

    private val numberPattern = Regex("""(\d+[.,]?\d*|[.,]\d+)""")

    /** Accetta solo cifre con virgola o punto decimale; restituisce null per testo vuoto o non numerico. */
    fun parse(raw: String?): Float? {
        val text = raw?.trim().orEmpty()
        if (!numberPattern.matches(text)) return null
        return text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
    }

    fun check(field: Field, raw: String?): Check {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Check.Empty
        val value = parse(text) ?: return Check.NotANumber
        return if (value in field.min..field.max) Check.Valid(value) else Check.OutOfRange(field.min, field.max)
    }

    fun isFutureDate(date: LocalDate, today: LocalDate): Boolean = date.isAfter(today)

    /** Estremo dell'intervallo senza decimali inutili (30 -> "30", 12,5 -> "12,5"). */
    fun formatBound(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
}
