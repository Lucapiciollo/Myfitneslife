package com.myfitai.app.domain.ai

import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.UserProfileRepository
import kotlinx.coroutines.flow.first

/** Enqueues one recommendation for the current profile/data snapshot, without duplicate work. */
class NutritionPathTrigger(
    private val profiles: UserProfileRepository,
    private val bia: BiaRepository,
    private val bodyMeasurements: BodyMeasurementRepository,
    private val scheduler: AiJobScheduler,
) {
    suspend fun maybeEnqueue(profileId: Long) {
        if (profiles.get(profileId) == null) return
        val biaRows = bia.all(profileId).first()
        val bodyRows = bodyMeasurements.all(profileId).first()
        if (biaRows.isEmpty() || bodyRows.isEmpty()) return
        val latestBia = biaRows.maxOf { it.measuredAtEpochMillis }
        val latestBody = bodyRows.maxOf { it.measuredAtEpochMillis }
        scheduler.enqueue(AiJobType.NUTRITION_PATH, profileId, "$latestBia-$latestBody")
    }
}
