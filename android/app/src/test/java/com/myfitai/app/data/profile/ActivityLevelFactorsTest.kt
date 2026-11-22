package com.myfitai.app.data.profile

import com.myfitai.app.domain.calculation.LocalCalculationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLevelFactorsTest {
    @Test
    fun defaultsAndDescriptionsMatchTheApprovedFiveLevels() {
        assertEquals(
            listOf(1.20, 1.375, 1.55, 1.725, 1.90),
            ActivityLevelFactors.options.map { it.defaultCoefficient },
        )
        assertEquals(
            listOf(
                "Lavoro sedentario, pochi passi, nessun allenamento.",
                "Vita prevalentemente sedentaria, ma qualche camminata o 1–3 allenamenti leggeri.",
                "Movimento quotidiano discreto e allenamento regolare, circa 3–5 volte a settimana.",
                "Lavoro attivo o allenamenti intensi e frequenti.",
                "Attività fisica molto elevata, spesso associata a lavoro fisico impegnativo e allenamenti intensi.",
            ),
            ActivityLevelFactors.options.map { it.description },
        )
        assertTrue(ActivityLevelFactors.isValid(ActivityLevelFactors.defaultCoefficients))
    }

    @Test
    fun validationRequiresAllLevelsBoundsPrecisionAndStrictOrder() {
        val levels = LocalCalculationEngine.ActivityLevel.values()
        assertFalse(ActivityLevelFactors.isValid(ActivityLevelFactors.defaultCoefficients - levels.first()))

        assertFalse(ActivityLevelFactors.isValid(ActivityLevelFactors.defaultCoefficients.toMutableMap().apply {
            this[levels[0]] = 0.999
        }))
        assertFalse(ActivityLevelFactors.isValid(ActivityLevelFactors.defaultCoefficients.toMutableMap().apply {
            this[levels.last()] = 2.501
        }))
        assertFalse(ActivityLevelFactors.isValid(ActivityLevelFactors.defaultCoefficients.toMutableMap().apply {
            this[levels[1]] = 1.3751
        }))
        assertFalse(ActivityLevelFactors.isValid(ActivityLevelFactors.defaultCoefficients.toMutableMap().apply {
            this[levels[1]] = 1.20
        }))

        assertTrue(ActivityLevelFactors.isValid(ActivityLevelFactors.defaultCoefficients.toMutableMap().apply {
            this[levels[1]] = 1.376
        }))
    }

    @Test
    fun calculationUsesConfiguredMultiplierAndStillRequiresKnownActivityLevel() {
        val default = LocalCalculationEngine.calculate(input())
        val configured = LocalCalculationEngine.calculate(input(multiplier = 1.600))
        assertEquals(default.bmrKcal!! * 1.55, default.tdeeKcal!!, 0.000001)
        assertEquals(configured.bmrKcal!! * 1.60, configured.tdeeKcal!!, 0.000001)
        assertNull(LocalCalculationEngine.calculate(input(multiplier = 2.501)).tdeeKcal)
        assertNull(LocalCalculationEngine.calculate(input(activityLevel = null, multiplier = 1.60)).tdeeKcal)
    }

    private fun input(
        activityLevel: LocalCalculationEngine.ActivityLevel? = LocalCalculationEngine.ActivityLevel.MODERATE,
        multiplier: Double? = null,
    ) = LocalCalculationEngine.Input(
        weightKg = 70.0,
        heightCm = 170.0,
        ageYears = 30,
        biologicalSex = LocalCalculationEngine.BiologicalSex.MALE,
        activityLevel = activityLevel,
        goal = LocalCalculationEngine.Goal.MAINTENANCE,
        activityMultiplier = multiplier,
    )
}
