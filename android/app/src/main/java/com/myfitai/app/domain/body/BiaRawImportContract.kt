package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiCompactEnvelope
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

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

object BiaMeasurementNormalizer {
    data class Result(val preview: BiaImportContract.Preview, val derivedFields: Set<String>, val source: String?)

    fun normalize(document: BiaRawImportContract.Document): Result {
        if (!document.isBiaDocument) {
            return Result(
                BiaImportContract.Preview(false, document.rejectionReason, null, null, null, null, null, null, null, null, document.confidence, ""),
                emptySet(), document.source,
            )
        }

        val rows = document.measurements
        val weightKg = find(rows, setOf("kg")) { it.matchesAny("peso", "weight", "body weight") }
        val directBodyFatPercent = find(rows, percentUnits) { it.matchesAny("tasso di grasso corporeo", "grasso corporeo", "percent body fat", "body fat percentage", "body fat", "pbf", "massa grassa") }
        val fatMassKg = find(rows, setOf("kg")) { it.matchesAny("massa grassa", "body fat mass", "fat mass") }
        val visceralFat = findAnyUnit(rows) { it.matchesAny("grado di grasso viscerale", "grasso viscerale", "visceral fat level", "visceral fat") }
        val muscleMassKg = find(rows, setOf("kg")) { !it.matchesAny("scheletrico", "skeletal") && it.matchesAny("massa muscolare", "muscle mass") }
        val skeletalMuscleKg = find(rows, setOf("kg")) { it.matchesAny("muscolo scheletrico", "massa muscolare scheletrica", "skeletal muscle mass", "skeletal muscle", "smm") }
        val directWaterPercent = find(rows, percentUnits) { it.matchesAny("contenuto d acqua", "acqua corporea", "body water", "total body water", "tbw") }
        val waterKg = find(rows, setOf("kg", "l", "liter", "litre", "litri")) { it.matchesAny("contenuto d acqua", "acqua corporea", "body water", "total body water", "tbw") }
        val bmrKcal = find(rows, setOf("kcal", "kcal/day", "kcal/d", "?")) { it.matchesAny("tasso metabolico basale", "metabolismo basale", "basal metabolic rate", "bmr") }
        val directLeanMassKg = find(rows, setOf("kg")) { it.matchesAny("peso corporeo senza grasso", "massa magra", "fat free mass", "fat-free mass", "lean body mass", "lean mass", "ffm") }
        val boneMassKg = find(rows, setOf("kg")) { it.matchesAny("massa ossea", "peso osseo", "bone mass", "bone mineral mass") }
        val subcutaneousFatPercent = find(rows, percentUnits) { it.matchesAny("grasso sottocutaneo", "subcutaneous fat", "subcutaneous fat rate") }
        val directProteinPercent = find(rows, percentUnits) { it.matchesAny("quantita di proteine", "proteine", "protein percentage", "protein rate", "protein") }
        val directProteinKg = find(rows, setOf("kg")) { it.matchesAny("quantita di proteine", "proteine", "protein mass", "protein") }
        val bodyAgeYears = findAnyUnit(rows) { it.matchesAny("eta corporea", "eta metabolica", "body age", "metabolic age") }
        val bmi = findAnyUnit(rows) { it.matchesAny("bmi", "indice di massa corporea", "body mass index") }

        val derived = linkedSetOf<String>()
        val bodyFatPercent = directBodyFatPercent ?: derivePercent(fatMassKg, weightKg)?.also { derived += "bodyFatPercent" }
        val bodyWaterPercent = directWaterPercent ?: derivePercent(waterKg, weightKg)?.also { derived += "bodyWaterPercent" }
        val resolvedFatMassKg = fatMassKg ?: deriveMass(bodyFatPercent, weightKg)?.also { derived += "fatMassKg" }
        val leanMassKg = directLeanMassKg ?: if (weightKg != null && resolvedFatMassKg != null) {
            (weightKg - resolvedFatMassKg).takeIf { it > 0f }?.also { derived += "leanMassKg" }
        } else null
        val resolvedWaterKg = waterKg ?: deriveMass(bodyWaterPercent, weightKg)?.also { derived += "bodyWaterKg" }
        val proteinPercent = directProteinPercent ?: derivePercent(directProteinKg, weightKg)?.also { derived += "proteinPercent" }
        val proteinKg = directProteinKg ?: deriveMass(proteinPercent, weightKg)?.also { derived += "proteinKg" }
        val notes = buildList {
            document.source?.let { add("Sorgente: $it.") }
            if (derived.isNotEmpty()) add("Derivati: ${derived.joinToString()}.")
        }.joinToString(" ").take(2_000)

        return Result(
            BiaImportContract.Preview(
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
                fatMassKg = resolvedFatMassKg,
                leanMassKg = leanMassKg,
                bodyWaterKg = resolvedWaterKg,
                subcutaneousFatPercent = subcutaneousFatPercent,
                boneMassKg = boneMassKg,
                proteinPercent = proteinPercent,
                proteinKg = proteinKg,
                bodyAgeYears = bodyAgeYears,
                bmi = bmi,
            ),
            derived, document.source,
        )
    }

    private val percentUnits = setOf("%", "percent", "percentage")

    private fun find(rows: List<BiaRawImportContract.Measurement>, units: Set<String>, predicate: (String) -> Boolean): Float? =
        rows.firstOrNull { normalizeUnit(it.rawUnit) in units && predicate(normalizeLabel(it.rawLabel)) }?.value

    private fun findAnyUnit(rows: List<BiaRawImportContract.Measurement>, predicate: (String) -> Boolean): Float? =
        rows.firstOrNull { predicate(normalizeLabel(it.rawLabel)) }?.value

    private fun derivePercent(part: Float?, total: Float?): Float? {
        if (part == null || total == null || part <= 0f || total <= 0f) return null
        return (part / total * 100f).takeIf { it in 0f..100f }
    }

    private fun deriveMass(percent: Float?, total: Float?): Float? {
        if (percent == null || total == null || percent !in 0f..100f || total <= 0f) return null
        return (percent / 100f * total).takeIf { it > 0f }
    }

    private fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val patterns = listOf("dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm")
        return patterns.firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.ITALIAN).apply { isLenient = false }.parse(value)?.time }.getOrNull()
        }
    }

    private fun normalizeLabel(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "").replace("[^a-z0-9]+".toRegex(), " ").trim()

    private fun normalizeUnit(value: String): String = value.lowercase(Locale.ROOT).trim().replace("²", "2")

    private fun String.matchesAny(vararg aliases: String): Boolean = aliases.any { alias ->
        val a = normalizeLabel(alias)
        this == a || this.contains(a)
    }
}
