package com.myfitai.app.domain.body

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

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
        val rawWeightKg = find(rows, kg, *weightAliases)
        // Le derivazioni usano solo un peso plausibile; quello impossibile viene segnalato da sanitize().
        val weightKg = BodyValueRanges.orNullIfOutOfRange(rawWeightKg, BodyValueRanges.WEIGHT_KG)
        val directBodyFatPercent = find(rows, percentUnits, *bodyFatPercentAliases)
        val fatMassKg = find(rows, kg, *fatMassAliases)
        val visceralFat = findAnyUnit(rows, *visceralAliases)
        val muscleMassKg = find(rows, kg, *muscleAliases)
        val skeletalMuscleKg = find(rows, kg, *skeletalAliases)
        val directWaterPercent = find(rows, percentUnits, *waterAliases)
        val waterKg = find(rows, setOf("kg", "l", "liter", "litre", "litri"), *waterAliases)
        val bmrKcal = find(rows, setOf("kcal", "kcal/day", "kcal/d", "kcal/giorno", "kcal/gg", "cal", "?", ""), *bmrAliases)
        val directLeanMassKg = find(rows, kg, *leanAliases)
        val boneMassKg = find(rows, kg, *boneAliases)
        val subcutaneousFatPercent = find(rows, percentUnits, *subcutaneousAliases)
        val directProteinPercent = find(rows, percentUnits, *proteinAliases)
        val directProteinKg = find(rows, kg, *proteinKgAliases)
        val bodyAgeYears = findAnyUnit(rows, *bodyAgeAliases)
        val bmi = findAnyUnit(rows, *bmiAliases)

        // Un valore derivato e' usato (e dichiarato) solo se quello esplicito manca; se entrambi ci sono
        // vince l'esplicito e un forte scostamento dalla formula diventa un avviso.
        val warnings = mutableListOf<String>()
        fun checkConsistency(label: String, explicitKg: Float?, percent: Float?, fromPercentKg: Float?) {
            val expected = fromPercentKg ?: return
            val kgValue = explicitKg ?: return
            if (abs(kgValue - expected) / max(kgValue, expected) > CONSISTENCY_TOLERANCE) {
                val read = String.format(Locale.ROOT, "%.1f", kgValue)
                val calc = String.format(Locale.ROOT, "%.1f", expected)
                val pct = percent?.let { " (" + String.format(Locale.ROOT, "%.1f", it) + "% del peso)" }.orEmpty()
                warnings += "$label: $read kg letti non coincidono con il valore atteso di $calc kg$pct. Usato il valore letto."
            }
        }
        checkConsistency("Massa grassa", fatMassKg, directBodyFatPercent, deriveMass(directBodyFatPercent, weightKg))
        checkConsistency("Acqua", waterKg, directWaterPercent, deriveMass(directWaterPercent, weightKg))
        checkConsistency("Proteine", directProteinKg, directProteinPercent, deriveMass(directProteinPercent, weightKg))
        val expectedFatMass = fatMassKg ?: deriveMass(directBodyFatPercent, weightKg)
        checkConsistency("Massa magra", directLeanMassKg, null, if (weightKg != null && expectedFatMass != null) weightKg - expectedFatMass else null)
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
                warnings = warnings,
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

    /** Scostamento relativo oltre cui due valori espliciti della stessa grandezza generano un avviso. */
    private const val CONSISTENCY_TOLERANCE = 0.05f

    // Sinonimi per campo (Fitdays, Renpho, Xiaomi, Garmin, Omron...), gia' normalizzati come le etichette
    // lette e in ordine di specificita': il primo che combacia esattamente vince. L'unita' finale
    // (kg, kcal, anni...) e' tolta dall'etichetta, quindi non va scritta negli alias.
    private val weightAliases = arrayOf("peso corporeo", "peso", "peso totale", "body weight", "total weight", "weight")
    private val bodyFatPercentAliases = arrayOf(
        "tasso di grasso corporeo", "percentuale di grasso corporeo", "percentuale grasso corporeo", "grasso corporeo percentuale",
        "grasso corporeo", "percentuale di grasso", "grasso", "percent body fat", "body fat percentage", "body fat rate",
        "body fat", "fat percentage", "fat rate", "pbf", "bfp", "bf", "fat", "massa grassa",
    )
    private val fatMassAliases = arrayOf(
        "massa grassa", "massa grassa corporea", "peso grasso", "grasso corporeo", "grasso", "body fat mass", "fat mass",
        "fat weight", "body fat", "fat",
    )
    private val visceralAliases = arrayOf(
        "grado di grasso viscerale", "livello di grasso viscerale", "indice di grasso viscerale", "grasso viscerale", "viscerale",
        "visceral fat level", "visceral fat rating", "visceral fat index", "visceral fat", "visceral",
    )
    private val muscleAliases = arrayOf(
        "massa muscolare totale", "massa muscolare", "massa muscolare corporea", "muscoli", "muscolo", "total muscle mass",
        "muscle mass", "muscle weight", "muscle",
    )
    private val skeletalAliases = arrayOf(
        "massa muscolo scheletrico", "massa muscolare scheletrica", "massa muscoli scheletrici", "muscolo scheletrico",
        "muscoli scheletrici", "muscolatura scheletrica", "skeletal muscle mass", "skeletal muscle weight", "skeletal muscle", "smm",
    )
    private val waterAliases = arrayOf(
        "contenuto d acqua", "acqua corporea totale", "acqua corporea", "percentuale di acqua", "percentuale acqua", "acqua totale",
        "acqua", "total body water", "body water percentage", "body water", "water percentage", "water rate", "water", "tbw",
    )
    private val bmrAliases = arrayOf(
        "tasso metabolico basale", "metabolismo basale", "basal metabolic rate", "resting metabolic rate", "bmr", "rmr",
    )
    private val leanAliases = arrayOf(
        "peso corporeo senza grasso", "massa magra corporea", "massa magra", "massa libera da grasso", "massa priva di grasso",
        "peso magro", "fat free body weight", "fat free mass", "lean body mass", "lean body weight", "lean mass", "ffm", "lbm", "lean",
    )
    private val boneAliases = arrayOf(
        "massa ossea", "peso osseo", "massa ossea minerale", "ossa", "bone mass", "bone mineral mass", "bone mineral content",
        "bone weight", "bone",
    )
    private val subcutaneousAliases = arrayOf(
        "grasso sottocutaneo", "sottocutaneo", "subcutaneous fat", "subcutaneous fat rate", "subcutaneous fat percentage",
    )
    private val proteinAliases = arrayOf(
        "quantita di proteine", "percentuale di proteine", "percentuale proteine", "proteine", "protein percentage",
        "protein rate", "proteins", "protein",
    )
    private val proteinKgAliases = arrayOf(
        "quantita di proteine", "massa proteica", "proteine", "protein mass", "protein weight", "proteins", "protein",
    )
    private val bodyAgeAliases = arrayOf(
        "eta corporea", "eta metabolica", "eta biologica", "eta del corpo", "body age", "metabolic age", "physical age",
    )
    private val bmiAliases = arrayOf("bmi", "indice di massa corporea", "imc", "body mass index")

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
