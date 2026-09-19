package com.myfitai.app.data.repository

import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.AiUsageRecordEntity

class AiUsageRepository(private val db: MyFitAiDatabase) {
    suspend fun insert(value: AiUsageRecordEntity): Long = db.aiUsageDao().insert(value)
    suspend fun totalCostNanos(provider: String): Long = db.aiUsageDao().totalCostNanos(provider)
    suspend fun costNanosBetween(provider: String, fromInclusive: Long, toExclusive: Long): Long =
        db.aiUsageDao().costNanosBetween(provider, fromInclusive, toExclusive)
    suspend fun count(provider: String): Long = db.aiUsageDao().count(provider)
    suspend fun latest(provider: String): AiUsageRecordEntity? = db.aiUsageDao().latest(provider)
}
