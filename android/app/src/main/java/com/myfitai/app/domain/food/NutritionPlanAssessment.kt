package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import kotlin.math.abs

/**
 * Single, pure evaluation of a generated plan against the app targets.
 *
 * Input is the plan as recalculated by the app: [NutritionPlanCompactContract] derives every day total from the
 * meal and caloric-supplement records, so the `D` totals sent by the model are ignored. The same [Assessment] feeds
 * the business validator ([NutritionPlanContract.validateBusinessAssessed]), the retry message
 * ([Assessment.toleranceExceededReason]) and the persisted appValidation ([NutritionIntegrityValidator]), so the
 * plan is judged exactly once.
 *
 * Limits, stated on purpose: the per-meal kcal and macro figures are DECLARED BY THE MODEL. There is no local food
 * table, so nothing here (nor in [NutritionIntegrityValidator]) verifies that "100 g of rice" really is the
 * declared kcal/macros; the app only checks that the declared numbers add up to the targets and are internally
 * coherent. The tolerance therefore measures agreement with the model's own arithmetic, not nutritional truth.
 *
 * Policy (decisions D5, D6, D7):
 * - kcal per day is the only numeric blocking criterion: reference ±3% on the first attempt, ±4% on retries
 *   ([toleranceForAttempt]). The reference value recorded in appValidation stays ±3%.
 * - Macros never block: protein below 85% of target and carbs/fat outside ±25% are warnings.
 * - Calorie centering is informational: a mean absolute deviation above 2% is a warning.
 */
object NutritionPlanAssessment {
    /** Official tolerance used on the first attempt and recorded as the reference. */
    const val REFERENCE_TOLERANCE = NutritionBusinessValidator.DEFAULT_TOLERANCE

    /** Tolerance applied from the first retry on. */
    const val RETRY_TOLERANCE = 0.04

    /** Mean absolute kcal deviation above which the plan only gets a "loosely centered" warning. */
    const val CENTERING_WARNING_MEAN = 0.02

    /** Protein below this share of the target is a warning (never a blocker). */
    const val PROTEIN_MIN_RATIO = 0.85

    /** Carbs and fat deviating more than this from the target are a warning (never a blocker). */
    const val MACRO_BAND = 0.25

    /** Days listed in the retry message; any further day is summarised as `|+N`. */
    const val MAX_FIX_DAYS = 6

    /** Upper bound of the retry reason length: prefix + [MAX_FIX_DAYS] days + `|+N` suffix. */
    const val MAX_REASON_CHARS = 320

    const val TOLERANCE_EXCEEDED_CODE = "TARGET_TOLERANCE_EXCEEDED"

    private const val EPSILON = 1e-9

    /** First business attempt ±3%, every retry ±4%. */
    fun toleranceForAttempt(attemptIndex: Int): Double = if (attemptIndex <= 0) REFERENCE_TOLERANCE else RETRY_TOLERANCE

    /** OK ≤ 3%, RETRY_ZONE ≤ 4% (acceptable only after a retry), OUT beyond 4%. */
    enum class DayClass { OK, RETRY_ZONE, OUT }

    /** Numbers of one day: targets, actuals recalculated by the app and signed deviations ((actual-target)/target). */
    data class DayAssessment(
        val dateEpochDay: Long,
        val targetKcal: Double,
        val actualKcal: Double,
        val kcalDeviation: Double,
        val targetProteinG: Double,
        val actualProteinG: Double,
        val proteinDeviation: Double,
        val targetCarbsG: Double,
        val actualCarbsG: Double,
        val carbsDeviation: Double,
        val targetFatG: Double,
        val actualFatG: Double,
        val fatDeviation: Double,
        val classification: DayClass,
        /** True when this day's kcal fall outside the tolerance applied to the attempt. */
        val blocking: Boolean,
    )

    /** The tolerance values in force; recorded in appValidation (percent points, no personal data). */
    data class TolerancePolicy(
        val referencePercent: Double,
        val retryPercent: Double,
        val appliedPercent: Double,
        val centeringWarningPercent: Double,
        val proteinMinPercent: Double,
        val macroBandPercent: Double,
    ) {
        companion object {
            fun applied(tolerance: Double) = TolerancePolicy(
                referencePercent = REFERENCE_TOLERANCE * 100.0,
                retryPercent = RETRY_TOLERANCE * 100.0,
                appliedPercent = tolerance * 100.0,
                centeringWarningPercent = CENTERING_WARNING_MEAN * 100.0,
                proteinMinPercent = PROTEIN_MIN_RATIO * 100.0,
                macroBandPercent = MACRO_BAND * 100.0,
            )
        }
    }

    data class Assessment(
        val days: List<DayAssessment>,
        /** Tolerance applied to the blocking decision of this attempt. */
        val appliedTolerance: Double,
        /** Zero-based business attempt that produced this plan. */
        val attempt: Int,
        val blockers: List<NutritionValidationIssue>,
        val warnings: List<NutritionValidationIssue>,
    ) {
        val accepted: Boolean get() = blockers.isEmpty()
        val tolerancePolicy: TolerancePolicy get() = TolerancePolicy.applied(appliedTolerance)

        /**
         * Compact retry reason listing every day outside tolerance (never model text):
         * `TARGET_TOLERANCE_EXCEEDED:<epochDay>:target=<kcal>;actual=<kcal>|...`, at most [MAX_FIX_DAYS] days, then `|+N`.
         * The first day keeps the legacy `code:day:target=..;actual=..` shape. Null when nothing blocks.
         */
        fun toleranceExceededReason(): String? {
            val bad = days.filter { it.blocking }.sortedBy { it.dateEpochDay }
            if (bad.isEmpty()) return null
            val shown = bad.take(MAX_FIX_DAYS).joinToString("|") {
                "${it.dateEpochDay}:target=${it.targetKcal.toInt()};actual=${it.actualKcal.toInt()}"
            }
            val hidden = bad.size - MAX_FIX_DAYS
            return "$TOLERANCE_EXCEEDED_CODE:$shown" + if (hidden > 0) "|+$hidden" else ""
        }
    }

