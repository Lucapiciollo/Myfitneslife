package com.myfitai.app.domain.safety

import com.myfitai.app.domain.ai.AiUserContext
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSafetyGateTest {
    private val declaredNone = HealthDeclarationInput(pregnancyOrLactation = false, clinicianManagedConditionOrMedication = false)

    private fun input(
        age: Int? = 40,
        bmi: Double? = 24.0,
        goal: LocalCalculationEngine.Goal? = LocalCalculationEngine.Goal.MAINTENANCE,
        declaration: HealthDeclarationInput? = declaredNone,
        weights: List<Pair<Long, Float>> = emptyList(),
        sex: LocalCalculationEngine.BiologicalSex = LocalCalculationEngine.BiologicalSex.FEMALE,
        bmr: Double? = null,
        tdee: Double? = null,
        target: Double? = null,
    ) = SafetyInput(age, bmi, goal, sex, declaration, weights, bmr, tdee, target)

    @Test
    fun adultBoundaryAndMissingAgeFailClosed() {
        assertEquals(SafetyOutcome.BLOCK_REFER, GenerationSafetyGate.evaluate(input(age = 17)).outcome)
        assertFalse(SafetyRuleCode.AGE_UNDER_18 in GenerationSafetyGate.evaluate(input(age = 18)).rules)
        assertEquals(SafetyRuleCode.AGE_UNAVAILABLE, GenerationSafetyGate.evaluate(input(age = null)).rules.first())
    }

    @Test
    fun unapprovedBmiThresholdsDoNotAffectTheGate() {
        assertEquals(SafetyOutcome.ALLOW, GenerationSafetyGate.evaluate(input(bmi = 18.49, goal = LocalCalculationEngine.Goal.RECOMPOSITION)).outcome)
        assertEquals(SafetyOutcome.ALLOW, GenerationSafetyGate.evaluate(input(bmi = 16.99, goal = LocalCalculationEngine.Goal.MAINTENANCE)).outcome)
    }

    @Test
    fun declarationsAreRequiredAndMaintenanceIsTheOnlyAllowedGoalForDeclaredRisks() {
        assertEquals(SafetyOutcome.BLOCK_REFER, GenerationSafetyGate.evaluate(input(declaration = null)).outcome)
        assertEquals(SafetyOutcome.BLOCK_REFER, GenerationSafetyGate.evaluate(input(declaration = HealthDeclarationInput(null, false))).outcome)
        assertEquals(
            SafetyOutcome.BLOCK_NON_MAINTENANCE,
            GenerationSafetyGate.evaluate(input(goal = LocalCalculationEngine.Goal.WEIGHT_LOSS, declaration = HealthDeclarationInput(true, true))).outcome,
        )
        assertEquals(
            listOf(SafetyRuleCode.PREGNANCY_OR_LACTATION_DECLARED, SafetyRuleCode.CLINICIAN_MANAGED_CONDITION_DECLARED),
            GenerationSafetyGate.evaluate(input(declaration = HealthDeclarationInput(true, true))).rules,
        )
    }

    @Test
    fun pregnancyAndConditionWarnForMaintenanceAndBlockOtherGoals() {
        assertEquals(
            SafetyOutcome.ALLOW_WITH_NOTICE,
            GenerationSafetyGate.evaluate(input(declaration = HealthDeclarationInput(true, false))).outcome,
        )
        assertEquals(
            SafetyOutcome.ALLOW_WITH_NOTICE,
            GenerationSafetyGate.evaluate(input(declaration = HealthDeclarationInput(false, true))).outcome,
        )
        assertEquals(
            SafetyOutcome.BLOCK_NON_MAINTENANCE,
            GenerationSafetyGate.evaluate(input(goal = LocalCalculationEngine.Goal.MUSCLE_GAIN, declaration = HealthDeclarationInput(false, true))).outcome,
        )
    }

    @Test
    fun calorieRulesFailClosedAndUseTheConfiguredFloorAndCap() {
        assertEquals(
            SafetyOutcome.BLOCK_DEFICIT,
            GenerationSafetyGate.evaluate(input(tdee = 2000.0, target = 1399.0, bmr = 1400.0, goal = LocalCalculationEngine.Goal.WEIGHT_LOSS)).outcome,
        )
        assertEquals(SafetyOutcome.BLOCK_DEFICIT, GenerationSafetyGate.evaluate(input(tdee = 1390.0, target = 1390.0, bmr = 1400.0, goal = LocalCalculationEngine.Goal.WEIGHT_LOSS)).outcome)
        assertEquals(SafetyOutcome.BLOCK_DEFICIT, GenerationSafetyGate.evaluate(input(tdee = 2600.0, target = 1849.0, bmr = 1500.0, goal = LocalCalculationEngine.Goal.WEIGHT_LOSS)).outcome)
        assertEquals(SafetyOutcome.ALLOW, GenerationSafetyGate.evaluate(input(tdee = 2600.0, target = 1850.0, bmr = 1500.0, goal = LocalCalculationEngine.Goal.WEIGHT_LOSS)).outcome)
        assertEquals(
            SafetyOutcome.ALLOW,
            GenerationSafetyGate.evaluate(input(tdee = 1900.0, target = 1300.0, goal = LocalCalculationEngine.Goal.WEIGHT_LOSS)).outcome,
        )
        assertEquals(
            SafetyOutcome.BLOCK_DEFICIT,
            GenerationSafetyGate.evaluate(
                input(tdee = 1900.0, target = 1300.0, sex = LocalCalculationEngine.BiologicalSex.MALE, goal = LocalCalculationEngine.Goal.WEIGHT_LOSS),
            ).outcome,
        )
        assertEquals(SafetyOutcome.BLOCK_REFER, GenerationSafetyGate.evaluate(input(tdee = Double.NaN)).outcome)
    }

    @Test
    fun warningsDoNotOverrideHarderOutcomesAndRuleOrderingIsStable() {
        val riskInput = input(
            age = 70,
            bmi = 18.4,
            goal = LocalCalculationEngine.Goal.WEIGHT_LOSS,
            declaration = HealthDeclarationInput(false, true),
            weights = listOf(14L * 86_400_000L to 78f, 0L to 80f),
            bmr = null,
            tdee = 2400.0,
            target = 1600.0,
        )
        val first = GenerationSafetyGate.evaluate(riskInput)
        val second = GenerationSafetyGate.evaluate(riskInput.copy(weightObservations = riskInput.weightObservations.reversed()))
        assertEquals(SafetyOutcome.BLOCK_NON_MAINTENANCE, first.outcome)
        assertEquals(first.rules, second.rules)
        assertTrue(SafetyRuleCode.OLDER_ADULT_REDUCTION_GOAL in first.rules)
        assertTrue(SafetyRuleCode.RAPID_WEIGHT_LOSS_WITH_REDUCTION_GOAL in first.rules)
    }

    @Test
    fun aiContextLegacyThresholdsRemainAliasesAndRapidLossBehaviorIsShared() {
        assertEquals(SafetyThresholds.MIN_ADULT_AGE_YEARS, AiUserContext.SAFETY_MIN_ADULT_AGE)
        assertEquals(SafetyThresholds.LEGACY_OLDER_ADULT_FLAG_AGE_YEARS, AiUserContext.SAFETY_ELDERLY_AGE)
        assertEquals(SafetyThresholds.LOW_BMI, AiUserContext.SAFETY_LOW_BMI, 0.0)
        val observations = listOf(0L to 80f, 14L * 86_400_000L to 78f)
        assertEquals(AiUserContext.safetyFlag(40, 24.0, LocalCalculationEngine.Goal.WEIGHT_LOSS, observations, null, "Maschio").reasons.contains("RAPID_WEIGHT_LOSS"),
            GenerationSafetyGate.isRapidWeightLoss(observations))
    }
}

