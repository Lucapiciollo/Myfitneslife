package com.myfitai.app.domain.ai

import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.domain.calculation.LocalCalculationEngine
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

    const val SAFETY_MIN_ADULT_AGE = 18
    const val SAFETY_ELDERLY_AGE = 75
    const val SAFETY_LOW_BMI = 18.5
    const val SAFETY_RAPID_LOSS_PERCENT_PER_WEEK = 1.0
    const val SAFETY_RAPID_LOSS_MIN_SPAN_DAYS = 14
    const val SAFETY_RAPID_LOSS_WINDOW_DAYS = 28
    const val SAFETY_MIN_TARGET_KCAL_FEMALE = 1200.0
    const val SAFETY_MIN_TARGET_KCAL_OTHER = 1500.0
    private const val DAY_MILLIS = 86_400_000.0

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
            if (rapidWeightLoss(weightObservations)) reasons += "RAPID_WEIGHT_LOSS"
            val floor = if (isFemale(biologicalSex)) SAFETY_MIN_TARGET_KCAL_FEMALE else SAFETY_MIN_TARGET_KCAL_OTHER
            if (targetKcal != null && targetKcal < floor) reasons += "EXTREME_LOW_TARGET"
        }
        return SafetyFlag(reasons.isNotEmpty(), reasons)
    }

    private fun rapidWeightLoss(observations: List<Pair<Long, Float>>): Boolean {
        val ordered = observations.sortedBy { it.first }
        val latest = ordered.lastOrNull() ?: return false
        val window = ordered.filter { it.first >= latest.first - SAFETY_RAPID_LOSS_WINDOW_DAYS * DAY_MILLIS }
        val first = window.firstOrNull() ?: return false
        val spanDays = (latest.first - first.first) / DAY_MILLIS
        if (spanDays < SAFETY_RAPID_LOSS_MIN_SPAN_DAYS || first.second <= 0f) return false
        val weeklyLossPercent = (first.second - latest.second) / first.second * 100.0 / (spanDays / 7.0)
        return weeklyLossPercent > SAFETY_RAPID_LOSS_PERCENT_PER_WEEK
    }

    private fun isFemale(sex: String?): Boolean = sex?.trim()?.lowercase() in setOf("femmina", "female", "f")

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
