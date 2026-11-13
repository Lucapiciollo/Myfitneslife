package com.myfitai.app.domain.body

import org.json.JSONObject

object BiaImportContract {
    const val SCHEMA_NAME = "myfitai_bia_import_v1"
    val schemaJson = JSONObject("""
        {
          "type":"object","additionalProperties":false,
          "properties":{
            "isBiaDocument":{"type":"boolean"},
            "rejectionReason":{"type":"string"},
            "measuredAtEpochMillis":{"type":"integer"},
            "weightKg":{"type":"number"},
            "bodyFatPercent":{"type":"number"},
            "visceralFatLevel":{"type":"number"},
            "muscleMassKg":{"type":"number"},
            "skeletalMuscleKg":{"type":"number"},
            "bodyWaterPercent":{"type":"number"},
            "bmrKcal":{"type":"number"},
            "fatMassKg":{"type":"number"},
            "leanMassKg":{"type":"number"},
            "bodyWaterKg":{"type":"number"},
            "subcutaneousFatPercent":{"type":"number"},
            "boneMassKg":{"type":"number"},
            "proteinPercent":{"type":"number"},
            "proteinKg":{"type":"number"},
            "bodyAgeYears":{"type":"number"},
            "bmi":{"type":"number"},
            "confidence":{"type":"string","enum":["HIGH","MEDIUM","LOW"]},
            "notes":{"type":"string"}
          },
          "required":["isBiaDocument","rejectionReason","confidence","notes"]
        }
    """.trimIndent()).toString()

    data class Preview(
        val isBiaDocument: Boolean,
        val rejectionReason: String,
        val measuredAtEpochMillis: Long?,
        val weightKg: Float?,
        val bodyFatPercent: Float?,
        val visceralFatLevel: Float?,
        val muscleMassKg: Float?,
        val skeletalMuscleKg: Float?,
        val bodyWaterPercent: Float?,
        val bmrKcal: Float?,
        val confidence: String,
        val notes: String,
        val fatMassKg: Float? = null,
        val leanMassKg: Float? = null,
        val bodyWaterKg: Float? = null,
        val subcutaneousFatPercent: Float? = null,
        val boneMassKg: Float? = null,
        val proteinPercent: Float? = null,
        val proteinKg: Float? = null,
        val bodyAgeYears: Float? = null,
        val bmi: Float? = null,
        /** Valori scartati perché fuori da [BodyValueRanges]: la UI li mostra, mai valori impossibili. */
        val warnings: List<String> = emptyList(),
    )

    class NotBiaDocument(message: String) : IllegalArgumentException(message)

    /**
     * Annulla i campi fuori dagli intervalli plausibili di [BodyValueRanges] (e le masse maggiori del
     * peso) aggiungendo una segnalazione per ciascuno. Un documento non BIA resta invariato.
     */
    fun sanitize(preview: Preview): Preview {
        if (!preview.isBiaDocument) return preview
        val warnings = preview.warnings.toMutableList()
        fun clean(field: BodyValueRanges.BiaField, value: Float?): Float? {
            if (value == null) return null
            BodyValueRanges.violation(field.label, value, field.range)?.let { warnings += "$it Valore ignorato." }
            return BodyValueRanges.orNullIfOutOfRange(value, field.range)
        }
        val weight = clean(BodyValueRanges.BiaField.WEIGHT, preview.weightKg)
        val limit = weight?.times(1.01f)
        fun mass(field: BodyValueRanges.BiaField, value: Float?): Float? {
            val cleaned = clean(field, value)
            if (cleaned != null && limit != null && cleaned > limit) {
                warnings += "${field.label}: ${cleaned} kg supera il peso (${weight} kg). Valore ignorato."
                return null
            }
            return cleaned
        }
        val cleaned = preview.copy(
            weightKg = weight,
            bodyFatPercent = clean(BodyValueRanges.BiaField.BODY_FAT, preview.bodyFatPercent),
            visceralFatLevel = clean(BodyValueRanges.BiaField.VISCERAL, preview.visceralFatLevel),
            muscleMassKg = mass(BodyValueRanges.BiaField.MUSCLE, preview.muscleMassKg),
            skeletalMuscleKg = clean(BodyValueRanges.BiaField.SKELETAL, preview.skeletalMuscleKg),
            bodyWaterPercent = clean(BodyValueRanges.BiaField.WATER_PERCENT, preview.bodyWaterPercent),
            bmrKcal = clean(BodyValueRanges.BiaField.BMR, preview.bmrKcal),
            fatMassKg = mass(BodyValueRanges.BiaField.FAT_MASS, preview.fatMassKg),
            leanMassKg = mass(BodyValueRanges.BiaField.LEAN_MASS, preview.leanMassKg),
            bodyWaterKg = mass(BodyValueRanges.BiaField.WATER_KG, preview.bodyWaterKg),
            subcutaneousFatPercent = clean(BodyValueRanges.BiaField.SUBCUTANEOUS, preview.subcutaneousFatPercent),
            boneMassKg = mass(BodyValueRanges.BiaField.BONE, preview.boneMassKg),
            proteinPercent = clean(BodyValueRanges.BiaField.PROTEIN_PCT, preview.proteinPercent),
            proteinKg = mass(BodyValueRanges.BiaField.PROTEIN_MASS, preview.proteinKg),
            bodyAgeYears = clean(BodyValueRanges.BiaField.BODY_AGE, preview.bodyAgeYears),
            bmi = clean(BodyValueRanges.BiaField.BMI_FIELD, preview.bmi),
        )
        val result = cleaned.copy(warnings = warnings)
        return result.copy(warnings = warnings + coherenceWarnings(result))
    }

