package com.myfitai.app.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import com.google.android.material.button.MaterialButton
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.domain.food.NutritionPathAiJobHandler
import kotlinx.coroutines.launch
import org.json.JSONObject

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
        val profileId = data.activeProfileStore.currentIdOrNull()
        if (profileId == null || jobKey.isBlank()) {
            showManualFallback("Non posso generare il consiglio automatico. Puoi comunque scegliere l'obiettivo.")
            return
        }
        lifecycleScope.launch {
            data.nutritionPathScheduler.observe(profileId, jobKey).collect { info ->
                when (info?.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> status("Analizzo il profilo…")
                    WorkInfo.State.SUCCEEDED -> render(info.outputData.getString(NutritionPathAiJobHandler.KEY_PAYLOAD))
                    WorkInfo.State.FAILED -> showManualFallback(
                        info.outputData.getString(AiJobWorker.KEY_ERROR)
                            ?: "Consiglio automatico non disponibile. Puoi scegliere manualmente."
                    )
                    else -> Unit
                }
            }
        }
    }

    private fun render(payload: String?) {
        if (payload == null) {
            showManualFallback("Risultato non disponibile. Puoi scegliere manualmente.")
            return
        }

        val root = JSONObject(payload)
        val recommendation = root.getJSONObject("recommendation")
        selectedPath = recommendation.getString("path")

        findViewById<TextView>(R.id.recommendationPath).text = label(selectedPath!!)
        findViewById<TextView>(R.id.recommendationReason).text = recommendation.getString("reason")
        findViewById<TextView>(R.id.recommendationConfidence).text =
            "Confidenza ${confidenceLabel(recommendation.getDouble("confidence"))}"

        val hasBia = root.optBoolean("hasBia", false)
        val hasBody = root.optBoolean("hasBodyMeasurements", false)
        status(
            when {
                hasBia && hasBody -> "Consiglio basato su profilo, BIA e misure corporee."
                hasBia -> "Consiglio basato su profilo e BIA. Le circonferenze potranno affinare le valutazioni future."
                hasBody -> "Consiglio basato su profilo e misure corporee. Una BIA potrà affinare la valutazione."
                else -> "Consiglio iniziale basato sui dati del profilo. Puoi aggiungere una BIA in seguito per affinare la valutazione."
            }
        )

        val alternatives = findViewById<LinearLayout>(R.id.alternativesContainer)
        alternatives.removeAllViews()
        val array = root.optJSONArray("alternatives")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                addAlternativeButton(
                    container = alternatives,
                    path = item.getString("path"),
                    reason = item.optString("reason"),
                )
            }
        }

        findViewById<MaterialButton>(R.id.chooseButton).apply {
            isEnabled = selectedPath != null
            text = "Usa ${label(selectedPath!!)}"
        }
    }

    private fun showManualFallback(message: String) {
        selectedPath = null
        findViewById<TextView>(R.id.recommendationPath).text = "Scegli il tuo obiettivo"
        findViewById<TextView>(R.id.recommendationReason).text =
            "La scelta potrà essere rivalutata in seguito quando saranno disponibili più dati."
        findViewById<TextView>(R.id.recommendationConfidence).text = ""
        status(message)

        findViewById<MaterialButton>(R.id.chooseButton).isEnabled = false
        val alternatives = findViewById<LinearLayout>(R.id.alternativesContainer)
        alternatives.removeAllViews()
        GOAL_PATHS.forEach { path ->
            addAlternativeButton(alternatives, path, "")
        }
    }

    private fun addAlternativeButton(container: LinearLayout, path: String, reason: String) {
        container.addView(
            MaterialButton(this).apply {
                text = if (reason.isBlank()) label(path) else "${label(path)}\n$reason"
                isAllCaps = false
                setOnClickListener { choose(path) }
            }
        )
    }

    private fun choose(path: String?) {
        if (path == null) return
        lifecycleScope.launch {
            val id = data.activeProfileStore.currentIdOrNull() ?: return@launch
            val profile = data.userProfileRepository.get(id) ?: return@launch
            data.userProfileRepository.update(
                profile.copy(
                    goal = canonical(path),
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
            )
            openFoodPlan(java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY).toEpochDay())
            finish()
        }
    }

    private fun status(text: String) {
        findViewById<TextView>(R.id.pathStatus).text = text
    }

    private fun confidenceLabel(value: Double): String = when {
        value >= 0.75 -> "alta"
        value >= 0.45 -> "media"
        else -> "bassa"
    }

    private fun label(path: String) = mapOf(
        "RECOMPOSITION" to "Ricomposizione",
        "WEIGHT_LOSS" to "Dimagrimento",
        "MAINTENANCE" to "Mantenimento",
        "MUSCLE_GAIN" to "Aumento massa muscolare",
        "PERFORMANCE" to "Performance",
    )[path] ?: path

    private fun canonical(path: String) = label(path)

    companion object {
        const val EXTRA_JOB_KEY = "nutrition_path_job_key"
        private val GOAL_PATHS = listOf(
            "RECOMPOSITION",
            "WEIGHT_LOSS",
            "MAINTENANCE",
            "MUSCLE_GAIN",
            "PERFORMANCE",
        )
    }
}
