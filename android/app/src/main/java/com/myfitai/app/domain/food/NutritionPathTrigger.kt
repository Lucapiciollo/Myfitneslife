package com.myfitai.app.domain.food

import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.UserProfileRepository
import kotlinx.coroutines.flow.first

class NutritionPathTrigger(
    private val profiles: UserProfileRepository,
    private val bia: BiaRepository,
    private val body: BodyMeasurementRepository,
    private val scheduler: NutritionPathScheduler,
) {
    suspend fun maybeEnqueue(profileId: Long) {
        if (profiles.get(profileId) == null) return
        val biaRows = bia.all(profileId).first()
        val bodyRows = body.all(profileId).first()
        if (biaRows.isEmpty() || bodyRows.isEmpty()) return
        val key = "${biaRows.maxOf { it.measuredAtEpochMillis }}-${bodyRows.maxOf { it.measuredAtEpochMillis }}"
        scheduler.enqueue(profileId, key)
    }
}
