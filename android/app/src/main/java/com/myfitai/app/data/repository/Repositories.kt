package com.myfitai.app.data.repository

import androidx.room.withTransaction
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.*
import com.myfitai.app.domain.food.FoodSupplement
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

class UserProfileRepository(private val db: MyFitAiDatabase) {
    val profiles: Flow<List<UserProfileEntity>> = db.userProfileDao().observeAll()
    fun profile(profileId: Long): Flow<UserProfileEntity?> = db.userProfileDao().observe(profileId)
    suspend fun get(profileId: Long) = db.userProfileDao().get(profileId)
    suspend fun getFirst() = db.userProfileDao().getFirst()
    suspend fun create(value: UserProfileEntity): Long = db.userProfileDao().insert(value)
    suspend fun update(value: UserProfileEntity) = db.userProfileDao().update(value)
    suspend fun delete(value: UserProfileEntity) = db.userProfileDao().delete(value)
}

class BiaRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<BiaMeasurementEntity>> = db.biaMeasurementDao().observeAll(profileId)
    fun latest(profileId: Long): Flow<BiaMeasurementEntity?> = db.biaMeasurementDao().observeLatest(profileId)
    fun between(profileId: Long, from: Long, to: Long) = db.biaMeasurementDao().observeBetween(profileId, from, to)
    suspend fun insert(value: BiaMeasurementEntity) = db.biaMeasurementDao().insert(value)
    suspend fun update(value: BiaMeasurementEntity) = db.biaMeasurementDao().update(value)
    suspend fun delete(value: BiaMeasurementEntity) = db.biaMeasurementDao().delete(value)
}

class BodyMeasurementRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<BodyMeasurementEntity>> = db.bodyMeasurementDao().observeAll(profileId)
    fun latest(profileId: Long): Flow<BodyMeasurementEntity?> = db.bodyMeasurementDao().observeLatest(profileId)
    fun between(profileId: Long, from: Long, to: Long) = db.bodyMeasurementDao().observeBetween(profileId, from, to)
    suspend fun insert(value: BodyMeasurementEntity) = db.bodyMeasurementDao().insert(value)
    suspend fun update(value: BodyMeasurementEntity) = db.bodyMeasurementDao().update(value)
    suspend fun delete(value: BodyMeasurementEntity) = db.bodyMeasurementDao().delete(value)
}

class WorkoutRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<WorkoutEntity>> = db.workoutDao().observeAll(profileId)
    fun between(profileId: Long, from: Long, to: Long) = db.workoutDao().observeBetween(profileId, from, to)
    suspend fun insert(value: WorkoutEntity) = db.workoutDao().insert(value)
    suspend fun update(value: WorkoutEntity) = db.workoutDao().update(value)
    suspend fun delete(value: WorkoutEntity) = db.workoutDao().delete(value)
}

class CheatEntryRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<CheatEntryEntity>> = db.cheatEntryDao().observeAll(profileId)
    fun between(profileId: Long, from: Long, to: Long) = db.cheatEntryDao().observeBetween(profileId, from, to)
    suspend fun insert(value: CheatEntryEntity) = db.cheatEntryDao().insert(value)
    suspend fun update(value: CheatEntryEntity) = db.cheatEntryDao().update(value)
    suspend fun delete(value: CheatEntryEntity) = db.cheatEntryDao().delete(value)
}

class FoodConsumptionRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<FoodConsumptionEntity>> = db.foodConsumptionDao().observeAll(profileId)
    fun forDay(profileId: Long, dateEpochDay: Long): Flow<List<FoodConsumptionEntity>> = db.foodConsumptionDao().observeForDay(profileId, dateEpochDay)
    fun forVersionDay(profileId: Long, planVersionId: Long, dateEpochDay: Long): Flow<List<FoodConsumptionEntity>> = db.foodConsumptionDao().observeForVersionDay(profileId, planVersionId, dateEpochDay)
    suspend fun getForItem(profileId: Long, planVersionId: Long, itemKey: String) = db.foodConsumptionDao().getForItem(profileId, planVersionId, itemKey)
    fun observeForItem(profileId: Long, planVersionId: Long, itemKey: String): Flow<FoodConsumptionEntity?> = db.foodConsumptionDao().observeForItem(profileId, planVersionId, itemKey)
    suspend fun upsert(value: FoodConsumptionEntity): Long = db.foodConsumptionDao().upsert(value)
    suspend fun deleteForItem(profileId: Long, planVersionId: Long, itemKey: String) = db.foodConsumptionDao().deleteForItem(profileId, planVersionId, itemKey)
    suspend fun deleteByProfile(profileId: Long) = db.foodConsumptionDao().deleteByProfile(profileId)
}

