package com.myfitai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.myfitai.app.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<UserProfileEntity>>

    @Query("SELECT * FROM user_profile WHERE id = :profileId LIMIT 1")
    fun observe(profileId: Long): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE id = :profileId LIMIT 1")
    suspend fun get(profileId: Long): UserProfileEntity?

    @Query("SELECT * FROM user_profile ORDER BY id ASC LIMIT 1")
    suspend fun getFirst(): UserProfileEntity?

    @Insert
    suspend fun insert(profile: UserProfileEntity): Long

    @Update
    suspend fun update(profile: UserProfileEntity)

    @Delete
    suspend fun delete(profile: UserProfileEntity)
}

@Dao
interface BiaMeasurementDao {
    @Query("SELECT * FROM bia_measurements WHERE profileId = :profileId ORDER BY measuredAtEpochMillis DESC, id DESC")
    fun observeAll(profileId: Long): Flow<List<BiaMeasurementEntity>>

    @Query("SELECT * FROM bia_measurements WHERE profileId = :profileId ORDER BY measuredAtEpochMillis DESC, id DESC LIMIT 1")
    fun observeLatest(profileId: Long): Flow<BiaMeasurementEntity?>

    @Query("SELECT * FROM bia_measurements WHERE profileId = :profileId AND measuredAtEpochMillis BETWEEN :from AND :to ORDER BY measuredAtEpochMillis ASC, id ASC")
    fun observeBetween(profileId: Long, from: Long, to: Long): Flow<List<BiaMeasurementEntity>>

