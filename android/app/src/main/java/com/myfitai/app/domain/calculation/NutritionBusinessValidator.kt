package com.myfitai.app.domain.calculation

import kotlin.math.abs

/**
 * Validator autorevole dell'app. La tolleranza ufficiale è ±3% sui target correnti.
 * L'eventuale agentValidation proveniente dall'AI non sostituisce questo controllo.
 */
object NutritionBusinessValidator {
    const val DEFAULT_TOLERANCE = 0.03

    data class Targets(
        val kcal: Double,
        val proteinG: Double,
        val carbsG: Double,
        val fatG: Double,
    )

    data class Actuals(
        val kcal: Double,
        val proteinG: Double,
        val carbsG: Double,
        val fatG: Double,
    )

    data class FieldValidation(
        val target: Double,
        val actual: Double,
        val deviationRatio: Double,
        val valid: Boolean,
    )

    data class Result(
        val valid: Boolean,
        val kcal: FieldValidation,
        val protein: FieldValidation,
        val carbs: FieldValidation,
        val fat: FieldValidation,
    )

    fun validate(
        targets: Targets,
        actuals: Actuals,
        tolerance: Double = DEFAULT_TOLERANCE,
    ): Result {
        require(tolerance in 0.0..0.20) { "Tolerance out of supported range" }
        require(targets.kcal > 0 && targets.proteinG > 0 && targets.carbsG >= 0 && targets.fatG > 0) {
            "Targets must be valid and non-negative"
        }
        require(actuals.kcal >= 0 && actuals.proteinG >= 0 && actuals.carbsG >= 0 && actuals.fatG >= 0) {
            "Actuals must be non-negative"
        }

        val kcal = validateField(targets.kcal, actuals.kcal, tolerance)
        val protein = validateField(targets.proteinG, actuals.proteinG, tolerance)
        val carbs = if (targets.carbsG == 0.0) {
            FieldValidation(0.0, actuals.carbsG, if (actuals.carbsG == 0.0) 0.0 else Double.POSITIVE_INFINITY, actuals.carbsG == 0.0)
        } else validateField(targets.carbsG, actuals.carbsG, tolerance)
        val fat = validateField(targets.fatG, actuals.fatG, tolerance)

        return Result(
            valid = kcal.valid && protein.valid && carbs.valid && fat.valid,
            kcal = kcal,
            protein = protein,
            carbs = carbs,
            fat = fat,
        )
    }

    private fun validateField(target: Double, actual: Double, tolerance: Double): FieldValidation {
        val deviation = abs(actual - target) / target
        return FieldValidation(
            target = target,
            actual = actual,
            deviationRatio = deviation,
            valid = deviation <= tolerance + 1e-9,
        )
    }
}
