package com.myfitai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.myfitai.app.data.local.entity.AiUsageRecordEntity

@Dao
interface AiUsageDao {
    @Insert
    suspend fun insert(value: AiUsageRecordEntity): Long

    @Query("SELECT COALESCE(SUM(costUsdNanos), 0) FROM ai_usage_records WHERE provider = :provider")
    suspend fun totalCostNanos(provider: String): Long

    @Query("SELECT COALESCE(SUM(costUsdNanos), 0) FROM ai_usage_records WHERE provider = :provider AND timestampEpochMillis >= :fromInclusive AND timestampEpochMillis < :toExclusive")
    suspend fun costNanosBetween(provider: String, fromInclusive: Long, toExclusive: Long): Long

    @Query("SELECT COUNT(*) FROM ai_usage_records WHERE provider = :provider")
    suspend fun count(provider: String): Long

    @Query("SELECT * FROM ai_usage_records WHERE provider = :provider ORDER BY timestampEpochMillis DESC, id DESC LIMIT 1")
    suspend fun latest(provider: String): AiUsageRecordEntity?
}
