package com.myfitai.app.data.profile

import android.content.Context

/** User-controlled cadence for automatic nutrition-plan generation. */
class NutritionAutoGenerationPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(profileId: Long): Boolean = prefs.getBoolean(key(profileId, KEY_ENABLED), false)

    fun setEnabled(profileId: Long, value: Boolean) {
        prefs.edit().putBoolean(key(profileId, KEY_ENABLED), value).apply()
    }

    fun cadence(profileId: Long): Cadence = prefs.getString(key(profileId, KEY_CADENCE), Cadence.WEEK.name)
        ?.let(Cadence::fromStored) ?: Cadence.WEEK

    fun setCadence(profileId: Long, value: Cadence) {
        prefs.edit().putString(key(profileId, KEY_CADENCE), value.name).apply()
    }

    enum class Cadence(val label: String) {
        DAY("Ogni giorno"),
        WEEK("Ogni settimana"),
        TWO_WEEKS("Ogni 2 settimane"),
        THREE_WEEKS("Ogni 3 settimane"),
        MONTH("Ogni mese");

        fun nextStart(current: java.time.LocalDate): java.time.LocalDate = when (this) {
            DAY -> current.plusDays(1)
            WEEK -> current.plusWeeks(1)
            TWO_WEEKS -> current.plusWeeks(2)
            THREE_WEEKS -> current.plusWeeks(3)
            MONTH -> current.plusMonths(1)
        }

        companion object {
            fun fromStored(value: String): Cadence = entries.firstOrNull { it.name == value } ?: WEEK
        }
    }

    private fun key(profileId: Long, suffix: String): String = "nutrition_auto_${profileId}_$suffix"

    private companion object {
        const val PREFS_NAME = "nutrition_auto_generation"
        const val KEY_ENABLED = "enabled"
        const val KEY_CADENCE = "cadence"
    }
}
