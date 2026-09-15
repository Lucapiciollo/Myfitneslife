package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.data.local.entity.WorkoutEntity
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

    @Test
    fun identicalRecipeOnDifferentDays_isRejected() {
        val varied = response()
        val duplicate = varied.copy(days = varied.days.map { day ->
            day.copy(meals = day.meals.map { meal ->
                meal.copy(
                    title = "Same recipe",
                    ingredients = listOf(meal.ingredients.first().copy(name = "Same ingredient")),
                )
            })
        })
        assertFalse(NutritionPlanContract.validateBusiness(duplicate, week, targets, enforceWeeklyVariety = true).isSuccess)
    }

    @Test
    fun recurringStapleWithDifferentRecipe_isAllowed() {
        val base = response()
        val varied = base.copy(days = base.days.mapIndexed { index, day ->
            day.copy(meals = day.meals.map { meal ->
                meal.copy(
                    title = "${meal.title} $index",
                    ingredients = meal.ingredients.map { ingredient -> ingredient.copy(name = "${ingredient.name} $index") },
                )
            })
        })
        assertTrue(NutritionPlanContract.validateBusiness(varied, week, targets, enforceWeeklyVariety = true).isSuccess)
    }

    @Test
    fun normalMode_withoutWorkouts_rejectsCreatine() {
        val mode = SportsNutritionClassifier.classify("sedentario", emptyList())
        val changed = withFirstDaySupplements(
            response(),
            listOf(creatine(kcal = 0, protein = 0f, carbs = 0f, fat = 0f)),
        )
        assertFalse(
            NutritionPlanContract.validateBusiness(
                changed,
                week,
                targets,
                mode,
            ).isSuccess,
        )
    }

    @Test
    fun sportMode_withWorkouts_allowsCreatine() {
        val mode = SportsNutritionClassifier.classify(
            "moderatamente attivo",
            listOf(
                workout(1_000L, "PESI", "Upper"),
                workout(2_000L, "PESI", "Lower"),
            ),
        )
        val base = response()
        val changed = withFirstDaySupplements(
            base,
            listOf(creatine(kcal = 0, protein = 0f, carbs = 0f, fat = 0f)),
        )
        assertTrue(
            NutritionPlanContract.validateBusiness(
                changed,
                week,
                targets,
                mode,
            ).isSuccess,
        )
    }

    @Test
    fun proteinPowder_inNormalMode_isAllowedAndCountedInTotals() {
        val base = responseWithMacroRoomForSupplement()
        val changed = withFirstDaySupplements(
            base,
            listOf(proteinPowder(kcal = 120, protein = 24f, carbs = 3f, fat = 2f)),
        )
        assertTrue(
            NutritionPlanContract.validateBusiness(
                changed,
                week,
                targets,
                SportsNutritionClassifier.Mode.NORMAL,
            ).isSuccess,
        )
    }

    @Test
    fun creatineWithCalories_isRejected() {
        val base = responseWithMacroRoomForSupplement()
        val changed = withFirstDaySupplements(
            base,
            listOf(creatine(kcal = 20, protein = 0f, carbs = 0f, fat = 0f)),
        )
        assertFalse(
            NutritionPlanContract.validateBusiness(
                changed,
                week,
                targets,
                SportsNutritionClassifier.Mode.SPORT,
            ).isSuccess,
        )
    }

    @Test
    fun wheyNotCountedIntoDayTotals_isRejected() {
        val changed = withFirstDaySupplements(
            response(),
            listOf(proteinPowder(kcal = 120, protein = 24f, carbs = 3f, fat = 2f)),
        )
        assertFalse(
            NutritionPlanContract.validateBusiness(
                changed,
                week,
                targets,
                SportsNutritionClassifier.Mode.NORMAL,
            ).isSuccess,
        )
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
                    meals = listOf(
                        meal.copy(title = "Pasto completo $offset", ingredients = meal.ingredients.map { it.copy(name = "Riso $offset") }),
                        meal.copy(type = "Cena", title = "Cena completa $offset", ingredients = meal.ingredients.map { it.copy(name = "Patate $offset") }),
                        meal.copy(type = "Colazione", title = "Colazione completa $offset", ingredients = meal.ingredients.map { it.copy(name = "Avena $offset") }),
                    ),
                )
            },
            agentValidation = NutritionPlanContract.AgentValidation(false, "advisory only"),
        )
    }

    private fun responseWithMacroRoomForSupplement(): NutritionPlanContract.Response {
        val meal = NutritionPlanContract.GeneratedMeal(
            type = "Pranzo",
            title = "Pasto completo",
            timeMinutes = 780,
            kcal = 760,
            proteinG = 45.333f,
            carbsG = 92.333f,
            fatG = 22.667f,
            preparation = "Preparazione semplice",
            ingredients = listOf(
                NutritionPlanContract.GeneratedIngredient("Riso", 100f, "g", "100 g", "crudo", "high", "cereali"),
            ),
        )
        val validDayMeal = meal.copy(
            kcal = 800,
            proteinG = 53.333f,
            carbsG = 93.333f,
            fatG = 23.333f,
        )
        return NutritionPlanContract.Response(
            weekStartEpochDay = week.toEpochDay(),
            days = (0L..6L).mapIndexed { index, offset ->
                NutritionPlanContract.GeneratedDay(
                    dateEpochDay = week.plusDays(offset).toEpochDay(),
                    totalKcal = 2400,
                    proteinG = 160f,
                    carbsG = 280f,
                    fatG = 70f,
                    meals = if (index == 0) {
                        listOf(
                            meal.copy(title = "Pasto completo 0", ingredients = meal.ingredients.map { it.copy(name = "Riso 0") }),
                            meal.copy(type = "Cena", title = "Cena completa 0", ingredients = meal.ingredients.map { it.copy(name = "Patate 0") }),
                            meal.copy(type = "Colazione", title = "Colazione completa 0", ingredients = meal.ingredients.map { it.copy(name = "Avena 0") }),
                        )
                    } else {
                        listOf(
                            validDayMeal.copy(title = "Pasto completo $index", ingredients = validDayMeal.ingredients.map { it.copy(name = "Riso $index") }),
                            validDayMeal.copy(type = "Cena", title = "Cena completa $index", ingredients = validDayMeal.ingredients.map { it.copy(name = "Patate $index") }),
                            validDayMeal.copy(type = "Colazione", title = "Colazione completa $index", ingredients = validDayMeal.ingredients.map { it.copy(name = "Avena $index") }),
                        )
                    },
                )
            },
            agentValidation = NutritionPlanContract.AgentValidation(false, "advisory only"),
        )
    }

    private fun withFirstDaySupplements(
        source: NutritionPlanContract.Response,
        supplements: List<NutritionPlanContract.GeneratedSupplement>,
    ): NutritionPlanContract.Response {
        val first = source.days.first().copy(supplements = supplements)
        return source.copy(days = listOf(first) + source.days.drop(1))
    }

    private fun creatine(kcal: Int, protein: Float, carbs: Float, fat: Float) =
        NutritionPlanContract.GeneratedSupplement(
            kind = "CREATINE",
            name = "Creatina monoidrato",
            dose = 5f,
            unit = "g",
            timeMinutes = 1080,
            kcal = kcal,
            proteinG = protein,
            carbsG = carbs,
            fatG = fat,
            notes = "Uso sportivo",
        )

    private fun proteinPowder(kcal: Int, protein: Float, carbs: Float, fat: Float) =
        NutritionPlanContract.GeneratedSupplement(
            kind = "PROTEIN_POWDER",
            name = "Whey",
            dose = 30f,
            unit = "g",
            timeMinutes = 1110,
            kcal = kcal,
            proteinG = protein,
            carbsG = carbs,
            fatG = fat,
            notes = "Post-workout o praticita",
        )

    private fun workout(startedAt: Long, type: String, title: String) = WorkoutEntity(
        id = 0,
        profileId = 1,
        startedAtEpochMillis = startedAt,
        type = type,
        title = title,
        durationMinutes = 60,
        isRestDay = false,
        notes = null,
    )
}
