package com.myfitai.app.domain.shopping

import com.myfitai.app.domain.food.FoodIngredient
import com.myfitai.app.domain.food.FoodPlanSnapshot
import com.myfitai.app.domain.food.catalog.FoodCatalog
import com.myfitai.app.domain.food.catalog.FoodEntry
import com.myfitai.app.domain.food.catalog.FoodState
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Pure deterministic shopping-list aggregation. No AI is involved.
 * Quantities are summed only when units and weight state are compatible.
 */
object ShoppingListEngine {

    data class Item(
        val key: String,
        val name: String,
        val category: String,
        val quantity: Double,
        val unit: String,
        val weightState: String?,
        val sourceCount: Int,
    ) {
        /**
         * Quantita' mostrata nella lista (solo presentazione: [quantity] e la chiave restano quelli del piano).
         * Per g e ml arrotonda SEMPRE per eccesso a numeri comodi, cosi' la spesa non si riduce mai.
         */
        fun displayQuantity(): String {
            val shown = roundedQuantity()
            return when {
                unit == "g" && shown >= 1000.0 -> formatTrimmed(shown / 1000.0, 2) + " kg"
                unit == "ml" && shown >= 1000.0 -> formatTrimmed(shown / 1000.0, 2) + " l"
                else -> format(shown) + " " + unit
            }
        }

        /** [quantity] arrotondata per eccesso per g/ml (vedi [shoppingStep]); le altre unita' restano invariate. */
        fun roundedQuantity(): Double = if (unit == "g" || unit == "ml") roundUpForShopping(quantity) else quantity
    }

    /** Passo di arrotondamento della spesa: <20 -> 1, 20..<100 -> 5, >=100 -> 10. */
    internal fun shoppingStep(quantity: Double): Double = when {
        quantity < 20.0 -> 1.0
        quantity < 100.0 -> 5.0
        else -> 10.0
    }

    /** Arrotonda per eccesso al passo comodo (tolleranza numerica per i valori gia' esatti). */
    internal fun roundUpForShopping(quantity: Double): Double {
        if (!quantity.isFinite() || quantity <= 0.0) return quantity
        val step = shoppingStep(quantity)
        return kotlin.math.ceil(quantity / step - 1e-9) * step
    }

