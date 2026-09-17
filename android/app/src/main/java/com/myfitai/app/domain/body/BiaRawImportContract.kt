package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiCompactEnvelope
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

object BiaRawImportContract {
    const val SCHEMA_NAME = "myfitai_bia_raw_pipe_v2"
    const val PROTOCOL = "BIA2\nD|0_or_1|measuredAtText_or_?|source_or_?|HIGH_MEDIUM_LOW|rejectionReason|notes\nM|rawLabel|numericValue|rawUnit"
    val schemaJson: String get() = AiCompactEnvelope.schemaJson(PROTOCOL)

    data class Measurement(
        val rawLabel: String,
        val value: Float,
        val rawUnit: String,
    )

    data class Document(
        val isBiaDocument: Boolean,
        val measuredAtText: String?,
        val source: String?,
        val confidence: String,
        val rejectionReason: String,
        val notes: String,
        val measurements: List<Measurement>,
    )

    fun parseEnvelope(json: String): Document = parse(AiCompactEnvelope.data(json))

    fun parse(payload: String): Document {
        val lines = payload.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.size >= 2 && lines[0] == "BIA2") { "BIA_RAW_PIPE_INVALID" }

        val header = lines[1].split('|')
        require(header.size == 8 && header[0] == "D") { "BIA_RAW_HEADER_INVALID" }
        val isBia = when (header[1]) {
            "1" -> true
            "0" -> false
            else -> error("BIA_RAW_FLAG_INVALID")
        }

        val measurements = lines.drop(2).map { line ->
            val p = line.split('|')
            require(p.size == 4 && p[0] == "M") { "BIA_RAW_MEASUREMENT_INVALID" }
            val label = p[1].trim()
            val value = p[2].trim().toFloatOrNull()?.takeIf { it.isFinite() }
                ?: error("BIA_RAW_NUMBER_INVALID")
            val unit = p[3].trim()
            require(label.isNotBlank()) { "BIA_RAW_LABEL_INVALID" }
            Measurement(label, value, unit)
        }

        if (!isBia) require(measurements.isEmpty()) { "BIA_RAW_REJECTION_WITH_VALUES" }

        return Document(
            isBiaDocument = isBia,
            measuredAtText = header[2].trim().takeUnless { it == "?" || it.isBlank() },
            source = header[3].trim().takeUnless { it == "?" || it.isBlank() },
            confidence = header[4].trim(),
            rejectionReason = header[5].trim(),
            notes = header[6].trim() + header[7].trim().let { if (it.isBlank()) "" else if (header[6].isBlank()) it else " $it" },
            measurements = measurements,
        )
    }
}

object BiaMeasurementNormalizer {
    data class Result(
        val preview: BiaImportContract.Preview,
        val derivedFields: Set<String>,
        val source: String?,
    )

