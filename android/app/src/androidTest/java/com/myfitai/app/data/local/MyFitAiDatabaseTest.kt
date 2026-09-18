package com.myfitai.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.local.entity.WorkoutEntity
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.domain.food.FoodConsumptionService
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.food.FoodConsumptionStatus
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.time.TimeProvider
import com.myfitai.app.data.repository.DayDraft
import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.PlanVersionDraft
import com.myfitai.app.data.repository.SupplementDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyFitAiDatabaseTest {
    private lateinit var db: MyFitAiDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyFitAiDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun bodyMeasurements_areIsolatedByProfile_andRangeAscending() = runBlocking {
        val profile1Id = db.userProfileDao().insert(profile("Uno"))
        val profile2Id = db.userProfileDao().insert(profile("Due"))
        val dao = db.bodyMeasurementDao()
        dao.insert(measure(profile1Id, 1000, 90f))
        dao.insert(measure(profile1Id, 3000, 86f))
        dao.insert(measure(profile1Id, 2000, 88f))
        dao.insert(measure(profile2Id, 4000, 70f))
        assertEquals(listOf(3000L, 2000L, 1000L), dao.observeAll(profile1Id).first().map { it.measuredAtEpochMillis })
        assertEquals(listOf(1000L, 2000L), dao.observeBetween(profile1Id, 1000, 2500).first().map { it.measuredAtEpochMillis })
        assertEquals(listOf(4000L), dao.observeAll(profile2Id).first().map { it.measuredAtEpochMillis })
    }

    @Test
    fun bodyMeasurements_sameTimestamp_keepNewestInsertFirst() = runBlocking {
        val profileId = db.userProfileDao().insert(profile("Storico misure"))
        val dao = db.bodyMeasurementDao()
        val firstId = dao.insert(measure(profileId, 5000, 90f))
        val secondId = dao.insert(measure(profileId, 5000, 89f))
        val all = dao.observeAll(profileId).first()
        assertEquals(listOf(secondId, firstId), all.map { it.id })
        assertEquals(listOf(89f, 90f), all.map { it.waistCm })
    }

    @Test
    fun biaHistory_isIsolatedByProfile_andChronological() = runBlocking {
        val profile1Id = db.userProfileDao().insert(profile("BIA uno"))
        val profile2Id = db.userProfileDao().insert(profile("BIA due"))
        val dao = db.biaMeasurementDao()
        dao.insert(bia(profile1Id, 1000, 80f))
        dao.insert(bia(profile1Id, 3000, 78f))
        dao.insert(bia(profile1Id, 2000, null))
        dao.insert(bia(profile2Id, 4000, 70f))
        val all = dao.observeAll(profile1Id).first()
        assertEquals(listOf(3000L, 2000L, 1000L), all.map { it.measuredAtEpochMillis })
        assertEquals(listOf(78f, null, 80f), all.map { it.weightKg })
        assertEquals(listOf(1000L, 2000L), dao.observeBetween(profile1Id, 1000, 2500).first().map { it.measuredAtEpochMillis })
        assertEquals(listOf(70f), dao.observeAll(profile2Id).first().map { it.weightKg })
    }

    @Test
    fun workoutHistory_isIsolatedByProfile_andRangeAscending() = runBlocking {
        val profile1Id = db.userProfileDao().insert(profile("Workout uno"))
        val profile2Id = db.userProfileDao().insert(profile("Workout due"))
        val dao = db.workoutDao()
        dao.insert(workout(profile1Id, 3000, "Upper"))
        dao.insert(workout(profile1Id, 1000, "Lower"))
        dao.insert(workout(profile1Id, 2000, "Riposo", rest = true))
        dao.insert(workout(profile2Id, 4000, "Cardio"))
        assertEquals(listOf(3000L, 2000L, 1000L), dao.observeAll(profile1Id).first().map { it.startedAtEpochMillis })
        assertEquals(listOf(1000L, 2000L), dao.observeBetween(profile1Id, 1000, 2500).first().map { it.startedAtEpochMillis })
        assertEquals(listOf("Cardio"), dao.observeAll(profile2Id).first().map { it.title })
    }

    @Test
    fun mealPlan_appendVersion_neverOverwritesPreviousVersion() = runBlocking {
        val profileId = db.userProfileDao().insert(profile("Piano"))
        val repository = MealPlanRepository(db)
        val planId = repository.createPlan(profileId, 20000, 1000)
        val emptyDraft = PlanVersionDraft(
            source = "TEST",
            reason = null,
            targetKcal = 2200,
            targetProteinG = 160f,
            targetCarbsG = 230f,
            targetFatG = 70f,
            days = listOf(DayDraft(20000, 2200, 160f, 230f, 70f, emptyList())),
        )
        repository.appendVersion(profileId, planId, 2000, emptyDraft)
        repository.appendVersion(profileId, planId, 3000, emptyDraft.copy(reason = "ADAPTATION"))
        val versions = repository.versions(profileId, planId).first()
        assertEquals(2, versions.size)
        assertEquals(listOf(2, 1), versions.map { it.versionNumber })
        assertTrue(versions.any { it.reason == "ADAPTATION" })
    }

    @Test
    fun mealPlan_latestSnapshot_containsDaysMealsAndIngredients() = runBlocking {
        val profileId = db.userProfileDao().insert(profile("Piano completo"))
        val repository = MealPlanRepository(db)
        val weekStart = 21000L
        val planId = repository.createPlan(profileId, weekStart, 1000)
        repository.appendVersion(
            profileId = profileId,
            planId = planId,
            createdAtEpochMillis = 2000,
            draft = PlanVersionDraft(
                source = "TEST",
                reason = null,
                targetKcal = 2200,
                targetProteinG = 160f,
                targetCarbsG = 230f,
                targetFatG = 70f,
                days = listOf(
                    DayDraft(
                        dateEpochDay = weekStart,
                        totalKcal = 2200,
                        proteinG = 160f,
                        carbsG = 230f,
                        fatG = 70f,
                        meals = listOf(
                            MealDraft(
                                type = "Pranzo",
                                title = "Riso e pollo",
                                timeMinutes = 780,
                                kcal = 700,
                                proteinG = 50f,
                                carbsG = 80f,
                                fatG = 18f,
                                preparation = "Cuoci e componi",
                                ingredients = listOf(
                                    IngredientDraft("Riso", 80f, "g", "80 g", "DRY", "HIGH", "carbs"),
                                    IngredientDraft("Pollo", 200f, "g", "200 g", "RAW", "HIGH", "protein"),
                                ),
                            )
                        ),
                    )
                ),
            ),
        )

        val snapshot = repository.loadLatestSnapshot(profileId, weekStart)!!
        assertEquals(profileId, snapshot.profileId)
        assertEquals(1, snapshot.version.versionNumber)
        assertEquals(1, snapshot.version.days.size)
        assertEquals("Riso e pollo", snapshot.version.days.single().meals.single().title)
        assertEquals(listOf("Riso", "Pollo"), snapshot.version.days.single().meals.single().ingredients.map { it.name })
        assertTrue(snapshot.version.days.single().supplements.isEmpty())
        assertEquals(null, snapshot.version.days.single().hydrationNote)
    }

    @Test
    fun mealPlan_swapLikeNewVersion_preservesSupplementsAndHydration() = runBlocking {
        val profileId = db.userProfileDao().insert(profile("Swap test"))
        val repository = MealPlanRepository(db)
        val weekStart = 22000L
        val planId = repository.createPlan(profileId, weekStart, 1000)

        val supplements = listOf(
            SupplementDraft(
                kind = "PROTEIN_POWDER",
                name = "Whey",
                dose = 30f,
                unit = "g",
                timeMinutes = 1110,
                kcal = 120,
                proteinG = 24f,
                carbsG = 3f,
                fatG = 2f,
                notes = "Post-workout",
            )
        )

        val originalMeals = listOf(
            MealDraft(
                type = "Pranzo",
                title = "Riso e pollo",
                timeMinutes = 780,
                kcal = 700,
                proteinG = 50f,
                carbsG = 80f,
                fatG = 18f,
                preparation = "Cuoci e componi",
                ingredients = listOf(
                    IngredientDraft("Riso", 80f, "g", "80 g", "DRY", "HIGH", "carbs"),
                    IngredientDraft("Pollo", 200f, "g", "200 g", "RAW", "HIGH", "protein"),
                ),
            ),
            MealDraft(
                type = "Cena",
                title = "Salmone e patate",
                timeMinutes = 1200,
                kcal = 800,
                proteinG = 45f,
                carbsG = 75f,
                fatG = 30f,
                preparation = "Forno",
                ingredients = listOf(
                    IngredientDraft("Salmone", 180f, "g", "180 g", "RAW", "HIGH", "protein"),
                    IngredientDraft("Patate", 300f, "g", "300 g", "RAW", "HIGH", "carbs"),
                ),
            ),
        )

        repository.appendVersion(
            profileId = profileId,
            planId = planId,
            createdAtEpochMillis = 2000,
            draft = PlanVersionDraft(
                source = "TEST",
                reason = null,
                targetKcal = 2200,
                targetProteinG = 160f,
                targetCarbsG = 230f,
                targetFatG = 70f,
                days = listOf(
                    DayDraft(
                        dateEpochDay = weekStart,
                        totalKcal = 2200,
                        proteinG = 160f,
                        carbsG = 230f,
                        fatG = 70f,
                        meals = originalMeals,
                        supplements = supplements,
                        hydrationNote = "Distribuisci l'acqua nella giornata",
                    )
                ),
            ),
        )

        val before = repository.loadLatestSnapshot(profileId, weekStart)!!
        val sourceDay = before.version.days.single()
        val mealToReplace = sourceDay.meals.first { it.type == "Pranzo" }

        val replacedMeals = sourceDay.meals.map { meal ->
            if (meal.id == mealToReplace.id) {
                MealDraft(
                    type = meal.type,
                    title = "Pasta e tonno",
                    timeMinutes = meal.timeMinutes,
                    kcal = meal.kcal,
                    proteinG = 48f,
                    carbsG = 82f,
                    fatG = 17f,
                    preparation = "Componi",
                    ingredients = listOf(
                        IngredientDraft("Pasta", 90f, "g", "90 g", "DRY", "HIGH", "carbs"),
                        IngredientDraft("Tonno", 120f, "g", "120 g", "DRAINED", "HIGH", "protein"),
                    ),
                )
            } else {
                MealDraft(
                    type = meal.type,
                    title = meal.title,
                    timeMinutes = meal.timeMinutes,
                    kcal = meal.kcal,
                    proteinG = meal.proteinG,
                    carbsG = meal.carbsG,
                    fatG = meal.fatG,
                    preparation = meal.preparation,
                    ingredients = meal.ingredients.map { i ->
                        IngredientDraft(i.name, i.quantity, i.unit, i.displayDose, i.weightState, i.nutritionConfidence, i.category)
                    },
                )
            }
        }

        repository.appendVersion(
            profileId = profileId,
            planId = planId,
            createdAtEpochMillis = 3000,
            draft = PlanVersionDraft(
                source = "TEST",
                reason = "AI_MEAL_SWAP:${mealToReplace.id}",
                targetKcal = 2200,
                targetProteinG = 160f,
                targetCarbsG = 230f,
                targetFatG = 70f,
                days = listOf(
                    DayDraft(
                        dateEpochDay = sourceDay.dateEpochDay,
                        totalKcal = 2200,
                        proteinG = 160f,
                        carbsG = 230f,
                        fatG = 70f,
                        meals = replacedMeals,
                        supplements = sourceDay.supplements.map { s ->
                            SupplementDraft(s.kind, s.name, s.dose, s.unit, s.timeMinutes, s.kcal, s.proteinG, s.carbsG, s.fatG, s.notes)
                        },
                        hydrationNote = sourceDay.hydrationNote,
                    )
                ),
            ),
        )

        val after = repository.loadLatestSnapshot(profileId, weekStart)!!
        val afterDay = after.version.days.single()
        assertEquals(before.version.days.single().supplements, afterDay.supplements)
        assertEquals(before.version.days.single().hydrationNote, afterDay.hydrationNote)
        assertEquals(2, afterDay.meals.size)
        assertEquals("Pasta e tonno", afterDay.meals.first { it.type == "Pranzo" }.title)
        assertEquals("Salmone e patate", afterDay.meals.first { it.type == "Cena" }.title)
    }

    @Test
    fun mealPlan_nestedReads_areProfileScoped() = runBlocking {
        val ownerId = db.userProfileDao().insert(profile("Owner"))
        val otherId = db.userProfileDao().insert(profile("Other"))
        val repository = MealPlanRepository(db)
        val planId = repository.createPlan(ownerId, 23000L, 1000L)
        repository.appendVersion(
            profileId = ownerId,
            planId = planId,
            createdAtEpochMillis = 2000L,
            draft = PlanVersionDraft(
                source = "TEST",
                reason = null,
                targetKcal = 2200,
                targetProteinG = 160f,
                targetCarbsG = 230f,
                targetFatG = 70f,
                days = listOf(DayDraft(
                    dateEpochDay = 23000L,
                    totalKcal = 2200,
                    proteinG = 160f,
                    carbsG = 230f,
                    fatG = 70f,
                    meals = listOf(MealDraft(
                        type = "Pranzo",
                        title = "Owner meal",
                        timeMinutes = 780,
                        kcal = 700,
                        proteinG = 50f,
                        carbsG = 80f,
                        fatG = 18f,
                        preparation = "Fixture",
                        ingredients = listOf(IngredientDraft("Riso", 80f, "g", "80 g", "DRY", "HIGH", "carbs")),
                    )),
                )),
            ),
        )
        val ownerSnapshot = repository.loadLatestSnapshot(ownerId, 23000L)!!
        val mealId = ownerSnapshot.version.days.single().meals.single().id

        assertTrue(repository.versions(otherId, planId).first().isEmpty())
        assertEquals(null, repository.loadLatestSnapshot(otherId, 23000L))
        assertEquals(null, repository.getMealDetail(otherId, mealId))
        assertEquals("Owner meal", repository.getMealDetail(ownerId, mealId)?.title)
        assertEquals(null, repository.getMealContext(otherId, mealId))
        assertEquals(planId, repository.getMealContext(ownerId, mealId)?.planId)
        assertEquals("Owner meal", repository.getMealContext(ownerId, mealId)?.meal?.title)
    }

    @Test
    fun foodConsumption_isProfileScoped_andKeepsSnapshot() = runBlocking {
        val profile1Id = db.userProfileDao().insert(profile("Consumo uno"))
        val profile2Id = db.userProfileDao().insert(profile("Consumo due"))
        val repository = com.myfitai.app.data.repository.FoodConsumptionRepository(db)
        val value = FoodConsumptionEntity(
            profileId = profile1Id,
            planId = 10L,
            planVersionId = 20L,
            dayId = 30L,
            plannedDateEpochDay = 23000L,
            itemType = "MEAL",
            itemKey = "MEAL:40",
            mealId = 40L,
            supplementKey = null,
            status = "CONSUMED",
            recordedAtEpochMillis = 1000L,
            updatedAtEpochMillis = 1000L,
            quantityFactor = 1f,
            kcal = 700,
            proteinG = 50f,
            carbsG = 80f,
            fatG = 18f,
            note = "Pasto completo",
        )
        repository.upsert(value)

        assertEquals(1, repository.all(profile1Id).first().size)
        assertEquals(0, repository.all(profile2Id).first().size)
        assertEquals(value.copy(id = 1L), repository.getForItem(profile1Id, 20L, "MEAL:40"))
    }

    @Test
    fun foodConsumptionService_scalesSnapshot_updatesStatus_andClears() = runBlocking {
        val profileId = db.userProfileDao().insert(profile("Servizio consumo"))
        val store = ActiveProfileStore(ApplicationProvider.getApplicationContext())
        store.setActiveProfile(profileId)
        val service = FoodConsumptionService(
            repository = com.myfitai.app.data.repository.FoodConsumptionRepository(db),
            activeProfileStore = store,
            time = object : TimeProvider {
                override val zoneId = java.time.ZoneId.of("UTC")
                override fun nowEpochMillis(): Long = 2000L
            },
        )
        val meal = FoodMeal(40L, 30L, 0, "Pranzo", "Riso e pollo", 780, 700, 50f, 80f, 18f, null, emptyList())

        val consumed = service.setMealStatus(10L, 20L, 30L, 23000L, meal, FoodConsumptionStatus.CONSUMED, quantityFactor = 0.5f)
        assertEquals(350, consumed.kcal)
        assertEquals(25f, consumed.proteinG)
        assertEquals(2000L, consumed.recordedAtEpochMillis)

        val skipped = service.setMealStatus(10L, 20L, 30L, 23000L, meal, FoodConsumptionStatus.SKIPPED)
        assertEquals(consumed.id, skipped.id)
        assertEquals(FoodConsumptionStatus.SKIPPED.name, skipped.status)
        service.clear(20L, "MEAL:40")
        assertEquals(null, db.foodConsumptionDao().getForItem(profileId, 20L, "MEAL:40"))
    }

    @Test
    fun workout_createsDefaultEnergy_andExternalUpdateIsReadableByDay() = runBlocking {
        val profileId = db.userProfileDao().insert(profile("Allenamento energetico"))
        val workouts = com.myfitai.app.data.repository.WorkoutRepository(db)
        val energy = com.myfitai.app.data.repository.WorkoutEnergyExpenditureRepository(db)
        val workoutId = workouts.insert(workout(profileId, 86_400_000L, "Pesi"), nowEpochMillis = 2_000L)

        val default = energy.forDay(profileId, java.time.Instant.ofEpochMilli(86_400_000L).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay())
        assertEquals(1, default.size)
        assertEquals(500, default.single().caloriesKcal)
        assertEquals("DEFAULT", default.single().source)

        energy.updateCalories(workoutId, 640, updatedAtEpochMillis = 3_000L)
        val updated = energy.getByWorkoutId(workoutId)!!
        assertEquals(640, updated.caloriesKcal)
        assertEquals("EXTERNAL_APP", updated.source)
        assertEquals(3_000L, updated.updatedAtEpochMillis)
    }

    @Test
    fun profileCalculation_readsExerciseEnergyFromLedgerAndAddsItToTdee() = runBlocking {
        val now = 86_400_000L
        val profileId = db.userProfileDao().insert(UserProfileEntity(
            name = "Calcolo energetico",
            birthDateEpochDay = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().minusYears(40).toEpochDay(),
            heightCm = 180f,
            currentWeightKg = 80f,
            goal = "Mantenimento",
            activityLevel = "Sedentario",
            wakeTimeMinutes = null,
            sleepTimeMinutes = null,
            dietaryPreferencesJson = null,
            photoPath = null,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            biologicalSex = "Maschio",
            initialWeightKg = 80f,
        ))
        val workoutRepository = com.myfitai.app.data.repository.WorkoutRepository(db)
        val energyRepository = com.myfitai.app.data.repository.WorkoutEnergyExpenditureRepository(db)
        val workoutId = workoutRepository.insert(workout(profileId, now, "Pesi"), nowEpochMillis = now)
        energyRepository.updateCalories(workoutId, 640, now + 1)
        val store = ActiveProfileStore(ApplicationProvider.getApplicationContext())
        store.selectProfile(profileId)

        val snapshot = ProfileCalculationService(
            profiles = com.myfitai.app.data.repository.UserProfileRepository(db),
            bia = com.myfitai.app.data.repository.BiaRepository(db),
            bodyMeasurements = com.myfitai.app.data.repository.BodyMeasurementRepository(db),
            activeProfileStore = store,
            exerciseEnergy = energyRepository,
        ).profileSnapshot(profileId, java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate())!!

        assertEquals(640, snapshot.calculation.exerciseKcal)
        assertEquals(2076.0, snapshot.calculation.baseTdeeKcal!!, 0.1)
        assertEquals(2716.0, snapshot.calculation.tdeeKcal!!, 0.1)
    }

    private fun profile(name: String): UserProfileEntity {
        val now = 1000L
        return UserProfileEntity(name = name, birthDateEpochDay = null, heightCm = null, currentWeightKg = null, goal = null, activityLevel = null, wakeTimeMinutes = null, sleepTimeMinutes = null, dietaryPreferencesJson = null, photoPath = null, createdAtEpochMillis = now, updatedAtEpochMillis = now)
    }

    private fun measure(profileId: Long, at: Long, waist: Float) = BodyMeasurementEntity(profileId = profileId, measuredAtEpochMillis = at, chestCm = null, waistCm = waist, abdomenCm = null, shouldersCm = null, glutesCm = null, armLeftCm = null, armRightCm = null, thighLeftCm = null, thighRightCm = null, calfLeftCm = null, calfRightCm = null)

    private fun bia(profileId: Long, at: Long, weight: Float?) = BiaMeasurementEntity(profileId = profileId, measuredAtEpochMillis = at, weightKg = weight, bodyFatPercent = null, visceralFatLevel = null, muscleMassKg = null, skeletalMuscleKg = null, bodyWaterPercent = null, bmrKcal = null, fasting = false, justWokeUp = false, afterBathroom = false, noRecentWorkout = false)

    private fun workout(profileId: Long, at: Long, title: String, rest: Boolean = false) = WorkoutEntity(
        profileId = profileId,
        startedAtEpochMillis = at,
        type = if (rest) "REST" else "PESI",
        title = title,
        durationMinutes = if (rest) null else 45,
        isRestDay = rest,
    )
}
