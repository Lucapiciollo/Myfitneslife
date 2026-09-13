package com.myfitai.app.domain.food

import com.myfitai.app.data.local.entity.WorkoutEntity
import java.util.Locale

/** Deterministic local classifier: AI never decides whether the profile is sports-oriented. */
object SportsNutritionClassifier {
    enum class Mode { NORMAL, SPORT }

    fun classify(activityLevel: String?, workouts: List<WorkoutEntity>): Mode {
        val normalized = activityLevel.orEmpty().trim().lowercase(Locale.ITALIAN)
        val trainingSessions = workouts.count { !it.isRestDay }
        val declaredHighActivity = normalized.contains("molto") || normalized.contains("estremamente")
        val declaredModerate = normalized.contains("moderatamente")

        return if (
            declaredHighActivity ||
            trainingSessions >= 3 ||
            (declaredModerate && trainingSessions >= 2)
        ) Mode.SPORT else Mode.NORMAL
    }
}
