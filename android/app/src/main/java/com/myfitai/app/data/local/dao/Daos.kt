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
    @Query("SELECT * FROM user_profile WHERE id = 1 LIMIT 1")
    fun observe(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE id = 1 LIMIT 1")
    suspend fun get(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfileEntity)
}

@Dao
interface BiaMeasurementDao {
    @Query("SELECT * FROM bia_measurements ORDER BY measuredAtEpochMillis DESC")
    fun observeAll(): Flow<List<BiaMeasurementEntity>>

    @Query("SELECT * FROM bia_measurements ORDER BY measuredAtEpochMillis DESC LIMIT 1")
    fun observeLatest(): Flow<BiaMeasurementEntity?>

    @Query("SELECT * FROM bia_measurements WHERE measuredAtEpochMillis BETWEEN :from AND :to ORDER BY measuredAtEpochMillis ASC")
    fun observeBetween(from: Long, to: Long): Flow<List<BiaMeasurementEntity>>

    @Insert
    suspend fun insert(value: BiaMeasurementEntity): Long

    @Update
    suspend fun update(value: BiaMeasurementEntity)

    @Delete
    suspend fun delete(value: BiaMeasurementEntity)
}

@Dao
interface BodyMeasurementDao {
    @Query("SELECT * FROM body_measurements ORDER BY measuredAtEpochMillis DESC")
    fun observeAll(): Flow<List<BodyMeasurementEntity>>

    @Query("SELECT * FROM body_measurements ORDER BY measuredAtEpochMillis DESC LIMIT 1")
    fun observeLatest(): Flow<BodyMeasurementEntity?>

    @Query("SELECT * FROM body_measurements WHERE measuredAtEpochMillis BETWEEN :from AND :to ORDER BY measuredAtEpochMillis ASC")
    fun observeBetween(from: Long, to: Long): Flow<List<BodyMeasurementEntity>>

    @Insert
    suspend fun insert(value: BodyMeasurementEntity): Long

    @Update
    suspend fun update(value: BodyMeasurementEntity)

    @Delete
    suspend fun delete(value: BodyMeasurementEntity)
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts ORDER BY startedAtEpochMillis DESC")
    fun observeAll(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE startedAtEpochMillis BETWEEN :from AND :to ORDER BY startedAtEpochMillis ASC")
    fun observeBetween(from: Long, to: Long): Flow<List<WorkoutEntity>>

    @Insert
    suspend fun insert(value: WorkoutEntity): Long

    @Update
    suspend fun update(value: WorkoutEntity)

    @Delete
    suspend fun delete(value: WorkoutEntity)
}

@Dao
interface MealPlanDao {
    @Query("SELECT * FROM meal_plans ORDER BY weekStartEpochDay DESC")
    fun observePlans(): Flow<List<MealPlanEntity>>

    @Query("SELECT * FROM meal_plans WHERE weekStartEpochDay = :weekStart LIMIT 1")
    suspend fun getPlanForWeek(weekStart: Long): MealPlanEntity?

    @Query("SELECT * FROM meal_plan_versions WHERE planId = :planId ORDER BY versionNumber DESC")
    fun observeVersions(planId: Long): Flow<List<MealPlanVersionEntity>>

    @Query("SELECT * FROM meal_plan_versions WHERE planId = :planId ORDER BY versionNumber DESC LIMIT 1")
    suspend fun getLatestVersion(planId: Long): MealPlanVersionEntity?

    @Query("SELECT * FROM meal_plan_days WHERE versionId = :versionId ORDER BY dateEpochDay ASC")
    suspend fun getDays(versionId: Long): List<MealPlanDayEntity>

    @Query("SELECT * FROM meals WHERE dayId = :dayId ORDER BY sortOrder ASC")
    suspend fun getMeals(dayId: Long): List<MealEntity>

    @Query("SELECT * FROM meal_ingredients WHERE mealId = :mealId ORDER BY sortOrder ASC")
    suspend fun getIngredients(mealId: Long): List<MealIngredientEntity>

    @Insert
    suspend fun insertPlan(value: MealPlanEntity): Long

    @Insert
    suspend fun insertVersion(value: MealPlanVersionEntity): Long

    @Insert
    suspend fun insertDays(values: List<MealPlanDayEntity>): List<Long>

    @Insert
    suspend fun insertMeals(values: List<MealEntity>): List<Long>

    @Insert
    suspend fun insertIngredients(values: List<MealIngredientEntity>): List<Long>
}

@Dao
interface CheatEntryDao {
    @Query("SELECT * FROM cheat_entries ORDER BY occurredAtEpochMillis DESC")
    fun observeAll(): Flow<List<CheatEntryEntity>>

    @Query("SELECT * FROM cheat_entries WHERE occurredAtEpochMillis BETWEEN :from AND :to ORDER BY occurredAtEpochMillis ASC")
    fun observeBetween(from: Long, to: Long): Flow<List<CheatEntryEntity>>

    @Insert
    suspend fun insert(value: CheatEntryEntity): Long

    @Delete
    suspend fun delete(value: CheatEntryEntity)
}

@Dao
interface WeeklyReviewDao {
    @Query("SELECT * FROM weekly_reviews ORDER BY weekStartEpochDay DESC")
    fun observeAll(): Flow<List<WeeklyReviewEntity>>

    @Query("SELECT * FROM weekly_reviews WHERE weekStartEpochDay = :weekStart LIMIT 1")
    suspend fun getForWeek(weekStart: Long): WeeklyReviewEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: WeeklyReviewEntity): Long
}
