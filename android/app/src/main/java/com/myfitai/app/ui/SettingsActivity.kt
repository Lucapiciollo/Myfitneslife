package com.myfitai.app.ui

import android.os.Bundle
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.myfitai.app.R
import com.myfitai.app.ai.AiRuntimeConfig
import com.myfitai.app.ai.AiModelConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.GeminiByokProvider
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.security.AiCredentialProvider
import com.myfitai.app.security.SecureAiCredentialStore
import com.myfitai.app.ui.widgets.SettingRowView
import kotlinx.coroutines.launch

class SettingsActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()

        val settings = AiSettingsStore(this)
        val credentialStore = SecureAiCredentialStore(this)

        val useGeminiSwitch = findViewById<MaterialSwitch>(R.id.useGeminiSwitch)
        val geminiContainer = findViewById<View>(R.id.geminiKeyContainer)
        val openAiContainer = findViewById<View>(R.id.openAiKeyContainer)
        val openAiInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.openAiApiKeyInput)
        val geminiInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.geminiApiKeyInput)
        val activeProviderText = findViewById<TextView>(R.id.activeProviderText)
        val geminiStatus = findViewById<TextView>(R.id.geminiKeyStatusText)
        val openAiStatus = findViewById<TextView>(R.id.openAiKeyStatusText)
        geminiInput.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        openAiInput.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        geminiInput.isLongClickable = false
        openAiInput.isLongClickable = false
        useGeminiSwitch.isChecked = settings.useGemini

        fun render() {
            val useGemini = useGeminiSwitch.isChecked
            geminiContainer.visibility = View.VISIBLE
            openAiContainer.visibility = View.VISIBLE
            val geminiConfigured = credentialStore.exists(AiCredentialProvider.GEMINI)
            val openAiConfigured = credentialStore.exists(AiCredentialProvider.OPENAI)
            val geminiModel = settings.geminiVerifiedModel ?: AiModelConfig.GEMINI_PRIMARY
            findViewById<View>(R.id.geminiInputLayout).visibility = if (geminiConfigured) View.GONE else View.VISIBLE
            findViewById<View>(R.id.pasteGeminiKeyButton).visibility = if (geminiConfigured) View.GONE else View.VISIBLE
            findViewById<View>(R.id.saveGeminiKeyButton).visibility = if (geminiConfigured) View.GONE else View.VISIBLE
            findViewById<View>(R.id.replaceGeminiKeyButton).visibility = if (geminiConfigured) View.VISIBLE else View.GONE
            findViewById<View>(R.id.deleteGeminiKeyButton).visibility = if (geminiConfigured) View.VISIBLE else View.GONE
            findViewById<View>(R.id.openAiInputLayout).visibility = if (openAiConfigured) View.GONE else View.VISIBLE
            findViewById<View>(R.id.pasteOpenAiKeyButton).visibility = if (openAiConfigured) View.GONE else View.VISIBLE
            findViewById<View>(R.id.saveOpenAiKeyButton).visibility = if (openAiConfigured) View.GONE else View.VISIBLE
            findViewById<View>(R.id.replaceOpenAiKeyButton).visibility = if (openAiConfigured) View.VISIBLE else View.GONE
            findViewById<View>(R.id.deleteOpenAiKeyButton).visibility = if (openAiConfigured) View.VISIBLE else View.GONE
            val config = AiRuntimeConfig(
                useGemini = useGemini,
                geminiConfigured = geminiConfigured,
                openAiConfigured = openAiConfigured,
            )
            activeProviderText.text = when (val selected = config.selectedProvider()) {
                com.myfitai.app.ai.AiProviderType.GEMINI -> "Provider attivo: Gemini BYOK"
                com.myfitai.app.ai.AiProviderType.OPENAI -> "Provider attivo: OpenAI BYOK"
                com.myfitai.app.ai.AiProviderType.NOT_CONFIGURED -> if (useGemini) "Gemini selezionato ma non configurato" else "Provider OpenAI selezionato ma non configurato"
            }
            geminiStatus.text = if (geminiConfigured) {
                "Gemini configurato ✓\nModello: ${AiModelConfig.displayName(geminiModel)}"
            } else {
                "Chiave Gemini non configurata"
            }
            openAiStatus.text = if (openAiConfigured) "OpenAI configurato ✓ · chiave nascosta" else "Chiave OpenAI non configurata"
        }

        fun saveCredential(
            provider: AiCredentialProvider,
            input: com.google.android.material.textfield.TextInputEditText,
            verify: suspend (String) -> com.myfitai.app.ai.AiRawResponse,
        ) {
            val candidate = input.text?.toString()?.trim().orEmpty()
            if (candidate.isBlank()) { Toast.makeText(this, "Inserisci una API key valida", Toast.LENGTH_SHORT).show(); return }
            lifecycleScope.launch {
                runCatching {
                    val response = verify(candidate)
                    credentialStore.save(provider, candidate)
                    if (provider == AiCredentialProvider.GEMINI) settings.geminiVerifiedModel = response.model
                }
                    .onSuccess {
                        input.text?.clear()
                        clearClipboard()
                        Toast.makeText(this@SettingsActivity, "Chiave verificata e salvata in modo sicuro", Toast.LENGTH_SHORT).show()
                        render()
                    }
                    .onFailure {
                        input.text?.clear()
                        clearClipboard()
                        logVerificationOutcome(provider, it)
                        Toast.makeText(this@SettingsActivity, providerError(provider, it), Toast.LENGTH_LONG).show()
                    }
            }
        }

        findViewById<View>(R.id.saveOpenAiKeyButton).setOnClickListener {
            saveCredential(AiCredentialProvider.OPENAI, openAiInput) { candidate -> com.myfitai.app.ai.OpenAiProvider(credentialStore).verifyApiKey(candidate) }
        }
        findViewById<View>(R.id.pasteOpenAiKeyButton).setOnClickListener { pasteInto(openAiInput) }
        findViewById<View>(R.id.saveGeminiKeyButton).setOnClickListener {
            saveCredential(AiCredentialProvider.GEMINI, geminiInput) { candidate -> GeminiByokProvider(credentialStore).verifyApiKey(candidate) }
        }
        findViewById<View>(R.id.pasteGeminiKeyButton).setOnClickListener { pasteInto(geminiInput) }
        findViewById<View>(R.id.replaceGeminiKeyButton).setOnClickListener {
            geminiInput.text?.clear()
            findViewById<View>(R.id.geminiInputLayout).visibility = View.VISIBLE
            findViewById<View>(R.id.saveGeminiKeyButton).visibility = View.VISIBLE
            findViewById<View>(R.id.replaceGeminiKeyButton).visibility = View.GONE
        }
        findViewById<View>(R.id.deleteGeminiKeyButton).setOnClickListener {
            credentialStore.delete(AiCredentialProvider.GEMINI)
            settings.geminiVerifiedModel = null
            geminiInput.text?.clear()
            render()
        }
        findViewById<View>(R.id.replaceOpenAiKeyButton).setOnClickListener {
            openAiInput.text?.clear()
            findViewById<View>(R.id.openAiInputLayout).visibility = View.VISIBLE
            findViewById<View>(R.id.saveOpenAiKeyButton).visibility = View.VISIBLE
            findViewById<View>(R.id.replaceOpenAiKeyButton).visibility = View.GONE
        }
        findViewById<View>(R.id.deleteOpenAiKeyButton).setOnClickListener {
            credentialStore.delete(AiCredentialProvider.OPENAI); openAiInput.text?.clear(); render()
        }
        useGeminiSwitch.setOnCheckedChangeListener { _, checked ->
            settings.useGemini = checked
            render()
        }

        findViewById<View>(R.id.rowProfile).setOnClickListener { go(ProfileActivity::class.java) }
        findViewById<View>(R.id.rowFoodPreferences).setOnClickListener { go(ProfileEditActivity::class.java) }
        findViewById<SettingRowView>(R.id.rowUnits).apply {
            setTrailingBadge("Metrico", R.color.text_secondary)
            isClickable = false
            isFocusable = false
        }
        findViewById<View>(R.id.rowPrivacy).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Privacy e dati")
                .setMessage("I dati del profilo restano nello storage locale dell'app. I backup Android sono disabilitati e le chiavi IA sono protette tramite Android Keystore. L'export viene condiviso solo quando lo richiedi esplicitamente.")
                .setPositiveButton("OK", null)
                .show()
        }
        findViewById<View>(R.id.rowExport).setOnClickListener { go(ExportActivity::class.java) }

        bindDataDeletion()

        render()
    }


    private fun bindDataDeletion() {
        val actions = listOf(
            R.id.rowDeletePlans to DeletionAction("alimentazioni", data.dataDeletionService::deleteMealPlans),
            R.id.rowDeleteBia to DeletionAction("misure BIA", data.dataDeletionService::deleteBiaMeasurements),
            R.id.rowDeleteBody to DeletionAction("misure corporee", data.dataDeletionService::deleteBodyMeasurements),
            R.id.rowDeleteWorkouts to DeletionAction("allenamenti", data.dataDeletionService::deleteWorkouts),
            R.id.rowDeleteCheats to DeletionAction("sgarri registrati", data.dataDeletionService::deleteCheatEntries),
            R.id.rowDeleteReviews to DeletionAction("review settimanali", data.dataDeletionService::deleteWeeklyReviews),
        )
        actions.forEach { (viewId, action) ->
            findViewById<View>(viewId).setOnClickListener { confirmDeletion(action.label, action.delete) }
        }
        findViewById<View>(R.id.rowDeleteRecordedData).setOnClickListener {
            confirmDeletion(
                "tutti i dati registrati (alimentazioni, misure, allenamenti, sgarri e review)",
                data.dataDeletionService::deleteRecordedData,
            )
        }
    }

    private fun confirmDeletion(label: String, delete: suspend () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminare $label?")
            .setMessage("L'operazione è irreversibile per il profilo attivo. Profilo e chiavi IA resteranno disponibili.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina") { _, _ ->
                lifecycleScope.launch {
                    runCatching { delete() }
                        .onSuccess { Toast.makeText(this@SettingsActivity, "$label eliminati", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(this@SettingsActivity, "Eliminazione non riuscita", Toast.LENGTH_LONG).show() }
                }
            }
            .show()
    }

    private data class DeletionAction(val label: String, val delete: suspend () -> Unit)

    private fun providerError(provider: AiCredentialProvider, error: Throwable): String {
        val label = if (provider == AiCredentialProvider.GEMINI) "Gemini" else "OpenAI"
        val tail = "La chiave precedente non è stata modificata."
        return when (error) {
            is com.myfitai.app.ai.AiTransportException.Http -> when (error.failureKind) {
                com.myfitai.app.ai.AiTransportFailureKind.INVALID_API_KEY -> "API key $label invalida o senza permessi. $tail"
                com.myfitai.app.ai.AiTransportFailureKind.UNSUPPORTED_AUTH_METHOD ->
                    "La chiave $label è stata riconosciuta come Auth Key, ma la richiesta è stata rifiutata (HTTP ${error.statusCode}, ACCESS_TOKEN_TYPE_UNSUPPORTED). " +
                        "Di solito accade quando la Auth Key è legata a un progetto/organizzazione che non consente l'accesso via API key REST. $tail"
                com.myfitai.app.ai.AiTransportFailureKind.MODEL_UNAVAILABLE ->
                    if (provider == AiCredentialProvider.GEMINI) "Modello Gemini non disponibile (nemmeno il fallback). $tail"
                    else "Modello OpenAI non disponibile per questa chiave. $tail"
                com.myfitai.app.ai.AiTransportFailureKind.QUOTA_EXHAUSTED -> "Quota $label esaurita. $tail"
                com.myfitai.app.ai.AiTransportFailureKind.RATE_LIMITED -> "Limite temporaneo $label raggiunto. Riprova più tardi."
                com.myfitai.app.ai.AiTransportFailureKind.PROVIDER_UNAVAILABLE -> "$label non è temporaneamente disponibile (HTTP ${error.statusCode}). Riprova più tardi; $tail"
                else -> "$label ha rifiutato la richiesta (HTTP ${error.statusCode}). $tail"
            }
            is com.myfitai.app.ai.AiTransportException.Network -> "Problema di rete o timeout. $tail"
            is com.myfitai.app.ai.AiTransportException.InvalidResponse -> "Risposta $label non interpretabile. $tail"
            else -> "$label non raggiungibile. $tail"
        }
    }

    private fun pasteInto(input: com.google.android.material.textfield.TextInputEditText) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Clipboard vuota", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText(text)
        input.setSelection(text.length)
        input.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        input.isLongClickable = false
        input.requestFocus()
        clipboard.clearPrimaryClip()
        Toast.makeText(this, "Chiave incollata. Verifica e salva per registrarla.", Toast.LENGTH_SHORT).show()
    }

    private fun clearClipboard() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()
    }

    /**
     * Sanitized verification telemetry: records ONLY provider + HTTP status + failure category.
     * Never logs the API key, request/response body, headers or any secret. Debug builds only.
     */
    private fun logVerificationOutcome(provider: AiCredentialProvider, error: Throwable) {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val providerName = if (provider == AiCredentialProvider.GEMINI) "GEMINI" else "OPENAI"
        val detail = when (error) {
            is com.myfitai.app.ai.AiTransportException.Http -> "status=${error.statusCode} kind=${error.failureKind}"
            is com.myfitai.app.ai.AiTransportException.Network -> "status=- kind=NETWORK"
            is com.myfitai.app.ai.AiTransportException.InvalidResponse -> "status=- kind=INVALID_RESPONSE"
            is com.myfitai.app.ai.AiTransportException.NotConfigured -> "status=- kind=NOT_CONFIGURED"
            else -> "status=- kind=UNKNOWN"
        }
        android.util.Log.w("MyFitAiVerify", "provider=$providerName $detail (key/body never logged)")
    }
}
