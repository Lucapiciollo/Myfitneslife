package com.myfitai.app.domain.calculation

/**
 * Shared source of truth for the nutrition metrics rendered by Home, Food and the BIA Coach.
 *
 * The numbers are the ones of the profile, one value for every day of the week:
 *   TDEE   = BMR x factor of the profile's activity level (the level already includes the habitual activity,
 *            sport included);
 *   target = TDEE x goal factor, with the safety floor and the macro policy of [LocalCalculationEngine].
 * Nothing here depends on the day: no check-in, no training program, no single workout.
 */
object OperationalNutritionMetrics {
    data class Result(
        val bmrKcal: Double?,
        val tdeeKcal: Double?,
        val targetKcal: Double?,
        val proteinG: Double?,
        val carbsG: Double?,
        val fatG: Double?,
    )

    fun calculate(calculation: LocalCalculationEngine.Result?): Result = Result(
        bmrKcal = calculation?.bmrKcal,
        tdeeKcal = calculation?.tdeeKcal,
        targetKcal = calculation?.targetKcal,
        proteinG = calculation?.proteinG,
        carbsG = calculation?.carbsG,
        fatG = calculation?.fatG,
    )
}
