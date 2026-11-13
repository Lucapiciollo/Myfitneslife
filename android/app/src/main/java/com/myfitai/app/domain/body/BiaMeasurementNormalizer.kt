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
        val warnings = mutableListOf<String>()
        val rawWeightKg = findMass(rows, *weightAliases)
        // Le derivazioni usano solo un peso plausibile; quello impossibile viene segnalato da sanitize().
        val weightKg = BodyValueRanges.orNullIfOutOfRange(rawWeightKg, BodyValueRanges.WEIGHT_KG)
        // Le coppie % / kg dello stesso parametro (grasso, acqua, proteine) si abbinano per etichetta E unita'.
        val fat = resolvePair(
            rows, "Grasso corporeo", bodyFatPercentAliases, fatMassAliases, weightKg, warnings,
            percentPlausible = 2f..60f, massPlausible = 2f..60f,
        )
        val water = resolvePair(
            rows, "Acqua corporea", waterAliases, waterAliases, weightKg, warnings,
            percentPlausible = 40f..75f, massPlausible = 20f..70f,
        )
        val protein = resolvePair(
            rows, "Proteine", proteinAliases, proteinKgAliases, weightKg, warnings,
            percentPlausible = 8f..25f, massPlausible = 5f..25f,
        )
        val directBodyFatPercent = fat.percent
        val fatMassKg = fat.mass
        val visceralFat = findAnyUnit(rows, *visceralAliases)
        val muscleMassKg = findMass(rows, *muscleAliases)
        val skeletalMuscleKg = findMass(rows, *skeletalAliases)
        val directWaterPercent = water.percent
        val waterKg = water.mass
        val bmrKcal = find(rows, setOf("kcal", "kcal/day", "kcal/d", "kcal/giorno", "kcal/gg", "cal", "?", ""), *bmrAliases)
        val directLeanMassKg = findMass(rows, *leanAliases)
        val boneMassKg = findMass(rows, *boneAliases)
        val subcutaneousFatPercent = findPercent(rows, *subcutaneousAliases)
        val directProteinPercent = protein.percent
        val directProteinKg = protein.mass
        val bodyAgeYears = findAnyUnit(rows, *bodyAgeAliases)
        val bmi = findAnyUnit(rows, *bmiAliases)

        // Un valore derivato e' usato (e dichiarato) solo se quello esplicito manca; se entrambi ci sono
        // vince l'esplicito e un forte scostamento dalla formula diventa un avviso.
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

    /** Scostamento relativo oltre cui due valori espliciti della stessa grandezza generano un avviso. */
    private const val CONSISTENCY_TOLERANCE = 0.05f

    // Sinonimi per campo (Fitdays, Renpho, Xiaomi, Garmin, Omron...), gia' normalizzati come le etichette
    // lette e in ordine di specificita': il primo che combacia esattamente vince. L'unita' finale
    // (kg, kcal, anni...) e' tolta dall'etichetta, quindi non va scritta negli alias.
    private val weightAliases = arrayOf("peso corporeo", "peso", "peso totale", "body weight", "total weight", "weight")
    private val bodyFatPercentAliases = arrayOf(
        "tasso di grasso corporeo", "percentuale di grasso corporeo", "percentuale grasso corporeo", "grasso corporeo percentuale",
        "grasso corporeo", "percentuale di grasso", "grasso", "percent body fat", "body fat percentage", "body fat rate",
        "body fat", "fat percentage", "fat rate", "pbf", "bfp", "bf", "fat",
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

    /** Campi solo in kg: una riga in % non li riempie mai; una riga senza unita' e' ammessa (poi vale sanitize). */
    private fun findMass(rows: List<BiaRawImportContract.Measurement>, vararg aliases: String): Float? =
        firstExact(rows.filter { effectiveClass(it) in massOrUnknown }, aliases)

    /** Campi solo in %: una riga in kg non li riempie mai. */
    private fun findPercent(rows: List<BiaRawImportContract.Measurement>, vararg aliases: String): Float? =
        firstExact(rows.filter { effectiveClass(it) in percentOrUnknown }, aliases)

    private enum class UnitClass { PERCENT, MASS, OTHER, UNKNOWN }

    private val massOrUnknown = setOf(UnitClass.MASS, UnitClass.UNKNOWN)
    private val percentOrUnknown = setOf(UnitClass.PERCENT, UnitClass.UNKNOWN)
    private val massUnits = setOf("kg", "kgs", "kilogram", "kilograms", "chilogrammo", "chilogrammi", "chilo", "chili", "l", "lt", "liter", "litre", "litri")
    private val percentClassUnits = setOf("%", "percent", "percentage", "percento", "percentuale", "pct")
    private val otherUnits = setOf("kcal", "cal", "anni", "anno", "years", "year", "yrs", "cm", "lb", "lbs", "st")

    private fun unitKey(raw: String): String =
        raw.lowercase(Locale.ROOT).replace("％", "%").replace("²", "2").replace("[\\s.]+".toRegex(), "")

    private fun unitClass(raw: String): UnitClass {
        val u = unitKey(raw)
        return when {
            u in percentClassUnits -> UnitClass.PERCENT
            u in massUnits -> UnitClass.MASS
            u in otherUnits || "/" in u -> UnitClass.OTHER
            else -> UnitClass.UNKNOWN
        }
    }

    /** Unita' scritta nell'etichetta ("Acqua (%)", "Acqua (kg)"), se c'e'. */
    private fun labelUnit(rawLabel: String): UnitClass? {
        val l = rawLabel.lowercase(Locale.ROOT)
        return when {
            '%' in l || '％' in l -> UnitClass.PERCENT
            labelMassUnit.containsMatchIn(l) -> UnitClass.MASS
            else -> null
        }
    }

    private val labelMassUnit = "(\\(\\s*(kg|l)\\s*\\))|(\\s(kg)\\s*$)".toRegex()

    /** Unita' della riga: campo unita' e etichetta devono concordare, altrimenti la riga resta senza unita' certa. */
    private fun effectiveClass(m: BiaRawImportContract.Measurement): UnitClass {
        val byUnit = unitClass(m.rawUnit)
        val byLabel = labelUnit(m.rawLabel)
        return when {
            byUnit == UnitClass.OTHER -> UnitClass.OTHER
            byLabel == null -> byUnit
            byUnit == UnitClass.UNKNOWN -> byLabel
            byUnit == byLabel -> byUnit
            else -> UnitClass.UNKNOWN
        }
    }

    private data class Candidate(val label: String, val value: Float, val unit: UnitClass, val order: Int)
    private data class Resolved(val percent: Float?, val mass: Float?)

    private fun fmt(value: Float): String = String.format(Locale.ROOT, "%.1f", value)

    private fun consistentWithWeight(percent: Float, mass: Float, weightKg: Float): Boolean {
        val expected = percent / 100f * weightKg
        return abs(mass - expected) / max(mass, expected) <= PAIR_TOLERANCE
    }

    /**
     * Abbina le righe di un parametro disponibile sia in % sia in kg (grasso, acqua, proteine) per
     * etichetta **e unita'**: una riga in kg non riempie mai il campo %, e viceversa. Alias generici
     * ("Acqua", "Proteine") con unita' sbagliata o mancante si risolvono con il peso (percentuale x peso = kg)
     * e con intervalli plausibili per tipo di campo; se resta ambiguo il campo resta vuoto con un avviso.
     */
    private fun resolvePair(
        rows: List<BiaRawImportContract.Measurement>,
        name: String,
        percentAliases: Array<String>,
        massAliases: Array<String>,
        weightKg: Float?,
        warnings: MutableList<String>,
        percentPlausible: ClosedFloatingPointRange<Float>,
        massPlausible: ClosedFloatingPointRange<Float>,
    ): Resolved {
        val percentNorm = percentAliases.map(::normalizeLabel)
        val massNorm = massAliases.map(::normalizeLabel)
        val percentOrder = (percentNorm + massNorm).distinct()
        val candidates = rows.mapIndexedNotNull { index, row ->
            val unit = effectiveClass(row)
            val label = canonicalLabel(row.rawLabel)
            if (unit == UnitClass.OTHER || label !in percentOrder) null else Candidate(label, row.value, unit, index)
        }
        val percentRows = candidates.filter { it.unit == UnitClass.PERCENT }
            .sortedWith(compareBy({ percentOrder.indexOf(it.label) }, { it.order })).map { it.value }.distinct()
        val massRows = candidates.filter { it.unit == UnitClass.MASS && it.label in massNorm }
            .sortedWith(compareBy({ massNorm.indexOf(it.label) }, { it.order })).map { it.value }.distinct()
        val unknownRows = candidates.filter { it.unit == UnitClass.UNKNOWN }.sortedBy { it.order }
        val massCeiling = weightKg?.let { it * 1.01f } ?: Float.MAX_VALUE
        val massRange = massPlausible.start..minOf(massPlausible.endInclusive, massCeiling)

        var percent = percentRows.firstOrNull()
        var mass = massRows.firstOrNull()

        if (weightKg != null) {
            val declared = percentRows.firstNotNullOfOrNull { p ->
                massRows.firstOrNull { consistentWithWeight(p, it, weightKg) }?.let { p to it }
            }
            if (declared != null) {
                percent = declared.first
                mass = declared.second
            } else {
                // Unita' contraddittorie (due righe nella stessa unita', ruoli implausibili): cerco la coppia
                // % / kg che torna con il peso; se non c'e', non indovino.
                val suspect = percentRows.size > 1 || massRows.size > 1 || percent == null || mass == null ||
                    percent !in percentPlausible || mass !in massRange
                if (suspect) {
                    val pool = (percentRows + massRows).distinct()
                    val pair = pool.firstNotNullOfOrNull { p ->
                        if (p !in percentPlausible) return@firstNotNullOfOrNull null
                        pool.firstOrNull { m -> m != p && m in massRange && consistentWithWeight(p, m, weightKg) }?.let { p to it }
                    }
                    if (pair != null) {
                        val changed = pair.first != percent || pair.second != mass
                        percent = pair.first
                        mass = pair.second
                        if (changed) {
                            warnings += "$name: unita' delle righe incoerenti; interpretati ${fmt(pair.first)} come % e ${fmt(pair.second)} come kg perche' tornano con il peso."
                        }
                    }
                }
            }
        }

        // Righe senza unita' certa: riempiono solo il campo ancora vuoto, mai per sovrascrivere.
        for (row in unknownRows) {
            if (percent != null && mass != null) break
            val label = row.label
            val v = row.value
            val role: UnitClass? = when {
                label in percentNorm && label !in massNorm -> UnitClass.PERCENT
                label in massNorm && label !in percentNorm -> UnitClass.MASS
                weightKg != null && percent != null && mass == null && consistentWithWeight(percent, v, weightKg) -> UnitClass.MASS
                weightKg != null && mass != null && percent == null && consistentWithWeight(v, mass, weightKg) -> UnitClass.PERCENT
                else -> {
                    val asPercent = v in percentPlausible
                    val asMass = v in massRange
                    when {
                        asPercent && !asMass -> UnitClass.PERCENT
                        asMass && !asPercent -> UnitClass.MASS
                        else -> null
                    }
                }
            }
            when {
                role == UnitClass.PERCENT && percent == null -> percent = v
                role == UnitClass.MASS && mass == null -> mass = v
                role == null -> warnings += "$name: valore ${fmt(v)} senza unita' (% o kg) non assegnato perche' ambiguo."
            }
        }
        return Resolved(percent, mass)
    }

    private const val PAIR_TOLERANCE = 0.04f

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
