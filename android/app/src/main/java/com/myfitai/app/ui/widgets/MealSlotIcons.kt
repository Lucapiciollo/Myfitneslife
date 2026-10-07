package com.myfitai.app.ui.widgets

import com.myfitai.app.R
import com.myfitai.app.domain.food.MealSlot
import com.myfitai.app.domain.food.MealSlotClassifier

/** Icon and spoken label of a meal slot; the single place that maps a meal to its icon. */
object MealSlotIcons {
    fun iconRes(slot: MealSlot): Int = when (slot) {
        MealSlot.BREAKFAST -> R.drawable.ic_meal_breakfast
        MealSlot.MORNING_SNACK -> R.drawable.ic_meal_morning_snack
        MealSlot.LUNCH, MealSlot.OTHER -> R.drawable.ic_meal_lunch
        MealSlot.AFTERNOON_SNACK -> R.drawable.ic_meal_afternoon_snack
        MealSlot.DINNER -> R.drawable.ic_meal_dinner
        MealSlot.EVENING_SNACK -> R.drawable.ic_meal_evening_snack
        MealSlot.WORKOUT -> R.drawable.ic_meal_workout
    }

    fun label(slot: MealSlot): String = when (slot) {
        MealSlot.BREAKFAST -> "Colazione"
        MealSlot.MORNING_SNACK -> "Spuntino del mattino"
        MealSlot.LUNCH -> "Pranzo"
        MealSlot.AFTERNOON_SNACK -> "Spuntino del pomeriggio"
        MealSlot.DINNER -> "Cena"
        MealSlot.EVENING_SNACK -> "Spuntino della sera"
        MealSlot.WORKOUT -> "Pasto per l'allenamento"
        MealSlot.OTHER -> "Pasto"
    }

    fun iconRes(type: String?, timeMinutes: Int?): Int = iconRes(MealSlotClassifier.classify(type, timeMinutes))
    fun label(type: String?, timeMinutes: Int?): String = label(MealSlotClassifier.classify(type, timeMinutes))
}