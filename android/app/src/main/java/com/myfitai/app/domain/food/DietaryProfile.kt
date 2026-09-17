package com.myfitai.app.domain.food

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Structured, provider-neutral food preferences and hard constraints stored in dietaryPreferencesJson. */
data class DietaryProfile(
    val preferredFoods: List<String> = emptyList(),
    val dislikedFoods: List<String> = emptyList(),
    val excludedFoods: List<String> = emptyList(),
    val intolerances: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val dietStyle: String? = null,
    val notes: String? = null,
) {
    val hardConstraints: List<String>
        get() = (allergies + intolerances + excludedFoods).map(::normalize).filter { it.isNotBlank() }.distinct()

    fun toJson(): String = JSONObject().apply {
        put("preferredFoods", JSONArray(preferredFoods))
        put("dislikedFoods", JSONArray(dislikedFoods))
        put("excludedFoods", JSONArray(excludedFoods))
        put("intolerances", JSONArray(intolerances))
        put("allergies", JSONArray(allergies))
        put("dietStyle", dietStyle ?: JSONObject.NULL)
        put("notes", notes ?: JSONObject.NULL)
    }.toString()

    fun toPromptCompact(): String = buildString {
        append("A=").append(allergies.joinToString(",").ifBlank { "-" })
        append(";I=").append(intolerances.joinToString(",").ifBlank { "-" })
        append(";E=").append(excludedFoods.joinToString(",").ifBlank { "-" })
        append(";D=").append(dislikedFoods.joinToString(",").ifBlank { "-" })
        append(";P=").append(preferredFoods.joinToString(",").ifBlank { "-" })
        append(";S=").append(dietStyle?.takeIf { it.isNotBlank() } ?: "-")
        append(";N=").append(notes?.takeIf { it.isNotBlank() } ?: "-")
    }.replace('|', '/').replace('\n', ' ').replace('\r', ' ')

    companion object {
        fun parse(json: String?): DietaryProfile {
            if (json.isNullOrBlank()) return DietaryProfile()
            return runCatching {
                val root = JSONObject(json)
                DietaryProfile(
                    preferredFoods = root.stringList("preferredFoods"),
                    dislikedFoods = root.stringList("dislikedFoods"),
                    excludedFoods = root.stringList("excludedFoods"),
                    intolerances = root.stringList("intolerances"),
                    allergies = root.stringList("allergies"),
                    dietStyle = root.optString("dietStyle").takeIf { it.isNotBlank() && it != "null" },
                    notes = root.optString("notes").takeIf { it.isNotBlank() && it != "null" },
                )
            }.getOrElse { DietaryProfile(notes = json.trim()) }
        }

        fun csv(value: String?): List<String> = value.orEmpty()
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy(::normalize)

        internal fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()

        private fun JSONObject.stringList(key: String): List<String> {
            val array = optJSONArray(key) ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }
}

/** Deterministic final gate: an LLM cannot override declared allergies/intolerances/exclusions/diet style. */
object FoodConstraintValidator {
    data class Violation(val ingredient: String, val constraint: String, val kind: String)

    fun validate(plan: NutritionPlanContract.Response, profile: DietaryProfile): Result<Unit> = runCatching {
        val violations = plan.days.flatMap { day ->
            val mealViolations = day.meals.flatMap { meal ->
                validateIngredientNames(meal.ingredients.map { it.name }, profile)
            }
            val supplementViolations = day.supplements.flatMap { supplement ->
                buildList {
                    addAll(validateIngredientNames(listOf(supplement.name), profile))
                    if (supplement.kind == "PROTEIN_POWDER" && hasHardConstraints(profile) && proteinSourceIsAmbiguous(supplement.name)) {
                        add(Violation(supplement.name, "source-not-explicit", "SUPPLEMENT_SOURCE_UNCLEAR"))
                    }
                }
            }
            mealViolations + supplementViolations
        }
        require(violations.isEmpty()) {
            "FOOD_CONSTRAINT_VIOLATION:${violations.take(5).joinToString(",") { "${it.kind}:${it.constraint}:${it.ingredient}" }}"
        }
    }

    fun validateAlternative(alternative: MealAlternativeContract.Alternative, profile: DietaryProfile): Result<Unit> = runCatching {
        val violations = validateIngredientNames(alternative.ingredients.map { it.name }, profile)
        require(violations.isEmpty()) { "FOOD_CONSTRAINT_VIOLATION" }
    }

    fun validateIngredientNames(names: List<String>, profile: DietaryProfile): List<Violation> {
        if (!hasHardConstraints(profile)) return emptyList()

        return buildList {
            names.forEach { ingredient ->
                val normalizedIngredient = DietaryProfile.normalize(ingredient)
                profile.allergies.forEach { constraint ->
                    if (matches(normalizedIngredient, constraint)) add(Violation(ingredient, constraint, "ALLERGY"))
                }
                profile.intolerances.forEach { constraint ->
                    if (matches(normalizedIngredient, constraint)) add(Violation(ingredient, constraint, "INTOLERANCE"))
                }
                profile.excludedFoods.forEach { constraint ->
                    if (matches(normalizedIngredient, constraint)) add(Violation(ingredient, constraint, "EXCLUDED"))
                }
                dietStyleViolation(normalizedIngredient, profile.dietStyle)?.let { add(Violation(ingredient, it, "DIET_STYLE")) }
            }
        }
    }

    private fun hasHardConstraints(profile: DietaryProfile): Boolean =
        profile.allergies.isNotEmpty() || profile.intolerances.isNotEmpty() ||
            profile.excludedFoods.isNotEmpty() || !profile.dietStyle.isNullOrBlank()

    private fun proteinSourceIsAmbiguous(name: String): Boolean {
        val normalized = DietaryProfile.normalize(name)
        val explicitSources = setOf(
            "whey", "siero del latte", "caseina", "latte", "soia", "pisello", "riso", "canapa", "albume", "uovo", "vegan", "vegana"
        )
        return normalized in setOf("proteine in polvere", "protein powder", "proteine", "protein") ||
            explicitSources.none(normalized::contains)
    }

    private fun matches(ingredient: String, rawConstraint: String): Boolean {
        val constraint = DietaryProfile.normalize(rawConstraint)
        if (constraint.isBlank()) return false
        if (ingredient == constraint || ingredient.contains(constraint)) return true
        return aliases(constraint).any { alias -> ingredient == alias || ingredient.contains(alias) }
    }

    private fun aliases(constraint: String): Set<String> = when (constraint) {
        "lattosio" -> setOf("latte", "latticini", "yogurt", "formaggio", "panna", "burro", "siero del latte", "whey", "caseina")
        "glutine" -> setOf("frumento", "grano", "orzo", "segale", "farro", "spelta")
        "arachidi" -> setOf("arachide", "burro di arachidi")
        "frutta a guscio", "frutta secca" -> setOf("mandorle", "noci", "nocciole", "pistacchi", "anacardi", "pecan", "macadamia")
        "uova", "uovo" -> setOf("uovo", "uova", "albume", "tuorlo")
        "soia" -> setOf("soia", "tofu", "tempeh", "edamame")
        else -> emptySet()
    }

    private fun dietStyleViolation(ingredient: String, rawStyle: String?): String? {
        val style = DietaryProfile.normalize(rawStyle.orEmpty())
        if (style.isBlank() || style == "nessuno" || style == "onnivoro") return null
        val meat = setOf("pollo", "tacchino", "manzo", "vitello", "maiale", "prosciutto", "bresaola", "salsiccia", "carne")
        val fish = setOf("pesce", "salmone", "tonno", "merluzzo", "orata", "branzino", "gamber", "polpo", "calamaro")
        val animal = meat + fish + setOf("latte", "yogurt", "formaggio", "uovo", "uova", "albume", "tuorlo", "miele", "whey", "caseina", "siero del latte")
        return when (style) {
            "vegetariano", "vegetarian" -> if ((meat + fish).any(ingredient::contains)) rawStyle else null
            "vegano", "vegan" -> if (animal.any(ingredient::contains)) rawStyle else null
            "pescetariano", "pescatariano", "pescetarian" -> if (meat.any(ingredient::contains)) rawStyle else null
            else -> null
        }
    }
}
