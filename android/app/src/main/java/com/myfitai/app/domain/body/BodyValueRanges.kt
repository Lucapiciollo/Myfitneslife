package com.myfitai.app.domain.body

import java.util.Locale

/**
 * Unica fonte degli intervalli plausibili per i valori corporei inseriti a mano o letti da una foto.
 *
 * Usata da `BiaViewModel` (salvataggio e modifica), `BiaImportContract`/`BiaMeasurementNormalizer`
 * (import da foto: i campi fuori intervallo diventano nulli con una segnalazione) e
 * `BodyMeasurementsViewModel` (peso e circonferenze).
 *
 * Gli intervalli sono volutamente larghi: servono a scartare valori impossibili (refusi, cifre
 * lette male dalla foto, unita' sbagliate), non a giudicare lo stato di salute.
 *
 * TODO(K3, dopo il merge): il form delle misure ha `BodyMeasurementRanges`; deve riferirsi a
 * [CIRCUMFERENCE_CM] e [WEIGHT_KG] di questo oggetto invece di duplicare i limiti.
 */
object BodyValueRanges {
    data class Range(val min: Float, val max: Float, val unit: String) {
        operator fun contains(value: Float): Boolean = value.isFinite() && value in min..max

        /** Testo per l'utente, ad esempio `30–300 kg`. */
        fun describe(): String = listOf("${format(min)}–${format(max)}", unit).filter { it.isNotBlank() }.joinToString(" ")
    }

    val WEIGHT_KG = Range(30f, 300f, "kg")
    val BODY_FAT_PERCENT = Range(2f, 60f, "%")
    val VISCERAL_FAT_LEVEL = Range(1f, 59f, "")
    val MUSCLE_MASS_KG = Range(10f, 150f, "kg")
    val SKELETAL_MUSCLE_KG = Range(5f, 100f, "kg")
    val BODY_WATER_PERCENT = Range(20f, 80f, "%")
    val BMR_KCAL = Range(500f, 5_000f, "kcal")
    val FAT_MASS_KG = Range(1f, 200f, "kg")
    val LEAN_MASS_KG = Range(10f, 200f, "kg")
    val BODY_WATER_KG = Range(10f, 150f, "kg")
    val SUBCUTANEOUS_FAT_PERCENT = Range(2f, 60f, "%")
    val BONE_MASS_KG = Range(0.5f, 10f, "kg")
    val PROTEIN_PERCENT = Range(5f, 40f, "%")
    val PROTEIN_KG = Range(1f, 60f, "kg")
    val BODY_AGE_YEARS = Range(10f, 99f, "anni")
    val BMI = Range(10f, 70f, "")
    val CIRCUMFERENCE_CM = Range(10f, 250f, "cm")

    /** Tutti i campi BIA con etichetta, intervallo e accesso al valore. */
    enum class BiaField(val label: String, val range: Range, val read: (BiaValues) -> Float?) {
        WEIGHT("Peso", WEIGHT_KG, { it.weightKg }),
        BODY_FAT("Grasso corporeo", BODY_FAT_PERCENT, { it.bodyFatPercent }),
        VISCERAL("Grasso viscerale", VISCERAL_FAT_LEVEL, { it.visceralFatLevel }),
        MUSCLE("Massa muscolare", MUSCLE_MASS_KG, { it.muscleMassKg }),
        SKELETAL("Muscolo scheletrico", SKELETAL_MUSCLE_KG, { it.skeletalMuscleKg }),
        WATER_PERCENT("Acqua corporea", BODY_WATER_PERCENT, { it.bodyWaterPercent }),
        BMR("Metabolismo basale", BMR_KCAL, { it.bmrKcal }),
        FAT_MASS("Massa grassa", FAT_MASS_KG, { it.fatMassKg }),
        LEAN_MASS("Massa magra", LEAN_MASS_KG, { it.leanMassKg }),
        WATER_KG("Acqua corporea (kg)", BODY_WATER_KG, { it.bodyWaterKg }),
        SUBCUTANEOUS("Grasso sottocutaneo", SUBCUTANEOUS_FAT_PERCENT, { it.subcutaneousFatPercent }),
        BONE("Massa ossea", BONE_MASS_KG, { it.boneMassKg }),
        PROTEIN_PCT("Proteine", PROTEIN_PERCENT, { it.proteinPercent }),
        PROTEIN_MASS("Proteine (kg)", PROTEIN_KG, { it.proteinKg }),
        BODY_AGE("Età corporea", BODY_AGE_YEARS, { it.bodyAgeYears }),
        BMI_FIELD("BMI", BMI, { it.bmi }),
    }

