package com.myfitai.app.domain.ai

import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
import com.myfitai.app.domain.calculation.SafetyCalorieFloor
import com.myfitai.app.domain.safety.GenerationSafetyGate
import com.myfitai.app.domain.safety.SafetyThresholds
import java.time.LocalDate
import java.time.Period
import java.util.Locale

/** Canonical, compact user context shared by AI agents without changing their output protocols. */
object AiUserContext {
    // Date in questo contesto sono sempre ISO yyyy-MM-dd; l'eta e in anni compiuti, mai epoch.
    const val INPUT_DESCRIPTION = "U=sex|ageYears|heightCm|weightKg|goal|activity|wakeMinutes|sleepMinutes. LC=bmi|bmrKcal|tdeeKcal|targetKcal. ? means unavailable."

    /** Eta in anni compiuti, null se la data di nascita manca o e nel futuro. */
    fun ageYears(profile: UserProfileEntity, today: LocalDate): Int? = profile.birthDateEpochDay?.let { epochDay ->
        val birth = LocalDate.ofEpochDay(epochDay)
        if (birth.isAfter(today)) null else Period.between(birth, today).years
    }

    /**
     * Esito del controllo di sicurezza calcolato localmente. Non esclude nessun utente: e un'informazione
     * passata al modello, che con flag attivo deve invitare al medico e non dare consigli di restrizione.
     * Gravidanza e disturbi alimentari non sono dati noti all'app e quindi non entrano nel flag.
     * Regole e soglie sono IPOTESI REVERSIBILI da confermare dal responsabile prodotto.
     */
    data class SafetyFlag(val active: Boolean, val reasons: List<String>) {
        fun line(): String = if (active) "SAFETY_FLAG:ACTIVE(${reasons.joinToString(",")})" else "SAFETY_FLAG:NONE"
    }

    const val SAFETY_MIN_ADULT_AGE = SafetyThresholds.MIN_ADULT_AGE_YEARS
    const val SAFETY_ELDERLY_AGE = SafetyThresholds.LEGACY_OLDER_ADULT_FLAG_AGE_YEARS
    const val SAFETY_LOW_BMI = SafetyThresholds.LOW_BMI
    const val SAFETY_RAPID_LOSS_PERCENT_PER_WEEK = SafetyThresholds.RAPID_LOSS_PERCENT_PER_WEEK
    const val SAFETY_RAPID_LOSS_MIN_SPAN_DAYS = SafetyThresholds.RAPID_LOSS_MIN_SPAN_DAYS
    const val SAFETY_RAPID_LOSS_WINDOW_DAYS = SafetyThresholds.RAPID_LOSS_WINDOW_DAYS
    // Soglie kcal minime: definite una sola volta in SafetyCalorieFloor.

    /**
     * @param weightObservations (epoch millis, kg) di qualunque fonte, in qualunque ordine.
     * @param goal obiettivo gia mappato; le regole su IMC, perdita rapida e target riguardano solo il dimagrimento.
     */
    fun safetyFlag(
        ageYears: Int?,
        bmi: Double?,
        goal: LocalCalculationEngine.Goal?,
        weightObservations: List<Pair<Long, Float>>,
        targetKcal: Double?,
        biologicalSex: String?,
    ): SafetyFlag {
        val reasons = mutableListOf<String>()
        if (ageYears != null && ageYears < SAFETY_MIN_ADULT_AGE) reasons += "AGE_UNDER_18"
        if (ageYears != null && ageYears >= SAFETY_ELDERLY_AGE) reasons += "AGE_75_PLUS"
        if (goal == LocalCalculationEngine.Goal.WEIGHT_LOSS) {
            if (bmi != null && bmi < SAFETY_LOW_BMI) reasons += "LOW_BMI_WEIGHT_LOSS"
            if (GenerationSafetyGate.isRapidWeightLoss(weightObservations)) reasons += "RAPID_WEIGHT_LOSS"
            val floor = SafetyCalorieFloor.absoluteMinimumKcal(ProfileCalculationMapper.sex(biologicalSex))
            if (targetKcal != null && targetKcal < floor) reasons += "EXTREME_LOW_TARGET"
        }
        return SafetyFlag(reasons.isNotEmpty(), reasons)
    }

    fun profileLine(
        profile: UserProfileEntity,
        today: LocalDate,
        effectiveWeightKg: Float? = null,
    ): String {
        val age = ageYears(profile, today)
        return listOf(
            profile.biologicalSex,
            age,
            profile.heightCm,
            effectiveWeightKg ?: profile.currentWeightKg,
            profile.goal,
            profile.activityLevel,
            profile.wakeTimeMinutes,
            profile.sleepTimeMinutes,
        ).joinToString("|") { compact(it) }
    }

    fun calculationLine(calculation: LocalCalculationEngine.Result?): String = listOf(
        calculation?.bmi,
        calculation?.bmrKcal,
        calculation?.tdeeKcal,
        calculation?.targetKcal,
    ).joinToString("|") { compactNumber(it) }

    private fun compact(value: Any?): String = value?.toString()
        ?.replace('|', '/')
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "?"

    private fun compactNumber(value: Double?): String = value
        ?.takeIf { it.isFinite() }
        ?.let { String.format(Locale.US, "%.1f", it) }
        ?: "?"
}
