package com.myfitai.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val birthDateEpochDay: Long?,
    val heightCm: Float?,
    val currentWeightKg: Float?,
    val goal: String?,
    val activityLevel: String?,
    val wakeTimeMinutes: Int?,
    val sleepTimeMinutes: Int?,
    val dietaryPreferencesJson: String?,
    /** Percorso nello storage privato dell'app. Mai salvare bitmap/BLOB in Room. */
    val photoPath: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val biologicalSex: String? = null,
    val initialWeightKg: Float? = null,
)

@Entity(
    tableName = "bia_measurements",
    indices = [Index("profileId"), Index("measuredAtEpochMillis"), Index(value = ["profileId", "measuredAtEpochMillis"])],
)
data class BiaMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val profileId: Long,
    val measuredAtEpochMillis: Long,
    val weightKg: Float?,
    val bodyFatPercent: Float?,
    val visceralFatLevel: Float?,
    val muscleMassKg: Float?,
    val skeletalMuscleKg: Float?,
    val bodyWaterPercent: Float?,
    val bmrKcal: Float?,
    val fasting: Boolean,
    val justWokeUp: Boolean,
    val afterBathroom: Boolean,
    val noRecentWorkout: Boolean,
    val notes: String? = null,
)

@Entity(
    tableName = "body_measurements",
    indices = [Index("profileId"), Index("measuredAtEpochMillis"), Index(value = ["profileId", "measuredAtEpochMillis"])],
)
data class BodyMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val profileId: Long,
    val measuredAtEpochMillis: Long,
    val chestCm: Float?,
    val waistCm: Float?,
    val abdomenCm: Float?,
    val shouldersCm: Float?,
    val glutesCm: Float?,
    val armLeftCm: Float?,
    val armRightCm: Float?,
    val thighLeftCm: Float?,
    val thighRightCm: Float?,
    val calfLeftCm: Float?,
    val calfRightCm: Float?,
    val notes: String? = null,
)

@Entity(
    tableName = "workouts",
    indices = [Index("profileId"), Index("startedAtEpochMillis"), Index(value = ["profileId", "startedAtEpochMillis"])],
)
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val profileId: Long,
    val startedAtEpochMillis: Long,
    val type: String,
    val title: String,
    val durationMinutes: Int?,
    val isRestDay: Boolean,
    val perceivedIntensity: Int? = null,
    val notes: String? = null,
)

@Entity(
    tableName = "workout_energy_expenditures",
    foreignKeys = [ForeignKey(
        entity = WorkoutEntity::class,
        parentColumns = ["id"],
        childColumns = ["workoutId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("profileId"),
        Index("workoutId", unique = true),
        Index(value = ["profileId", "exerciseDateEpochDay"]),
    ],
)
data class WorkoutEnergyExpenditureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val workoutId: Long,
    val exerciseDateEpochDay: Long,
    val caloriesKcal: Int,
    val source: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "meal_plans",
    indices = [Index("profileId"), Index("createdAtEpochMillis"), Index(value = ["profileId", "weekStartEpochDay"], unique = true)],
)
data class MealPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val profileId: Long,
    val createdAtEpochMillis: Long,
    val weekStartEpochDay: Long,
    val status: String,
)

@Entity(
    tableName = "meal_plan_versions",
    foreignKeys = [ForeignKey(
        entity = MealPlanEntity::class,
        parentColumns = ["id"],
        childColumns = ["planId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("planId"), Index(value = ["planId", "versionNumber"], unique = true)],
)
data class MealPlanVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val versionNumber: Int,
    val createdAtEpochMillis: Long,
    val source: String,
    val reason: String?,
    val targetKcal: Int?,
    val targetProteinG: Float?,
    val targetCarbsG: Float?,
    val targetFatG: Float?,
    val appValidationJson: String? = null,
)

@Entity(
    tableName = "meal_plan_days",
    foreignKeys = [ForeignKey(
        entity = MealPlanVersionEntity::class,
        parentColumns = ["id"],
        childColumns = ["versionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("versionId"), Index(value = ["versionId", "dateEpochDay"], unique = true)],
)
data class MealPlanDayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val versionId: Long,
    val dateEpochDay: Long,
    val totalKcal: Int?,
    val proteinG: Float?,
    val carbsG: Float?,
    val fatG: Float?,
    val supplementsJson: String? = null,
    val hydrationNote: String? = null,
)

@Entity(
    tableName = "meals",
    foreignKeys = [ForeignKey(
        entity = MealPlanDayEntity::class,
        parentColumns = ["id"],
        childColumns = ["dayId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("dayId"), Index(value = ["dayId", "sortOrder"], unique = true)],
)
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long,
    val sortOrder: Int,
    val type: String,
    val title: String,
    val timeMinutes: Int?,
    val kcal: Int?,
    val proteinG: Float?,
    val carbsG: Float?,
    val fatG: Float?,
    val preparation: String?,
)

@Entity(
    tableName = "meal_ingredients",
    foreignKeys = [ForeignKey(
        entity = MealEntity::class,
        parentColumns = ["id"],
        childColumns = ["mealId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("mealId")],
)
data class MealIngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealId: Long,
    val name: String,
    val quantity: Float,
    val unit: String,
    val displayDose: String?,
    val weightState: String?,
    val nutritionConfidence: String?,
    val category: String?,
    val sortOrder: Int,
)

@Entity(
    tableName = "cheat_entries",
    foreignKeys = [ForeignKey(
        entity = MealPlanVersionEntity::class,
        parentColumns = ["id"],
        childColumns = ["planVersionId"],
        onDelete = ForeignKey.SET_NULL,
    )],
    indices = [Index("profileId"), Index("occurredAtEpochMillis"), Index(value = ["profileId", "occurredAtEpochMillis"]), Index("planVersionId")],
)
data class CheatEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val profileId: Long,
    val occurredAtEpochMillis: Long,
    val description: String,
    val quantityText: String?,
    val estimatedKcal: Int?,
    val estimatedProteinG: Float?,
    val estimatedCarbsG: Float?,
    val estimatedFatG: Float?,
    val planVersionId: Long?,
    val notes: String? = null,
)

/** Historical consumption snapshot; never infer consumption from a planned meal. */
@Entity(
    tableName = "food_consumptions",
    indices = [
        Index("profileId"),
        Index("planVersionId"),
        Index("plannedDateEpochDay"),
        Index(value = ["profileId", "planVersionId", "itemKey"], unique = true),
    ],
)
data class FoodConsumptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val profileId: Long,
    val planId: Long,
    val planVersionId: Long,
    val dayId: Long,
    val plannedDateEpochDay: Long,
    val itemType: String,
    val itemKey: String,
    val mealId: Long?,
    val supplementKey: String?,
    val status: String,
    val recordedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val quantityFactor: Float,
    val kcal: Int?,
    val proteinG: Float?,
    val carbsG: Float?,
    val fatG: Float?,
    val note: String? = null,
)

@Entity(
    tableName = "weekly_reviews",
    indices = [Index("profileId"), Index(value = ["profileId", "weekStartEpochDay"], unique = true)],
)
data class WeeklyReviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val profileId: Long,
    val weekStartEpochDay: Long,
    val createdAtEpochMillis: Long,
    val adherencePercent: Float?,
    val summary: String,
    val structuredJson: String?,
)
