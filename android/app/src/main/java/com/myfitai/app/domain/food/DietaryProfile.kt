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
    /** [Status.UNREADABLE] means the stored JSON could not be read: constraints are UNKNOWN, not empty. */
    val status: Status = Status.OK,
) {
    enum class Status { OK, UNREADABLE }

    val isUnreadable: Boolean get() = status == Status.UNREADABLE

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
        /** Stable machine code surfaced when the saved constraints cannot be read. */
        const val UNREADABLE_CODE = "PROFILE_CONSTRAINTS_UNREADABLE"
        const val UNREADABLE_MESSAGE =
            "$UNREADABLE_CODE: le preferenze alimentari salvate (allergie, intolleranze, esclusioni) non sono leggibili. " +
                "Apri Preferenze alimentari, controllale e salvale di nuovo prima di generare piani o alternative."

        /**
         * Blank input means "no constraints". Corrupted or structurally wrong JSON yields [Status.UNREADABLE]
         * (the raw text is kept in [notes] so the user can see and fix it): callers must NOT treat that profile as
         * constraint free.
         */
        fun parse(json: String?): DietaryProfile {
            if (json.isNullOrBlank()) return DietaryProfile()
            val unreadable = DietaryProfile(notes = json.trim(), status = Status.UNREADABLE)
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return unreadable
            val lists = listOf("preferredFoods", "dislikedFoods", "excludedFoods", "intolerances", "allergies")
                .map { key -> root.stringList(key) ?: return unreadable }
            return DietaryProfile(
                preferredFoods = lists[0],
                dislikedFoods = lists[1],
                excludedFoods = lists[2],
                intolerances = lists[3],
                allergies = lists[4],
                dietStyle = root.optString("dietStyle").takeIf { it.isNotBlank() && it != "null" },
                notes = root.optString("notes").takeIf { it.isNotBlank() && it != "null" },
            )
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

        /** Missing/null key = empty list; array of strings = list; plain string = csv; any other shape = null (unreadable). */
        private fun JSONObject.stringList(key: String): List<String>? {
            if (!has(key) || isNull(key)) return emptyList()
            return when (val value = opt(key)) {
                is JSONArray -> buildList {
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item is JSONObject || item is JSONArray) return null
                        value.optString(i).trim().takeIf { it.isNotBlank() && it != "null" }?.let(::add)
                    }
                }
                is String -> csv(value)
                else -> null
            }
        }
    }
}

/**
 * Deterministic final gate: an LLM cannot override declared allergies/intolerances/exclusions/diet style.
 *
 * List based check, NOT a medical guarantee: see [FoodSynonyms] and 06-ai/providers/shared/FOOD_CONSTRAINTS_LIMITS.md.
 * Fail closed: an unreadable profile never passes.
 */
object FoodConstraintValidator {
    data class Violation(val ingredient: String, val constraint: String, val kind: String)

    const val KIND_UNREADABLE = DietaryProfile.UNREADABLE_CODE

    fun hasHardConstraints(profile: DietaryProfile): Boolean =
        profile.isUnreadable || profile.allergies.isNotEmpty() || profile.intolerances.isNotEmpty() ||
            profile.excludedFoods.isNotEmpty() || !profile.dietStyle.isNullOrBlank()

    fun validate(plan: NutritionPlanContract.Response, profile: DietaryProfile): Result<Unit> = runCatching {
        require(!profile.isUnreadable) { DietaryProfile.UNREADABLE_CODE }
        val violations = plan.days.flatMap { day ->
            val mealViolations = day.meals.flatMap { meal ->
                validateIngredientNames(meal.ingredients.map { it.name } + meal.title, profile)
            }
            val supplementViolations = day.supplements.flatMap { supplement ->
                buildList {
                    addAll(validateIngredientNames(listOfNotNull(supplement.name, supplement.notes.takeIf { it.isNotBlank() }), profile))
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
        require(!profile.isUnreadable) { DietaryProfile.UNREADABLE_CODE }
        val violations = validateIngredientNames(alternative.ingredients.map { it.name } + alternative.title, profile)
        require(violations.isEmpty()) { "FOOD_CONSTRAINT_VIOLATION" }
    }

    fun validateIngredientNames(names: List<String>, profile: DietaryProfile): List<Violation> {
        if (profile.isUnreadable) return listOf(Violation("-", DietaryProfile.UNREADABLE_CODE, KIND_UNREADABLE))
        if (!hasHardConstraints(profile)) return emptyList()

        val allergies = profile.allergies.map { it to FoodSynonyms.constraintMatcher(it, strict = true) }
        val intolerances = profile.intolerances.map { it to FoodSynonyms.constraintMatcher(it, strict = true) }
        val excluded = profile.excludedFoods.map { it to FoodSynonyms.constraintMatcher(it, strict = false) }
        val diet = FoodSynonyms.dietMatcher(profile.dietStyle)

        return buildList {
            names.forEach { ingredient ->
                val tokens = FoodSynonyms.tokenize(ingredient)
                allergies.forEach { (constraint, matcher) -> if (matcher.matches(tokens)) add(Violation(ingredient, constraint, "ALLERGY")) }
                intolerances.forEach { (constraint, matcher) -> if (matcher.matches(tokens)) add(Violation(ingredient, constraint, "INTOLERANCE")) }
                excluded.forEach { (constraint, matcher) -> if (matcher.matches(tokens)) add(Violation(ingredient, constraint, "EXCLUDED")) }
                if (diet != null && diet.matches(tokens)) add(Violation(ingredient, profile.dietStyle.orEmpty(), "DIET_STYLE"))
            }
        }
    }

    private fun proteinSourceIsAmbiguous(name: String): Boolean {
        val normalized = DietaryProfile.normalize(name)
        val explicitSources = setOf(
            "whey", "siero del latte", "caseina", "latte", "soia", "pisello", "riso", "canapa", "albume", "uovo", "vegan", "vegana"
        )
        return normalized in setOf("proteine in polvere", "protein powder", "proteine", "protein") ||
            explicitSources.none(normalized::contains)
    }
}
