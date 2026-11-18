package com.myfitai.app.domain.safety

/** Stato della soglia: i valori proposti non sono approvati né attivi nel runtime. */
enum class ThresholdVerification {
    EXISTING_CODE,
    SOURCE_REPORTED_NOT_REVERIFIED,
    INTERNAL_HYPOTHESIS_TO_CALIBRATE,
    PRODUCT_DECISION_PENDING,
    PRODUCT_DECISION_APPROVED,
}

data class SafetyThresholdDefinition(
    val id: String,
    val value: String,
    val source: String,
    val verification: ThresholdVerification,
)

/** Unica fonte per le soglie condivise dal gate S1 e dal flag informativo legacy. */
object SafetyThresholds {
    const val VERSION = "2026-11-15.2"

    // Valori già usati da AiUserContext: mantenerli invariati finché il gate non viene approvato/integrato.
    const val MIN_ADULT_AGE_YEARS = 18
    const val LEGACY_OLDER_ADULT_FLAG_AGE_YEARS = 75
    const val LOW_BMI = 18.5
    const val RAPID_LOSS_PERCENT_PER_WEEK = 1.0
    const val RAPID_LOSS_MIN_SPAN_DAYS = 14
    const val RAPID_LOSS_WINDOW_DAYS = 28

    // Proposte BMI documentate ma non valutate dal gate: non attivarle prima di una decisione validata.
    const val VERY_LOW_BMI = 17.0
    const val OLDER_ADULT_NOTICE_AGE_YEARS = 65
    const val MAX_DAILY_DEFICIT_KCAL = 750.0

    /** Catalogo esplicito: distingue i dati già presenti nel codice dalle decisioni ancora da approvare. */
    val DEFINITIONS = listOf(
        SafetyThresholdDefinition("adult_min_age", MIN_ADULT_AGE_YEARS.toString(), "App da 14 anni; sotto i 18 anni blocco dei piani IA", ThresholdVerification.PRODUCT_DECISION_APPROVED),
        SafetyThresholdDefinition("legacy_older_flag_age", LEGACY_OLDER_ADULT_FLAG_AGE_YEARS.toString(), "AiUserContext legacy", ThresholdVerification.EXISTING_CODE),
        SafetyThresholdDefinition("low_bmi", LOW_BMI.toString(), "AiUserContext legacy informativo; non attivo nel gate generazione", ThresholdVerification.EXISTING_CODE),
        SafetyThresholdDefinition("very_low_bmi", VERY_LOW_BMI.toString(), "Proposta non valutata dal gate; fonte/uso da riverificare", ThresholdVerification.PRODUCT_DECISION_PENDING),
        SafetyThresholdDefinition("older_adult_notice_age", OLDER_ADULT_NOTICE_AGE_YEARS.toString(), "Avviso per dimagrimento dai 65 anni", ThresholdVerification.PRODUCT_DECISION_APPROVED),
        SafetyThresholdDefinition("rapid_loss_percent_week", RAPID_LOSS_PERCENT_PER_WEEK.toString(), "AiUserContext legacy; ipotesi interna da calibrare", ThresholdVerification.INTERNAL_HYPOTHESIS_TO_CALIBRATE),
        SafetyThresholdDefinition("rapid_loss_min_span_days", RAPID_LOSS_MIN_SPAN_DAYS.toString(), "AiUserContext legacy", ThresholdVerification.EXISTING_CODE),
        SafetyThresholdDefinition("rapid_loss_window_days", RAPID_LOSS_WINDOW_DAYS.toString(), "AiUserContext legacy", ThresholdVerification.EXISTING_CODE),
        SafetyThresholdDefinition("max_daily_deficit_kcal", MAX_DAILY_DEFICIT_KCAL.toString(), "Cap massimo giornaliero approvato dal responsabile prodotto", ThresholdVerification.PRODUCT_DECISION_APPROVED),
    )
}