class WeeklyReviewRepository(private val db: MyFitAiDatabase) {
    fun all(profileId: Long): Flow<List<WeeklyReviewEntity>> = db.weeklyReviewDao().observeAll(profileId)
    suspend fun getForWeek(profileId: Long, weekStartEpochDay: Long) = db.weeklyReviewDao().getForWeek(profileId, weekStartEpochDay)
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

data class SupplementDraft(
    val kind: String,
    val name: String,
    val dose: Float,
    val unit: String,
    val timeMinutes: Int?,
    val kcal: Int,
    val proteinG: Float,
    val carbsG: Float,
    val fatG: Float,
    val notes: String?,
)

data class DayDraft(
    val dateEpochDay: Long,
    val totalKcal: Int?,
    val proteinG: Float?,
    val carbsG: Float?,
    val fatG: Float?,
    val meals: List<MealDraft>,
    val supplements: List<SupplementDraft> = emptyList(),
    val hydrationNote: String? = null,
)

data class PlanVersionDraft(
    val source: String,
    val reason: String?,
    val targetKcal: Int?,
    val targetProteinG: Float?,
    val targetCarbsG: Float?,
    val targetFatG: Float?,
    val days: List<DayDraft>,
    val appValidationJson: String? = null,
)

class MealPlanRepository(private val db: MyFitAiDatabase) {
    data class MealDetailContext(
        val planId: Long,
        val planVersionId: Long,
        val dayId: Long,
        val dateEpochDay: Long,
        val meal: com.myfitai.app.domain.food.FoodMeal,
    )
    fun plans(profileId: Long): Flow<List<MealPlanEntity>> = db.mealPlanDao().observePlans(profileId)
    fun versions(profileId: Long, planId: Long): Flow<List<MealPlanVersionEntity>> = db.mealPlanDao().observeVersions(profileId, planId)
    suspend fun getPlanForWeek(profileId: Long, weekStartEpochDay: Long) = db.mealPlanDao().getPlanForWeek(profileId, weekStartEpochDay)

    suspend fun createPlan(profileId: Long, weekStartEpochDay: Long, createdAtEpochMillis: Long): Long =
        db.mealPlanDao().insertPlan(
            MealPlanEntity(
                profileId = profileId,
                createdAtEpochMillis = createdAtEpochMillis,
                weekStartEpochDay = weekStartEpochDay,
                status = "ACTIVE",
            )
        )

    suspend fun appendVersion(
        profileId: Long,
        planId: Long,
        createdAtEpochMillis: Long,
        draft: PlanVersionDraft,
    ): Long = db.withTransaction {
        val dao = db.mealPlanDao()
        val plan = dao.getPlan(profileId, planId) ?: error("PLAN_NOT_FOUND_FOR_PROFILE")
        val nextVersion = (dao.getLatestVersion(profileId, plan.id)?.versionNumber ?: 0) + 1
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
                appValidationJson = draft.appValidationJson,
            )
        )