    /** Controlli di coerenza tra grandezze in kg: segnalano soltanto, non cambiano i valori. */
    private fun coherenceWarnings(p: Preview): List<String> = buildList {
        fun kg(value: Float) = String.format(java.util.Locale.ROOT, "%.1f", value)
        val weight = p.weightKg
        val fat = p.fatMassKg
        val lean = p.leanMassKg
        if (weight != null && fat != null && lean != null && kotlin.math.abs(fat + lean - weight) / weight > COHERENCE_TOLERANCE) {
            add("Massa grassa + massa magra (${kg(fat + lean)} kg) non coincide con il peso (${kg(weight)} kg): controllare la lettura.")
        }
        val leanLimit = lean?.times(1.02f)
        if (leanLimit != null) {
            p.bodyWaterKg?.takeIf { it > leanLimit }?.let { add("Acqua (${kg(it)} kg) supera la massa magra (${kg(lean)} kg): controllare unita' e valori.") }
            p.muscleMassKg?.takeIf { it > leanLimit }?.let { add("Massa muscolare (${kg(it)} kg) supera la massa magra (${kg(lean)} kg): controllare la lettura.") }
        }
        val muscle = p.muscleMassKg
        val skeletal = p.skeletalMuscleKg
        if (muscle != null && skeletal != null && skeletal > muscle * 1.02f) {
            add("Muscolo scheletrico (${kg(skeletal)} kg) supera la massa muscolare (${kg(muscle)} kg): controllare la lettura.")
        }
    }

    private const val COHERENCE_TOLERANCE = 0.10f

    fun parse(json: String): Preview = sanitize(parseRaw(json))

    private fun parseRaw(json: String): Preview {
        val root = JSONObject(json)
        return Preview(
            isBiaDocument = root.getBoolean("isBiaDocument"),
            rejectionReason = root.getString("rejectionReason").trim(),
            measuredAtEpochMillis = root.optLong("measuredAtEpochMillis", 0L).takeIf { it > 0L },
            weightKg = root.optionalFloat("weightKg"),
            bodyFatPercent = root.optionalFloat("bodyFatPercent"),
            visceralFatLevel = root.optionalFloat("visceralFatLevel"),
            muscleMassKg = root.optionalFloat("muscleMassKg"),
            skeletalMuscleKg = root.optionalFloat("skeletalMuscleKg"),
            bodyWaterPercent = root.optionalFloat("bodyWaterPercent"),
            bmrKcal = root.optionalFloat("bmrKcal"),
            confidence = root.getString("confidence"),
            notes = root.getString("notes"),
            fatMassKg = root.optionalFloat("fatMassKg"),
            leanMassKg = root.optionalFloat("leanMassKg"),
            bodyWaterKg = root.optionalFloat("bodyWaterKg"),
            subcutaneousFatPercent = root.optionalFloat("subcutaneousFatPercent"),
            boneMassKg = root.optionalFloat("boneMassKg"),
            proteinPercent = root.optionalFloat("proteinPercent"),
            proteinKg = root.optionalFloat("proteinKg"),
            bodyAgeYears = root.optionalFloat("bodyAgeYears"),
            bmi = root.optionalFloat("bmi"),
        )
    }

    fun validate(preview: Preview): Result<Unit> = runCatching {
        if (!preview.isBiaDocument) {
            require(preview.rejectionReason.isNotBlank()) { "Motivo di rifiuto BIA mancante" }
            return@runCatching
        }
        require(preview.rejectionReason.isBlank()) { "Un documento BIA non può avere un motivo di rifiuto" }
        require(listOf(preview.weightKg, preview.bodyFatPercent, preview.visceralFatLevel, preview.muscleMassKg, preview.skeletalMuscleKg, preview.bodyWaterPercent, preview.bmrKcal, preview.fatMassKg, preview.leanMassKg, preview.bodyWaterKg, preview.subcutaneousFatPercent, preview.boneMassKg, preview.proteinPercent, preview.proteinKg, preview.bodyAgeYears, preview.bmi).any { it != null }) { "Nessun valore BIA leggibile" }
        BodyValueRanges.validateBia(preview.toRangeValues())?.let { throw IllegalArgumentException(it) }
        require(preview.notes.length <= 2_000)
        require(preview.confidence in setOf("HIGH", "MEDIUM", "LOW")) { "Confidenza non valida" }
    }

    private fun Preview.toRangeValues() = BodyValueRanges.BiaValues(
        weightKg = weightKg, bodyFatPercent = bodyFatPercent, visceralFatLevel = visceralFatLevel,
        muscleMassKg = muscleMassKg, skeletalMuscleKg = skeletalMuscleKg, bodyWaterPercent = bodyWaterPercent,
        bmrKcal = bmrKcal, fatMassKg = fatMassKg, leanMassKg = leanMassKg, bodyWaterKg = bodyWaterKg,
        subcutaneousFatPercent = subcutaneousFatPercent, boneMassKg = boneMassKg, proteinPercent = proteinPercent,
        proteinKg = proteinKg, bodyAgeYears = bodyAgeYears, bmi = bmi,
    )

    private fun JSONObject.optionalFloat(key: String): Float? = if (has(key) && !isNull(key)) optDouble(key).toFloat() else null
}
