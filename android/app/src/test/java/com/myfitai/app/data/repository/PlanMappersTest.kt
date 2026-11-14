package com.myfitai.app.data.repository

import com.myfitai.app.data.local.entity.MealEntity
import com.myfitai.app.data.local.entity.MealIngredientEntity
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il mapper unico deve propagare OGNI proprieta di entita, modello di dominio e bozza.
 * I test usano la riflessione: un campo aggiunto domani e non mappato fa fallire il test.
 */
class PlanMappersTest {

    @Test
    fun ingredient_everyPropertyIsCopiedAcrossEntityModelAndDraft() {
        val entity = filled<MealIngredientEntity>()

        val model = entity.toFoodIngredient()
        assertSameProperties("entity->model", entity, model, ignore = emptySet())

        val draft = model.toDraft()
        // La bozza non conosce id/mealId/sortOrder (assegnati alla persistenza).
        assertSameProperties("model->draft", model, draft, ignore = setOf("id", "mealId", "sortOrder"), requireAllTargetFieldsPresentInSource = true)

        val back = draft.toEntity(mealId = entity.mealId, sortOrder = entity.sortOrder)
        assertSameProperties("draft->entity", entity, back, ignore = setOf("id"))
    }

    @Test
    fun meal_everyPropertyIsCopiedAcrossEntityModelAndDraft() {
        val entity = filled<MealEntity>()
        val ingredient = filled<MealIngredientEntity>().toFoodIngredient()

        val model = entity.toFoodMeal(listOf(ingredient))
        assertSameProperties("entity->model", entity, model, ignore = setOf("ingredients"))

        val draft = model.toDraft()
        assertSameProperties("model->draft", model, draft, ignore = setOf("id", "dayId", "sortOrder", "ingredients"), requireAllTargetFieldsPresentInSource = true)
        assertEquals(listOf(ingredient.toDraft()), draft.ingredients)

        val back = draft.toEntity(dayId = entity.dayId, sortOrder = entity.sortOrder)
        assertSameProperties("draft->entity", entity, back, ignore = setOf("id"))
    }

    @Test
    fun legacyRowsStayReadable_withNullSnapshotAndDeclaredNutrition() {
        val legacyIngredient = MealIngredientEntity(
            id = 7, mealId = 3, name = "Riso", quantity = 80f, unit = "g", displayDose = "80 g",
            weightState = "DRY", nutritionConfidence = "HIGH", category = "carbs", sortOrder = 0,
        )
        val legacyMeal = MealEntity(
            id = 3, dayId = 1, sortOrder = 0, type = "Pranzo", title = "Riso", timeMinutes = 780,
            kcal = 700, proteinG = 40f, carbsG = 80f, fatG = 20f, preparation = null,
        )

        val ingredient = legacyIngredient.toFoodIngredient()
        val meal = legacyMeal.toFoodMeal(listOf(ingredient))

        assertEquals(80f, ingredient.quantity)
        assertEquals("g", ingredient.unit)
        assertNull(ingredient.foodId)
        assertNull(ingredient.grams)
        assertNull(ingredient.kcal)
        assertNull(ingredient.proteinG)
        assertNull(ingredient.carbsG)
        assertNull(ingredient.fatG)
        assertNull(ingredient.fiberG)
        assertNull(ingredient.sodiumMg)
        assertEquals("DECLARED", meal.nutritionSource)
        assertEquals(700, meal.kcal)

        // Una nuova versione creata da un piano vecchio resta "dichiarata" e senza snapshot.
        val draft = meal.toDraft()
        assertEquals("DECLARED", draft.nutritionSource)
        assertNull(draft.ingredients.single().foodId)
        assertNull(draft.ingredients.single().kcal)
    }

    @Test
    fun snapshotSurvivesNewVersionCopy() {
        val entity = MealIngredientEntity(
            mealId = 3, name = "Riso basmati", quantity = 90f, unit = "g", displayDose = "90 g", weightState = "RAW",
            nutritionConfidence = "HIGH", category = "cereali", sortOrder = 1,
            foodId = "riso_basmati_crudo", grams = 90f, kcal = 320f, proteinG = 7f, carbsG = 70f, fatG = 1f, fiberG = 1.5f, sodiumMg = 4f,
        )
        val copy = entity.toFoodIngredient().toDraft().toEntity(mealId = 9, sortOrder = 1)
        assertEquals(entity.copy(mealId = 9), copy)
        assertNotNull(copy.foodId)
    }

    // ---- riflessione ------------------------------------------------------------------------------------------

    private fun assertSameProperties(
        label: String,
        source: Any,
        target: Any,
        ignore: Set<String>,
        requireAllTargetFieldsPresentInSource: Boolean = false,
    ) {
        val sourceFields = props(source.javaClass).associateBy { it.name }
        val targetFields = props(target.javaClass).associateBy { it.name }
        // Ogni proprieta della sorgente (tranne quelle dichiaratamente fuori mappa) deve esistere nel bersaglio.
        sourceFields.keys.filter { it !in ignore }.forEach { name ->
            assertTrue("$label: la proprieta '$name' di ${source.javaClass.simpleName} non esiste in ${target.javaClass.simpleName}", name in targetFields)
        }
        if (requireAllTargetFieldsPresentInSource) {
            targetFields.keys.forEach { name ->
                assertTrue("$label: la proprieta '$name' di ${target.javaClass.simpleName} non esiste in ${source.javaClass.simpleName}", name in sourceFields)
            }
        }
        targetFields.filterKeys { it !in ignore && it in sourceFields }.forEach { (name, field) ->
            assertEquals("$label: la proprieta '$name' non e stata copiata", sourceFields.getValue(name).get(source), field.get(target))
        }
    }

    private fun props(type: Class<*>): List<Field> = type.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .onEach { it.isAccessible = true }

    private inline fun <reified T : Any> filled(): T {
        val ctor = T::class.java.declaredConstructors
            .filter { !it.isSynthetic }
            .maxByOrNull { it.parameterCount } ?: error("nessun costruttore")
        val fields = props(T::class.java)
        assertEquals("costruttore e campi devono coincidere", ctor.parameterCount, fields.size)
        val args = ctor.parameterTypes.mapIndexed { index, type -> sample(type, index) }
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(*args.toTypedArray()) as T
    }

    /** Valori distinti e non-default per posizione, cosi un campo non copiato non passa inosservato. */
    private fun sample(type: Class<*>, index: Int): Any = when (type) {
        Long::class.javaPrimitiveType, Long::class.javaObjectType -> 1000L + index
        Int::class.javaPrimitiveType, Int::class.javaObjectType -> 100 + index
        Float::class.javaPrimitiveType, Float::class.javaObjectType -> 10.5f + index
        Double::class.javaPrimitiveType, Double::class.javaObjectType -> 10.25 + index
        Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> true
        String::class.java -> "valore_$index"
        else -> error("tipo non gestito dal test: ${type.name}")
    }
}
