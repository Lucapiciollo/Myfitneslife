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
    /**
     * The exact stored text when [status] is [Status.UNREADABLE] (legacy free text such as
     * "Allergico alle noci, niente lattosio", or broken JSON), so the preferences screen can show it to the user.
     * Never persisted by [toJson].
     */
    val rawLegacyText: String? = null,
) {
    enum class Status { OK, UNREADABLE }

    /** Why a declared constraint cannot be fully verified by the list based check. */
    enum class NoticeReason {
        /** Not in the curated lists: only the words of the constraint are searched in ingredient names. */
        NAME_ONLY,
        /** Known limit (nichel, solfiti): only the literal word can be found. */
        LIMITED,
        /** Free-text diet style that is not vegetarian/vegan/pescetarian: not enforced by the check. */
        UNKNOWN_STYLE,
    }

    enum class NoticeCategory { ALLERGY, INTOLERANCE, EXCLUDED, DIET_STYLE }

    /** A constraint the user declared but the app can only check partially: the UI may show it. */
    data class ConstraintNotice(val constraint: String, val category: NoticeCategory, val reason: NoticeReason)

    val isUnreadable: Boolean get() = status == Status.UNREADABLE

    val hardConstraints: List<String>
        get() = (allergies + intolerances + excludedFoods).map(::normalize).filter { it.isNotBlank() }.distinct()

    /**
     * Declared constraints that are NOT fully verifiable ("controllo su elenco, non garanzia medica"):
     * allergies/intolerances outside the curated lists (checked by name only), known limits (nichel, solfiti)
     * and unknown diet styles. Empty for a fully covered profile.
     */
    val constraintNotices: List<ConstraintNotice>
        get() = buildList {
            fun scan(values: List<String>, category: NoticeCategory, strict: Boolean, reportNameOnly: Boolean) {
                values.filter { normalize(it).isNotBlank() }.forEach { value ->
                    val matcher = FoodSynonyms.constraintMatcher(value, strict)
                    when {
                        matcher.limited -> add(ConstraintNotice(value, category, NoticeReason.LIMITED))
                        reportNameOnly && matcher.nameOnly -> add(ConstraintNotice(value, category, NoticeReason.NAME_ONLY))
                    }
                }
            }
            scan(allergies, NoticeCategory.ALLERGY, strict = true, reportNameOnly = true)
            scan(intolerances, NoticeCategory.INTOLERANCE, strict = true, reportNameOnly = true)
            scan(excludedFoods, NoticeCategory.EXCLUDED, strict = false, reportNameOnly = false)
            val style = dietStyle
            if (!style.isNullOrBlank() && !FoodSynonyms.isUnrestrictedStyle(style) && FoodSynonyms.dietStyle(style) == null) {
                add(ConstraintNotice(style, NoticeCategory.DIET_STYLE, NoticeReason.UNKNOWN_STYLE))
            }
        }

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
        /** Stable machine code carried by the exceptions (field `code`); it is never part of the user text. */
        const val UNREADABLE_CODE = "PROFILE_CONSTRAINTS_UNREADABLE"

        /** User facing text: no machine code in it. */
        const val UNREADABLE_MESSAGE =
            "Le preferenze alimentari salvate (allergie, intolleranze, esclusioni) non sono leggibili. " +
                "Apri Preferenze alimentari, controllale e salvale di nuovo prima di generare piani o alternative."

        /** Reminder the UI can show next to the constraints. */
        const val LIMITS_NOTICE = "Controllo su elenco, non garanzia medica."

        /**
         * Blank input means "no constraints". Corrupted or structurally wrong JSON, or legacy free text, yields
         * [Status.UNREADABLE]: the raw text is kept in [notes] and [rawLegacyText] so the user can see and fix it,
         * and callers must NOT treat that profile as constraint free. The legacy key "style" is read as [dietStyle].
         */
        fun parse(json: String?): DietaryProfile {
            if (json.isNullOrBlank()) return DietaryProfile()
            val unreadable = DietaryProfile(notes = json.trim(), status = Status.UNREADABLE, rawLegacyText = json.trim())
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return unreadable
            val lists = listOf("preferredFoods", "dislikedFoods", "excludedFoods", "intolerances", "allergies")
                .map { key -> root.stringList(key) ?: return unreadable }
            return DietaryProfile(
                preferredFoods = lists[0],
                dislikedFoods = lists[1],
                excludedFoods = lists[2],
                intolerances = lists[3],
                allergies = lists[4],
                dietStyle = root.text("dietStyle")
                    ?: root.text("style")?.takeUnless { FoodSynonyms.isUnrestrictedStyle(it) },
                notes = root.text("notes"),
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

        private fun JSONObject.text(key: String): String? =
            optString(key).takeIf { it.isNotBlank() && it != "null" }

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
 * List based check, "controllo su elenco, non garanzia medica": see [FoodSynonyms] and
 * 06-ai/providers/shared/FOOD_CONSTRAINTS_LIMITS.md. Fail closed: an unreadable profile never passes.
 */
object FoodConstraintValidator {
    data class Violation(val ingredient: String, val constraint: String, val kind: String)

    const val KIND_UNREADABLE = DietaryProfile.UNREADABLE_CODE

    fun hasHardConstraints(profile: DietaryProfile): Boolean =
        profile.isUnreadable || profile.allergies.isNotEmpty() || profile.intolerances.isNotEmpty() ||
            profile.excludedFoods.isNotEmpty() ||
            (!profile.dietStyle.isNullOrBlank() && !FoodSynonyms.isUnrestrictedStyle(profile.dietStyle))

    fun validate(plan: NutritionPlanContract.Response, profile: DietaryProfile): Result<Unit> = runCatching {
        require(!profile.isUnreadable) { DietaryProfile.UNREADABLE_CODE }
        val violations = plan.days.flatMap { day ->
            val mealViolations = day.meals.flatMap { meal ->
                validateIngredientNames(meal.ingredients.map { it.name }, profile, titles = listOf(meal.title))
            }
            val supplementViolations = day.supplements.flatMap { supplement ->
                buildList {
                    // No ingredient list can clarify a supplement text, so name and note are matched in full.
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
        val violations = validateIngredientNames(alternative.ingredients.map { it.name }, profile, titles = listOf(alternative.title))
        require(violations.isEmpty()) { "FOOD_CONSTRAINT_VIOLATION" }
    }

    /**
     * [names] are ingredient (or supplement/food) names and are matched in full. [titles] are meal titles: only
     * strong, unambiguous terms count there, because the ingredient list is authoritative for generic staples
     * ("Pasta al pomodoro" is fine for gluten when the ingredient is "Pasta di riso").
     */
    fun validateIngredientNames(names: List<String>, profile: DietaryProfile, titles: List<String> = emptyList()): List<Violation> {
        if (profile.isUnreadable) return listOf(Violation("-", DietaryProfile.UNREADABLE_CODE, KIND_UNREADABLE))
        if (!hasHardConstraints(profile)) return emptyList()

        val allergies = profile.allergies.map { it to FoodSynonyms.constraintMatcher(it, strict = true) }
        val intolerances = profile.intolerances.map { it to FoodSynonyms.constraintMatcher(it, strict = true) }
        val excluded = profile.excludedFoods.map { it to FoodSynonyms.constraintMatcher(it, strict = false) }
        val diet = FoodSynonyms.dietMatcher(profile.dietStyle)

        return buildList {
            fun check(text: String, titleMode: Boolean) {
                val tokens = FoodSynonyms.tokenize(text)
                allergies.forEach { (constraint, matcher) -> if (matcher.matches(tokens, titleMode)) add(Violation(text, constraint, "ALLERGY")) }
                intolerances.forEach { (constraint, matcher) -> if (matcher.matches(tokens, titleMode)) add(Violation(text, constraint, "INTOLERANCE")) }
                excluded.forEach { (constraint, matcher) -> if (matcher.matches(tokens, titleMode)) add(Violation(text, constraint, "EXCLUDED")) }
                if (diet != null && diet.matches(tokens, titleMode)) add(Violation(text, profile.dietStyle.orEmpty(), "DIET_STYLE"))
            }
            names.forEach { check(it, titleMode = false) }
            titles.forEach { check(it, titleMode = true) }
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
