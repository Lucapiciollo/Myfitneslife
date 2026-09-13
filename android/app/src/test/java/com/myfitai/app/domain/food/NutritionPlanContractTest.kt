package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NutritionPlanContractTest {
    private val week = LocalDate.of(2026, 9, 14)
    private val targets = NutritionBusinessValidator.Targets(2400.0, 160.0, 280.0, 70.0)

    @Test
    fun validWeek_passesAuthoritativeAppValidation() {
        assertTrue(NutritionPlanContract.validateBusiness(response(), week, targets).isSuccess)
    }

    @Test
    fun dayOutsideThreePercent_isRejected() {
        val original = response()
        val badDay = original.days.first().copy(totalKcal = 2200)
        val changed = original.copy(days = listOf(badDay) + original.days.drop(1))
        assertFalse(NutritionPlanContract.validateBusiness(changed, week, targets).isSuccess)
    }

    @Test
    fun wrongWeekDates_areRejected() {
        val original = response()
        val changed = original.copy(days = original.days.mapIndexed { index, day ->
            if (index == 6) day.copy(dateEpochDay = week.plusDays(8).toEpochDay()) else day
        })
        assertFalse(NutritionPlanContract.validateBusiness(changed, week, targets).isSuccess)
    }

    @Test
    fun agentValidation_doesNotOverrideAppValidation() {
        val original = response().copy(agentValidation = NutritionPlanContract.AgentValidation(true, "looks valid"))
        val badDay = original.days.first().copy(proteinG = 120f)
        val changed = original.copy(days = listOf(badDay) + original.days.drop(1))
        assertFalse(NutritionPlanContract.validateBusiness(changed, week, targets).isSuccess)
    }

    private fun response(): NutritionPlanContract.Response {
        val meal = NutritionPlanContract.GeneratedMeal(
            type = "Pranzo",
            title = "Pasto completo",
            timeMinutes = 780,
            kcal = 800,
            proteinG = 53.333f,
            carbsG = 93.333f,
            fatG = 23.333f,
            preparation = "Preparazione semplice",
            ingredients = listOf(
                NutritionPlanContract.GeneratedIngredient("Riso", 100f, "g", "100 g", "crudo", "high", "cereali"),
            ),
        )
        return NutritionPlanContract.Response(
            weekStartEpochDay = week.toEpochDay(),
            days = (0L..6L).map { offset ->
                NutritionPlanContract.GeneratedDay(
                    dateEpochDay = week.plusDays(offset).toEpochDay(),
                    totalKcal = 2400,
                    proteinG = 160f,
                    carbsG = 280f,
                    fatG = 70f,
                    meals = listOf(meal, meal.copy(type = "Cena"), meal.copy(type = "Colazione")),
                )
            },
            agentValidation = NutritionPlanContract.AgentValidation(false, "advisory only"),
        )
    }
}
