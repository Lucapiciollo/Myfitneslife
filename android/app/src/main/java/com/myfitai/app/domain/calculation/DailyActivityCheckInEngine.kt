package com.myfitai.app.domain.calculation

/** Conservative, local-only adjustment for the current-day activity check-in. */
object DailyActivityCheckInEngine {
    enum class Intensity { LIGHT, MODERATE, HARD }

    fun effectiveTdeeKcal(
        bmrKcal: Double?,
        habitualTdeeKcal: Double?,
        status: String?,
        adjustmentKcal: Int,
    ): Double? {
        val bmr = bmrKcal?.takeIf { it.isFinite() && it > 0.0 } ?: return habitualTdeeKcal
        val habitual = habitualTdeeKcal?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return when (status) {
            "REST" -> minOf(habitual, bmr * LocalCalculationEngine.ActivityLevel.SEDENTARY.multiplier)
            "PLANNED_WORKOUT" -> habitual + adjustmentKcal.coerceIn(0, 225)
            else -> habitual
        }
    }

    fun adjustmentKcal(durationMinutes: Int, intensity: Intensity): Int {
        val duration = when {
            durationMinutes <= 30 -> 30
            durationMinutes <= 45 -> 45
            durationMinutes <= 60 -> 60
            else -> 75
        }
        val base = when (duration) {
            30 -> 50
            45 -> 75
            60 -> 100
            else -> 125
        }
        val extra = when (intensity) {
            Intensity.LIGHT -> 0
            Intensity.MODERATE -> when (duration) { 30 -> 25; 45 -> 35; 60 -> 40; else -> 45 }
            Intensity.HARD -> when (duration) { 30 -> 50; 45 -> 75; 60 -> 90; else -> 100 }
        }
        return (base + extra).coerceAtMost(225)
    }
}