/** Golden examples stay beside the gate tests to keep this S1 slice within its five-file limit. */
class SafetyGoldenTest {
    private val declaredNone = HealthDeclarationInput(false, false)

    private data class Case(val name: String, val input: SafetyInput, val expected: SafetyOutcome)

    @Test
    fun proposedGoldenProfilesHaveDeterministicOutcomes() {
        val cases = listOf(
            Case("ordinary adult", SafetyInput(40, 24.0, LocalCalculationEngine.Goal.MAINTENANCE, null, declaredNone), SafetyOutcome.ALLOW),
            Case("minor", SafetyInput(17, 24.0, LocalCalculationEngine.Goal.MAINTENANCE, null, declaredNone), SafetyOutcome.BLOCK_REFER),
            Case("unapproved low BMI threshold", SafetyInput(40, 16.9, LocalCalculationEngine.Goal.MAINTENANCE, null, declaredNone), SafetyOutcome.ALLOW),
            Case("health declaration missing", SafetyInput(40, null, LocalCalculationEngine.Goal.MAINTENANCE, null, null), SafetyOutcome.BLOCK_REFER),
            Case("pregnancy on maintenance", SafetyInput(30, 24.0, LocalCalculationEngine.Goal.MAINTENANCE, null, HealthDeclarationInput(true, false)), SafetyOutcome.ALLOW_WITH_NOTICE),
            Case("older adult notice", SafetyInput(65, 24.0, LocalCalculationEngine.Goal.WEIGHT_LOSS, null, declaredNone), SafetyOutcome.ALLOW_WITH_NOTICE),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, GenerationSafetyGate.evaluate(case.input).outcome)
        }
    }
}