    /**
     * Evaluates [response] day by day. [dailyTargets] overrides [targets] for its dates. A kcal outside [tolerance]
     * is the only blocker; macros and centering produce warnings. Throws [IllegalArgumentException]
     * (`CALORIE_TARGET_INVALID`) when a kcal target is not positive.
     */
    fun assess(
        response: NutritionPlanContract.Response,
        targets: NutritionBusinessValidator.Targets,
        dailyTargets: Map<Long, NutritionBusinessValidator.Targets> = emptyMap(),
        tolerance: Double = REFERENCE_TOLERANCE,
        belowOnly: Boolean = false,
        attempt: Int = 0,
    ): Assessment {
        require(tolerance in 0.0..0.20) { "Tolerance out of supported range" }
        val blockers = mutableListOf<NutritionValidationIssue>()
        val warnings = mutableListOf<NutritionValidationIssue>()
        val days = response.days.map { day ->
            val target = dailyTargets[day.dateEpochDay] ?: targets
            require(target.kcal > 0.0) { "CALORIE_TARGET_INVALID" }
            val kcalDeviation = deviation(target.kcal, day.totalKcal.toDouble())
            val proteinDeviation = deviation(target.proteinG, day.proteinG.toDouble())
            val carbsDeviation = deviation(target.carbsG, day.carbsG.toDouble())
            val fatDeviation = deviation(target.fatG, day.fatG.toDouble())
            val absKcal = abs(kcalDeviation)
            val aboveWhenBelowOnly = belowOnly && kcalDeviation > EPSILON
            val classification = when {
                aboveWhenBelowOnly -> DayClass.OUT
                absKcal <= REFERENCE_TOLERANCE + EPSILON -> DayClass.OK
                absKcal <= RETRY_TOLERANCE + EPSILON -> DayClass.RETRY_ZONE
                else -> DayClass.OUT
            }
            val blocking = aboveWhenBelowOnly || absKcal > tolerance + EPSILON
            if (blocking) {
                blockers += issue(
                    "TARGET_DAY_KCAL_OUT_OF_RANGE", Severity.ERROR, "Le calorie del giorno non rispettano il target dinamico",
                    day.dateEpochDay, target.kcal, day.totalKcal.toDouble(), kcalDeviation * 100.0,
                )
            } else if (classification == DayClass.RETRY_ZONE) {
                warnings += issue(
                    "KCAL_IN_RETRY_ZONE", Severity.WARNING, "Calorie del giorno oltre il 3% ma entro il 4% accettato dopo la correzione",
                    day.dateEpochDay, target.kcal, day.totalKcal.toDouble(), kcalDeviation * 100.0,
                )
            }
            if (target.proteinG > 0.0 && day.proteinG < target.proteinG * PROTEIN_MIN_RATIO - EPSILON) {
                warnings += issue(
                    "PROTEIN_BELOW_TARGET", Severity.WARNING, "Proteine sotto l'85% del target",
                    day.dateEpochDay, target.proteinG, day.proteinG.toDouble(), proteinDeviation * 100.0,
                )
            }
            if (target.carbsG > 0.0 && abs(carbsDeviation) > MACRO_BAND + EPSILON) {
                warnings += issue(
                    "CARBS_OUT_OF_BAND", Severity.WARNING, "Carboidrati oltre ±25% dal target",
                    day.dateEpochDay, target.carbsG, day.carbsG.toDouble(), carbsDeviation * 100.0,
                )
            }
            if (target.fatG > 0.0 && abs(fatDeviation) > MACRO_BAND + EPSILON) {
                warnings += issue(
                    "FAT_OUT_OF_BAND", Severity.WARNING, "Grassi oltre ±25% dal target",
                    day.dateEpochDay, target.fatG, day.fatG.toDouble(), fatDeviation * 100.0,
                )
            }
            DayAssessment(
                dateEpochDay = day.dateEpochDay,
                targetKcal = target.kcal, actualKcal = day.totalKcal.toDouble(), kcalDeviation = kcalDeviation,
                targetProteinG = target.proteinG, actualProteinG = day.proteinG.toDouble(), proteinDeviation = proteinDeviation,
                targetCarbsG = target.carbsG, actualCarbsG = day.carbsG.toDouble(), carbsDeviation = carbsDeviation,
                targetFatG = target.fatG, actualFatG = day.fatG.toDouble(), fatDeviation = fatDeviation,
                classification = classification,
                blocking = blocking,
            )
        }
        if (days.isNotEmpty()) {
            val mean = days.map { abs(it.kcalDeviation) }.average()
            if (mean > CENTERING_WARNING_MEAN + EPSILON) {
                warnings += NutritionValidationIssue(
                    code = "CALORIE_CENTERING_LOOSE",
                    severity = Severity.WARNING,
                    message = "Scostamento medio assoluto delle calorie oltre il 2%",
                    expected = CENTERING_WARNING_MEAN * 100.0,
                    actual = mean * 100.0,
                    deviationPercent = mean * 100.0,
                )
            }
        }
        return Assessment(days, tolerance, attempt, blockers, warnings)
    }

    private fun deviation(target: Double, actual: Double): Double = if (target > 0.0) (actual - target) / target else 0.0

    private fun issue(
        code: String, severity: Severity, message: String, day: Long, expected: Double, actual: Double, deviationPercent: Double,
    ) = NutritionValidationIssue(code, severity, message, day, null, expected, actual, deviationPercent)
}