        draft.days.forEach { day ->
            val dayId = dao.insertDays(listOf(MealPlanDayEntity(
                versionId = versionId,
                dateEpochDay = day.dateEpochDay,
                totalKcal = day.totalKcal,
                proteinG = day.proteinG,
                carbsG = day.carbsG,
                fatG = day.fatG,
                supplementsJson = serializeSupplements(day.supplements),
                hydrationNote = day.hydrationNote,
            ))).single()

            day.meals.forEachIndexed { mealIndex, meal ->
                val mealId = dao.insertMeals(listOf(MealEntity(
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
                ))).single()

                if (meal.ingredients.isNotEmpty()) {
                    dao.insertIngredients(meal.ingredients.mapIndexed { ingredientIndex, ingredient ->
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
                    })
                }
            }
        }
        versionId
    }

    suspend fun loadLatestSnapshot(profileId: Long, weekStartEpochDay: Long): com.myfitai.app.domain.food.FoodPlanSnapshot? = db.withTransaction {
        val dao = db.mealPlanDao()
        val plan = dao.getPlanForWeek(profileId, weekStartEpochDay) ?: return@withTransaction null
        val version = dao.getLatestVersion(profileId, plan.id) ?: return@withTransaction null
        val days = dao.getDays(profileId, version.id).map { day ->
            com.myfitai.app.domain.food.FoodPlanDay(
                id = day.id,
                dateEpochDay = day.dateEpochDay,
                totalKcal = day.totalKcal,
                proteinG = day.proteinG,
                carbsG = day.carbsG,
                fatG = day.fatG,
                meals = dao.getMeals(profileId, day.id).map { meal ->
                    com.myfitai.app.domain.food.FoodMeal(
                        id = meal.id,
                        dayId = meal.dayId,
                        sortOrder = meal.sortOrder,
                        type = meal.type,
                        title = meal.title,
                        timeMinutes = meal.timeMinutes,
                        kcal = meal.kcal,
                        proteinG = meal.proteinG,
                        carbsG = meal.carbsG,
                        fatG = meal.fatG,
                        preparation = meal.preparation,
                        ingredients = dao.getIngredients(profileId, meal.id).map { ingredient ->
                            com.myfitai.app.domain.food.FoodIngredient(
                                id = ingredient.id,
                                mealId = ingredient.mealId,
                                name = ingredient.name,
                                quantity = ingredient.quantity,
                                unit = ingredient.unit,
                                displayDose = ingredient.displayDose,
                                weightState = ingredient.weightState,
                                nutritionConfidence = ingredient.nutritionConfidence,
                                category = ingredient.category,
                                sortOrder = ingredient.sortOrder,
                            )
                        },
                    )
                },
                supplements = parseSupplements(day.supplementsJson),
                hydrationNote = day.hydrationNote,
            )
        }
        com.myfitai.app.domain.food.FoodPlanSnapshot(
            planId = plan.id,
            profileId = plan.profileId,
            weekStartEpochDay = plan.weekStartEpochDay,
            version = com.myfitai.app.domain.food.FoodPlanVersion(
                id = version.id,
                versionNumber = version.versionNumber,
                createdAtEpochMillis = version.createdAtEpochMillis,
                source = version.source,
                reason = version.reason,
                targetKcal = version.targetKcal,
                targetProteinG = version.targetProteinG,
                targetCarbsG = version.targetCarbsG,
                targetFatG = version.targetFatG,
                days = days,
            ),
        )
    }

    suspend fun getMealDetail(profileId: Long, mealId: Long): com.myfitai.app.domain.food.FoodMeal? = db.withTransaction {
        val dao = db.mealPlanDao()
        val meal = dao.getMeal(profileId, mealId) ?: return@withTransaction null
        com.myfitai.app.domain.food.FoodMeal(
            id = meal.id,
            dayId = meal.dayId,
            sortOrder = meal.sortOrder,
            type = meal.type,
            title = meal.title,
            timeMinutes = meal.timeMinutes,
            kcal = meal.kcal,
            proteinG = meal.proteinG,
            carbsG = meal.carbsG,
            fatG = meal.fatG,
            preparation = meal.preparation,
            ingredients = dao.getIngredients(profileId, meal.id).map { ingredient ->
                com.myfitai.app.domain.food.FoodIngredient(
                    id = ingredient.id,
                    mealId = ingredient.mealId,
                    name = ingredient.name,
                    quantity = ingredient.quantity,
                    unit = ingredient.unit,
                    displayDose = ingredient.displayDose,
                    weightState = ingredient.weightState,
                    nutritionConfidence = ingredient.nutritionConfidence,
                    category = ingredient.category,
                    sortOrder = ingredient.sortOrder,
                )
            },
        )
    }

    suspend fun getMealContext(profileId: Long, mealId: Long): MealDetailContext? = db.withTransaction {
        val dao = db.mealPlanDao()
        val row = dao.getMealContext(profileId, mealId) ?: return@withTransaction null
        val ingredients = dao.getIngredients(profileId, mealId).map { ingredient ->
            com.myfitai.app.domain.food.FoodIngredient(
                id = ingredient.id,
                mealId = ingredient.mealId,
                name = ingredient.name,
                quantity = ingredient.quantity,
                unit = ingredient.unit,
                displayDose = ingredient.displayDose,
                weightState = ingredient.weightState,
                nutritionConfidence = ingredient.nutritionConfidence,
                category = ingredient.category,
                sortOrder = ingredient.sortOrder,
            )
        }
        MealDetailContext(
            planId = row.planId,
            planVersionId = row.planVersionId,
            dayId = row.dayId,
            dateEpochDay = row.dateEpochDay,
            meal = com.myfitai.app.domain.food.FoodMeal(
                id = row.mealId,
                dayId = row.mealDayId,
                sortOrder = row.mealSortOrder,
                type = row.mealType,
                title = row.mealTitle,
                timeMinutes = row.mealTimeMinutes,
                kcal = row.mealKcal,
                proteinG = row.mealProteinG,
                carbsG = row.mealCarbsG,
                fatG = row.mealFatG,
                preparation = row.mealPreparation,
                ingredients = ingredients,
            ),
        )
    }

    private fun serializeSupplements(values: List<SupplementDraft>): String? {
        if (values.isEmpty()) return null
        val array = JSONArray()
        values.forEach { value ->
            array.put(JSONObject().apply {
                put("kind", value.kind)
                put("name", value.name)
                put("dose", value.dose.toDouble())
                put("unit", value.unit)
                if (value.timeMinutes == null) put("timeMinutes", JSONObject.NULL) else put("timeMinutes", value.timeMinutes)
                put("kcal", value.kcal)
                put("proteinG", value.proteinG.toDouble())
                put("carbsG", value.carbsG.toDouble())
                put("fatG", value.fatG.toDouble())
                put("notes", value.notes ?: "")
            })
        }
        return array.toString()
    }

    private fun parseSupplements(json: String?): List<FoodSupplement> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(FoodSupplement(
                        kind = item.optString("kind"),
                        name = item.optString("name"),
                        dose = item.optDouble("dose", 0.0).toFloat(),
                        unit = item.optString("unit"),
                        timeMinutes = if (item.isNull("timeMinutes")) null else item.optInt("timeMinutes"),
                        kcal = item.optInt("kcal", 0),
                        proteinG = item.optDouble("proteinG", 0.0).toFloat(),
                        carbsG = item.optDouble("carbsG", 0.0).toFloat(),
                        fatG = item.optDouble("fatG", 0.0).toFloat(),
                        notes = item.optString("notes").takeIf { it.isNotBlank() },
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }
}
