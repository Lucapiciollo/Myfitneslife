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
    /** Impronta degli input nutrizionali con cui il piano e stato generato; null = piano precedente alla UX7. */
    val inputsFingerprint: String? = null,
    /** Versione del catalogo alimenti usata per calcolare i macro; null per i piani dichiarati dal modello. */
    val catalogVersion: String? = null,
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
    val targetKcal: Int? = null,
    val targetProteinG: Float? = null,
    val targetCarbsG: Float? = null,
    val targetFatG: Float? = null,
    val baseTargetKcal: Int? = null,
)

data class FoodSupplement(
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
    /** DECLARED = macro dichiarati dal modello (piani storici); COMPUTED = calcolati dal catalogo. */
    val nutritionSource: String = NUTRITION_SOURCE_DECLARED,
) {
    companion object {
        const val NUTRITION_SOURCE_DECLARED = "DECLARED"
        const val NUTRITION_SOURCE_COMPUTED = "COMPUTED"
    }
}

data class FoodIngredient(
    val id: Long,
    val mealId: Long,
    val name: String,
    /** Grammi (o millilitri se unit = 'ml'): la semantica storica resta valida anche per i nuovi ingredienti. */
    val quantity: Float,
    val unit: String,
    val displayDose: String?,
    val weightState: String?,
    val nutritionConfidence: String?,
    val category: String?,
    val sortOrder: Int,
    /** Snapshot nutrizionale dal catalogo; tutti null per gli ingredienti dei piani precedenti. */
    val foodId: String? = null,
    val grams: Float? = null,
    val kcal: Float? = null,
    val proteinG: Float? = null,
    val carbsG: Float? = null,
    val fatG: Float? = null,
    val fiberG: Float? = null,
    val sodiumMg: Float? = null,
)
