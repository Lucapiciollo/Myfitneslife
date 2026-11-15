package com.myfitai.app.data.repository

import com.myfitai.app.data.local.dao.HistoricalMealRow
import com.myfitai.app.data.local.dao.MealContextRow
import com.myfitai.app.data.local.entity.MealEntity
import com.myfitai.app.data.local.entity.MealIngredientEntity
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.NutritionPlanContract

/**
 * Unico punto in cui un ingrediente/pasto viene copiato tra entita Room, modello di dominio e bozza di una nuova
 * versione del piano. Ogni copia deve passare di qui: un campo nuovo dimenticato in un sito sparso azzera lo snapshot
 * nutrizionale. `PlanMappersTest` verifica per riflessione che ogni proprieta venga propagata.
 */

fun MealIngredientEntity.toFoodIngredient() = FoodIngredient(
    id = id,
    mealId = mealId,
    name = name,
    quantity = quantity,
    unit = unit,
    displayDose = displayDose,
    weightState = weightState,
    nutritionConfidence = nutritionConfidence,
    category = category,
    sortOrder = sortOrder,
    foodId = foodId,
    grams = grams,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    fiberG = fiberG,
    sodiumMg = sodiumMg,
)

fun MealEntity.toFoodMeal(ingredients: List<FoodIngredient>) = FoodMeal(
    id = id,
    dayId = dayId,
    sortOrder = sortOrder,
    type = type,
    title = title,
    timeMinutes = timeMinutes,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    preparation = preparation,
    ingredients = ingredients,
    nutritionSource = nutritionSource,
)

fun MealContextRow.toFoodMeal(ingredients: List<FoodIngredient>) = FoodMeal(
    id = mealId,
    dayId = mealDayId,
    sortOrder = mealSortOrder,
    type = mealType,
    title = mealTitle,
    timeMinutes = mealTimeMinutes,
    kcal = mealKcal,
    proteinG = mealProteinG,
    carbsG = mealCarbsG,
    fatG = mealFatG,
    preparation = mealPreparation,
    ingredients = ingredients,
    nutritionSource = mealNutritionSource,
)

fun HistoricalMealRow.toFoodMeal(ingredients: List<FoodIngredient>) = FoodMeal(
    id = mealId,
    dayId = mealDayId,
    sortOrder = mealSortOrder,
    type = mealType,
    title = mealTitle,
    timeMinutes = mealTimeMinutes,
    kcal = mealKcal,
    proteinG = mealProteinG,
    carbsG = mealCarbsG,
    fatG = mealFatG,
    preparation = mealPreparation,
    ingredients = ingredients,
    nutritionSource = mealNutritionSource,
)

/** Copia di un ingrediente persistito in una bozza per una nuova versione del piano (snapshot incluso). */
fun FoodIngredient.toDraft() = IngredientDraft(
    name = name,
    quantity = quantity,
    unit = unit,
    displayDose = displayDose,
    weightState = weightState,
    nutritionConfidence = nutritionConfidence,
    category = category,
    foodId = foodId,
    grams = grams,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    fiberG = fiberG,
    sodiumMg = sodiumMg,
)

fun FoodMeal.toDraft() = MealDraft(
    type = type,
    title = title,
    timeMinutes = timeMinutes,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    preparation = preparation,
    ingredients = ingredients.map { it.toDraft() },
    nutritionSource = nutritionSource,
)

/** Pasto/ingrediente calcolato dall'app (MFP2, alternative MA2) -> bozza di versione: snapshot incluso. */
fun NutritionPlanContract.GeneratedIngredient.toDraft() = IngredientDraft(
    name = name,
    quantity = quantity,
    unit = unit,
    displayDose = displayDose,
    weightState = weightState,
    nutritionConfidence = nutritionConfidence,
    category = category,
    foodId = foodId,
    grams = grams,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    fiberG = fiberG,
    sodiumMg = sodiumMg,
)

fun NutritionPlanContract.GeneratedMeal.toDraft() = MealDraft(
    type = type,
    title = title,
    timeMinutes = timeMinutes,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    preparation = preparation,
    ingredients = ingredients.map { it.toDraft() },
    nutritionSource = nutritionSource,
)

fun IngredientDraft.toEntity(mealId: Long, sortOrder: Int) = MealIngredientEntity(
    mealId = mealId,
    name = name,
    quantity = quantity,
    unit = unit,
    displayDose = displayDose,
    weightState = weightState,
    nutritionConfidence = nutritionConfidence,
    category = category,
    sortOrder = sortOrder,
    foodId = foodId,
    grams = grams,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    fiberG = fiberG,
    sodiumMg = sodiumMg,
)

fun MealDraft.toEntity(dayId: Long, sortOrder: Int) = MealEntity(
    dayId = dayId,
    sortOrder = sortOrder,
    type = type,
    title = title,
    timeMinutes = timeMinutes,
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    preparation = preparation,
    nutritionSource = nutritionSource,
)
