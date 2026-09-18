package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.ai.AiJobState
import com.myfitai.app.domain.ai.AiJobType
import kotlinx.coroutines.launch

class NutritionPathActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private var selectedPath: String? = null
    private val jobKey: String by lazy { intent.getStringExtra(EXTRA_JOB_KEY).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nutrition_path)
        bindBack()
        bindBottom(com.myfitai.app.navigation.BottomNavBinder.Tab.MORE)
        findViewById<MaterialButton>(R.id.chooseButton).setOnClickListener { choosePath(selectedPath) }
        reattach()
    }

    private fun reattach() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        if (jobKey.isBlank()) {
            findViewById<TextView>(R.id.pathStatus).text = "Nessuna analisi disponibile. Richiedi un nuovo suggerimento dalle Impostazioni."
            return
        }
        lifecycleScope.launch {
            data.aiJobScheduler.observe(AiJobType.NUTRITION_PATH, profileId, jobKey).collect { state ->
                when (state) {
                    AiJobState.Idle -> loadResult(profileId, jobKey)
                    AiJobState.Running -> findViewById<TextView>(R.id.pathStatus).text = "Analisi in corso…"
                    is AiJobState.Succeeded -> { data.aiJobScheduler.consume(state.id); loadResult(profileId, jobKey) }
                    is AiJobState.Failed -> { data.aiJobScheduler.consume(state.id); findViewById<TextView>(R.id.pathStatus).text = state.message }
                }
            }
        }
    }

    private suspend fun loadResult(profileId: Long, key: String) {
        val row = data.aiJobResultRepository.find(profileId, AiJobType.NUTRITION_PATH, key)
        val json = row?.payloadJson ?: return
        val root = org.json.JSONObject(json)
        val recommendation = root.getJSONObject("recommendation")
        selectedPath = recommendation.getString("path")
        findViewById<TextView>(R.id.recommendationPath).text = label(selectedPath!!)
        findViewById<TextView>(R.id.recommendationReason).text = recommendation.getString("reason")
        findViewById<TextView>(R.id.recommendationConfidence).text = "Confidenza indicativa ${"%.1f".format(java.util.Locale.ITALIAN, recommendation.getDouble("confidence"))}"
        val container = findViewById<LinearLayout>(R.id.alternativesContainer)
        container.removeAllViews()
        val alternatives = root.optJSONArray("alternatives") ?: return
        for (index in 0 until alternatives.length()) {
            val item = alternatives.getJSONObject(index)
            val button = MaterialButton(this).apply {
                text = "${label(item.getString("path"))}\n${item.getString("reason")}"
                isAllCaps = false
                setOnClickListener { choosePath(item.getString("path")) }
            }
            container.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun choosePath(path: String?) {
        if (path.isNullOrBlank()) return
        val profile = data.activeProfileStore.currentIdOrNull() ?: return
        lifecycleScope.launch {
            val current = data.userProfileRepository.get(profile) ?: return@launch
            data.userProfileRepository.update(current.copy(goal = canonicalGoal(path), updatedAtEpochMillis = System.currentTimeMillis()))
            val weekStart = java.time.LocalDate.now()
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                .toEpochDay()
            data.aiJobScheduler.enqueue(AiJobType.WEEKLY_PLAN, profile, weekStart.toString())
            if (openFoodPlan(weekStart)) finish()
        }
    }

    private fun label(path: String): String = when (path) {
        "RECOMPOSITION" -> "Ricomposizione corporea"
        "WEIGHT_LOSS" -> "Perdita di peso graduale"
        "MAINTENANCE" -> "Mantenimento"
        "MUSCLE_GAIN" -> "Aumento massa muscolare"
        "PERFORMANCE" -> "Performance"
        else -> path.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun canonicalGoal(path: String): String = when (path) {
        "RECOMPOSITION" -> "Ricomposizione"
        "WEIGHT_LOSS" -> "Dimagrimento"
        "MAINTENANCE" -> "Mantenimento"
        "MUSCLE_GAIN" -> "Aumento massa muscolare"
        "PERFORMANCE" -> "Performance"
        else -> path
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_JOB_KEY = "nutrition_path_job_key"
    }
}
