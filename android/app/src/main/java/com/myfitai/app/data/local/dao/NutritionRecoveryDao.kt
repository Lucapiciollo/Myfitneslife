package com.myfitai.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.myfitai.app.data.local.entity.NutritionRecoveryEventEntity
import com.myfitai.app.data.local.entity.NutritionRecoveryWithdrawalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NutritionRecoveryDao {
    @Query("SELECT * FROM nutrition_recovery_events WHERE profileId = :profileId ORDER BY createdAtEpochMillis DESC, id DESC")
    fun observeEvents(profileId: Long): Flow<List<NutritionRecoveryEventEntity>>

    @Query("SELECT * FROM nutrition_recovery_events WHERE profileId = :profileId AND status = 'ACTIVE' AND expiresEpochDay >= :epochDay ORDER BY expiresEpochDay ASC, id ASC")
    suspend fun activeEvents(profileId: Long, epochDay: Long): List<NutritionRecoveryEventEntity>

    @Query("SELECT * FROM nutrition_recovery_events WHERE profileId = :profileId AND eventEpochDay = :epochDay AND source = :source AND status = 'ACTIVE' LIMIT 1")
    suspend fun activeEventForSource(profileId: Long, epochDay: Long, source: String): NutritionRecoveryEventEntity?

    @Insert
    suspend fun insertEvent(value: NutritionRecoveryEventEntity): Long

    @Update
    suspend fun updateEvent(value: NutritionRecoveryEventEntity)

    @Query("SELECT * FROM nutrition_recovery_events WHERE id = :eventId LIMIT 1")
    suspend fun getEvent(eventId: Long): NutritionRecoveryEventEntity?

    @Query("DELETE FROM nutrition_recovery_events WHERE profileId = :profileId")
    suspend fun deleteEventsByProfile(profileId: Long)

    @Query("UPDATE nutrition_recovery_events SET status = 'EXPIRED' WHERE profileId = :profileId AND status = 'ACTIVE' AND expiresEpochDay < :epochDay")
    suspend fun expireDue(profileId: Long, epochDay: Long)

    @Query("SELECT * FROM nutrition_recovery_withdrawals WHERE profileId = :profileId AND withdrawalEpochDay = :epochDay LIMIT 1")
    suspend fun getWithdrawal(profileId: Long, epochDay: Long): NutritionRecoveryWithdrawalEntity?

    @Query("SELECT * FROM nutrition_recovery_withdrawals WHERE profileId = :profileId AND withdrawalEpochDay <= :epochDay ORDER BY withdrawalEpochDay DESC LIMIT 1")
    suspend fun latestWithdrawalAtOrBefore(profileId: Long, epochDay: Long): NutritionRecoveryWithdrawalEntity?

    @Query("SELECT COALESCE(SUM(plannedRecoveryKcal), 0) FROM nutrition_recovery_withdrawals WHERE profileId = :profileId AND withdrawalEpochDay < :epochDay")
    suspend fun plannedBefore(profileId: Long, epochDay: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWithdrawal(value: NutritionRecoveryWithdrawalEntity)

    @Query("DELETE FROM nutrition_recovery_withdrawals WHERE profileId = :profileId")
    suspend fun deleteWithdrawalsByProfile(profileId: Long)

    @Query("SELECT * FROM nutrition_recovery_withdrawals WHERE profileId = :profileId ORDER BY withdrawalEpochDay DESC")
    fun observeWithdrawals(profileId: Long): Flow<List<NutritionRecoveryWithdrawalEntity>>
}
