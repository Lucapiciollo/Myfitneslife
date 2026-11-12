package com.myfitai.app.domain.body

import java.time.LocalDate

/** Logica pura del form misurazione: confronto con lo stato iniziale per l'avviso di uscita. */
object BodyMeasurementFormState {

    fun normalize(raw: String?): String = raw?.trim()?.replace(',', '.').orEmpty()

    private fun sameValue(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return true
        val pa = BodyMeasurementRanges.parse(na)
        return pa != null && pa == BodyMeasurementRanges.parse(nb)
    }

    /** True se data o valori digitati differiscono dalla base di partenza (vuota per una nuova misura). */
    fun isDirty(
        baselineValues: List<String>,
        currentValues: List<String>,
        baselineDate: LocalDate,
        currentDate: LocalDate,
    ): Boolean {
        if (baselineDate != currentDate) return true
        if (baselineValues.size != currentValues.size) return true
        return baselineValues.indices.any { !sameValue(baselineValues[it], currentValues[it]) }
    }
}
