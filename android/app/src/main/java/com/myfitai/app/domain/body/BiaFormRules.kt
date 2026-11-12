package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.domain.body.BodyValueRanges.BiaField
import java.time.Instant
import java.time.ZoneId

/**
 * Regole pure del form BIA: controllo di un campo digitato contro [BodyValueRanges], data futura,
 * stato iniziale per l'avviso di uscita e conversione da/verso array (stato salvato alla rotazione).
 */
object BiaFormRules {

    sealed interface Check {
        data object Empty : Check
        data object NotANumber : Check
        /** Per i campi che sono numeri interi (eta' corporea). */
        data object NotWhole : Check
        data class Valid(val value: Float) : Check
        data class OutOfRange(val range: BodyValueRanges.Range) : Check
    }

    /** Virgola o punto decimale; testo vuoto = campo svuotato; testo non numerico MAI trattato come vuoto. */
    fun check(field: BiaField, raw: String?): Check {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Check.Empty
        val value = BodyMeasurementRanges.parse(text) ?: return Check.NotANumber
        if (field == BiaField.BODY_AGE && value % 1f != 0f) return Check.NotWhole
        return if (value in field.range) Check.Valid(value) else Check.OutOfRange(field.range)
    }

    /** True se il giorno (locale) della rilevazione e' dopo oggi. */
    fun isFutureDay(measuredAtMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        BodyMeasurementRanges.isFutureDate(
            Instant.ofEpochMilli(measuredAtMillis).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate(),
        )

    /** Fotografia del form (16 valori nell'ordine di [BiaField], istante di misura, 4 condizioni). */
    data class Snapshot(val values: List<Float?>, val measuredAtMillis: Long, val conditions: List<Boolean>)

    /** True se qualcosa e' cambiato rispetto allo stato di partenza; senza base (modifica non ancora caricata) mai sporco. */
    fun isDirty(baseline: Snapshot?, current: Snapshot): Boolean =
        baseline != null && baseline != current

    fun snapshot(values: Map<BiaField, Float?>, measuredAtMillis: Long, conditions: List<Boolean>) =
        Snapshot(BiaField.entries.map { values[it] }, measuredAtMillis, conditions)

    /** Valori di una rilevazione salvata, per campo. */
    fun valuesOf(item: BiaMeasurementEntity): Map<BiaField, Float?> = mapOf(
        BiaField.WEIGHT to item.weightKg,
        BiaField.BODY_FAT to item.bodyFatPercent,
        BiaField.VISCERAL to item.visceralFatLevel,
        BiaField.MUSCLE to item.muscleMassKg,
        BiaField.SKELETAL to item.skeletalMuscleKg,
        BiaField.WATER_PERCENT to item.bodyWaterPercent,
        BiaField.BMR to item.bmrKcal,
        BiaField.FAT_MASS to item.fatMassKg,
        BiaField.LEAN_MASS to item.leanMassKg,
        BiaField.WATER_KG to item.bodyWaterKg,
        BiaField.SUBCUTANEOUS to item.subcutaneousFatPercent,
        BiaField.BONE to item.boneMassKg,
        BiaField.PROTEIN_PCT to item.proteinPercent,
        BiaField.PROTEIN_MASS to item.proteinKg,
        BiaField.BODY_AGE to item.bodyAgeYears?.toFloat(),
        BiaField.BMI_FIELD to item.bmi,
    )

    /** `Float?` come array senza nullable (NaN = vuoto) per salvarlo in un Bundle. */
    fun toArray(values: List<Float?>): FloatArray = FloatArray(values.size) { values[it] ?: Float.NaN }

    fun fromArray(array: FloatArray?): List<Float?> =
        BiaField.entries.indices.map { index -> array?.getOrNull(index)?.takeUnless { it.isNaN() } }

    /** Testo per un campo di input: virgola decimale, nessuno zero inutile. */
    fun inputText(value: Float?): String = value?.let(BiaHistoryText::number).orEmpty()
}
