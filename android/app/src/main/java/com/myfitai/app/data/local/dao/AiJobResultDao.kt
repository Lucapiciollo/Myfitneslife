package com.myfitai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.myfitai.app.data.local.entity.AiJobResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiJobResultDao {
    @Query("SELECT * FROM ai_job_results WHERE profileId = :profileId AND jobType = :jobType AND jobKey = :jobKey LIMIT 1")
    suspend fun find(profileId: Long, jobType: String, jobKey: String): AiJobResultEntity?

    @Query("SELECT * FROM ai_job_results WHERE profileId = :profileId AND jobType = :jobType AND jobKey = :jobKey LIMIT 1")
    fun observe(profileId: Long, jobType: String, jobKey: String): Flow<AiJobResultEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: AiJobResultEntity)

    @Query("UPDATE ai_job_results SET consumed = 1 WHERE profileId = :profileId AND jobType = :jobType AND jobKey = :jobKey")
    suspend fun markConsumed(profileId: Long, jobType: String, jobKey: String)

    @Query("DELETE FROM ai_job_results WHERE profileId = :profileId")
    suspend fun deleteByProfile(profileId: Long)

    /** Keeps the table bounded: only the most recent results per profile are worth reattaching to. */
    @Query(
        """
        DELETE FROM ai_job_results
        WHERE profileId = :profileId
          AND id NOT IN (SELECT id FROM ai_job_results WHERE profileId = :profileId ORDER BY updatedAtEpochMillis DESC LIMIT :keep)
        """
    )
    suspend fun trim(profileId: Long, keep: Int)
}
