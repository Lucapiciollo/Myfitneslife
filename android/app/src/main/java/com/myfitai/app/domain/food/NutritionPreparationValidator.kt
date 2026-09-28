package com.myfitai.app.domain.food

/**
 * Checks the small set of ingredient aliases that are commonly mentioned in a
 * preparation while being accidentally omitted from the structured ingredient list.
 * It never adds data: the caller must regenerate or reject the plan instead.
 */
object NutritionPreparationValidator {
    data class Violation(val mentionedIngredient: String, val mealTitle: String)

    private val knownIngredients = mapOf(
        "gruviera" to setOf("gruviera", "gruyere", "groviéra"),
        "parmigiano" to setOf("parmigiano", "parmesan"),
        "mozzarella" to setOf("mozzarella"),
        "ricotta" to setOf("ricotta"),
        "burro" to setOf("burro"),
        "olio" to setOf("olio", "olio extravergine", "olio evo"),
        "miele" to setOf("miele"),
        "zucchero" to setOf("zucchero"),
    )

    fun validate(meal: NutritionPlanContract.GeneratedMeal): List<Violation> {
        val preparation = normalize(meal.preparation)
        val ingredientNames = meal.ingredients.map { normalize(it.name) }
        return knownIngredients.keys.mapNotNull { canonical ->
            val mentioned = knownIngredients.getValue(canonical).firstOrNull { preparation.containsWord(it) }
            if (mentioned != null && ingredientNames.none { name ->
                    name.containsWord(canonical) || knownIngredients.getValue(canonical).any { alias -> name.containsWord(alias) }
                }) {
                Violation(mentioned, meal.title)
            } else null
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace('à', 'a').replace('è', 'e').replace('é', 'e').replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.containsWord(value: String): Boolean =
        Regex("(^|\\s)${Regex.escape(value)}(\\s|$)").containsMatchIn(this)
}
