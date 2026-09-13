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
    @Query("SELECT * FROM bia_measurements WHERE profileId = :profileId ORDER BY measuredAtEpochMillis DESC")
    fun observeAll(profileId: Long): Flow<List<BiaMeasurementEntity>>

    @Query("SELECT * FROM bia_measurements WHERE profileId = :profileId ORDER BY measuredAtEpochMillis DESC LIMIT 1")
    fun observeLatest(profileId: Long): Flow<BiaMeasurementEntity?>

    @Query("SELECT * FROM bia_measurements WHERE profileId = :profileId AND measuredAtEpochMillis BETWEEN :from AND :to ORDER BY measuredAtEpochMillis ASC")
    fun observeBetween(profileId: Long, from: Long, to: Long): Flow<List<BiaMeasurementEntity>>

    @Insert
    suspend fun insert(value: BiaMeasurementEntity): Long

    @Update
    suspend fun update(value: BiaMeasurementEntity)

    @Delete
    suspend fun delete(value: BiaMeasurementEntity)
}

@Dao
interface BodyMeasurementDao {
    @Query("SELECT * FROM body_measurements WHERE profileId = :profileId ORDER BY measuredAtEpochMillis DESC")
    fun observeAll(profileId: Long): Flow<List<BodyMeasurementEntity>>

    @Query("SELECT * FROM body_measurements WHERE profileId = :profileId ORDER BY measuredAtEpochMillis DESC LIMIT 1")
    fun observeLatest(profileId: Long): Flow<BodyMeasurementEntity?>

    @Query("SELECT * FROM body_measurements WHERE profileId = :profileId AND measuredAtEpochMillis BETWEEN :from AND :to ORDER BY measuredAtEpochMillis ASC")
    fun observeBetween(profileId: Long, from: Long, to: Long): Flow<List<BodyMeasurementEntity>>

    @Insert
    suspend fun insert(value: BodyMeasurementEntity): Long

    @Update
    suspend fun update(value: BodyMeasurementEntity)

    @Delete
    suspend fun delete(value: BodyMeasurementEntity)
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts WHERE profileId = :profileId ORDER BY startedAtEpochMillis DESC")
    fun observeAll(profileId: Long): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE profileId = :profileId AND startedAtEpochMillis BETWEEN :from AND :to ORDER BY startedAtEpochMillis ASC")
    fun observeBetween(profileId: Long, from: Long, to: Long): Flow<List<WorkoutEntity>>

    @Insert
    suspend fun insert(value: WorkoutEntity): Long

    @Update
    suspend fun update(value: WorkoutEntity)

    @Delete
    suspend fun delete(value: WorkoutEntity)
}

@Dao
interface MealPlanDao {
    @Query("SELECT * FROM meal_plans WHERE profileId = :profileId ORDER BY weekStartEpochDay DESC")
    fun observePlans(profileId: Long): Flow<List<MealPlanEntity>>

    @Query("SELECT * FROM meal_plans WHERE profileId = :profileId AND weekStartEpochDay = :weekStart LIMIT 1")
    suspend fun getPlanForWeek(profileId: Long, weekStart: Long): MealPlanEntity?

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
    @Query("SELECT * FROM cheat_entries WHERE profileId = :profileId ORDER BY occurredAtEpochMillis DESC")
    fun observeAll(profileId: Long): Flow<List<CheatEntryEntity>>

    @Query("SELECT * FROM cheat_entries WHERE profileId = :profileId AND occurredAtEpochMillis BETWEEN :from AND :to ORDER BY occurredAtEpochMillis ASC")
    fun observeBetween(profileId: Long, from: Long, to: Long): Flow<List<CheatEntryEntity>>

    @Insert
    suspend fun insert(value: CheatEntryEntity): Long

    @Delete
    suspend fun delete(value: CheatEntryEntity)
}

@Dao
interface WeeklyReviewDao {
    @Query("SELECT * FROM weekly_reviews WHERE profileId = :profileId ORDER BY weekStartEpochDay DESC")
    fun observeAll(profileId: Long): Flow<List<WeeklyReviewEntity>>

    @Query("SELECT * FROM weekly_reviews WHERE profileId = :profileId AND weekStartEpochDay = :weekStart LIMIT 1")
    suspend fun getForWeek(profileId: Long, weekStart: Long): WeeklyReviewEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: WeeklyReviewEntity): Long
}
