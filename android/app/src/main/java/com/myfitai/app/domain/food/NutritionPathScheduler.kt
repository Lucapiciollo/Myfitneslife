package com.myfitai.app.domain.food

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class NutritionPathScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    fun enqueue(profileId: Long, jobKey: String) {
        val request = OneTimeWorkRequestBuilder<NutritionPathWorker>().setInputData(workDataOf(NutritionPathWorker.KEY_PROFILE_ID to profileId, NutritionPathWorker.KEY_JOB_KEY to jobKey))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        workManager.enqueueUniqueWork(name(profileId, jobKey), ExistingWorkPolicy.KEEP, request)
    }
    fun observe(profileId: Long, jobKey: String): Flow<WorkInfo?> = workManager.getWorkInfosForUniqueWorkFlow(name(profileId, jobKey)).map { it.lastOrNull() }
    private fun name(profileId: Long, jobKey: String) = "nutrition-path-$profileId-$jobKey"
}
