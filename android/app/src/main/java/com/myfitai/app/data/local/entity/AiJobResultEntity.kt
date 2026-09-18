package com.myfitai.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Result of a background AI job.
 *
 * AI calls run in a worker and can outlive the screen that started them, so the payload has to be
 * durable: the UI reattaches by reading the row for its own job key instead of holding the result
 * in memory. `payloadJson` stays provider-neutral because it is always the canonical contract JSON
 * already validated locally.
 */
@Entity(
    tableName = "ai_job_results",
    foreignKeys = [
        ForeignKey(
            entity = UserProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["profileId", "jobType", "jobKey"], unique = true),
        Index(value = ["profileId", "jobType"]),
    ],
)
data class AiJobResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val jobType: String,
    val jobKey: String,
    val status: String,
    val payloadJson: String?,
    val errorMessage: String?,
    val provider: String?,
    val consumed: Boolean = false,
    val updatedAtEpochMillis: Long,
) {
    companion object {
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
    }
}