    @Insert suspend fun insert(value: BiaMeasurementEntity): Long
    @Update suspend fun update(value: BiaMeasurementEntity)
    @Delete suspend fun delete(value: BiaMeasurementEntity)
    @Query("DELETE FROM bia_measurements WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}

@Dao
interface BodyMeasurementDao {
    @Query("SELECT * FROM body_measurements WHERE profileId = :profileId ORDER BY measuredAtEpochMillis DESC, id DESC")
    fun observeAll(profileId: Long): Flow<List<BodyMeasurementEntity>>

    @Query("SELECT * FROM body_measurements WHERE profileId = :profileId ORDER BY measuredAtEpochMillis DESC, id DESC LIMIT 1")
    fun observeLatest(profileId: Long): Flow<BodyMeasurementEntity?>

    @Query("SELECT * FROM body_measurements WHERE profileId = :profileId AND measuredAtEpochMillis BETWEEN :from AND :to ORDER BY measuredAtEpochMillis ASC, id ASC")
    fun observeBetween(profileId: Long, from: Long, to: Long): Flow<List<BodyMeasurementEntity>>

    @Insert suspend fun insert(value: BodyMeasurementEntity): Long
    @Update suspend fun update(value: BodyMeasurementEntity)
    @Delete suspend fun delete(value: BodyMeasurementEntity)
    @Query("DELETE FROM body_measurements WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts WHERE profileId = :profileId ORDER BY startedAtEpochMillis DESC, id DESC")
    fun observeAll(profileId: Long): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE profileId = :profileId AND startedAtEpochMillis BETWEEN :from AND :to ORDER BY startedAtEpochMillis ASC, id ASC")
    fun observeBetween(profileId: Long, from: Long, to: Long): Flow<List<WorkoutEntity>>

    @Insert suspend fun insert(value: WorkoutEntity): Long
    @Update suspend fun update(value: WorkoutEntity)
    @Delete suspend fun delete(value: WorkoutEntity)
    @Query("DELETE FROM workouts WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}

@Dao
interface WorkoutEnergyExpenditureDao {
    @Query("SELECT * FROM workout_energy_expenditures WHERE profileId = :profileId ORDER BY exerciseDateEpochDay DESC, workoutId DESC")
    fun observeAll(profileId: Long): Flow<List<WorkoutEnergyExpenditureEntity>>

    @Query("SELECT * FROM workout_energy_expenditures WHERE profileId = :profileId AND exerciseDateEpochDay = :epochDay ORDER BY workoutId ASC")
    suspend fun forDay(profileId: Long, epochDay: Long): List<WorkoutEnergyExpenditureEntity>

    @Query("SELECT * FROM workout_energy_expenditures WHERE profileId = :profileId AND exerciseDateEpochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY exerciseDateEpochDay ASC, workoutId ASC")
    suspend fun forRange(profileId: Long, fromEpochDay: Long, toEpochDay: Long): List<WorkoutEnergyExpenditureEntity>

    @Query("SELECT * FROM workout_energy_expenditures WHERE workoutId = :workoutId LIMIT 1")
    suspend fun getByWorkoutId(workoutId: Long): WorkoutEnergyExpenditureEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(value: WorkoutEnergyExpenditureEntity): Long

    @Update
    suspend fun update(value: WorkoutEnergyExpenditureEntity)

    @Query("UPDATE workout_energy_expenditures SET caloriesKcal = :caloriesKcal, source = :source, updatedAtEpochMillis = :updatedAtEpochMillis WHERE workoutId = :workoutId")
    suspend fun updateCalories(workoutId: Long, caloriesKcal: Int, source: String, updatedAtEpochMillis: Long): Int

    @Query("DELETE FROM workout_energy_expenditures WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}

@Dao
interface MealPlanDao {
    @Query("SELECT * FROM meal_plans WHERE profileId = :profileId ORDER BY weekStartEpochDay DESC, id DESC")
    fun observePlans(profileId: Long): Flow<List<MealPlanEntity>>

    @Query("SELECT * FROM meal_plans WHERE profileId = :profileId AND weekStartEpochDay = :weekStart LIMIT 1")
    suspend fun getPlanForWeek(profileId: Long, weekStart: Long): MealPlanEntity?

    @Query("SELECT * FROM meal_plans WHERE profileId = :profileId AND id = :planId LIMIT 1")
    suspend fun getPlan(profileId: Long, planId: Long): MealPlanEntity?

    @Query("SELECT v.* FROM meal_plan_versions v INNER JOIN meal_plans p ON p.id = v.planId WHERE p.id = :planId AND p.profileId = :profileId ORDER BY v.versionNumber DESC, v.id DESC")
    fun observeVersions(profileId: Long, planId: Long): Flow<List<MealPlanVersionEntity>>

    @Query("SELECT v.* FROM meal_plan_versions v INNER JOIN meal_plans p ON p.id = v.planId WHERE p.id = :planId AND p.profileId = :profileId ORDER BY v.versionNumber DESC, v.id DESC LIMIT 1")
    suspend fun getLatestVersion(profileId: Long, planId: Long): MealPlanVersionEntity?

    @Query("SELECT d.* FROM meal_plan_days d INNER JOIN meal_plan_versions v ON v.id = d.versionId INNER JOIN meal_plans p ON p.id = v.planId WHERE d.versionId = :versionId AND p.profileId = :profileId ORDER BY d.dateEpochDay ASC, d.id ASC")
    suspend fun getDays(profileId: Long, versionId: Long): List<MealPlanDayEntity>

    @Query("SELECT m.* FROM meals m INNER JOIN meal_plan_days d ON d.id = m.dayId INNER JOIN meal_plan_versions v ON v.id = d.versionId INNER JOIN meal_plans p ON p.id = v.planId WHERE m.dayId = :dayId AND p.profileId = :profileId ORDER BY m.sortOrder ASC, m.id ASC")
    suspend fun getMeals(profileId: Long, dayId: Long): List<MealEntity>

    @Query("SELECT m.* FROM meals m INNER JOIN meal_plan_days d ON d.id = m.dayId INNER JOIN meal_plan_versions v ON v.id = d.versionId INNER JOIN meal_plans p ON p.id = v.planId WHERE m.id = :mealId AND p.profileId = :profileId LIMIT 1")
    suspend fun getMeal(profileId: Long, mealId: Long): MealEntity?

    @Query("SELECT p.id AS planId, v.id AS planVersionId, d.id AS dayId, d.dateEpochDay AS dateEpochDay, m.id AS mealId, m.dayId AS mealDayId, m.sortOrder AS mealSortOrder, m.type AS mealType, m.title AS mealTitle, m.timeMinutes AS mealTimeMinutes, m.kcal AS mealKcal, m.proteinG AS mealProteinG, m.carbsG AS mealCarbsG, m.fatG AS mealFatG, m.preparation AS mealPreparation FROM meals m INNER JOIN meal_plan_days d ON d.id = m.dayId INNER JOIN meal_plan_versions v ON v.id = d.versionId INNER JOIN meal_plans p ON p.id = v.planId WHERE m.id = :mealId AND p.profileId = :profileId LIMIT 1")
    suspend fun getMealContext(profileId: Long, mealId: Long): MealContextRow?

    @Query("SELECT i.* FROM meal_ingredients i INNER JOIN meals m ON m.id = i.mealId INNER JOIN meal_plan_days d ON d.id = m.dayId INNER JOIN meal_plan_versions v ON v.id = d.versionId INNER JOIN meal_plans p ON p.id = v.planId WHERE i.mealId = :mealId AND p.profileId = :profileId ORDER BY i.sortOrder ASC, i.id ASC")
    suspend fun getIngredients(profileId: Long, mealId: Long): List<MealIngredientEntity>

    @Insert suspend fun insertPlan(value: MealPlanEntity): Long
    @Insert suspend fun insertVersion(value: MealPlanVersionEntity): Long
    @Insert suspend fun insertDays(values: List<MealPlanDayEntity>): List<Long>
    @Insert suspend fun insertMeals(values: List<MealEntity>): List<Long>
    @Insert suspend fun insertIngredients(values: List<MealIngredientEntity>): List<Long>
    @Query("DELETE FROM meal_plans WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}

@Dao
interface CheatEntryDao {
    @Query("SELECT * FROM cheat_entries WHERE profileId = :profileId ORDER BY occurredAtEpochMillis DESC, id DESC")
    fun observeAll(profileId: Long): Flow<List<CheatEntryEntity>>

    @Query("SELECT * FROM cheat_entries WHERE profileId = :profileId AND occurredAtEpochMillis BETWEEN :from AND :to ORDER BY occurredAtEpochMillis ASC, id ASC")
    fun observeBetween(profileId: Long, from: Long, to: Long): Flow<List<CheatEntryEntity>>

    @Insert suspend fun insert(value: CheatEntryEntity): Long
    @Update suspend fun update(value: CheatEntryEntity)
    @Delete suspend fun delete(value: CheatEntryEntity)
    @Query("DELETE FROM cheat_entries WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}

data class MealContextRow(
    val planId: Long,
    val planVersionId: Long,
    val dayId: Long,
    val dateEpochDay: Long,
    val mealId: Long,
    val mealDayId: Long,
    val mealSortOrder: Int,
    val mealType: String,
    val mealTitle: String,
    val mealTimeMinutes: Int?,
    val mealKcal: Int?,
    val mealProteinG: Float?,
    val mealCarbsG: Float?,
    val mealFatG: Float?,
    val mealPreparation: String?,
)

@Dao
interface FoodConsumptionDao {
    @Query("SELECT * FROM food_consumptions WHERE profileId = :profileId ORDER BY plannedDateEpochDay DESC, updatedAtEpochMillis DESC, id DESC")
    fun observeAll(profileId: Long): Flow<List<FoodConsumptionEntity>>

    @Query("SELECT * FROM food_consumptions WHERE profileId = :profileId AND plannedDateEpochDay = :dateEpochDay ORDER BY updatedAtEpochMillis DESC, id DESC")
    fun observeForDay(profileId: Long, dateEpochDay: Long): Flow<List<FoodConsumptionEntity>>

    @Query("SELECT * FROM food_consumptions WHERE profileId = :profileId AND planVersionId = :planVersionId AND plannedDateEpochDay = :dateEpochDay ORDER BY updatedAtEpochMillis DESC, id DESC")
    fun observeForVersionDay(profileId: Long, planVersionId: Long, dateEpochDay: Long): Flow<List<FoodConsumptionEntity>>

    @Query("SELECT * FROM food_consumptions WHERE profileId = :profileId AND planVersionId = :planVersionId AND itemKey = :itemKey LIMIT 1")
    suspend fun getForItem(profileId: Long, planVersionId: Long, itemKey: String): FoodConsumptionEntity?

    @Query("SELECT * FROM food_consumptions WHERE profileId = :profileId AND planVersionId = :planVersionId AND itemKey = :itemKey LIMIT 1")
    fun observeForItem(profileId: Long, planVersionId: Long, itemKey: String): Flow<FoodConsumptionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: FoodConsumptionEntity): Long

    @Query("DELETE FROM food_consumptions WHERE profileId = :profileId AND planVersionId = :planVersionId AND itemKey = :itemKey")
    suspend fun deleteForItem(profileId: Long, planVersionId: Long, itemKey: String)

    @Query("DELETE FROM food_consumptions WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}

@Dao
interface WeeklyReviewDao {
    @Query("SELECT * FROM weekly_reviews WHERE profileId = :profileId ORDER BY weekStartEpochDay DESC")
    fun observeAll(profileId: Long): Flow<List<WeeklyReviewEntity>>

    @Query("SELECT * FROM weekly_reviews WHERE profileId = :profileId AND weekStartEpochDay = :weekStart LIMIT 1")
    suspend fun getForWeek(profileId: Long, weekStart: Long): WeeklyReviewEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: WeeklyReviewEntity): Long
    @Query("DELETE FROM weekly_reviews WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)
}
