package com.myfitai.app.fixtures

import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.food.SportsNutritionClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SixMonthHistoryFixtureTest {
    private val dataset = SixMonthHistoryFixture.build()

    @Test
    fun fixture_isDeterministicAndCoversSixMonthJourney() {
        val second = SixMonthHistoryFixture.build()

        assertEquals(dataset, second)
        assertEquals(13, dataset.bia.size)
        assertEquals(13, dataset.body.size)
        assertEquals(26, dataset.plans.size)
        assertEquals(18, dataset.cheats.size)
        assertTrue(dataset.workouts.size > 70)
        assertEquals(94f, dataset.bia.first().weightKg!!, 0.6f)
        assertEquals(89f, dataset.bia.last().weightKg!!, 0.6f)
        assertEquals(22f, dataset.bia.first().bodyFatPercent!!, 0.5f)
        assertEquals(18f, dataset.bia.last().bodyFatPercent!!, 0.5f)
        assertEquals(96f, dataset.body.first().waistCm!!, 0.7f)
        assertEquals(88.5f, dataset.body.last().waistCm!!, 0.7f)
    }

    @Test
    fun fixture_hasNormalAndSportWorkoutPhases() {
        val firstWeek = dataset.plans[1].weekStart
        val sportWeek = dataset.plans[12].weekStart
        val firstWorkouts = dataset.workouts.filter { date(it.startedAtEpochMillis) in firstWeek..firstWeek.plusDays(6) }
        val sportWorkouts = dataset.workouts.filter { date(it.startedAtEpochMillis) in sportWeek..sportWeek.plusDays(6) }

        assertEquals(SportsNutritionClassifier.Mode.NORMAL, SportsNutritionClassifier.classify("sedentario", firstWorkouts))
        assertEquals(SportsNutritionClassifier.Mode.SPORT, SportsNutritionClassifier.classify("moderatamente attivo", sportWorkouts))
        assertTrue(sportWorkouts.count { !it.isRestDay } >= 4)
        assertTrue(firstWorkouts.all { it.isRestDay })
    }

    @Test
    fun fixture_plans_haveSevenDaysValidIngredientsSupplementsAndHydration() {
        dataset.plans.forEach { plan ->
            plan.versions.forEach { version ->
                assertEquals(7, version.days.size)
                version.days.forEach { day ->
                    assertEquals(5, day.meals.size)
                    assertNotNull(day.totalKcal)
                    assertTrue(day.meals.all { meal ->
                        meal.timeMinutes in 0..1439 &&
                            meal.ingredients.isNotEmpty() &&
                            meal.ingredients.all { it.quantity > 0f && it.unit.isNotBlank() && !it.displayDose.isNullOrBlank() }
                    })
                    assertTrue(day.supplements.all { it.dose > 0f && it.timeMinutes in 0..1439 })
                }
            }
        }
        assertTrue(dataset.plans.flatMap { it.versions }.any { it.reason?.startsWith("AI_MEAL_SWAP:") == true })
        assertTrue(dataset.plans.flatMap { it.versions }.any { it.reason?.startsWith("CHEAT_ADAPTATION:") == true })
    }

    @Test
    fun twentySixWeeks_keepDailyTargetsAndSupplementRulesWithinContract() {
        dataset.plans.forEach { plan ->
            plan.versions.forEach { version ->
                val response = NutritionPlanContractResponseFactory.from(version)
                val mode = if (plan.weekStart.isBefore(SixMonthHistoryFixture.START.plusMonths(2))) {
                    com.myfitai.app.domain.food.SportsNutritionClassifier.Mode.NORMAL
                } else {
                    com.myfitai.app.domain.food.SportsNutritionClassifier.Mode.SPORT
                }
                val result = com.myfitai.app.domain.food.NutritionPlanContract.validateBusiness(
                    response,
                    plan.weekStart,
                    NutritionBusinessValidator.Targets(
                        version.targetKcal!!.toDouble(),
                        version.targetProteinG!!.toDouble(),
                        version.targetCarbsG!!.toDouble(),
                        version.targetFatG!!.toDouble(),
                    ),
                    mode,
                )
                assertTrue("${plan.weekStart} ${version.reason}: ${result.exceptionOrNull()?.message}", result.isSuccess)
            }
        }
    }

    @Test
    fun sixMonthCalculationRegression_hasExpectedTrendAndTargets() {
        val first = dataset.bia.first()
        val last = dataset.bia.last()
        val calculation = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = last.weightKg!!.toDouble(),
                heightCm = dataset.profile.heightCm!!.toDouble(),
                ageYears = 43,
                biologicalSex = LocalCalculationEngine.BiologicalSex.MALE,
                bodyFatPercent = last.bodyFatPercent!!.toDouble(),
                activityLevel = LocalCalculationEngine.ActivityLevel.MODERATE,
                goal = LocalCalculationEngine.Goal.RECOMPOSITION,
                waistCm = dataset.body.last().waistCm!!.toDouble(),
            ),
        )
        val weightTrend = LocalCalculationEngine.trend(dataset.bia.map { LocalCalculationEngine.TimedValue(it.measuredAtEpochMillis, it.weightKg!!.toDouble()) })
        val fatTrend = LocalCalculationEngine.trend(dataset.bia.map { LocalCalculationEngine.TimedValue(it.measuredAtEpochMillis, it.bodyFatPercent!!.toDouble()) })
        val muscleTrend = LocalCalculationEngine.trend(dataset.bia.map { LocalCalculationEngine.TimedValue(it.measuredAtEpochMillis, it.muscleMassKg!!.toDouble()) })

        assertEquals(-5.0, weightTrend.delta!!, 0.8)
        assertEquals(-4.0, fatTrend.delta!!, 0.5)
        assertEquals(1.7, muscleTrend.delta!!, 0.5)
        assertTrue(calculation.targetKcal!! > 2400.0)
        assertTrue(calculation.proteinG!! > 170.0)
        assertTrue(calculation.waistHeightRatio!! < 0.5)
        assertFalse(first.weightKg == last.weightKg)
    }

    private fun date(epoch: Long): LocalDate = java.time.Instant.ofEpochMilli(epoch).atZone(SixMonthHistoryFixture.ZONE).toLocalDate()
}