    fun normalize(document: BiaRawImportContract.Document): Result {
        if (!document.isBiaDocument) {
            return Result(
                preview = BiaImportContract.Preview(
                    isBiaDocument = false,
                    rejectionReason = document.rejectionReason,
                    measuredAtEpochMillis = null,
                    weightKg = null,
                    bodyFatPercent = null,
                    visceralFatLevel = null,
                    muscleMassKg = null,
                    skeletalMuscleKg = null,
                    bodyWaterPercent = null,
                    bmrKcal = null,
                    confidence = document.confidence,
                    notes = document.notes,
                ),
                derivedFields = emptySet(),
                source = document.source,
            )
        }

        val rows = document.measurements
        val weightKg = find(rows, Units.KG) { it.matchesAny("peso", "weight", "body weight") }
        val directBodyFatPercent = find(rows, Units.PERCENT) {
            it.matchesAny("tasso di grasso corporeo", "grasso corporeo", "percent body fat", "body fat percentage", "body fat", "pbf", "massa grassa")
        }
        val fatMassKg = find(rows, Units.KG) {
            it.matchesAny("massa grassa", "body fat mass", "fat mass")
        }
        val visceralFat = findAnyUnit(rows) {
            it.matchesAny("grado di grasso viscerale", "grasso viscerale", "visceral fat level", "visceral fat")
        }
        val muscleMassKg = find(rows, Units.KG) {
            !it.matchesAny("scheletrico", "skeletal") && it.matchesAny("massa muscolare", "muscle mass")
        }
        val skeletalMuscleKg = find(rows, Units.KG) {
            it.matchesAny("muscolo scheletrico", "massa muscolare scheletrica", "skeletal muscle mass", "skeletal muscle", "smm")
        }
        val directWaterPercent = find(rows, Units.PERCENT) {
            it.matchesAny("contenuto d acqua", "acqua corporea", "body water", "total body water", "tbw")
        }
        val waterKg = find(rows, setOf("kg", "l", "liter", "litre", "litri")) {
            it.matchesAny("contenuto d acqua", "acqua corporea", "body water", "total body water", "tbw")
        }
        val bmrKcal = find(rows, setOf("kcal", "kcal/day", "kcal/d", "?") ) {
            it.matchesAny("tasso metabolico basale", "metabolismo basale", "basal metabolic rate", "bmr")
        }

        val derived = linkedSetOf<String>()
        val bodyFatPercent = directBodyFatPercent ?: derivePercent(fatMassKg, weightKg)?.also { derived += "bodyFatPercent" }
        val bodyWaterPercent = directWaterPercent ?: derivePercent(waterKg, weightKg)?.also { derived += "bodyWaterPercent" }

        val sourceNote = document.source?.let { "Sorgente: $it." }.orEmpty()
        val derivedNote = if (derived.isEmpty()) "" else " Derivati: ${derived.joinToString()}."
        val notes = listOf(document.notes, sourceNote + derivedNote)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(2_000)

        return Result(
            preview = BiaImportContract.Preview(
                isBiaDocument = true,
                rejectionReason = "",
                measuredAtEpochMillis = parseDate(document.measuredAtText),
                weightKg = weightKg,
                bodyFatPercent = bodyFatPercent,
                visceralFatLevel = visceralFat,
                muscleMassKg = muscleMassKg,
                skeletalMuscleKg = skeletalMuscleKg,
                bodyWaterPercent = bodyWaterPercent,
                bmrKcal = bmrKcal,
                confidence = document.confidence,
                notes = notes,
            ),
            derivedFields = derived,
            source = document.source,
        )
    }

    private object Units {
        val KG = setOf("kg")
        val PERCENT = setOf("%", "percent", "percentage")
    }

    private fun find(
        rows: List<BiaRawImportContract.Measurement>,
        units: Set<String>,
        predicate: (String) -> Boolean,
    ): Float? = rows.firstOrNull { normalizeUnit(it.rawUnit) in units && predicate(normalizeLabel(it.rawLabel)) }?.value

    private fun findAnyUnit(
        rows: List<BiaRawImportContract.Measurement>,
        predicate: (String) -> Boolean,
    ): Float? = rows.firstOrNull { predicate(normalizeLabel(it.rawLabel)) }?.value

    private fun derivePercent(part: Float?, total: Float?): Float? {
        if (part == null || total == null || part <= 0f || total <= 0f) return null
        return (part / total * 100f).takeIf { it in 0f..100f }
    }

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val patterns = listOf(
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "dd-MM-yyyy HH:mm:ss",
            "dd-MM-yyyy HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.ITALIAN).apply { isLenient = false }.parse(value)?.time
            }.getOrNull()
        }
    }

    private fun normalizeLabel(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "")
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()

    private fun normalizeUnit(value: String): String = value.lowercase(Locale.ROOT).trim().replace("²", "2")

    private fun String.matchesAny(vararg aliases: String): Boolean = aliases.any { alias ->
        val normalizedAlias = normalizeLabel(alias)
        this == normalizedAlias || this.contains(normalizedAlias)
    }
}
