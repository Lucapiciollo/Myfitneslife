package com.myfitai.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "nutrition_recovery_events",
    indices = [
        Index("profileId"),
        Index(value = ["profileId", "status"]),
        Index(value = ["profileId", "expiresEpochDay"]),
    ],
)
data class NutritionRecoveryEventEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val createdAtEpochMillis: Long,
    val eventEpochDay: Long,
    val source: String,
    val originalExcessKcal: Int,
    val remainingKcal: Int,
    val recoveredKcal: Int,
    val expiresEpochDay: Long,
    val status: String,
    val reason: String,
)

@Entity(
    tableName = "nutrition_recovery_withdrawals",
    primaryKeys = ["profileId", "withdrawalEpochDay"],
    indices = [Index("eventId"), Index("profileId"), Index("withdrawalEpochDay")],
)
data class NutritionRecoveryWithdrawalEntity(
    val profileId: Long,
    val withdrawalEpochDay: Long,
    val eventId: Long,
    val plannedRecoveryKcal: Int,
    val confirmedRecoveryKcal: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
