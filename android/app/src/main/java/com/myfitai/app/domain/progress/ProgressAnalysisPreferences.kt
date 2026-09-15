package com.myfitai.app.domain.progress

import android.content.Context
import java.util.concurrent.TimeUnit

/** Stores scheduling and latest successful analysis metadata. No health measurements are duplicated here. */
class ProgressAnalysisPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var intervalWeeks: Int
        get() = prefs.getInt(KEY_INTERVAL_WEEKS, DEFAULT_INTERVAL_WEEKS).coerceIn(MIN_INTERVAL_WEEKS, MAX_INTERVAL_WEEKS)
        set(value) { prefs.edit().putInt(KEY_INTERVAL_WEEKS, value.coerceIn(MIN_INTERVAL_WEEKS, MAX_INTERVAL_WEEKS)).apply() }

    fun lastSuccessEpochMillis(profileId: Long): Long? = prefs.getLong(key(profileId, "last_success"), 0L).takeIf { it > 0L }
    fun lastClassification(profileId: Long): String? = prefs.getString(key(profileId, "classification"), null)
    fun lastConfidence(profileId: Long): String? = prefs.getString(key(profileId, "confidence"), null)
    fun lastSummary(profileId: Long): String? = prefs.getString(key(profileId, "summary"), null)
    fun lastProvider(profileId: Long): String? = prefs.getString(key(profileId, "provider"), null)
    fun lastModel(profileId: Long): String? = prefs.getString(key(profileId, "model"), null)

    fun recordSuccess(
        profileId: Long,
        atEpochMillis: Long,
        response: ProgressAnalysisCompactContract.Response,
        provider: String,
        model: String,
    ) {
        prefs.edit()
            .putLong(key(profileId, "last_success"), atEpochMillis)
            .putString(key(profileId, "classification"), response.classification.name)
            .putString(key(profileId, "confidence"), response.confidence.name)
            .putString(key(profileId, "summary"), response.summary)
            .putString(key(profileId, "provider"), provider)
            .putString(key(profileId, "model"), model)
            .apply()
    }

    fun nextDueEpochMillis(profileId: Long): Long? = lastSuccessEpochMillis(profileId)?.plus(intervalMillis())

    fun remainingMillis(profileId: Long, nowEpochMillis: Long): Long? =
        nextDueEpochMillis(profileId)?.let { (it - nowEpochMillis).coerceAtLeast(0L) }

    fun clearProfile(profileId: Long) {
        prefs.edit().apply {
            listOf("last_success", "classification", "confidence", "summary", "provider", "model")
                .forEach { remove(key(profileId, it)) }
        }.apply()
    }

    private fun intervalMillis(): Long = TimeUnit.DAYS.toMillis(intervalWeeks.toLong() * 7L)
    private fun key(profileId: Long, suffix: String) = "profile_${profileId}_$suffix"

    companion object {
        const val DEFAULT_INTERVAL_WEEKS = 4
        const val MIN_INTERVAL_WEEKS = 1
        const val MAX_INTERVAL_WEEKS = 12
        val SUGGESTED_INTERVALS = intArrayOf(1, 2, 4, 6, 8, 12)
        private const val PREFS = "progress_analysis_preferences"
        private const val KEY_INTERVAL_WEEKS = "interval_weeks"
    }
}