private object NutritionPlanContractResponseFactory {
    fun from(version: com.myfitai.app.data.repository.PlanVersionDraft): com.myfitai.app.domain.food.NutritionPlanContract.Response =
        com.myfitai.app.domain.food.NutritionPlanContract.Response(
            weekStartEpochDay = version.days.first().dateEpochDay,
            days = version.days.map { day ->
                com.myfitai.app.domain.food.NutritionPlanContract.GeneratedDay(
                    dateEpochDay = day.dateEpochDay,
                    totalKcal = day.totalKcal!!,
                    proteinG = day.proteinG!!,
                    carbsG = day.carbsG!!,
                    fatG = day.fatG!!,
                    meals = day.meals.map { meal ->
                        com.myfitai.app.domain.food.NutritionPlanContract.GeneratedMeal(
                            type = meal.type,
                            title = meal.title,
                            timeMinutes = meal.timeMinutes!!,
                            kcal = meal.kcal!!,
                            proteinG = meal.proteinG!!,
                            carbsG = meal.carbsG!!,
                            fatG = meal.fatG!!,
                            preparation = meal.preparation!!,
                            ingredients = meal.ingredients.map { ingredient ->
                                com.myfitai.app.domain.food.NutritionPlanContract.GeneratedIngredient(
                                    ingredient.name,
                                    ingredient.quantity,
                                    ingredient.unit,
                                    ingredient.displayDose!!,
                                    ingredient.weightState!!,
                                    ingredient.nutritionConfidence!!,
                                    ingredient.category!!,
                                )
                            },
                        )
                    },
                    supplements = day.supplements.map { supplement ->
                        com.myfitai.app.domain.food.NutritionPlanContract.GeneratedSupplement(
                            supplement.kind,
                            supplement.name,
                            supplement.dose,
                            supplement.unit,
                            supplement.timeMinutes!!,
                            supplement.kcal,
                            supplement.proteinG,
                            supplement.carbsG,
                            supplement.fatG,
                            supplement.notes.orEmpty(),
                        )
                    },
                    hydrationNote = "Fixture hydration",
                )
            },
            agentValidation = com.myfitai.app.domain.food.NutritionPlanContract.AgentValidation(true, "fixture"),
        )
}
