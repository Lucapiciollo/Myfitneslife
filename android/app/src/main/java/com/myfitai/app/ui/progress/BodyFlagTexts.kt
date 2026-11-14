package com.myfitai.app.ui.progress

import androidx.annotation.StringRes
import com.myfitai.app.R
import com.myfitai.app.domain.body.BodyFlag
import com.myfitai.app.domain.body.BodyFlagCode
import com.myfitai.app.domain.body.BodyFlagHint
import com.myfitai.app.domain.body.BodyFlagReliability
import com.myfitai.app.domain.body.BodyFlagSeverity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Unico punto che collega codici e chiavi di messaggio del motore (`body_flag_*`) alle stringhe di `strings.xml`
 * (blocco "FLAG L2"). Nessun `when` sparso nella UI: la UI legge solo queste tabelle. Il test
 * `BodyFlagTextsTest` garantisce che ogni chiave prodotta dal motore abbia le sue stringhe e che i testi restino
 * prudenti. I testi non contengono mai valori inventati: i numeri arrivano dalla `evidence` del motore.
 */
object BodyFlagTexts {
    /** Valore virtuale per i testi: altezza del profilo usata dal motore (non e nella evidence). */
    const val HEIGHT_KEY = "profileHeightCm"

    /**
     * Stringhe di un flag. [contextKeys] sono, nell'ordine dei segnaposto `%1$s`, `%2$s`..., le chiavi della
     * evidence del motore (o [HEIGHT_KEY]) con cui si riempie la frase di contesto.
     */
    data class Entry(
        @StringRes val title: Int,
        @StringRes val context: Int,
        @StringRes val why: Int,
        @StringRes val limit: Int,
        @StringRes val action: Int,
        val contextKeys: List<String>,
    )

    private val WAIST_KEYS = listOf("waistCm")
    private val WHTR_KEYS = listOf("waistCm", HEIGHT_KEY, "whtr")
    private val BMI_KEYS = listOf("bmi", "weightKg")

    val entries: Map<BodyFlagCode, Entry> = mapOf(
        BodyFlagCode.RAPID_WEIGHT_LOSS to Entry(
            R.string.body_flag_rapid_weight_loss_title, R.string.body_flag_rapid_weight_loss_context,
            R.string.body_flag_rapid_weight_loss_why, R.string.body_flag_rapid_weight_loss_limit,
            R.string.body_flag_action_remeasure,
            listOf("weightKgStart", "weightKgLatest", "weeklyRateFirstKg", "weeklyRateSecondKg"),
        ),
        BodyFlagCode.LEAN_MASS_LOSS_SHARE to Entry(
            R.string.body_flag_lean_mass_loss_share_title, R.string.body_flag_lean_mass_loss_share_context,
            R.string.body_flag_lean_mass_loss_share_why, R.string.body_flag_lean_mass_loss_share_limit,
            R.string.body_flag_action_remeasure,
            listOf("leanMassKgFirst", "leanMassKgLast", "weightKgFirst", "weightKgLast"),
        ),
        BodyFlagCode.SKELETAL_MUSCLE_DECLINE to Entry(
            R.string.body_flag_skeletal_muscle_decline_check_strength_title,
            R.string.body_flag_skeletal_muscle_decline_check_strength_context,
            R.string.body_flag_skeletal_muscle_decline_check_strength_why,
            R.string.body_flag_skeletal_muscle_decline_check_strength_limit,
            R.string.body_flag_action_strength_test,
            listOf("skeletalMuscleKgFirst", "skeletalMuscleKgLast"),
        ),
        BodyFlagCode.WHTR_HIGH to Entry(
            R.string.body_flag_whtr_high_title, R.string.body_flag_whtr_high_context,
            R.string.body_flag_whtr_high_why, R.string.body_flag_whtr_high_limit,
            R.string.body_flag_action_waist, WHTR_KEYS,
        ),
        BodyFlagCode.WAIST_SECOND_LEVEL to Entry(
            R.string.body_flag_waist_second_level_title, R.string.body_flag_waist_second_level_context,
            R.string.body_flag_waist_second_level_why, R.string.body_flag_waist_second_level_limit,
            R.string.body_flag_action_waist, WAIST_KEYS,
        ),
        BodyFlagCode.WHTR_ABOVE_HALF to Entry(
            R.string.body_flag_whtr_above_half_title, R.string.body_flag_whtr_above_half_context,
            R.string.body_flag_whtr_above_half_why, R.string.body_flag_whtr_above_half_limit,
            R.string.body_flag_action_waist, WHTR_KEYS,
        ),
        BodyFlagCode.WAIST_FIRST_LEVEL to Entry(
            R.string.body_flag_waist_first_level_title, R.string.body_flag_waist_first_level_context,
            R.string.body_flag_waist_first_level_why, R.string.body_flag_waist_first_level_limit,
            R.string.body_flag_action_waist, WAIST_KEYS,
        ),
        BodyFlagCode.VISCERAL_FAT_RISING to Entry(
            R.string.body_flag_visceral_fat_rising_title, R.string.body_flag_visceral_fat_rising_context,
            R.string.body_flag_visceral_fat_rising_why, R.string.body_flag_visceral_fat_rising_limit,
            R.string.body_flag_action_remeasure,
            listOf("visceralFatLevelFirst", "visceralFatLevelLast", "whtr"),
        ),
        BodyFlagCode.BMI_BAND_UNDER to Entry(
            R.string.body_flag_bmi_band_under_title, R.string.body_flag_bmi_band_under_context,
            R.string.body_flag_bmi_band_under_why, R.string.body_flag_bmi_band_under_limit,
            R.string.body_flag_action_info, BMI_KEYS,
        ),
        BodyFlagCode.BMI_BAND_HIGH to Entry(
            R.string.body_flag_bmi_band_high_title, R.string.body_flag_bmi_band_high_context,
            R.string.body_flag_bmi_band_high_why, R.string.body_flag_bmi_band_high_limit,
            R.string.body_flag_action_info, BMI_KEYS,
        ),
        BodyFlagCode.BMI_BAND_OVER to Entry(
            R.string.body_flag_bmi_band_over_title, R.string.body_flag_bmi_band_over_context,
            R.string.body_flag_bmi_band_over_why, R.string.body_flag_bmi_band_over_limit,
            R.string.body_flag_action_info, BMI_KEYS,
        ),
        BodyFlagCode.BODY_FAT_TREND_UP to Entry(
            R.string.body_flag_body_fat_trend_up_title, R.string.body_flag_body_fat_trend_up_context,
            R.string.body_flag_body_fat_trend_up_why, R.string.body_flag_body_fat_trend_up_limit,
            R.string.body_flag_action_info, listOf("bodyFatPercentFirst", "bodyFatPercentLast"),
        ),
        BodyFlagCode.BODY_FAT_TREND_DOWN to Entry(
            R.string.body_flag_body_fat_trend_down_title, R.string.body_flag_body_fat_trend_down_context,
            R.string.body_flag_body_fat_trend_down_why, R.string.body_flag_body_fat_trend_down_limit,
            R.string.body_flag_action_info, listOf("bodyFatPercentFirst", "bodyFatPercentLast"),
        ),
    )

