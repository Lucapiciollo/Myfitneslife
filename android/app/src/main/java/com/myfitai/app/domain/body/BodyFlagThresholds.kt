package com.myfitai.app.domain.body

/** Stato di verifica di una fonte: la ricerca del 14/11/2026 NON e stata riverificata sui testi primari. */
enum class SourceVerification {
    /** Citata dal ricercatore, non riverificata sul testo primario. */
    REPORTED_BY_RESEARCH_NOT_REVERIFIED,

    /** Ipotesi interna prudente, dichiarata da calibrare dal responsabile prodotto. */
    INTERNAL_HYPOTHESIS_TO_CALIBRATE,

    /** Non verificata e NON usata dal motore. */
    UNVERIFIED_NOT_USED,
}

data class ThresholdSource(
    val id: String,
    val body: String,
    val document: String,
    val year: Int?,
    val applicability: String,
    val reliability: BodyFlagReliability,
    val verification: SourceVerification,
)

/**
 * Soglie del motore come DATI VERSIONATI, separate dalla logica di [BodyFlagEngine].
 * Cambiare una soglia = nuova [version] e nota nella documentazione (docs/MOTORE_SEGNALAZIONI_CORPOREE.md).
 * Le soglie "da calibrare" sono ipotesi reversibili, non misure.
 */
