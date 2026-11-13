package com.myfitai.app.domain.food

import java.text.Normalizer
import java.util.Locale

/**
 * Meal variety: a compact memory of what the user was served recently (sent to the model) and a local,
 * non-blocking check of the generated plan against that memory.
 *
 * Repeating a meal is allowed when it cannot reasonably be avoided, so nothing here rejects a plan: the
 * result is a notice and `WARNING` entries in the persisted app validation.
 */
object MealVariety {
    const val MEMORY_WEEKS = 2
    const val MAX_MEMORY_MEALS = 60
    private const val MIN_INGREDIENTS_FOR_SET_MATCH = 3
    private const val MAX_EXAMPLES = 3

    data class Meal(val type: String, val title: String, val ingredients: List<String> = emptyList())

    data class DayMeals(val dateEpochDay: Long, val meals: List<Meal>)

    data class Report(
        /** Meals of the new plan that were already served in the memory window. */
        val repeatedFromRecent: List<String>,
        /** Meals served on two or more days of the new plan. */
        val repeatedInPeriod: List<String>,
    ) {
        val hasRepetitions: Boolean get() = repeatedFromRecent.isNotEmpty() || repeatedInPeriod.isNotEmpty()

        fun issues(): List<NutritionValidationIssue> = buildList {
            if (repeatedFromRecent.isNotEmpty()) add(issue("VARIETY_REPEATS_RECENT", "Pasti già proposti nelle ultime $MEMORY_WEEKS settimane", repeatedFromRecent))
            if (repeatedInPeriod.isNotEmpty()) add(issue("VARIETY_REPEATS_IN_PLAN", "Pasti ripetuti in più giorni del piano", repeatedInPeriod))
        }

        /** Short Italian text for the user; null when nothing repeats. */
        fun notice(): String? {
            if (!hasRepetitions) return null
            val parts = buildList {
                if (repeatedFromRecent.isNotEmpty()) add("${repeatedFromRecent.size} già proposti nelle ultime $MEMORY_WEEKS settimane")
                if (repeatedInPeriod.isNotEmpty()) add("${repeatedInPeriod.size} ripetuti nel piano")
            }
            return "Alcuni pasti si ripetono (${parts.joinToString(", ")}): consentito quando non era evitabile."
        }

        private fun issue(code: String, label: String, titles: List<String>) = NutritionValidationIssue(
            code = code,
            severity = Severity.WARNING,
            message = "$label: ${titles.size} (${titles.take(MAX_EXAMPLES).joinToString("; ")})",
        )
    }

    /** Most recent distinct meals first, capped: this is what the prompt carries as `RM:` lines. */
    fun memory(history: List<DayMeals>): List<Meal> {
        val groups = mutableListOf<Group>()
        history.sortedByDescending { it.dateEpochDay }.forEach { day ->
            day.meals.forEach { meal ->
                val key = Key.of(meal)
                if (key.titleKey.isNotBlank() && groups.none { it.key.matches(key) }) groups += Group(key, meal)
            }
        }
        return groups.take(MAX_MEMORY_MEALS).map { it.meal }
    }

    fun promptLines(memory: List<Meal>): List<String> = memory.map { "RM:${clean(it.type)}|${clean(it.title)}" }

    fun check(generated: List<DayMeals>, recent: List<Meal>): Report {
        val groups = mutableListOf<Group>()
        recent.forEach { meal ->
            val key = Key.of(meal)
            if (key.titleKey.isNotBlank() && groups.none { it.key.matches(key) }) groups += Group(key, meal, recent = true)
        }
        generated.forEach { day ->
            day.meals.forEach { meal ->
                val key = Key.of(meal)
                if (key.titleKey.isBlank()) return@forEach
                val group = groups.firstOrNull { it.key.matches(key) } ?: Group(key, meal).also { groups += it }
                if (group.generatedDays.isEmpty()) group.shownTitle = meal.title
                group.generatedDays += day.dateEpochDay
            }
        }
        val used = groups.filter { it.generatedDays.isNotEmpty() }
        return Report(
            repeatedFromRecent = used.filter { it.recent }.map { it.shownTitle },
            repeatedInPeriod = used.filter { it.generatedDays.size >= 2 }.map { it.shownTitle },
        )
    }

    private class Group(val key: Key, val meal: Meal, val recent: Boolean = false) {
        /** Title as written in the new plan, so notices quote what the user will see. */
        var shownTitle: String = meal.title
        val generatedDays = mutableSetOf<Long>()
    }

    /** Two meals are "the same" when the titles normalise equally or they use the same ingredient set. */
    private data class Key(val titleKey: String, val ingredientKey: String?) {
        fun matches(other: Key): Boolean =
            titleKey == other.titleKey || (ingredientKey != null && ingredientKey == other.ingredientKey)

        companion object {
            fun of(meal: Meal): Key {
                val ingredients = meal.ingredients.map(::normalize).filter { it.isNotBlank() }.toSortedSet()
                return Key(
                    titleKey = normalize(meal.title),
                    ingredientKey = ingredients.takeIf { it.size >= MIN_INGREDIENTS_FOR_SET_MATCH }?.joinToString(","),
                )
            }
        }
    }

    internal fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun clean(value: String): String = value.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim().ifBlank { "?" }
}
