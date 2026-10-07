package com.myfitai.app.domain.food

import java.text.Normalizer
import java.util.Locale

/** Moment of the day a meal belongs to; drives the meal icon. */
enum class MealSlot { BREAKFAST, MORNING_SNACK, LUNCH, AFTERNOON_SNACK, DINNER, EVENING_SNACK, WORKOUT, OTHER }

object MealSlotClassifier {
    /**
     * The meal name wins (the plan names its slots "Colazione", "Spuntino Mattina", "Pranzo", ...);
     * the time of day only decides when the name does not say which meal or snack it is.
     */
    fun classify(type: String?, timeMinutes: Int?): MealSlot {
        val name = normalize(type)
        return when {
            name.containsAny("pre workout", "preworkout", "post workout", "postworkout", "allenamento", "workout") -> MealSlot.WORKOUT
            name.containsAny("colazione", "breakfast") -> MealSlot.BREAKFAST
            name.containsAny("pranzo", "lunch") -> MealSlot.LUNCH
            name.containsAny("cena", "dinner") -> MealSlot.DINNER
            name.containsAny("spuntino", "snack", "merenda") -> snack(name, timeMinutes)
            else -> byTime(timeMinutes)
        }
    }

    private fun snack(name: String, timeMinutes: Int?): MealSlot = when {
        name.containsAny("mattina", "morning") -> MealSlot.MORNING_SNACK
        name.containsAny("pomeriggio", "afternoon", "merenda") -> MealSlot.AFTERNOON_SNACK
        name.containsAny("sera", "serale", "evening", "notte", "night") -> MealSlot.EVENING_SNACK
        timeMinutes == null -> MealSlot.AFTERNOON_SNACK
        timeMinutes < 12 * 60 -> MealSlot.MORNING_SNACK
        timeMinutes < 18 * 60 -> MealSlot.AFTERNOON_SNACK
        else -> MealSlot.EVENING_SNACK
    }

    private fun byTime(timeMinutes: Int?): MealSlot = when {
        timeMinutes == null -> MealSlot.OTHER
        timeMinutes < 4 * 60 -> MealSlot.EVENING_SNACK
        timeMinutes < 10 * 60 + 30 -> MealSlot.BREAKFAST
        timeMinutes < 12 * 60 -> MealSlot.MORNING_SNACK
        timeMinutes < 15 * 60 -> MealSlot.LUNCH
        timeMinutes < 18 * 60 -> MealSlot.AFTERNOON_SNACK
        timeMinutes < 22 * 60 -> MealSlot.DINNER
        else -> MealSlot.EVENING_SNACK
    }

    private fun String.containsAny(vararg words: String) = words.any { it in this }

    private fun normalize(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}