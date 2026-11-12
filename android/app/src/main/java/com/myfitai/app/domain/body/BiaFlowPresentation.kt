package com.myfitai.app.domain.body

import com.myfitai.app.domain.body.BodyValueRanges.BiaField
import org.json.JSONObject

/**
 * Logica pura attorno a import foto e analisi della schermata BIA: lettura del payload del job, confidenza in
 * parole, campi da ricontrollare, note con provenienza da salvare, riconoscimento del job dalle etichette di
 * WorkManager e testo d'errore dell'analisi. Nessun accesso ad Android: testabile sulla JVM.
 */
object BiaFlowPresentation {

    /** Testo fisso scritto da `BiaImportAiJobHandler` quando la foto non e' una BIA (non e' un codice tecnico). */
    const val NOT_BIA_PHOTO_MESSAGE = "La foto non sembra una rilevazione BIA."

    private const val ANALYSIS_ERROR_PREFIX = "BIA_PROGRESS_COACH_FAILED:"
    private const val MAX_NOTES_CHARS = 2_000

    enum class Confidence { HIGH, MEDIUM, LOW, UNKNOWN }

    fun confidence(raw: String?): Confidence = when (raw?.trim()?.uppercase()) {
        "HIGH" -> Confidence.HIGH
        "MEDIUM" -> Confidence.MEDIUM
        "LOW" -> Confidence.LOW
        else -> Confidence.UNKNOWN
    }

    fun isNotBiaPhoto(rawError: String?): Boolean = rawError?.trim() == NOT_BIA_PHOTO_MESSAGE

    /** Il job di analisi scrive `BIA_PROGRESS_COACH_FAILED:<codice o frase>`: alla tabella degli errori va il solo contenuto. */
    fun analysisError(rawError: String?): String? =
        rawError?.removePrefix(ANALYSIS_ERROR_PREFIX)?.trim()?.takeIf { it.isNotEmpty() }

    /** Con confidenza bassa ogni valore letto va ricontrollato; altrimenti nessuno e' segnalato in particolare. */
    fun fieldsToDoubleCheck(confidence: Confidence, values: Map<BiaField, Float?>): Set<BiaField> =
        if (confidence == Confidence.LOW) values.filterValues { it != null }.keys else emptySet()

    /** Chiave del job (timestamp) dalle etichette di WorkManager: `ai-bia_import-profile-<id>-job-<chiave>`. */
    fun jobKeyFromTags(tags: Set<String>): String? =
        tags.firstOrNull { it.contains(JOB_MARKER) }
            ?.substringAfterLast(JOB_MARKER)
            ?.takeIf { it.isNotBlank() }

    private const val JOB_MARKER = "-job-"

    /** Provenienza dell'import (note) piu' gli avvisi di lettura, entro il limite di salvataggio. */
    fun notesForSave(notes: String?, warnings: List<String>): String? {
        val parts = buildList {
            notes?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            if (warnings.isNotEmpty()) add("Avvisi di lettura: ${warnings.joinToString(" ")}")
        }
        return parts.joinToString(" ").take(MAX_NOTES_CHARS).takeIf { it.isNotEmpty() }
    }

    fun importValues(preview: BiaImportContract.Preview): Map<BiaField, Float?> = mapOf(
        BiaField.WEIGHT to preview.weightKg,
        BiaField.BODY_FAT to preview.bodyFatPercent,
        BiaField.VISCERAL to preview.visceralFatLevel,
        BiaField.MUSCLE to preview.muscleMassKg,
        BiaField.SKELETAL to preview.skeletalMuscleKg,
        BiaField.WATER_PERCENT to preview.bodyWaterPercent,
        BiaField.BMR to preview.bmrKcal,
        BiaField.FAT_MASS to preview.fatMassKg,
        BiaField.LEAN_MASS to preview.leanMassKg,
        BiaField.WATER_KG to preview.bodyWaterKg,
        BiaField.SUBCUTANEOUS to preview.subcutaneousFatPercent,
        BiaField.BONE to preview.boneMassKg,
        BiaField.PROTEIN_PCT to preview.proteinPercent,
        BiaField.PROTEIN_MASS to preview.proteinKg,
        BiaField.BODY_AGE to preview.bodyAgeYears,
        BiaField.BMI_FIELD to preview.bmi,
    )

    data class ImportResult(
        val preview: BiaImportContract.Preview,
        val provider: String,
        val model: String,
    )

    /** Payload scritto da `BiaImportAiJobHandler`; "warnings" e' facoltativo (compatibile con payload senza avvisi). */
    fun parseImportPayload(json: String): ImportResult {
        val p = JSONObject(json)
        fun number(key: String): Float? =
            if (p.has(key) && !p.isNull(key)) p.optDouble(key).toFloat().takeIf { it.isFinite() } else null
        val warnings = p.optJSONArray("warnings")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { text -> text.isNotEmpty() } }
        }.orEmpty()
        val preview = BiaImportContract.Preview(
            isBiaDocument = true,
            rejectionReason = "",
            measuredAtEpochMillis = p.optLong("measuredAtEpochMillis").takeIf { it > 0 },
            weightKg = number("weightKg"),
            bodyFatPercent = number("bodyFatPercent"),
            visceralFatLevel = number("visceralFatLevel"),
            muscleMassKg = number("muscleMassKg"),
            skeletalMuscleKg = number("skeletalMuscleKg"),
            bodyWaterPercent = number("bodyWaterPercent"),
            bmrKcal = number("bmrKcal"),
            confidence = p.optString("confidence"),
            notes = p.optString("notes"),
            fatMassKg = number("fatMassKg"),
            leanMassKg = number("leanMassKg"),
            bodyWaterKg = number("bodyWaterKg"),
            subcutaneousFatPercent = number("subcutaneousFatPercent"),
            boneMassKg = number("boneMassKg"),
            proteinPercent = number("proteinPercent"),
            proteinKg = number("proteinKg"),
            bodyAgeYears = number("bodyAgeYears"),
            bmi = number("bmi"),
            warnings = warnings,
        )
        return ImportResult(preview, p.optString("provider"), p.optString("model"))
    }
}