    val severityLabels: Map<BodyFlagSeverity, Int> = mapOf(
        BodyFlagSeverity.INFO to R.string.body_flag_severity_info,
        BodyFlagSeverity.ATTENZIONE to R.string.body_flag_severity_attention,
    )

    val reliabilityLabels: Map<BodyFlagReliability, Int> = mapOf(
        BodyFlagReliability.ALTA to R.string.body_flag_reliability_high,
        BodyFlagReliability.MEDIA to R.string.body_flag_reliability_medium,
        BodyFlagReliability.BASSA to R.string.body_flag_reliability_low,
    )

    val hintLabels: Map<BodyFlagHint, Int> = mapOf(
        BodyFlagHint.NOT_ADULT to R.string.body_flag_hint_not_adult,
        BodyFlagHint.ADD_BIRTH_DATE to R.string.body_flag_hint_add_birth_date,
        BodyFlagHint.ADD_HEIGHT to R.string.body_flag_hint_add_height,
        BodyFlagHint.MEASURE_WAIST to R.string.body_flag_hint_measure_waist,
        BodyFlagHint.ADD_BIA to R.string.body_flag_hint_add_bia,
    )

    /** Valori della frase di contesto, nell'ordine dei segnaposto; "-" se un dato non c'e (non si inventa). */
    fun contextArgs(flag: BodyFlag, heightCm: Double?): List<String> {
        val keys = entries.getValue(flag.code).contextKeys
        return keys.map { key ->
            val value = if (key == HEIGHT_KEY) heightCm else flag.evidence.firstOrNull { it.key == key }?.value
            value?.let { formatValue(key, it) } ?: MISSING_VALUE
        }
    }

    /** Numeri all'italiana: rapporto vita/altezza con 2 decimali, interi senza decimali, altrimenti 1 decimale. */
    fun formatValue(key: String, value: Double): String = when {
        key == "whtr" -> String.format(Locale.ITALIAN, "%.2f", value)
        abs(value - value.roundToLong()) < WHOLE_EPSILON -> value.roundToLong().toString()
        else -> String.format(Locale.ITALIAN, "%.1f", value)
    }

    private const val MISSING_VALUE = "-"
    private const val WHOLE_EPSILON = 1e-6
}
