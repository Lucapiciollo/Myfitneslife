package com.myfitai.app.domain.food

import com.myfitai.app.data.local.entity.WorkoutEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SportsNutritionClassifierTest {

    @Test
    fun sedentaryWithoutWorkouts_isNormal() {
        val mode = SportsNutritionClassifier.classify("sedentario", emptyList())
        assertEquals(SportsNutritionClassifier.Mode.NORMAL, mode)
    }

    @Test
    fun moderateWithStructuredWorkouts_isSport() {
        val mode = SportsNutritionClassifier.classify(
            "moderatamente attivo",
            listOf(
                workout(1_000L, "PESI", "Upper", false),
                workout(2_000L, "PESI", "Lower", false),
            ),
        )
        assertEquals(SportsNutritionClassifier.Mode.SPORT, mode)
    }

    @Test
    fun highActivityWithoutWorkouts_isSport() {
        val mode = SportsNutritionClassifier.classify("molto attivo", emptyList())
        assertEquals(SportsNutritionClassifier.Mode.SPORT, mode)
    }

    private fun workout(startedAt: Long, type: String, title: String, rest: Boolean) = WorkoutEntity(
        id = 0,
        profileId = 1,
        startedAtEpochMillis = startedAt,
        type = type,
        title = title,
        durationMinutes = if (rest) null else 60,
        isRestDay = rest,
        notes = null,
    )
}
