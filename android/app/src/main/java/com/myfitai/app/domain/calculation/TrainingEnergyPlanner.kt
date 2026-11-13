package com.myfitai.app.domain.calculation

/**
 * What the stored activity level means for a profile.
 *
 * The per-day program planner that used to live here is gone: the level of the profile is now the ONLY source
 * of the energy factor (TDEE = BMR x level factor). Only the [ActivityBasis] marker remains, because the profile
 * screens, backup and export still read and write it; the UI tranches will remove it together with them.
 */
object TrainingEnergyPlanner {
    enum class ActivityBasis {
        /** Chosen before workouts could be scheduled: it already counts training. */
        LEGACY_INCLUDES_TRAINING,

        /** Confirmed after the schedule existed: everyday activity only. */
        EVERYDAY_ONLY,
    }
}
