package com.myfitai.app.data.profile

import android.content.Context
import com.myfitai.app.domain.calculation.TrainingEnergyPlanner.ActivityBasis
import com.myfitai.app.domain.calculation.TrainingProgram

/** Per-profile recurring weekly training program. Stored locally and never sent anywhere by itself. */
class TrainingProgramPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(profileId: Long): TrainingProgram = TrainingProgram.fromStored(
        mask = prefs.getInt(key(KEY_DAYS, profileId), 0),
        durationMinutes = prefs.getInt(key(KEY_DURATION, profileId), TrainingProgram.DEFAULT_DURATION_MINUTES),
        intensityName = prefs.getString(key(KEY_INTENSITY, profileId), null),
        startMinutes = prefs.getInt(key(KEY_START, profileId), NO_START),
    )

    /**
     * @param synchronous write to disk before returning. Normal app flows use the default asynchronous write;
     * tests that restore state right before the process ends must pass true or the write can be lost.
     */
    fun set(profileId: Long, program: TrainingProgram, synchronous: Boolean = false) {
        val editor = prefs.edit()
            .putInt(key(KEY_DAYS, profileId), program.daysMask)
            .putInt(key(KEY_DURATION, profileId), program.durationMinutes)
            .putString(key(KEY_INTENSITY, profileId), program.intensity.name)
            .putInt(key(KEY_START, profileId), program.startMinutes ?: NO_START)
        if (synchronous) editor.commit() else editor.apply()
    }

    /**
     * What the stored activity level means. Profiles created before the schedule existed chose a level that
     * already counted training, so they default to [ActivityBasis.LEGACY_INCLUDES_TRAINING] until the user
     * confirms the level again from the profile screen.
     */
    fun activityBasis(profileId: Long): ActivityBasis =
        ActivityBasis.entries.firstOrNull { it.name == prefs.getString(key(KEY_BASIS, profileId), null) }
            ?: ActivityBasis.LEGACY_INCLUDES_TRAINING

    fun setActivityBasis(profileId: Long, basis: ActivityBasis, synchronous: Boolean = false) {
        val editor = prefs.edit().putString(key(KEY_BASIS, profileId), basis.name)
        if (synchronous) editor.commit() else editor.apply()
    }

    fun clearProfile(profileId: Long, synchronous: Boolean = false) {
        val editor = prefs.edit()
            .remove(key(KEY_BASIS, profileId))
            .remove(key(KEY_DAYS, profileId))
            .remove(key(KEY_DURATION, profileId))
            .remove(key(KEY_INTENSITY, profileId))
            .remove(key(KEY_START, profileId))
        if (synchronous) editor.commit() else editor.apply()
    }

    private fun key(name: String, profileId: Long) = "${name}_$profileId"

    private companion object {
        const val PREFS_NAME = "training_program_preferences"
        const val KEY_DAYS = "training_days"
        const val KEY_DURATION = "training_duration_minutes"
        const val KEY_INTENSITY = "training_intensity"
        const val KEY_START = "training_start_minutes"
        const val KEY_BASIS = "training_activity_basis"
        const val NO_START = -1
    }
}
