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
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType
import java.util.concurrent.TimeUnit

class NutritionPathScheduler(private val context: Context, private val aiJobScheduler: AiJobScheduler) {
    fun enqueue(profileId: Long, jobKey: String) {
        aiJobScheduler.enqueue(AiJobType.NUTRITION_PATH, profileId, jobKey)
    }
    fun observe(profileId: Long, jobKey: String): Flow<WorkInfo?> = aiJobScheduler.observe(AiJobType.NUTRITION_PATH, profileId, jobKey)
}
