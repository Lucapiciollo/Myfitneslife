package com.myfitai.app.data.repository

import androidx.room.withTransaction
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.*
import kotlinx.coroutines.flow.Flow

class UserProfileRepository(private val db: MyFitAiDatabase) {
    val profile: Flow<UserProfileEntity?> = db.userProfileDao().observe()
    suspend fun get() = db.userProfileDao().get()
    suspend fun upsert(value: UserProfileEntity) = db.userProfileDao().upsert(value)
}

class BiaRepository(private val db: MyFitAiDatabase) {
    val all: Flow<List<BiaMeasurementEntity>> = db.biaMeasurementDao().observeAll()
    val latest: Flow<BiaMeasurementEntity?> = db.biaMeasurementDao().observeLatest()
    fun between(from: Long, to: Long) = db.biaMeasurementDao().observeBetween(from, to)
    suspend fun insert(value: BiaMeasurementEntity) = db.biaMeasurementDao().insert(value)
    suspend fun update(value: BiaMeasurementEntity) = db.biaMeasurementDao().update(value)
    suspend fun delete(value: BiaMeasurementEntity) = db.biaMeasurementDao().delete(value)
}

class BodyMeasurementRepository(private val db: MyFitAiDatabase) {
    val all: Flow<List<BodyMeasurementEntity>> = db.bodyMeasurementDao().observeAll()
    val latest: Flow<BodyMeasurementEntity?> = db.bodyMeasurementDao().observeLatest()
    fun between(from: Long, to: Long) = db.bodyMeasurementDao().observeBetween(from, to)
    suspend fun insert(value: BodyMeasurementEntity) = db.bodyMeasurementDao().insert(value)
    suspend fun update(value: BodyMeasurementEntity) = db.bodyMeasurementDao().update(value)
    suspend fun delete(value: BodyMeasurementEntity) = db.bodyMeasurementDao().delete(value)
}

class WorkoutRepository(private val db: MyFitAiDatabase) {
    val all: Flow<List<WorkoutEntity>> = db.workoutDao().observeAll()
    fun between(from: Long, to: Long) = db.workoutDao().observeBetween(from, to)
    suspend fun insert(value: WorkoutEntity) = db.workoutDao().insert(value)
    suspend fun update(value: WorkoutEntity) = db.workoutDao().update(value)
    suspend fun delete(value: WorkoutEntity) = db.workoutDao().delete(value)
}

class CheatEntryRepository(private val db: MyFitAiDatabase) {
    val all: Flow<List<CheatEntryEntity>> = db.cheatEntryDao().observeAll()
    fun between(from: Long, to: Long) = db.cheatEntryDao().observeBetween(from, to)
    suspend fun insert(value: CheatEntryEntity) = db.cheatEntryDao().insert(value)
    suspend fun delete(value: CheatEntryEntity) = db.cheatEntryDao().delete(value)
}

class WeeklyReviewRepository(private val db: MyFitAiDatabase) {
    val all: Flow<List<WeeklyReviewEntity>> = db.weeklyReviewDao().observeAll()
    suspend fun getForWeek(weekStartEpochDay: Long) = db.weeklyReviewDao().getForWeek(weekStartEpochDay)
    suspend fun upsert(value: WeeklyReviewEntity) = db.weeklyReviewDao().upsert(value)
}

data class IngredientDraft(
    val name: String,
    val quantity: Float,
    val unit: String,
    val displayDose: String?,
    val weightState: String?,
    val nutritionConfidence: String?,
    val category: String?,
)

data class MealDraft(
    val type: String,
    val title: String,
    val timeMinutes: Int?,
    val kcal: Int?,
    val proteinG: Float?,
    val carbsG: Float?,
    val fatG: Float?,
    val preparation: String?,
    val ingredients: List<IngredientDraft>,
)

data class DayDraft(
    val dateEpochDay: Long,
    val totalKcal: Int?,
    val proteinG: Float?,
    val carbsG: Float?,
    val fatG: Float?,
    val meals: List<MealDraft>,
)

data class PlanVersionDraft(
    val source: String,
    val reason: String?,
    val targetKcal: Int?,
    val targetProteinG: Float?,
    val targetCarbsG: Float?,
    val targetFatG: Float?,
    val days: List<DayDraft>,
)

class MealPlanRepository(private val db: MyFitAiDatabase) {
    val plans: Flow<List<MealPlanEntity>> = db.mealPlanDao().observePlans()

    fun versions(planId: Long): Flow<List<MealPlanVersionEntity>> = db.mealPlanDao().observeVersions(planId)

    suspend fun createPlan(weekStartEpochDay: Long, createdAtEpochMillis: Long): Long =
        db.mealPlanDao().insertPlan(
            MealPlanEntity(
                createdAtEpochMillis = createdAtEpochMillis,
                weekStartEpochDay = weekStartEpochDay,
                status = "ACTIVE",
            )
        )

    /**
     * Salva una versione immutabile completa. Non aggiorna versioni precedenti: lo sgarro/adattamento
     * dovrà sempre passare da questo metodo creando versionNumber + 1.
     */
    suspend fun appendVersion(
        planId: Long,
        createdAtEpochMillis: Long,
        draft: PlanVersionDraft,
    ): Long = db.withTransaction {
        val dao = db.mealPlanDao()
        val nextVersion = (dao.getLatestVersion(planId)?.versionNumber ?: 0) + 1
        val versionId = dao.insertVersion(
            MealPlanVersionEntity(
                planId = planId,
                versionNumber = nextVersion,
                createdAtEpochMillis = createdAtEpochMillis,
                source = draft.source,
                reason = draft.reason,
                targetKcal = draft.targetKcal,
                targetProteinG = draft.targetProteinG,
                targetCarbsG = draft.targetCarbsG,
                targetFatG = draft.targetFatG,
            )
        )

        draft.days.forEach { day ->
            val dayId = dao.insertDays(
                listOf(
                    MealPlanDayEntity(
                        versionId = versionId,
                        dateEpochDay = day.dateEpochDay,
                        totalKcal = day.totalKcal,
                        proteinG = day.proteinG,
                        carbsG = day.carbsG,
                        fatG = day.fatG,
                    )
                )
            ).single()

            day.meals.forEachIndexed { mealIndex, meal ->
                val mealId = dao.insertMeals(
                    listOf(
                        MealEntity(
                            dayId = dayId,
                            sortOrder = mealIndex,
                            type = meal.type,
                            title = meal.title,
                            timeMinutes = meal.timeMinutes,
                            kcal = meal.kcal,
                            proteinG = meal.proteinG,
                            carbsG = meal.carbsG,
                            fatG = meal.fatG,
                            preparation = meal.preparation,
                        )
                    )
                ).single()

                if (meal.ingredients.isNotEmpty()) {
                    dao.insertIngredients(
                        meal.ingredients.mapIndexed { ingredientIndex, ingredient ->
                            MealIngredientEntity(
                                mealId = mealId,
                                name = ingredient.name,
                                quantity = ingredient.quantity,
                                unit = ingredient.unit,
                                displayDose = ingredient.displayDose,
                                weightState = ingredient.weightState,
                                nutritionConfidence = ingredient.nutritionConfidence,
                                category = ingredient.category,
                                sortOrder = ingredientIndex,
                            )
                        }
                    )
                }
            }
        }
        versionId
    }
}
