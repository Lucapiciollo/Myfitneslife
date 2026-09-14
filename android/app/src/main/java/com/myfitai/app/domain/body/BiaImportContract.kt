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
    )

    class NotBiaDocument(message: String) : IllegalArgumentException(message)

    fun parse(json: String): Preview {
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
        )
    }

    fun validate(preview: Preview): Result<Unit> = runCatching {
        if (!preview.isBiaDocument) {
            require(preview.rejectionReason.isNotBlank()) { "Motivo di rifiuto BIA mancante" }
            return@runCatching
        }
        require(preview.rejectionReason.isBlank()) { "Un documento BIA non può avere un motivo di rifiuto" }
        require(listOf(preview.weightKg, preview.bodyFatPercent, preview.visceralFatLevel, preview.muscleMassKg, preview.skeletalMuscleKg, preview.bodyWaterPercent, preview.bmrKcal).any { it != null }) { "Nessun valore BIA leggibile" }
        listOf(preview.weightKg, preview.visceralFatLevel, preview.muscleMassKg, preview.skeletalMuscleKg, preview.bmrKcal).filterNotNull().forEach { require(it > 0f && it.isFinite()) }
        preview.bodyFatPercent?.let { require(it in 0f..100f) }
        preview.bodyWaterPercent?.let { require(it in 0f..100f) }
        require(preview.notes.length <= 2_000)
        require(preview.confidence in setOf("HIGH", "MEDIUM", "LOW")) { "Confidenza non valida" }
    }

    private fun JSONObject.optionalFloat(key: String): Float? = if (has(key) && !isNull(key)) optDouble(key).toFloat() else null
}
