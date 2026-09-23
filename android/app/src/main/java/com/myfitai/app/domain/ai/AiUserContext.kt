package com.myfitai.app.domain.ai

import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import java.time.LocalDate
import java.time.Period
import java.util.Locale

/** Canonical, compact user context shared by AI agents without changing their output protocols. */
object AiUserContext {
    const val INPUT_DESCRIPTION = "U=sex|ageYears|heightCm|weightKg|goal|activity|wakeMinutes|sleepMinutes. LC=bmi|bmrKcal|tdeeKcal|targetKcal. ? means unavailable."

    fun profileLine(
        profile: UserProfileEntity,
        today: LocalDate,
        effectiveWeightKg: Float? = null,
    ): String {
        val age = profile.birthDateEpochDay?.let { epochDay ->
            val birth = LocalDate.ofEpochDay(epochDay)
            if (birth.isAfter(today)) null else Period.between(birth, today).years
        }
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
