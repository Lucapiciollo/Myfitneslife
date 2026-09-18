package com.myfitai.app.data.repository

import com.myfitai.app.data.local.dao.AiJobResultDao
import com.myfitai.app.data.local.entity.AiJobResultEntity
import com.myfitai.app.domain.ai.AiJobType
import kotlinx.coroutines.flow.Flow

class AiJobResultRepository(private val dao: AiJobResultDao) {
    suspend fun recordSuccess(profileId: Long, type: AiJobType, jobKey: String, payloadJson: String?, provider: String?) {
        dao.upsert(
            AiJobResultEntity(
                profileId = profileId,
                jobType = type.name,
                jobKey = jobKey,
                status = AiJobResultEntity.STATUS_SUCCEEDED,
                payloadJson = payloadJson,
                errorMessage = null,
                provider = provider,
                consumed = false,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        )
        dao.trim(profileId, MAX_RESULTS_PER_PROFILE)
    }

    suspend fun recordFailure(profileId: Long, type: AiJobType, jobKey: String, errorMessage: String) {
        dao.upsert(
            AiJobResultEntity(
                profileId = profileId,
                jobType = type.name,
                jobKey = jobKey,
                status = AiJobResultEntity.STATUS_FAILED,
                payloadJson = null,
                errorMessage = errorMessage.take(500),
                provider = null,
                consumed = false,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        )
        dao.trim(profileId, MAX_RESULTS_PER_PROFILE)
    }

    suspend fun find(profileId: Long, type: AiJobType, jobKey: String): AiJobResultEntity? =
        dao.find(profileId, type.name, jobKey)

    fun observe(profileId: Long, type: AiJobType, jobKey: String): Flow<AiJobResultEntity?> =
        dao.observe(profileId, type.name, jobKey)

    suspend fun markConsumed(profileId: Long, type: AiJobType, jobKey: String) =
        dao.markConsumed(profileId, type.name, jobKey)

    suspend fun deleteByProfile(profileId: Long) = dao.deleteByProfile(profileId)

    private companion object {
        const val MAX_RESULTS_PER_PROFILE = 40
    }
}