data class BodyFlagThresholds(
    val version: String = VERSION,

    // Perimetro e plausibilita
    val adultMinAgeYears: Int = 18,
    val heightMinCm: Double = 100.0,
    val heightMaxCm: Double = 230.0,

    // WHtR (vita / altezza)
    val whtrFirstLevel: Double = 0.5,
    val whtrSecondLevel: Double = 0.6,

    // Circonferenza vita (cm): primo e secondo livello per sesso
    val waistMaleFirstCm: Double = 94.0,
    val waistMaleSecondCm: Double = 102.0,
    val waistFemaleFirstCm: Double = 80.0,
    val waistFemaleSecondCm: Double = 88.0,

    // BMI: solo informativo e condizione per altre regole
    val bmiUnder: Double = 18.5,
    val bmiOver: Double = 25.0,
    val bmiHigh: Double = 30.0,

    // Recenza dei dati (giorni)
    val waistRecentDays: Long = 90,
    val waistMaxAgeDays: Long = 180,
    val weightRecentDays: Long = 30,
    val weightMaxAgeDays: Long = 90,
    val biaMaxAgeDays: Long = 60,

    // Trend BIA
    val trendWindowDays: Long = 90,
    val trendMinMeasurements: Int = 3,
    val trendMinSpanDays: Long = 28,

    // Grasso viscerale (livello BIA, senza soglia clinica): salita minima in livelli
    val visceralMinRiseLevels: Double = 1.0,

    // Muscolo scheletrico: calo minimo in kg (> soglia di rumore della BIA) e eta dell'indicatore secondario
    val muscleDeclineMinKg: Double = 1.5,
    val muscleSecondIndicatorAgeYears: Int = 60,

    // Ritmo di calo del peso
    val rapidLossKgPerWeek: Double = 1.0,
    val rapidLossRecencyDays: Long = 14,
    val weekToleranceDays: Double = 1.0,

    // Calo di massa magra (DA CALIBRARE)
    val leanLossMinKg: Double = 2.0,
    val leanLossShareOfWeightLoss: Double = 0.40,
) {
    companion object {
        const val VERSION = "2026-11-14.1"

        const val SRC_WHO_WAIST_2008 = "WHO_WAIST_2008"
        const val SRC_NICE_NG246_2025 = "NICE_NG246_2025"
        const val SRC_WHO_BMI_2000 = "WHO_BMI_2000"
        const val SRC_EWGSOP2_2019 = "EWGSOP2_2019"
        const val SRC_FITDAYS_VISCERAL = "FITDAYS_VISCERAL"
        const val SRC_INTERNAL_RAPID_LOSS = "INTERNAL_RAPID_LOSS"
        const val SRC_INTERNAL_LEAN_LOSS = "INTERNAL_LEAN_LOSS"
        const val SRC_INTERNAL_BIA_NOISE = "INTERNAL_BIA_NOISE"
        const val SRC_FITDAYS_BANDS = "FITDAYS_BANDS"
        const val SRC_BODY_FAT_AGE_TABLES = "BODY_FAT_AGE_TABLES"

        val DEFAULT = BodyFlagThresholds()

        /**
         * Fonti riportate dalla ricerca del 14/11/2026 (docs/RICERCA_SPECIALISTA_IA_2026-11-14.md). Titoli e anni
         * sono quelli noti degli enti ma NON sono stati riverificati sui testi primari: da confermare.
         */
        val SOURCES: Map<String, ThresholdSource> = listOf(
            ThresholdSource(
                SRC_WHO_WAIST_2008, "OMS (WHO)",
                "Waist circumference and waist-hip ratio: report of a WHO expert consultation", 2008,
                "Adulti; livelli di vita U 94/102 cm, D 80/88 cm (origine prevalentemente europea)",
                BodyFlagReliability.ALTA, SourceVerification.REPORTED_BY_RESEARCH_NOT_REVERIFIED,
            ),
            ThresholdSource(
                SRC_NICE_NG246_2025, "NICE (UK)", "Overweight and obesity management (NG246)", 2025,
                "Adulti; rapporto vita/altezza 0,5 e 0,6",
                BodyFlagReliability.ALTA, SourceVerification.REPORTED_BY_RESEARCH_NOT_REVERIFIED,
            ),
            ThresholdSource(
                SRC_WHO_BMI_2000, "OMS (WHO)", "Classificazione BMI (TRS 894)", 2000,
                "Adulti; solo informativo, non distingue massa grassa e magra",
                BodyFlagReliability.ALTA, SourceVerification.REPORTED_BY_RESEARCH_NOT_REVERIFIED,
            ),
            ThresholdSource(
                SRC_EWGSOP2_2019, "EWGSOP2", "Consenso europeo sulla perdita di massa e forza muscolare (revisione)", 2019,
                "NON applicabile alla BIA domestica: serve un test funzionale (SARC-F, alzate dalla sedia), non ancora in app",
                BodyFlagReliability.ALTA, SourceVerification.REPORTED_BY_RESEARCH_NOT_REVERIFIED,
            ),
            ThresholdSource(
                SRC_FITDAYS_VISCERAL, "Produttore bilancia (Fitdays)", "Livello di grasso viscerale (nessuna soglia clinica)", null,
                "Scala del produttore; usato solo come trend condizionato a WHtR >= 0,5",
                BodyFlagReliability.BASSA, SourceVerification.REPORTED_BY_RESEARCH_NOT_REVERIFIED,
            ),
            ThresholdSource(
                SRC_INTERNAL_RAPID_LOSS, "MyFitAI", "Regola prudenziale: calo > 1 kg/settimana per 2 settimane consecutive", null,
                "Adulti; soglia dalla ricerca interna, da calibrare",
                BodyFlagReliability.MEDIA, SourceVerification.INTERNAL_HYPOTHESIS_TO_CALIBRATE,
            ),
            ThresholdSource(
                SRC_INTERNAL_LEAN_LOSS, "MyFitAI", "Flag composito: calo massa magra >= 2 kg e quota > 40% del calo di peso", null,
                "Adulti con BIA domestica coerente; la massa magra include acqua; da calibrare",
                BodyFlagReliability.BASSA, SourceVerification.INTERNAL_HYPOTHESIS_TO_CALIBRATE,
            ),
            ThresholdSource(
                SRC_INTERNAL_BIA_NOISE, "MyFitAI", "Soglie di rumore BIA e qualita misura (BiaNoiseThresholds, BiaMeasurementQualityEngine)", null,
                "Ipotesi conservative gia in uso; riusate, non duplicate",
                BodyFlagReliability.BASSA, SourceVerification.INTERNAL_HYPOTHESIS_TO_CALIBRATE,
            ),
            ThresholdSource(
                SRC_FITDAYS_BANDS, "Produttore bilancia (Fitdays)", "Fasce di riferimento dell'app del produttore", null,
                "NON verificate: il motore non le usa",
                BodyFlagReliability.BASSA, SourceVerification.UNVERIFIED_NOT_USED,
            ),
            ThresholdSource(
                SRC_BODY_FAT_AGE_TABLES, "Varie", "Tabelle di % massa grassa per eta", null,
                "NON verificate: il motore non le usa",
                BodyFlagReliability.BASSA, SourceVerification.UNVERIFIED_NOT_USED,
            ),
        ).associateBy { it.id }
    }
}
