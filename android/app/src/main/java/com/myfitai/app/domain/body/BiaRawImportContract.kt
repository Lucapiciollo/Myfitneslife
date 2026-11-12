package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiCompactEnvelope

object BiaRawImportContract {
    const val SCHEMA_NAME = "myfitai_bia_raw_pipe_v2"
    const val PROTOCOL = "B2\nD|0_or_1|date_or_?|source_or_?|H_M_L|reason\nM|rawLabel|value|unit"
    val schemaJson: String get() = AiCompactEnvelope.schemaJson(PROTOCOL)

    data class Measurement(val rawLabel: String, val value: Float, val rawUnit: String)

    data class Document(
        val isBiaDocument: Boolean,
        val measuredAtText: String?,
        val source: String?,
        val confidence: String,
        val rejectionReason: String,
        val measurements: List<Measurement>,
    )

    fun parseEnvelope(json: String): Document = parse(AiCompactEnvelope.data(json))

    fun parse(payload: String): Document {
        val lines = payload.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.size >= 2 && lines[0] == "B2") { "BIA_RAW_PIPE_INVALID" }
        val h = lines[1].split('|')
        require(h.size == 6 && h[0] == "D") { "BIA_RAW_HEADER_INVALID" }
        val isBia = when (h[1]) { "1" -> true; "0" -> false; else -> error("BIA_RAW_FLAG_INVALID") }
        val confidence = when (h[4]) { "H" -> "HIGH"; "M" -> "MEDIUM"; "L" -> "LOW"; else -> error("BIA_RAW_CONFIDENCE_INVALID") }
        val measurements = lines.drop(2).map { line ->
            val p = line.split('|')
            require(p.size == 4 && p[0] == "M") { "BIA_RAW_MEASUREMENT_INVALID" }
            val label = p[1].trim()
            val value = p[2].trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: error("BIA_RAW_NUMBER_INVALID")
            require(label.isNotBlank()) { "BIA_RAW_LABEL_INVALID" }
            Measurement(label, value, p[3].trim())
        }
        if (!isBia) require(measurements.isEmpty()) { "BIA_RAW_REJECTION_WITH_VALUES" }
        return Document(
            isBiaDocument = isBia,
            measuredAtText = h[2].trim().takeUnless { it == "?" || it.isBlank() },
            source = h[3].trim().takeUnless { it == "?" || it.isBlank() },
            confidence = confidence,
            rejectionReason = h[5].trim(),
            measurements = measurements,
        )
    }
}
