package com.myfitai.app.data.profile

import android.content.Context
import java.util.concurrent.TimeUnit

/** Per-profile automation switches; manual actions never depend on these values. */
class AiAutomationPreferences(context: Context) {
    enum class Feature { BIA_PROGRESS_COACH, PROGRESS_ANALYSIS, BODY_PROPORTIONS, WEEKLY_REVIEW }
    enum class Frequency { WEEKLY, MONTHLY }
    data class Config(val enabled: Boolean, val frequency: Frequency, val notificationsEnabled: Boolean)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(profileId: Long, feature: Feature): Config = Config(
        enabled = prefs.getBoolean(key(profileId, feature, ENABLED), false),
        frequency = runCatching { Frequency.valueOf(prefs.getString(key(profileId, feature, FREQUENCY), Frequency.WEEKLY.name)!!) }
            .getOrDefault(Frequency.WEEKLY),
        notificationsEnabled = prefs.getBoolean(key(profileId, feature, NOTIFICATIONS), true),
    )

    fun setEnabled(profileId: Long, feature: Feature, value: Boolean) = prefs.edit().putBoolean(key(profileId, feature, ENABLED), value).apply()
    fun setFrequency(profileId: Long, feature: Feature, value: Frequency) = prefs.edit().putString(key(profileId, feature, FREQUENCY), value.name).apply()
    fun setNotificationsEnabled(profileId: Long, feature: Feature, value: Boolean) = prefs.edit().putBoolean(key(profileId, feature, NOTIFICATIONS), value).apply()

    fun markRun(profileId: Long, feature: Feature, atEpochMillis: Long = System.currentTimeMillis()) =
        prefs.edit().putLong(key(profileId, feature, LAST_RUN), atEpochMillis).apply()

    fun lastRun(profileId: Long, feature: Feature): Long? = prefs.getLong(key(profileId, feature, LAST_RUN), 0L).takeIf { it > 0L }

    fun nextDue(profileId: Long, feature: Feature): Long? = lastRun(profileId, feature)?.let { run ->
        run + if (get(profileId, feature).frequency == Frequency.MONTHLY) TimeUnit.DAYS.toMillis(30) else TimeUnit.DAYS.toMillis(7)
    }

    fun clearProfile(profileId: Long) {
        prefs.edit().apply {
            Feature.entries.forEach { feature ->
                listOf(ENABLED, FREQUENCY, NOTIFICATIONS, LAST_RUN).forEach { remove(key(profileId, feature, it)) }
            }
        }.apply()
    }

    private fun key(profileId: Long, feature: Feature, suffix: String) = "profile_${profileId}_${feature.name.lowercase()}_$suffix"

    companion object {
        private const val PREFS = "myfitai_ai_automation_preferences"
        private const val ENABLED = "enabled"
        private const val FREQUENCY = "frequency"
        private const val NOTIFICATIONS = "notifications"
        private const val LAST_RUN = "last_run"
    }
}