    data class BiaValues(
        val weightKg: Float? = null,
        val bodyFatPercent: Float? = null,
        val visceralFatLevel: Float? = null,
        val muscleMassKg: Float? = null,
        val skeletalMuscleKg: Float? = null,
        val bodyWaterPercent: Float? = null,
        val bmrKcal: Float? = null,
        val fatMassKg: Float? = null,
        val leanMassKg: Float? = null,
        val bodyWaterKg: Float? = null,
        val subcutaneousFatPercent: Float? = null,
        val boneMassKg: Float? = null,
        val proteinPercent: Float? = null,
        val proteinKg: Float? = null,
        val bodyAgeYears: Float? = null,
        val bmi: Float? = null,
    ) {
        fun isEmpty(): Boolean = BiaField.entries.all { it.read(this) == null }
    }

    /** Messaggio di errore con campo, valore e intervallo oppure null se il valore è assente o plausibile. */
    fun violation(label: String, value: Float?, range: Range): String? {
        if (value == null) return null
        if (value in range) return null
        return "$label: ${format(value)}${range.unit.let { if (it.isBlank()) "" else " $it" }} non è plausibile (intervallo ${range.describe()})."
    }

    /** `null` se il valore è assente o fuori intervallo: per l'import da foto, mai valori impossibili. */
    fun orNullIfOutOfRange(value: Float?, range: Range): Float? = value?.takeIf { it in range }

    /**
     * Primo errore trovato nei valori BIA, o null. Controlla ogni intervallo e la coerenza con il peso
     * (massa magra, grassa, muscolare, ossea e acqua non possono superare il peso).
     */
    fun validateBia(values: BiaValues): String? {
        BiaField.entries.forEach { field ->
            violation(field.label, field.read(values), field.range)?.let { return it }
        }
        val weight = values.weightKg ?: return null
        val limit = weight * WEIGHT_TOLERANCE
        listOf(
            "Massa magra" to values.leanMassKg,
            "Massa grassa" to values.fatMassKg,
            "Massa muscolare" to values.muscleMassKg,
            "Acqua corporea (kg)" to values.bodyWaterKg,
            "Massa ossea" to values.boneMassKg,
            "Proteine (kg)" to values.proteinKg,
        ).forEach { (label, mass) ->
            if (mass != null && mass > limit) {
                return "$label (${format(mass)} kg) non può superare il peso (${format(weight)} kg)."
            }
        }
        return null
    }

    /** Primo errore tra peso e circonferenze (etichetta -> valore in cm), o null. */
    fun validateMeasurements(circumferencesCm: List<Pair<String, Float?>>, weightKg: Float?): String? {
        violation("Peso", weightKg, WEIGHT_KG)?.let { return it }
        circumferencesCm.forEach { (label, value) -> violation(label, value, CIRCUMFERENCE_CM)?.let { return it } }
        return null
    }

    /** Tolleranza dell'1% per l'arrotondamento dei display delle bilance. */
    private const val WEIGHT_TOLERANCE = 1.01f

    private fun format(value: Float): String {
        val text = String.format(Locale.ROOT, "%.1f", value)
        return text.removeSuffix(".0")
    }
}
