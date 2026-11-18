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
    /** Dichiarazione salute locale e versionata; null significa che non è stata dichiarata. */
    val healthDeclarationJson: String? = null,
) {
    /** Keep sensitive declaration answers out of accidental logs and diagnostic string interpolation. */
    override fun toString(): String =
        "UserProfileEntity(id=$id, name=$name, birthDateEpochDay=$birthDateEpochDay, heightCm=$heightCm, " +
            "currentWeightKg=$currentWeightKg, goal=$goal, activityLevel=$activityLevel, " +
            "wakeTimeMinutes=$wakeTimeMinutes, sleepTimeMinutes=$sleepTimeMinutes, " +
            "dietaryPreferencesJson=$dietaryPreferencesJson, photoPath=$photoPath, " +
            "createdAtEpochMillis=$createdAtEpochMillis, updatedAtEpochMillis=$updatedAtEpochMillis, " +
            "biologicalSex=$biologicalSex, initialWeightKg=$initialWeightKg, " +
            "healthDeclarationJson=<redacted>)"
}

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
    val fatMassKg: Float? = null,
    val leanMassKg: Float? = null,
    val bodyWaterKg: Float? = null,
    val subcutaneousFatPercent: Float? = null,
    val boneMassKg: Float? = null,
    val proteinPercent: Float? = null,
    val proteinKg: Float? = null,
    val bodyAgeYears: Int? = null,
    val bmi: Float? = null,
)

@Entity(
    tableName = "bia_analysis_results",
    indices = [
        Index("profileId"),
        Index("biaMeasurementId"),
        Index(value = ["profileId", "createdAtEpochMillis"]),
    ],
)
data class BiaAnalysisResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val profileId: Long,
    val biaMeasurementId: Long,
    val createdAtEpochMillis: Long,
    val provider: String,
    val model: String,
    val payloadJson: String,
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
    /** Hip circumference, distinct from gluteal circumference. */
    val hipsCm: Float? = null,
    /** Standalone weigh-in associated with this body-measurement date, not a BIA reading. */
    val weightKg: Float? = null,
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
    /** Impronta degli input nutrizionali alla generazione (UX7); null per i piani precedenti alla migrazione 21. */
    val inputsFingerprint: String? = null,
    /** Versione del catalogo alimenti usata per calcolare i macro (migrazione 22); null per i piani precedenti. */
    val catalogVersion: String? = null,
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
    val targetKcal: Int? = null,
    val targetProteinG: Float? = null,
    val targetCarbsG: Float? = null,
    val targetFatG: Float? = null,
    val baseTargetKcal: Int? = null,
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
    /** DECLARED = macro dichiarati dal modello (piani storici); COMPUTED = calcolati dal catalogo (migrazione 22). */
    @ColumnInfo(defaultValue = "'DECLARED'") val nutritionSource: String = "DECLARED",
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
    /** Snapshot nutrizionale per ingrediente (migrazione 22); tutti null per i piani precedenti. */
    val foodId: String? = null,
    val grams: Float? = null,
    val kcal: Float? = null,
    val proteinG: Float? = null,
    val carbsG: Float? = null,
    val fatG: Float? = null,
    val fiberG: Float? = null,
    val sodiumMg: Float? = null,
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

@Entity(
    tableName = "body_expectation_goals",
    indices = [Index("profileId"), Index(value = ["profileId", "periodStartEpochDay"], unique = true)],
)
data class BodyExpectationGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val profileId: Long,
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    val plannedDays: Int,
    val theoreticalDeficitKcal: Int?,
    val expectedFatLossMinKg: Double?,
    val expectedFatLossMaxKg: Double?,
    val initialWeightKg: Float?,
    val finalWeightKg: Float?,
    val status: String,
    val note: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** Alimento inserito dall'utente (migrazione 22); valori per 100 g, sempre marcati come stima. */
@Entity(
    tableName = "custom_foods",
    indices = [Index("profileId")],
)
data class CustomFoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val name: String,
    val kcalPer100: Float,
    val proteinPer100: Float,
    val carbsPer100: Float,
    val fatPer100: Float,
    val fiberPer100: Float? = null,
    val sodiumMgPer100: Float? = null,
    val createdAtEpochMillis: Long,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "'USER'") val source: String = "USER",
    @ColumnInfo(defaultValue = "'ESTIMATED'") val confidence: String = "ESTIMATED",
)
