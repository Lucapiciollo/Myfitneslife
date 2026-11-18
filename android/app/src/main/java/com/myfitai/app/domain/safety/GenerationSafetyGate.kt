package com.myfitai.app.domain.safety

import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.SafetyCalorieFloor

/**
 * Gate deterministico S1. È puro e attualmente non collegato a UI, persistenza o chiamate IA.
 * Le regole marcate come proposte restano inattive nel prodotto finché non approvate.
 */
object GenerationSafetyGate {
    private const val DAY_MILLIS = 86_400_000.0
    private const val NUMERIC_EPSILON = 1e-9
    private val maintenanceOnlyRules = setOf(
        SafetyRuleCode.PREGNANCY_OR_LACTATION_DECLARED,
        SafetyRuleCode.CLINICIAN_MANAGED_CONDITION_DECLARED,
    )

    fun evaluate(input: SafetyInput): SafetyDecision {
        val rules = linkedSetOf<SafetyRuleCode>()
        val age = input.ageYears
        when {
            age == null -> rules += SafetyRuleCode.AGE_UNAVAILABLE
            age !in 0..120 -> rules += SafetyRuleCode.AGE_INVALID
            age < SafetyThresholds.MIN_ADULT_AGE_YEARS -> rules += SafetyRuleCode.AGE_UNDER_18
        }

        val declaration = input.healthDeclaration
        if (declaration == null || declaration.pregnancyOrLactation == null || declaration.clinicianManagedConditionOrMedication == null) {
            rules += SafetyRuleCode.HEALTH_DECLARATION_INCOMPLETE
        } else {
            if (declaration.pregnancyOrLactation) rules += SafetyRuleCode.PREGNANCY_OR_LACTATION_DECLARED
            if (declaration.clinicianManagedConditionOrMedication) rules += SafetyRuleCode.CLINICIAN_MANAGED_CONDITION_DECLARED
        }

        if (input.goal == LocalCalculationEngine.Goal.WEIGHT_LOSS && isRapidWeightLoss(input.weightObservations)) {
            rules += SafetyRuleCode.RAPID_WEIGHT_LOSS_WITH_REDUCTION_GOAL
        }
        if (age != null && age >= SafetyThresholds.OLDER_ADULT_NOTICE_AGE_YEARS && input.goal == LocalCalculationEngine.Goal.WEIGHT_LOSS) {
            rules += SafetyRuleCode.OLDER_ADULT_REDUCTION_GOAL
        }

        if (hasInvalidCalorieInput(input)) {
            rules += SafetyRuleCode.CALORIE_INPUT_INVALID
        } else {
            val target = input.targetKcal
            val floor = if (SafetyCalorieFloor.appliesTo(input.goal)) {
                SafetyCalorieFloor.minimumKcal(input.bmrKcal, input.biologicalSex)
            } else {
                null
            }
            val tdee = input.tdeeKcal
            if (target != null && floor != null && target < floor - NUMERIC_EPSILON) {
                rules += SafetyRuleCode.TARGET_BELOW_CALORIE_FLOOR
            }
            if (input.goal.isReductionGoal() && tdee != null && floor != null && tdee < floor - NUMERIC_EPSILON) {
                rules += SafetyRuleCode.FLOOR_ABOVE_TDEE
            }
            if (input.goal.isReductionGoal() && target != null && tdee != null && tdee - target > SafetyThresholds.MAX_DAILY_DEFICIT_KCAL + NUMERIC_EPSILON) {
                rules += SafetyRuleCode.DEFICIT_ABOVE_DAILY_CAP
            }
        }

        val orderedRules = rules.sortedBy { it.ordinal }
        val outcome = orderedRules
            .map { rule ->
                if (input.goal == LocalCalculationEngine.Goal.MAINTENANCE && rule in maintenanceOnlyRules) {
                    SafetyOutcome.ALLOW_WITH_NOTICE
                } else {
                    rule.outcome
                }
            }
            .maxByOrNull { it.priority }
            ?: SafetyOutcome.ALLOW
        return SafetyDecision(outcome = outcome, rules = orderedRules)
    }

    /** Stessa regola percentuale legacy di AiUserContext; condivisa per evitare una seconda formula. */
    fun isRapidWeightLoss(observations: List<Pair<Long, Float>>): Boolean {
        val ordered = observations.sortedBy { it.first }
        val latest = ordered.lastOrNull() ?: return false
        val windowStart = latest.first - SafetyThresholds.RAPID_LOSS_WINDOW_DAYS * DAY_MILLIS
        val window = ordered.filter { it.first >= windowStart }
        val first = window.firstOrNull() ?: return false
        val spanDays = (latest.first - first.first) / DAY_MILLIS
        if (spanDays < SafetyThresholds.RAPID_LOSS_MIN_SPAN_DAYS || first.second <= 0f) return false
        val weeklyLossPercent = (first.second - latest.second) / first.second * 100.0 / (spanDays / 7.0)
        return weeklyLossPercent > SafetyThresholds.RAPID_LOSS_PERCENT_PER_WEEK
    }

    private fun hasInvalidCalorieInput(input: SafetyInput): Boolean = listOfNotNull(
        input.bmrKcal,
        input.tdeeKcal,
        input.targetKcal,
    ).any { !it.isFinite() || it <= 0.0 }

    private fun LocalCalculationEngine.Goal?.isReductionGoal(): Boolean = when (this) {
        LocalCalculationEngine.Goal.WEIGHT_LOSS,
        LocalCalculationEngine.Goal.RECOMPOSITION -> true
        else -> false
    }
}
