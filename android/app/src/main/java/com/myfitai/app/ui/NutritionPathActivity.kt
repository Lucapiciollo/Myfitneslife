package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import com.google.android.material.button.MaterialButton
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.NutritionPathContract
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.myfitai.app.domain.ai.AiJobWorker

class NutritionPathActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val jobKey by lazy { intent.getStringExtra(EXTRA_JOB_KEY).orEmpty() }
    private var selectedPath: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nutrition_path)
        bindBack()
        findViewById<MaterialButton>(R.id.chooseButton).setOnClickListener { choose(selectedPath) }
        observe()
    }
    private fun observe() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        if (jobKey.isBlank()) { status("Nessun suggerimento disponibile."); return }
        lifecycleScope.launch {
            data.nutritionPathScheduler.observe(profileId, jobKey).collect { info ->
                when (info?.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> status("Analisi in corso…")
                    WorkInfo.State.SUCCEEDED -> render(info.outputData.getString(com.myfitai.app.domain.food.NutritionPathAiJobHandler.KEY_PAYLOAD))
                    WorkInfo.State.FAILED -> status(info.outputData.getString(AiJobWorker.KEY_ERROR) ?: "Suggerimento non disponibile")
                    else -> Unit
                }
            }
        }
    }
    private fun render(payload: String?) {
        if (payload == null) { status("Risultato non disponibile"); return }
        val root = JSONObject(payload)
        val recommendation = root.getJSONObject("recommendation")
        selectedPath = recommendation.getString("path")
        findViewById<TextView>(R.id.recommendationPath).text = label(selectedPath!!)
        findViewById<TextView>(R.id.recommendationReason).text = recommendation.getString("reason")
        findViewById<TextView>(R.id.recommendationConfidence).text = "Confidenza indicativa ${recommendation.getDouble("confidence")}" 
        val alternatives = findViewById<LinearLayout>(R.id.alternativesContainer)
        alternatives.removeAllViews()
        val array = root.optJSONArray("alternatives") ?: return
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            alternatives.addView(MaterialButton(this).apply { text = label(item.getString("path")); isAllCaps = false; setOnClickListener { choose(item.getString("path")) } })
        }
    }
    private fun choose(path: String?) {
        if (path == null) return
        lifecycleScope.launch {
            val id = data.activeProfileStore.currentIdOrNull() ?: return@launch
            val profile = data.userProfileRepository.get(id) ?: return@launch
            data.userProfileRepository.update(profile.copy(goal = canonical(path), updatedAtEpochMillis = System.currentTimeMillis()))
            openFoodPlan(java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY).toEpochDay())
            finish()
        }
    }
    private fun status(text: String) { findViewById<TextView>(R.id.pathStatus).text = text }
    private fun label(path: String) = mapOf("RECOMPOSITION" to "Ricomposizione", "WEIGHT_LOSS" to "Dimagrimento", "MAINTENANCE" to "Mantenimento", "MUSCLE_GAIN" to "Aumento massa muscolare", "PERFORMANCE" to "Performance")[path] ?: path
    private fun canonical(path: String) = label(path)
    companion object { const val EXTRA_JOB_KEY = "nutrition_path_job_key" }
}
