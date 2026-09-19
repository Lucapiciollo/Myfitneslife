package com.myfitai.app.qa

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.ai.AiTransportException
import com.myfitai.app.ai.GeminiByokProvider
import com.myfitai.app.domain.food.NutritionPlanContract
import com.myfitai.app.security.SecureAiCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only progressive Gemini responseSchema probe. Never logs key, prompts or response JSON. */
class GeminiSchemaProbeActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Gemini schema probe pronto"; setPadding(32, 32, 32, 32) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(Button(this@GeminiSchemaProbeActivity).apply {
                text = "RUN F FULL"
                setOnClickListener { runFullProbe() }
            })
        })
    }

    private fun runProbe() {
        status.text = "Probe in corso..."
        scope.launch {
            val result = withContext(Dispatchers.IO) { probe() }
            status.text = result
        }
    }

    private fun runFullProbe() {
        status.text = "Probe F in corso..."
        scope.launch {
            val result = withContext(Dispatchers.IO) { probeFullSchema() }
            status.text = result
        }
    }

    private suspend fun probeFullSchema(): String {
        val provider = GeminiByokProvider(SecureAiCredentialStore(this))
        val request = AiStructuredRequest(
            systemPrompt = "Return only JSON matching the schema.",
            userPrompt = "Return the smallest valid weekly-plan JSON example for this schema.",
            schemaName = NutritionPlanContract.SCHEMA_NAME,
            schemaJson = NutritionPlanContract.schemaJson,
            maxOutputTokens = 256,
            thinkingBudget = 0,
        )
        val outcome = runCatching { provider.generateStructured(request); "PASS" }.getOrElse { error ->
            when (error) {
                is AiTransportException.Http -> "FAIL_HTTP_${error.statusCode}_${error.failureKind}"
                is AiTransportException.Network -> "FAIL_NETWORK"
                is AiTransportException.InvalidResponse -> "FAIL_INVALID_RESPONSE"
                else -> "FAIL_${error.javaClass.simpleName}"
            }
        }
        Log.i("MyFitAiSchemaProbe", "case=F_FULL outcome=$outcome")
        return "F_FULL=$outcome"
    }

    private suspend fun probe(): String {
        val provider = GeminiByokProvider(SecureAiCredentialStore(this))
        val cases = listOf(
            "A" to """{"type":"object","properties":{"weekStartEpochDay":{"type":"integer"}},"required":["weekStartEpochDay"]}""",
            "B" to """{"type":"object","properties":{"weekStartEpochDay":{"type":"integer"},"days":{"type":"array","items":{"type":"object","properties":{"dateEpochDay":{"type":"integer"}},"required":["dateEpochDay"]}}},"required":["weekStartEpochDay","days"]}""",
            "C" to """{"type":"object","properties":{"weekStartEpochDay":{"type":"integer"},"days":{"type":"array","items":{"type":"object","properties":{"dateEpochDay":{"type":"integer"},"meals":{"type":"array","items":{"type":"object","properties":{"title":{"type":"string"}},"required":["title"]}}},"required":["dateEpochDay","meals"]}}},"required":["weekStartEpochDay","days"]}""",
            "D" to """{"type":"object","properties":{"weekStartEpochDay":{"type":"integer"},"days":{"type":"array","items":{"type":"object","properties":{"dateEpochDay":{"type":"integer"},"meals":{"type":"array","items":{"type":"object","properties":{"title":{"type":"string"},"ingredients":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"quantity":{"type":"number"}},"required":["name","quantity"]}}},"required":["title","ingredients"]}}},"required":["dateEpochDay","meals"]}}},"required":["weekStartEpochDay","days"]}""",
            "E" to """{"type":"object","properties":{"weekStartEpochDay":{"type":"integer"},"days":{"type":"array","items":{"type":"object","properties":{"dateEpochDay":{"type":"integer"},"supplements":{"type":"array","items":{"type":"object","properties":{"kind":{"type":"string","enum":["CREATINE","PROTEIN_POWDER"]},"dose":{"type":"number"}},"required":["kind","dose"]}},"meals":{"type":"array","items":{"type":"object","properties":{"title":{"type":"string"},"ingredients":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"quantity":{"type":"number"}},"required":["name","quantity"]}}},"required":["title","ingredients"]}}},"required":["dateEpochDay","supplements","meals"]}}},"required":["weekStartEpochDay","days"]}""",
        )
        val outcomes = mutableListOf<String>()
        for ((name, schema) in cases) {
            val request = AiStructuredRequest(
                systemPrompt = "Return only JSON matching the schema.",
                userPrompt = "Return a minimal valid example for probe $name.",
                schemaName = "myfitai_probe_$name",
                schemaJson = schema,
                maxOutputTokens = 256,
                thinkingBudget = 0,
            )
            val outcome = runCatching { provider.generateStructured(request); "PASS" }.getOrElse { error ->
                when (error) {
                    is AiTransportException.Http -> "FAIL_HTTP_${error.statusCode}_${error.failureKind}"
                    is AiTransportException.Network -> "FAIL_NETWORK"
                    is AiTransportException.InvalidResponse -> "FAIL_INVALID_RESPONSE"
                    else -> "FAIL_${error.javaClass.simpleName}"
                }
            }
            Log.i("MyFitAiSchemaProbe", "case=$name outcome=$outcome")
            outcomes += "$name=$outcome"
        }
        val fullRequest = AiStructuredRequest(
            systemPrompt = "Return only JSON matching the schema.",
            userPrompt = "Return the smallest valid weekly-plan JSON example for this schema.",
            schemaName = NutritionPlanContract.SCHEMA_NAME,
            schemaJson = NutritionPlanContract.schemaJson,
            maxOutputTokens = 256,
            thinkingBudget = 0,
        )
        val fullOutcome = runCatching { provider.generateStructured(fullRequest); "PASS" }.getOrElse { error ->
            when (error) {
                is AiTransportException.Http -> "FAIL_HTTP_${error.statusCode}_${error.failureKind}"
                is AiTransportException.Network -> "FAIL_NETWORK"
                is AiTransportException.InvalidResponse -> "FAIL_INVALID_RESPONSE"
                else -> "FAIL_${error.javaClass.simpleName}"
            }
        }
        Log.i("MyFitAiSchemaProbe", "case=F_FULL outcome=$fullOutcome")
        outcomes += "F_FULL=$fullOutcome"
        return outcomes.joinToString("\n")
    }
}
