package com.myfitai.app.qa

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.ai.AiJobState
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.notifications.AiJobNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * QA-only entry point that enqueues a background AI job exactly like the app UI does, so the
 * WorkManager -> provider -> validation -> persistence -> notification path can be exercised from
 * adb without UI automation.
 *
 * `--es job_type PROGRESS_ANALYSIS` selects the operation; `--ez notify_only true` only fires the
 * notification, to verify channels without spending provider quota.
 */
class GeneratePlanNowActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { textSize = 16f; setPadding(32, 32, 32, 32); text = "Avvio operazione IA…" }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(status) })

        val data = AppDataContainer.get(this)
        val profileId = data.activeProfileStore.currentIdOrNull()
        if (profileId == null || profileId <= 0L) {
            status.text = "Nessun profilo attivo"
            Log.e(TAG, "no active profile")
            return
        }
        val type = AiJobType.fromNameOrNull(intent?.getStringExtra(EXTRA_JOB_TYPE)) ?: AiJobType.WEEKLY_PLAN
        val week = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val jobKey = when (type) {
            AiJobType.WEEKLY_PLAN -> week.toEpochDay().toString()
            else -> LocalDate.now().toEpochDay().toString()
        }

        if (intent?.getBooleanExtra(EXTRA_NOTIFY_ONLY, false) == true) {
            AiJobNotifier.notifySuccess(this, type, profileId, jobKey, "QA · notify-only")
            status.text = "Notifica inviata (${type.name})"
            Log.i(TAG, "notify-only sent type=${type.name} profile=$profileId key=$jobKey")
            return
        }

        Log.i(TAG, "enqueue type=${type.name} profile=$profileId key=$jobKey")
        data.aiJobScheduler.enqueue(type, profileId, jobKey)

        scope.launch {
            data.aiJobScheduler.observe(type, profileId, jobKey).collect { state ->
                val text = when (state) {
                    AiJobState.Idle -> "IDLE"
                    AiJobState.Running -> "RUNNING"
                    is AiJobState.Succeeded -> "SUCCEEDED provider=${state.provider}"
                    is AiJobState.Failed -> "FAILED ${state.message}"
                }
                status.text = "${type.name}: $text"
                Log.i(TAG, "type=${type.name} state=$text")
            }
        }
    }

    private companion object {
        const val TAG = "MyFitAI.QA.Generate"
        const val EXTRA_NOTIFY_ONLY = "notify_only"
        const val EXTRA_JOB_TYPE = "job_type"
    }
}
