package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiCompactEnvelope

object BiaImportCompactContract {
    const val SCHEMA_NAME = "myfitai_bia_pipe_v1"
    val schemaJson: String get() = AiCompactEnvelope.schemaJson
    const val PROTOCOL = "BIA1\nB|0_or_1|timestamp_or_?|weight_or_?|bodyFat_or_?|visceral_or_?|muscle_or_?|skeletal_or_?|water_or_?|bmr_or_?|HIGH_MEDIUM_LOW|rejectionReason|notes"

    fun parseEnvelope(json: String): BiaImportContract.Preview = parse(AiCompactEnvelope.data(json))

    fun parse(payload: String): BiaImportContract.Preview {
        val lines = payload.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.size == 2 && lines[0] == "BIA1") { "BIA_PIPE_INVALID" }
        val p = lines[1].split('|')
        require(p.size == 13 && p[0] == "B") { "BIA_PIPE_FIELDS_INVALID" }
        val isBia = when (p[1]) { "1" -> true; "0" -> false; else -> error("BIA_PIPE_FLAG_INVALID") }
        return BiaImportContract.Preview(
            isBiaDocument = isBia,
            rejectionReason = p[11].trim(),
            measuredAtEpochMillis = p[2].longOrNull(),
            weightKg = p[3].floatOrNull(),
            bodyFatPercent = p[4].floatOrNull(),
            visceralFatLevel = p[5].floatOrNull(),
            muscleMassKg = p[6].floatOrNull(),
            skeletalMuscleKg = p[7].floatOrNull(),
            bodyWaterPercent = p[8].floatOrNull(),
            bmrKcal = p[9].floatOrNull(),
            confidence = p[10].trim(),
            notes = p[12].trim(),
        )
    }

    private fun String.floatOrNull(): Float? = if (this == "?") null else toFloatOrNull()?.takeIf { it.isFinite() } ?: error("BIA_PIPE_NUMBER_INVALID")
    private fun String.longOrNull(): Long? = if (this == "?") null else toLongOrNull() ?: error("BIA_PIPE_TIMESTAMP_INVALID")
}