    /**
     * [catalog] (opzionale) serve solo ai piani con snapshot (foodId, piani MFP2): gli ingredienti con foodId si
     * aggregano per alimento, gli altri restano sul comportamento storico per nome (invariato senza catalogo).
     */
    fun aggregate(snapshot: FoodPlanSnapshot, catalog: FoodCatalog? = null): List<Item> {
        data class Acc(
            val name: String,
            val category: String,
            val unit: String,
            val weightState: String?,
            var quantity: Double,
            var count: Int,
        )

        val aggregated = linkedMapOf<String, Acc>()
        val ordered = snapshot.version.days
            .sortedBy { it.dateEpochDay }
            .flatMap { it.meals.sortedBy { meal -> meal.sortOrder } }
            .flatMap { it.ingredients.sortedBy { ingredient -> ingredient.sortOrder } }
        val resolver = SnapshotResolver(ordered, catalog, snapshot.version.catalogVersion)
        ordered
            .forEach { ingredient ->
                if (!ingredient.quantity.isFinite() || ingredient.quantity <= 0f) return@forEach
                val resolved = resolver.resolve(ingredient)
                if (resolved != null) {
                    val existingFood = aggregated[resolved.key]
                    if (existingFood == null) {
                        aggregated[resolved.key] = Acc(
                            name = resolved.name,
                            category = resolved.category,
                            unit = resolved.unit,
                            weightState = resolved.weightState,
                            quantity = resolved.quantity,
                            count = 1,
                        )
                    } else {
                        existingFood.quantity += resolved.quantity
                        existingFood.count++
                    }
                    return@forEach
                }
                val normalizedUnit = normalizeUnit(ingredient.unit)
                val converted = convertQuantity(ingredient.quantity.toDouble(), ingredient.unit, normalizedUnit)
                val category = normalizeCategory(ingredient.category)
                val weightState = ingredient.weightState?.trim()?.takeIf { it.isNotBlank() }
                val key = stableKey(ingredient.name, normalizedUnit, weightState)
                val existing = aggregated[key]
                if (existing == null) {
                    aggregated[key] = Acc(
                        name = canonicalName(ingredient.name),
                        category = category,
                        unit = normalizedUnit,
                        weightState = weightState,
                        quantity = converted,
                        count = 1,
                    )
                } else {
                    existing.quantity += converted
                    existing.count++
                }
            }

        return aggregated.map { (key, acc) ->
            Item(
                key = key,
                name = acc.name,
                category = acc.category,
                quantity = acc.quantity,
                unit = acc.unit,
                weightState = acc.weightState,
                sourceCount = acc.count,
            )
        }.sortedWith(compareBy<Item> { it.category.lowercase(Locale.ROOT) }.thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.unit })
    }

    private class Resolution(
        val key: String,
        val name: String,
        val category: String,
        val unit: String,
        val weightState: String?,
        val quantity: Double,
    )

    /**
     * Risolve gli ingredienti con snapshot (foodId) sul catalogo. Un ingrediente NON risolto (null) segue il percorso storico.
     * - foodId + voce nota: grammi dello snapshot (o g/ml via densita'), cotto -> crudo SOLO se il catalogo dichiara la resa,
     *   liquidi in ml via densita', categoria dal catalogo (shopCat).
     * - foodId senza catalogo (o versione diversa/voce ignota): chiave per foodId e unita', nome e categoria dell'ingrediente.
     * - senza foodId: solo nei piani con foodId e solo se il nome combacia ESATTAMENTE con un alimento gia' presente nel piano
     *   (stesso alimento con e senza foodId: niente doppio conteggio); altrimenti storico.
     */
    private class SnapshotResolver(
        ingredients: List<FoodIngredient>,
        catalog: FoodCatalog?,
        catalogVersion: String?,
    ) {
        private val catalog: FoodCatalog? = catalog?.takeIf { catalogVersion == null || catalogVersion == it.version.toString() }
        private val planFoodIds: Set<String> = ingredients.mapNotNull { it.foodId?.trim()?.takeIf(String::isNotEmpty) }.toSet()

        fun resolve(ingredient: FoodIngredient): Resolution? {
            val foodId = ingredient.foodId?.trim()?.takeIf { it.isNotEmpty() }
            if (foodId != null) return resolveSnapshot(ingredient, foodId)
            return resolveByName(ingredient)
        }

        private fun resolveSnapshot(ingredient: FoodIngredient, foodId: String): Resolution? {
            val entry = catalog?.byId(foodId)
            if (entry == null) {
                val unit = normalizeUnit(ingredient.unit)
                return Resolution(
                    key = "food:$foodId|$unit",
                    name = canonicalName(ingredient.name),
                    category = normalizeCategory(ingredient.category),
                    unit = unit,
                    weightState = ingredient.weightState?.trim()?.takeIf { it.isNotBlank() },
                    quantity = convertQuantity(ingredient.quantity.toDouble(), ingredient.unit, unit),
                )
            }
            val grams = ingredient.grams?.takeIf { it.isFinite() && it > 0f }?.toDouble()
                ?: gramsFrom(ingredient.quantity.toDouble(), ingredient.unit, entry)
                ?: return null
            return fromEntry(entry, grams)
        }

        private fun resolveByName(ingredient: FoodIngredient): Resolution? {
            val cat = catalog ?: return null
            if (planFoodIds.isEmpty()) return null
            val found = cat.findByAlias(ingredient.name) ?: return null
            val declared = when (normalizeText(ingredient.weightState.orEmpty())) {
                "crudo", "secco" -> FoodState.RAW
                "cotto" -> FoodState.COOKED
                else -> null
            }
            val candidates = buildList {
                if (declared == null || found.state == declared) add(found)
                cat.pairOf(found)?.takeIf { declared != null && it.state == declared }?.let { add(it) }
            }
            val match = candidates.firstOrNull { it.id in planFoodIds } ?: return null
            val grams = gramsFrom(ingredient.quantity.toDouble(), ingredient.unit, match) ?: return null
            return fromEntry(match, grams)
        }

        private fun gramsFrom(quantity: Double, unit: String, entry: FoodEntry): Double? {
            val normalized = normalizeUnit(unit)
            val converted = convertQuantity(quantity, unit, normalized)
            return when (normalized) {
                "g" -> converted
                "ml" -> entry.density?.takeIf { it > 0.0 }?.let { converted * it }
                else -> null
            }
        }

        private fun fromEntry(entry: FoodEntry, grams: Double): Resolution {
            var target = entry
            var targetGrams = grams
            val yieldRatio = entry.yieldCookedPerRaw
            if (entry.state == FoodState.COOKED && yieldRatio != null && yieldRatio > 0.0) {
                val pair = catalog?.pairOf(entry)
                if (pair != null && pair.state == FoodState.RAW) {
                    target = pair
                    targetGrams = grams / yieldRatio
                }
            }
            val density = target.density?.takeIf { it > 0.0 }
            val unit = if (density != null) "ml" else "g"
            val quantity = if (density != null) targetGrams / density else targetGrams
            return Resolution(
                key = "food:${target.id}|$unit",
                name = target.name,
                category = normalizeCategory(target.shopCategory),
                unit = unit,
                weightState = when (target.state) {
                    FoodState.RAW -> "crudo"
                    FoodState.COOKED -> "cotto"
                    else -> null
                },
                quantity = quantity,
            )
        }
    }

    fun stableKey(name: String, unit: String, weightState: String?): String = listOf(
        normalizeText(name),
        normalizeUnit(unit),
        normalizeText(weightState.orEmpty()),
    ).joinToString("|")

    private fun normalizeUnit(unit: String): String = when (normalizeText(unit)) {
        "grammo", "grammi", "gr", "g" -> "g"
        "chilogrammo", "chilogrammi", "chilo", "chili", "kg" -> "g"
        "millilitro", "millilitri", "ml" -> "ml"
        "litro", "litri", "l" -> "ml"
        "pezzo", "pezzi", "pz", "unita", "unità", "u" -> "pz"
        else -> unit.trim().lowercase(Locale.ROOT).ifBlank { "unità" }
    }

    private fun convertQuantity(quantity: Double, originalUnit: String, normalizedUnit: String): Double {
        val original = normalizeText(originalUnit)
        return when {
            normalizedUnit == "g" && original in setOf("kg", "chilogrammo", "chilogrammi", "chilo", "chili") -> quantity * 1000.0
            normalizedUnit == "ml" && original in setOf("l", "litro", "litri") -> quantity * 1000.0
            else -> quantity
        }
    }

    private fun normalizeCategory(value: String?): String {
        val clean = value?.trim().orEmpty()
        if (clean.isBlank()) return "Altro"
        return clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }
    }

    private fun canonicalName(value: String): String {
        val clean = value.trim().replace(Regex("\\s+"), " ")
        return clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }
    }

    private fun normalizeText(value: String): String = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun formatTrimmed(value: Double, decimals: Int): String {
        val factor = Math.pow(10.0, decimals.toDouble())
        val rounded = kotlin.math.ceil(value * factor - 1e-9) / factor
        if (abs(rounded - rounded.toLong()) < 0.0001) return rounded.toLong().toString()
        return String.format(Locale.ITALIAN, "%.${decimals}f", rounded).trimEnd('0').trimEnd(',')
    }

    private fun format(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        return if (abs(rounded - rounded.toLong()) < 0.0001) rounded.toLong().toString()
        else String.format(Locale.ITALIAN, "%.1f", rounded)
    }
}