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
        appScope.launch {
            runCatching { AppDataContainer.get(this@MyFitAiApplication).notificationScheduler.refresh() }
        }
    }
}
