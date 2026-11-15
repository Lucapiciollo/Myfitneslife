package com.myfitai.app.domain.shopping

import com.myfitai.app.domain.food.FoodCatalogPlanFixtures
import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanSnapshot
import com.myfitai.app.domain.food.FoodPlanVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingListEngineTest {

    @Test
    fun `sums compatible gram and kilogram quantities`() {
        val result = ShoppingListEngine.aggregate(snapshot(
            ingredient("Riso basmati", 500f, "g", "secco"),
            ingredient("riso   basmati", 1f, "kg", "secco"),
        ))

        assertEquals(1, result.size)
        assertEquals(1500.0, result.single().quantity, 0.001)
        assertEquals("g", result.single().unit)
        assertEquals("1,5 kg", result.single().displayQuantity())
    }

    @Test
    fun `does not merge different weight states`() {
        val result = ShoppingListEngine.aggregate(snapshot(
            ingredient("Pollo", 200f, "g", "crudo"),
            ingredient("Pollo", 200f, "g", "cotto"),
        ))

        assertEquals(2, result.size)
        assertTrue(result.mapNotNull { it.weightState }.containsAll(listOf("crudo", "cotto")))
    }

    @Test
    fun `ignores invalid non positive quantities`() {
        val result = ShoppingListEngine.aggregate(snapshot(
            ingredient("Mela", 0f, "pz", null),
            ingredient("Banana", 2f, "pz", null),
        ))
        assertEquals(listOf("Banana"), result.map { it.name })
    }

    // ---- Piani con snapshot (MFP2): aggregazione per foodId ----

    private val catalog = FoodCatalogPlanFixtures.catalog

    private fun food(
        foodId: String,
        name: String,
        grams: Float?,
        unit: String = "g",
        quantity: Float = grams ?: 0f,
        weightState: String? = null,
        category: String? = "Dedotta dal nome",
    ) = ingredient(name, quantity, unit, weightState).copy(category = category, foodId = foodId, grams = grams)

    @Test
    fun `same foodId on different days is summed and uses the catalog shop category`() {
        val result = ShoppingListEngine.aggregate(
            snapshotDays(
                listOf(food("it.petto_pollo", "Petto di pollo (crudo)", 150f, weightState = "crudo")),
                listOf(food("it.petto_pollo", "Pollo petto", 100f, weightState = "crudo")),
            ),
            catalog,
        )

        val item = result.single()
        assertEquals(250.0, item.quantity, 0.001)
        assertEquals("g", item.unit)
        assertEquals("Carne", item.category)
        assertEquals("crudo", item.weightState)
        assertEquals(2, item.sourceCount)
        assertEquals("Petto di pollo (crudo)", item.name)
    }

    @Test
    fun `cooked is converted to raw only when the catalog declares the yield`() {
        val result = ShoppingListEngine.aggregate(
            snapshotDays(
                listOf(
                    food("it.pasta_semola_secca", "Pasta di semola secca", 80f, weightState = "crudo"),
                    food("it.pasta_semola_cotta", "Pasta di semola cotta", 235f, weightState = "cotto"),
                    food("it.petto_pollo", "Petto di pollo (crudo)", 100f, weightState = "crudo"),
                    food("it.petto_pollo_cotto", "Petto di pollo (cotto)", 100f, weightState = "cotto"),
                ),
            ),
            catalog,
        )

        val pasta = result.single { it.name == "Pasta di semola secca" }
        assertEquals(180.0, pasta.quantity, 0.001)
        assertEquals("crudo", pasta.weightState)
        assertEquals(2, pasta.sourceCount)
        assertTrue(result.none { it.name == "Pasta di semola cotta" })
        // carne: nessuna resa dichiarata, crudo e cotto restano voci separate
        assertEquals(2, result.count { it.category == "Carne" })
    }

    @Test
    fun `liquids sum milliliters and grams through the catalog density`() {
        val result = ShoppingListEngine.aggregate(
            snapshotDays(
                listOf(
                    food("it.olio_evo", "Olio extravergine d'oliva", grams = null, unit = "ml", quantity = 10f),
                    food("it.olio_evo", "Olio extravergine d'oliva", grams = null, unit = "g", quantity = 9.1f),
                ),
            ),
            catalog,
        )

        val item = result.single()
        assertEquals("ml", item.unit)
        assertEquals(20.0, item.quantity, 0.01)
        assertEquals("Olio, spezie e condimenti", item.category)
    }

    @Test
    fun `snapshot grams win over the stored quantity`() {
        val result = ShoppingListEngine.aggregate(
            snapshotDays(listOf(food("it.olio_evo", "Olio", grams = 10f, unit = "ml", quantity = 11f))),
            catalog,
        )
        assertEquals(10.0 / 0.91, result.single().quantity, 0.001)
    }

    @Test
    fun `foodId without catalog or with another catalog version aggregates by foodId keeping ingredient data`() {
        val days = arrayOf(
            listOf(food("it.petto_pollo", "Pollo", 150f, category = "Carne fresca")),
            listOf(food("it.petto_pollo", "Petto di pollo", 100f, category = "Carne fresca")),
        )
        val noCatalog = ShoppingListEngine.aggregate(snapshotDays(*days))
        assertEquals(1, noCatalog.size)
        assertEquals(250.0, noCatalog.single().quantity, 0.001)
        assertEquals("Carne fresca", noCatalog.single().category)

        val otherVersion = ShoppingListEngine.aggregate(snapshotDays(*days, catalogVersion = "99"), catalog)
        assertEquals(noCatalog, otherVersion)
    }

    @Test
    fun `mixed plan merges the same food with and without foodId and keeps the rest by name`() {
        val result = ShoppingListEngine.aggregate(
            snapshotDays(
                listOf(food("it.petto_pollo", "Petto di pollo (crudo)", 150f, weightState = "crudo")),
                listOf(
                    ingredient("Petto di pollo", 100f, "g", "crudo"),
                    ingredient("Petto di pollo", 100f, "g", "cotto"),
                    ingredient("Mela", 2f, "pz", null),
                ),
            ),
            catalog,
        )

        val pollo = result.single { it.name == "Petto di pollo (crudo)" }
        assertEquals(250.0, pollo.quantity, 0.001)
        assertEquals(2, pollo.sourceCount)
        // cotto dichiarato ma il cotto non e' nel piano: resta la riga storica per nome
        assertEquals(1, result.count { it.name == "Petto di pollo" && it.weightState == "cotto" })
        assertEquals(1, result.count { it.name == "Mela" })
        assertEquals(3, result.size)
    }

    @Test
    fun `declared plans are unchanged whether or not a catalog is provided`() {
        val declared = snapshotDays(
            listOf(
                ingredient("Riso basmati", 500f, "g", "secco"),
                ingredient("Petto di pollo", 200f, "g", "crudo"),
                ingredient("Latte intero", 200f, "ml", null),
            ),
            listOf(
                ingredient("riso   basmati", 1f, "kg", "secco"),
                ingredient("Petto di pollo", 150f, "g", "crudo"),
                ingredient("Olio extravergine d'oliva", 10f, "ml", null),
            ),
        )

        val without = ShoppingListEngine.aggregate(declared)
        assertEquals(without, ShoppingListEngine.aggregate(declared, catalog))
        assertEquals(1500.0, without.single { it.name == "Riso basmati" }.quantity, 0.001)
        assertEquals(350.0, without.single { it.name == "Petto di pollo" }.quantity, 0.001)
        assertEquals("Cereali", without.single { it.name == "Riso basmati" }.category)
    }

    private fun snapshotDays(vararg days: List<FoodIngredient>, catalogVersion: String? = null): FoodPlanSnapshot = FoodPlanSnapshot(
        planId = 1,
        profileId = 1,
        weekStartEpochDay = 1,
        version = FoodPlanVersion(
            id = 1,
            versionNumber = 1,
            createdAtEpochMillis = 1,
            source = "TEST",
            reason = null,
            targetKcal = 2000,
            targetProteinG = 150f,
            targetCarbsG = 200f,
            targetFatG = 70f,
            catalogVersion = catalogVersion ?: if (days.any { day -> day.any { it.foodId != null } }) catalog.version.toString() else null,
            days = days.mapIndexed { index, ingredients ->
                FoodPlanDay(
                    id = index + 1L,
                    dateEpochDay = index + 1L,
                    totalKcal = 2000,
                    proteinG = 150f,
                    carbsG = 200f,
                    fatG = 70f,
                    meals = listOf(
                        FoodMeal(
                            id = index + 1L,
                            dayId = index + 1L,
                            sortOrder = 0,
                            type = "Pranzo",
                            title = "Test",
                            timeMinutes = 720,
                            kcal = 500,
                            proteinG = 30f,
                            carbsG = 50f,
                            fatG = 10f,
                            preparation = null,
                            ingredients = ingredients.mapIndexed { i, ing -> ing.copy(sortOrder = i) },
                        )
                    ),
                )
            },
        ),
    )

    private fun ingredient(name: String, quantity: Float, unit: String, weightState: String?) = FoodIngredient(
        id = 0,
        mealId = 0,
        name = name,
        quantity = quantity,
        unit = unit,
        displayDose = null,
        weightState = weightState,
        nutritionConfidence = "HIGH",
        category = "Cereali",
        sortOrder = 0,
    )

    private fun snapshot(vararg ingredients: FoodIngredient): FoodPlanSnapshot = FoodPlanSnapshot(
        planId = 1,
        profileId = 1,
        weekStartEpochDay = 1,
        version = FoodPlanVersion(
            id = 1,
            versionNumber = 1,
            createdAtEpochMillis = 1,
            source = "TEST",
            reason = null,
            targetKcal = 2000,
            targetProteinG = 150f,
            targetCarbsG = 200f,
            targetFatG = 70f,
            days = listOf(
                FoodPlanDay(
                    id = 1,
                    dateEpochDay = 1,
                    totalKcal = 2000,
                    proteinG = 150f,
                    carbsG = 200f,
                    fatG = 70f,
                    meals = listOf(
                        FoodMeal(
                            id = 1,
                            dayId = 1,
                            sortOrder = 0,
                            type = "Pranzo",
                            title = "Test",
                            timeMinutes = 720,
                            kcal = 500,
                            proteinG = 30f,
                            carbsG = 50f,
                            fatG = 10f,
                            preparation = null,
                            ingredients = ingredients.toList(),
                        )
                    ),
                )
            ),
        ),
    )
}
