package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator

/** Deterministic preflight for deviation adaptation. It never calls an AI provider. */
object CheatAdjustmentFeasibilityEngine {
    enum class ReasonCode {
        POSSIBLE,
        NO_FUTURE_MEALS,
        UNAVOIDABLE_KCAL_ABOVE_MAX,
        UNAVOIDABLE_PROTEIN_ABOVE_MAX,
        UNAVOIDABLE_CARBS_ABOVE_MAX,
        UNAVOIDABLE_FAT_ABOVE_MAX,
        MINIMUM_FUTURE_KCAL_ABOVE_MAX,
    }

    data class Totals(
        val kcal: Double,
        val proteinG: Double,
        val carbsG: Double,
        val fatG: Double,
    )

    data class Result(
        val possible: Boolean,
        val reasonCode: ReasonCode,
        val unavoidable: Totals,
        val minimumFutureKcal: Double,
        val maximum: Totals,
        val residualKcalToCarry: Int,
    )

    fun evaluate(
        targets: NutritionBusinessValidator.Targets,
        tolerance: Double = NutritionBusinessValidator.DEFAULT_TOLERANCE,
        lockedMeals: List<FoodMeal>,
        deviation: CheatAdjustmentContract.Estimate,
        futureMeals: List<FoodMeal>,
        debugLogger: ((String) -> Unit)? = null,
    ): Result {
        val locked = totals(lockedMeals)
        val unavoidable = Totals(
            kcal = locked.kcal + deviation.kcal,
            proteinG = locked.proteinG + deviation.proteinG,
            carbsG = locked.carbsG + deviation.carbsG,
            fatG = locked.fatG + deviation.fatG,
        )
        val maximum = Totals(
            kcal = targets.kcal,
            proteinG = targets.proteinG,
            carbsG = targets.carbsG,
            fatG = targets.fatG,
        )
        val minimumFutureKcal = futureMeals.size * MINIMUM_REPLACEMENT_KCAL.toDouble()
        val reason = when {
            futureMeals.isEmpty() -> ReasonCode.NO_FUTURE_MEALS
            unavoidable.kcal > maximum.kcal -> ReasonCode.UNAVOIDABLE_KCAL_ABOVE_MAX
            unavoidable.proteinG > maximum.proteinG -> ReasonCode.UNAVOIDABLE_PROTEIN_ABOVE_MAX
            unavoidable.carbsG > maximum.carbsG -> ReasonCode.UNAVOIDABLE_CARBS_ABOVE_MAX
            unavoidable.fatG > maximum.fatG -> ReasonCode.UNAVOIDABLE_FAT_ABOVE_MAX
            unavoidable.kcal + minimumFutureKcal > maximum.kcal -> ReasonCode.MINIMUM_FUTURE_KCAL_ABOVE_MAX
            else -> ReasonCode.POSSIBLE
        }
        val result = Result(
            possible = reason == ReasonCode.POSSIBLE,
            reasonCode = reason,
            unavoidable = unavoidable,
            minimumFutureKcal = minimumFutureKcal,
            maximum = maximum,
            residualKcalToCarry = residualKcal(unavoidable, maximum),
        )
        debugLogger?.invoke(
            "locked+kDeviation=${format(unavoidable)} allowed=${formatRange(targets, tolerance)} " +
                "minimumFutureKcal=${formatNumber(minimumFutureKcal)} possible=${result.possible} reason=${reason.name}",
        )
        return result
    }

    private fun totals(meals: List<FoodMeal>): Totals = Totals(
        kcal = meals.sumOf { it.kcal?.toDouble() ?: 0.0 },
        proteinG = meals.sumOf { it.proteinG?.toDouble() ?: 0.0 },
        carbsG = meals.sumOf { it.carbsG?.toDouble() ?: 0.0 },
        fatG = meals.sumOf { it.fatG?.toDouble() ?: 0.0 },
    )

    private fun format(value: Totals): String =
        "kcal=${formatNumber(value.kcal)};P=${formatNumber(value.proteinG)};C=${formatNumber(value.carbsG)};F=${formatNumber(value.fatG)}"

    private fun formatRange(targets: NutritionBusinessValidator.Targets, tolerance: Double): String =
        "kcal=[${formatNumber(targets.kcal * (1 - tolerance))},${formatNumber(targets.kcal)}]" +
            ";P=[${formatNumber(targets.proteinG * (1 - tolerance))},${formatNumber(targets.proteinG)}]" +
            ";C=[${formatNumber(targets.carbsG * (1 - tolerance))},${formatNumber(targets.carbsG)}]" +
            ";F=[${formatNumber(targets.fatG * (1 - tolerance))},${formatNumber(targets.fatG)}]"

    private fun formatNumber(value: Double): String = "%.2f".format(java.util.Locale.US, value)

    private fun residualKcal(unavoidable: Totals, maximum: Totals): Int = maxOf(
        (unavoidable.kcal - maximum.kcal).coerceAtLeast(0.0),
        ((unavoidable.proteinG - maximum.proteinG).coerceAtLeast(0.0) * 4.0),
        ((unavoidable.carbsG - maximum.carbsG).coerceAtLeast(0.0) * 4.0),
        ((unavoidable.fatG - maximum.fatG).coerceAtLeast(0.0) * 9.0),
    ).toInt()

    const val MINIMUM_REPLACEMENT_KCAL = 100
}
