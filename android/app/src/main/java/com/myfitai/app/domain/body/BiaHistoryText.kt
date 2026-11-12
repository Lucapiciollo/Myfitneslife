package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import java.util.Locale

/**
 * Testo dello storico BIA: stessa sorgente per la riga visibile, per la contentDescription della card e per il
 * messaggio di conferma di eliminazione (stesso modello di [BodyMeasurementHistoryText]).
 */
object BiaHistoryText {

    fun number(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)

    private fun withUnit(value: Float, unit: String): String =
        if (unit.isBlank()) number(value) else "${number(value)} $unit"

    /** Tutti i valori presenti, nell'ordine del form. */
    fun values(item: BiaMeasurementEntity): String = listOfNotNull(
        item.weightKg?.let { "Peso ${withUnit(it, "kg")}" },
        item.bodyFatPercent?.let { "Grasso ${withUnit(it, "%")}" },
        item.visceralFatLevel?.let { "Viscerale ${withUnit(it, "")}" },
        item.muscleMassKg?.let { "Massa muscolare ${withUnit(it, "kg")}" },
        item.skeletalMuscleKg?.let { "Scheletrico ${withUnit(it, "kg")}" },
        item.bodyWaterPercent?.let { "Acqua ${withUnit(it, "%")}" },
        item.bmrKcal?.let { "BMR ${withUnit(it, "kcal")}" },
        item.fatMassKg?.let { "Grasso ${withUnit(it, "kg")}" },
        item.leanMassKg?.let { "Massa magra ${withUnit(it, "kg")}" },
        item.bodyWaterKg?.let { "Acqua ${withUnit(it, "kg")}" },
        item.subcutaneousFatPercent?.let { "Sottocutaneo ${withUnit(it, "%")}" },
        item.boneMassKg?.let { "Ossa ${withUnit(it, "kg")}" },
        item.proteinPercent?.let { "Proteine ${withUnit(it, "%")}" },
        item.proteinKg?.let { "Proteine ${withUnit(it, "kg")}" },
        item.bodyAgeYears?.let { "Età corporea $it" },
        item.bmi?.let { "BMI ${number(it)}" },
    ).joinToString(" · ").ifBlank { "Valori parziali" }

    /** Solo i valori principali (peso, grasso, massa muscolare): per messaggi brevi come la conferma di eliminazione. */
    fun mainValues(item: BiaMeasurementEntity): String = listOfNotNull(
        item.weightKg?.let { "Peso ${withUnit(it, "kg")}" },
        item.bodyFatPercent?.let { "Grasso ${withUnit(it, "%")}" },
        item.muscleMassKg?.let { "Massa muscolare ${withUnit(it, "kg")}" },
    ).joinToString(" · ").ifBlank { values(item) }

    /** Condizioni della misura a parole (nessuna informazione solo a colore), o null se non ne e' stata indicata nessuna. */
    fun conditions(item: BiaMeasurementEntity): String? {
        val conditions = listOfNotNull(
            "a digiuno".takeIf { item.fasting },
            "appena sveglio".takeIf { item.justWokeUp },
            "dopo bagno".takeIf { item.afterBathroom },
            "nessun allenamento recente".takeIf { item.noRecentWorkout },
        )
        return conditions.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Condizioni: ")
    }

    /** [template] ha due segnaposto: data (%1$s) e valori (%2$s). Le condizioni, se presenti, chiudono la frase. */
    fun cardDescription(template: String, date: String, values: String, conditions: String?): String {
        val base = String.format(Locale.ITALIAN, template, date, values)
        return if (conditions.isNullOrBlank()) base else "$base $conditions."
    }
}
