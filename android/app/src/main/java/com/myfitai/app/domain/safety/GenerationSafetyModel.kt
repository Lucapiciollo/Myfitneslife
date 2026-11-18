package com.myfitai.app.domain.safety

import com.myfitai.app.domain.calculation.LocalCalculationEngine

/** Esiti interni al dominio: il gate non contiene copy e non viene ancora invocato dal runtime. */
enum class SafetyOutcome(val priority: Int) {
    ALLOW(0),
    ALLOW_WITH_NOTICE(1),
    BLOCK_DEFICIT(2),
    BLOCK_NON_MAINTENANCE(3),
    BLOCK_REFER(4),
}

/** Codici stabili, non diagnostici; le risorse UI saranno aggiunte solo in una fase successiva. */
enum class SafetyRuleCode(val outcome: SafetyOutcome, val messageKey: String) {
    AGE_UNAVAILABLE(SafetyOutcome.BLOCK_REFER, "safety_age_unavailable"),
    AGE_INVALID(SafetyOutcome.BLOCK_REFER, "safety_age_invalid"),
    AGE_UNDER_18(SafetyOutcome.BLOCK_REFER, "safety_minor_plan_blocked"),
    HEALTH_DECLARATION_INCOMPLETE(SafetyOutcome.BLOCK_REFER, "safety_declaration_incomplete"),
    PREGNANCY_OR_LACTATION_DECLARED(SafetyOutcome.BLOCK_NON_MAINTENANCE, "safety_pregnancy_lactation_maintenance_only"),
    CLINICIAN_MANAGED_CONDITION_DECLARED(SafetyOutcome.BLOCK_NON_MAINTENANCE, "safety_health_condition_maintenance_only"),
    CALORIE_INPUT_INVALID(SafetyOutcome.BLOCK_REFER, "safety_calorie_input_invalid"),
    TARGET_BELOW_CALORIE_FLOOR(SafetyOutcome.BLOCK_DEFICIT, "safety_target_below_floor"),
    FLOOR_ABOVE_TDEE(SafetyOutcome.BLOCK_DEFICIT, "safety_floor_above_tdee"),
    DEFICIT_ABOVE_DAILY_CAP(SafetyOutcome.BLOCK_DEFICIT, "safety_deficit_above_cap"),
    RAPID_WEIGHT_LOSS_WITH_REDUCTION_GOAL(SafetyOutcome.ALLOW_WITH_NOTICE, "safety_rapid_weight_change"),
    OLDER_ADULT_REDUCTION_GOAL(SafetyOutcome.ALLOW_WITH_NOTICE, "safety_older_adult_reduction_goal"),
}

/** Le dichiarazioni restano nullable finché S2 non introduce la persistenza esplicita. */
data class HealthDeclarationInput(
    val pregnancyOrLactation: Boolean?,
    val clinicianManagedConditionOrMedication: Boolean?,
)

/** Snapshot sintetico, solo memoria: non persistere né inoltrare dichiarazioni sanitarie ai provider IA. */
data class SafetyInput(
    val ageYears: Int?,
    val bmi: Double?,
    val goal: LocalCalculationEngine.Goal?,
    val biologicalSex: LocalCalculationEngine.BiologicalSex?,
    val healthDeclaration: HealthDeclarationInput?,
    val weightObservations: List<Pair<Long, Float>> = emptyList(),
    val bmrKcal: Double? = null,
    val tdeeKcal: Double? = null,
    val targetKcal: Double? = null,
)

data class SafetyDecision(
    val outcome: SafetyOutcome,
    val rules: List<SafetyRuleCode>,
    val thresholdsVersion: String = SafetyThresholds.VERSION,
)
