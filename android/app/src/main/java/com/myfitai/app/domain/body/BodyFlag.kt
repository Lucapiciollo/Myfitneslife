package com.myfitai.app.domain.body

import com.myfitai.app.domain.calculation.LocalCalculationEngine
import java.time.LocalDate

/**
 * Modello delle segnalazioni corporee deterministiche (Lotto 1).
 *
 * Il CODICE decide se un flag esiste; un'IA, in lotti futuri, potra solo spiegarlo. Nessun testo di UI vive qui:
 * solo codici e chiavi di messaggio stabili. Il linguaggio resta quello di un "segnale da approfondire con un
 * medico", mai una diagnosi.
 */
enum class BodyFlagSeverity(val rank: Int) {
    INFO(1),
    ATTENZIONE(2),
}

/** Quanto il dato di partenza e solido. L'ordine di dichiarazione e dal piu al meno affidabile. */
enum class BodyFlagReliability(val rank: Int) {
    ALTA(3),
    MEDIA(2),
    BASSA(1),
}

/** Regole del motore; usate anche per dichiarare perche una regola non e stata valutata. */
enum class BodyFlagRule {
    WAIST_HEIGHT_RATIO,
    WAIST_CIRCUMFERENCE,
    BMI,
    VISCERAL_FAT,
    SKELETAL_MUSCLE,
    RAPID_WEIGHT_LOSS,
    LEAN_MASS_LOSS,
    BODY_FAT_TREND,
}

/**
 * Codici stabili dei flag. [messageKey] e solo una chiave: le stringhe NON esistono ancora (lotto successivo).
 * [priority] ordina i flag a pari gravita (piu alto = prima).
 */
enum class BodyFlagCode(val messageKey: String, val priority: Int) {
    RAPID_WEIGHT_LOSS("body_flag_rapid_weight_loss", 90),
    LEAN_MASS_LOSS_SHARE("body_flag_lean_mass_loss_share", 80),
    SKELETAL_MUSCLE_DECLINE("body_flag_skeletal_muscle_decline_check_strength", 70),
    WHTR_HIGH("body_flag_whtr_high", 60),
    WAIST_SECOND_LEVEL("body_flag_waist_second_level", 60),
    WHTR_ABOVE_HALF("body_flag_whtr_above_half", 40),
    WAIST_FIRST_LEVEL("body_flag_waist_first_level", 40),
    VISCERAL_FAT_RISING("body_flag_visceral_fat_rising", 30),
    BMI_BAND_UNDER("body_flag_bmi_band_under", 20),
    BMI_BAND_HIGH("body_flag_bmi_band_high", 20),
    BMI_BAND_OVER("body_flag_bmi_band_over", 15),
    BODY_FAT_TREND_UP("body_flag_body_fat_trend_up", 10),
    BODY_FAT_TREND_DOWN("body_flag_body_fat_trend_down", 10),
}

/** Valore realmente usato dal flag, con unita e data della rilevazione (per spiegarlo, mai per inventarlo). */
data class BodyFlagEvidence(
    val key: String,
    val value: Double,
    val unit: String,
    val date: LocalDate,
)

data class BodyFlag(
    val code: BodyFlagCode,
    val severity: BodyFlagSeverity,
    val reliability: BodyFlagReliability,
    val evidence: List<BodyFlagEvidence>,
    /** Id in [BodyFlagThresholds.SOURCES]. */
    val sourceIds: List<String>,
    val messageKey: String = code.messageKey,
)

/** Motivo interno per cui una regola non e stata valutata. Non e un messaggio per l'utente. */
enum class BodyFlagSkipReason {
    AGE_MISSING,
    NOT_ADULT,
    HEIGHT_MISSING,
    SEX_MISSING,
    WAIST_MISSING,
    WAIST_TOO_OLD,
    WEIGHT_MISSING,
    WEIGHT_TOO_OLD,
    BIA_MISSING,
    BIA_TOO_OLD,
    BIA_NOT_ENOUGH_COHERENT_MEASUREMENTS,
    OBSERVATION_WINDOW_TOO_SHORT,
    WEIGHT_LOSS_BELOW_NOISE,
    SECOND_INDICATOR_MISSING,
}

data class BodyFlagSkip(val rule: BodyFlagRule, val reason: BodyFlagSkipReason)

/** [flags] gia ordinati per gravita; [skipped] spiega i dati mancanti (uso interno, test, diagnostica sviluppatore). */
data class BodyFlagEvaluation(
    val flags: List<BodyFlag>,
    val skipped: List<BodyFlagSkip>,
    val thresholdsVersion: String,
)

/** Dati del profilo realmente necessari al motore; un campo mancante spegne solo le regole che lo richiedono. */
data class BodyFlagProfile(
    val sex: LocalCalculationEngine.BiologicalSex?,
    val ageYears: Int?,
    val heightCm: Double?,
    val goal: LocalCalculationEngine.Goal?,
)
