package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiCompactEnvelope
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object BiaImportCompactContract {
    const val SCHEMA_NAME = "myfitai_bia_pipe_v1"
    val schemaJson: String get() = AiCompactEnvelope.schemaJson
    const val PROTOCOL = "BIA1\nB|0_or_1|timestamp_or_?|weight_or_?|bodyFat_or_?|visceral_or_?|muscle_or_?|skeletal_or_?|water_or_?|bmr_or_?|HIGH_MEDIUM_LOW|rejectionReason|notes"

    fun parseEnvelope(json: String): BiaImportContract.Preview = parse(AiCompactEnvelope.data(json))

    fun parse(payload: String): BiaImportContract.Preview {
        // Some Gemini compact responses concatenate the protocol header and its only record
        // despite the prompt requiring a newline. Restore the structural boundary before parsing;
        // all 13 positional fields are still validated strictly below.
        val trimmedPayload = payload.trim().replace("\\n", "\n")
        val normalizedPayload = if (trimmedPayload.startsWith("BIA1") && !trimmedPayload.contains('\n')) {
            val record = trimmedPayload.substring(4).trimStart().trimStart('|').trimStart()
            if (record.startsWith("B|")) "BIA1\n$record" else trimmedPayload
        } else {
            trimmedPayload.replace(Regex("^BIA1\\s*B\\|"), "BIA1\nB|")
        }
        val lines = normalizedPayload.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.size == 2 && lines[0] == "BIA1") { "BIA_PIPE_INVALID" }
        val p = lines[1].split('|')
        require(p.size == 13 && p[0] == "B") { "BIA_PIPE_FIELDS_INVALID" }
        val isBia = when (p[1]) { "1" -> true; "0" -> false; else -> error("BIA_PIPE_FLAG_INVALID") }
        return BiaImportContract.Preview(
            isBiaDocument = isBia,
            rejectionReason = p[11].trim(),
            measuredAtEpochMillis = p[2].timestampOrNull(),
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

    private fun String.floatOrNull(): Float? = if (trim() == "?") null else trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() } ?: error("BIA_PIPE_NUMBER_INVALID value='$this'")

    /** Accept epoch millis and the date/time formats providers commonly return for a visible report. */
    private fun String.timestampOrNull(): Long? {
        val value = trim()
        if (value == "?") return null
        value.toLongOrNull()?.let { return it }
        runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it }
        listOf("dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy").forEach { pattern ->
            runCatching {
                LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern))
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()?.let { return it }
        }
        // Timestamp is optional metadata. Never reject otherwise readable measurements because
        // the provider formatted the visible date unexpectedly; do not guess a replacement date.
        return null
    }
}
