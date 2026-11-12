package com.myfitai.app.domain.body

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Trasforma le righe grezze lette dalla foto in una [BiaImportContract.Preview].
 *
 * Le etichette sono abbinate per **etichetta normalizzata esatta** (o sinonimo), in ordine di
 * specificita': mai per sottostringa. Cosi' "Peso osseo" non diventa il peso corporeo e le righe
 * segmentali ("Massa muscolare braccio destro") non riempiono i campi del corpo intero, qualunque
 * sia l'ordine delle righe. Valori fuori dagli intervalli di [BodyValueRanges] diventano nulli con
 * una segnalazione in [BiaImportContract.Preview.warnings].
 */
object BiaMeasurementNormalizer {
    data class Result(val preview: BiaImportContract.Preview, val derivedFields: Set<String>, val source: String?)

    /** Come e' stato calcolato ogni campo derivato: finisce nelle note per non perdere la provenienza. */
    private val derivationFormulas = mapOf(
        "bodyFatPercent" to "grasso% da massa grassa/peso",
        "bodyWaterPercent" to "acqua% da acqua kg/peso",
        "fatMassKg" to "massa grassa da grasso%×peso",
        "leanMassKg" to "massa magra da peso−massa grassa",
        "bodyWaterKg" to "acqua kg da acqua%×peso",
        "proteinPercent" to "proteine% da proteine kg/peso",
        "proteinKg" to "proteine kg da proteine%×peso",
    )

    fun normalize(document: BiaRawImportContract.Document): Result {
        if (!document.isBiaDocument) {
            return Result(
                BiaImportContract.Preview(false, document.rejectionReason, null, null, null, null, null, null, null, null, document.confidence, ""),
                emptySet(), document.source,
            )
        }

        val rows = document.measurements
        val kg = setOf("kg")
        val rawWeightKg = find(rows, kg, "peso", "peso corporeo", "body weight", "weight")
        // Le derivazioni usano solo un peso plausibile; quello impossibile viene segnalato da sanitize().
        val weightKg = BodyValueRanges.orNullIfOutOfRange(rawWeightKg, BodyValueRanges.WEIGHT_KG)
        val directBodyFatPercent = find(rows, percentUnits, "tasso di grasso corporeo", "grasso corporeo", "percent body fat", "body fat percentage", "body fat", "pbf", "massa grassa")
        val fatMassKg = find(rows, kg, "massa grassa", "body fat mass", "fat mass")
        val visceralFat = findAnyUnit(rows, "grado di grasso viscerale", "grasso viscerale", "visceral fat level", "visceral fat")
        val muscleMassKg = find(rows, kg, "massa muscolare", "muscle mass")
        val skeletalMuscleKg = find(rows, kg, "muscolo scheletrico", "massa muscolare scheletrica", "skeletal muscle mass", "skeletal muscle", "smm")
        val waterAliases = arrayOf("contenuto d acqua", "acqua corporea", "body water", "total body water", "tbw")
        val directWaterPercent = find(rows, percentUnits, *waterAliases)
        val waterKg = find(rows, setOf("kg", "l", "liter", "litre", "litri"), *waterAliases)
        val bmrKcal = find(rows, setOf("kcal", "kcal/day", "kcal/d", "?"), "tasso metabolico basale", "metabolismo basale", "basal metabolic rate", "bmr")
        val directLeanMassKg = find(rows, kg, "peso corporeo senza grasso", "massa magra", "fat free mass", "lean body mass", "lean mass", "ffm")
        val boneMassKg = find(rows, kg, "massa ossea", "peso osseo", "bone mass", "bone mineral mass")
        val subcutaneousFatPercent = find(rows, percentUnits, "grasso sottocutaneo", "subcutaneous fat", "subcutaneous fat rate")
        val directProteinPercent = find(rows, percentUnits, "quantita di proteine", "proteine", "protein percentage", "protein rate", "protein")
        val directProteinKg = find(rows, kg, "quantita di proteine", "proteine", "protein mass", "protein")
        val bodyAgeYears = findAnyUnit(rows, "eta corporea", "eta metabolica", "body age", "metabolic age")
        val bmi = findAnyUnit(rows, "bmi", "indice di massa corporea", "body mass index")

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

        val preview = BiaImportContract.sanitize(
            BiaImportContract.Preview(
                isBiaDocument = true,
                rejectionReason = "",
                measuredAtEpochMillis = parseDate(document.measuredAtText),
                weightKg = rawWeightKg,
                bodyFatPercent = bodyFatPercent,
                visceralFatLevel = visceralFat,
                muscleMassKg = muscleMassKg,
                skeletalMuscleKg = skeletalMuscleKg,
                bodyWaterPercent = bodyWaterPercent,
                bmrKcal = bmrKcal,
                confidence = document.confidence,
                notes = "",
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
        )
        // Un derivato eliminato dal controllo di plausibilita' non va dichiarato come derivato.
        val keptDerived = derived.filterTo(linkedSetOf()) { field -> fieldValue(preview, field) != null }
        val notes = buildList {
            add("Import foto.")
            document.source?.let { add("Sorgente: $it.") }
            if (keptDerived.isNotEmpty()) {
                add("Derivati: ${keptDerived.joinToString("; ") { derivationFormulas[it] ?: it }}.")
            }
        }.joinToString(" ").take(2_000)

        return Result(preview.copy(notes = notes), keptDerived, document.source)
    }

    private fun fieldValue(preview: BiaImportContract.Preview, field: String): Float? = when (field) {
        "bodyFatPercent" -> preview.bodyFatPercent
        "bodyWaterPercent" -> preview.bodyWaterPercent
        "fatMassKg" -> preview.fatMassKg
        "leanMassKg" -> preview.leanMassKg
        "bodyWaterKg" -> preview.bodyWaterKg
        "proteinPercent" -> preview.proteinPercent
        "proteinKg" -> preview.proteinKg
        else -> null
    }

    private val percentUnits = setOf("%", "percent", "percentage")

    /** Prima etichetta esatta nell'ordine di [aliases] (dal piu' specifico), tra le righe con unita' ammessa. */
    private fun find(rows: List<BiaRawImportContract.Measurement>, units: Set<String>, vararg aliases: String): Float? =
        firstExact(rows.filter { normalizeUnit(it.rawUnit) in units }, aliases)

    private fun findAnyUnit(rows: List<BiaRawImportContract.Measurement>, vararg aliases: String): Float? =
        firstExact(rows, aliases)

    private fun firstExact(rows: List<BiaRawImportContract.Measurement>, aliases: Array<out String>): Float? {
        val labelled = rows.map { canonicalLabel(it.rawLabel) to it.value }
        aliases.forEach { alias ->
            val wanted = normalizeLabel(alias)
            labelled.firstOrNull { it.first == wanted }?.let { return it.second }
        }
        return null
    }

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

    /** Etichetta normalizzata senza l'unita' finale ("Peso corporeo (kg)" -> "peso corporeo"). */
    private fun canonicalLabel(value: String): String =
        normalizeLabel(value).replace(trailingUnit, "").trim()

    private val trailingUnit = "(\\s+(kg|kcal|l|percent|anni|years))+$".toRegex()

    private fun normalizeUnit(value: String): String = value.lowercase(Locale.ROOT).trim().replace("²", "2")
}
