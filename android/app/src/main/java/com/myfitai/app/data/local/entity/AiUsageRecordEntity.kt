package com.myfitai.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable cost snapshot for a successful provider response. */
@Entity(
    tableName = "ai_usage_records",
    indices = [Index("provider"), Index("model"), Index("timestampEpochMillis")],
)
data class AiUsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMillis: Long,
    val provider: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val thoughtsTokens: Long,
    val cachedTokens: Long,
    val totalTokens: Long?,
    val inputUsdPerMillion: String,
    val outputUsdPerMillion: String,
    val cachedInputUsdPerMillion: String,
    val costUsdNanos: Long,
    val pricingSource: String,
    val pricingEffectiveDate: String,
)
