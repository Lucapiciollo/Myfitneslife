package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.ProfileCalculationMapper

/**
 * Deterministic local classifier: AI never decides whether the profile is sports-oriented.
 * Sports mode depends ONLY on the profile's activity level (very/extremely active, stored values included).
 */
object SportsNutritionClassifier {
    enum class Mode { NORMAL, SPORT }

    fun classify(activityLevel: String?): Mode = when (ProfileCalculationMapper.activity(activityLevel)) {
        LocalCalculationEngine.ActivityLevel.VERY_ACTIVE,
        LocalCalculationEngine.ActivityLevel.EXTREME,
        -> Mode.SPORT
        else -> Mode.NORMAL
    }
}
