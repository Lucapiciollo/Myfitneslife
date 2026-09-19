package com.myfitai.app

import android.app.Application
import com.myfitai.app.data.AppDataContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyFitAiApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val data = AppDataContainer.get(this)

        appScope.launch {
            runCatching { data.notificationScheduler.refresh() }
            data.activeProfileStore.currentIdOrNull()?.let { profileId ->
                runCatching { data.nutritionPlanScheduler.reschedule(profileId) }
            }
        }
        appScope.launch {
            data.activeProfileStore.activeProfileId.collect { profileId ->
                runCatching { data.notificationScheduler.refresh() }
                if (profileId > 0L) {
                    runCatching { data.nutritionPlanScheduler.reschedule(profileId) }
                }
            }
        }
    }
}
