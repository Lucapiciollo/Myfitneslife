package com.myfitai.app.domain.food

/**
 * Modello canonico letto dalla persistenza e usato dalla UI.
 * Le versioni del piano sono immutabili: una modifica futura genera una nuova PlanVersion.
 */
data class FoodPlanSnapshot(
    val planId: Long,
    val profileId: Long,
    val weekStartEpochDay: Long,
    val version: FoodPlanVersion,
)

data class FoodPlanVersion(
    val id: Long,
    val versionNumber: Int,
    val createdAtEpochMillis: Long,
    val source: String,
    val reason: String?,
    val targetKcal: Int?,
    val targetProteinG: Float?,
    val targetCarbsG: Float?,
    val targetFatG: Float?,
    val days: List<FoodPlanDay>,
)

data class FoodPlanDay(
    val id: Long,
    val dateEpochDay: Long,
    val totalKcal: Int?,
    val proteinG: Float?,
    val carbsG: Float?,
    val fatG: Float?,
    val meals: List<FoodMeal>,
    val supplements: List<FoodSupplement> = emptyList(),
    val hydrationNote: String? = null,
)

data class FoodSupplement(
    val kind: String,
    val name: String,
    val dose: Float,
    val unit: String,
    val timeMinutes: Int?,
    val kcal: Int,
    val proteinG: Float,
    val notes: String?,
)

data class FoodMeal(
    val id: Long,
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
    val ingredients: List<FoodIngredient>,
)

data class FoodIngredient(
    val id: Long,
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
