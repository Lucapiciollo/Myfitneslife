package com.myfitai.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.myfitai.app.R
import com.myfitai.app.ai.AiModelConfig
import com.myfitai.app.ai.AiProviderStatus
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRuntimeConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.GeminiByokProvider
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.security.AiCredentialProvider
import com.myfitai.app.security.SecureAiCredentialStore
import com.myfitai.app.ui.widgets.SelectableSegmentView
import com.myfitai.app.ui.widgets.SettingRowView
import kotlinx.coroutines.launch

/**
 * Impostazioni IA, condivise da tutti i profili: provider, chiavi API, modelli e costi.
 * È una finestra propria (non un tab embedded), così FLAG_SECURE protegge davvero i campi chiave.
 * Le chiavi restano solo in [SecureAiCredentialStore] (Android Keystore): i campi partono vuoti, non vengono mai
 * riletti né registrati nei log e vengono svuotati quando la schermata esce dal primo piano.
 */
class AiSettingsActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private lateinit var settings: AiSettingsStore
    private lateinit var credentialStore: SecureAiCredentialStore
    private lateinit var providerSegment: SelectableSegmentView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_ai_settings)
        bindBack()
        SettingsSurfaces.normalize(this)
        listOf(R.id.aiProviderHeader, R.id.aiKeysHeader, R.id.aiModelsHeader, R.id.aiCostsHeader).forEach {
            ViewCompat.setAccessibilityHeading(findViewById<TextView>(it), true)
        }
        findViewById<View>(R.id.aiSettingsHelpButton).setOnClickListener {
            showHelpCard(
                "Impostazioni IA",
                "Qui scegli il provider IA, verifichi e gestisci le chiavi API e il modello. Valgono per tutti i profili: " +
                    "cambiare profilo non le modifica. Le chiavi vengono protette localmente e non sono mostrate in chiaro dopo il salvataggio. " +
                    "Le automazioni, che cambiano per ogni profilo, sono in Automazioni IA.",
            )
        }

        settings = AiSettingsStore(this)
        credentialStore = SecureAiCredentialStore(this)

        val geminiInput = findViewById<TextInputEditText>(R.id.geminiApiKeyInput)
        val openAiInput = findViewById<TextInputEditText>(R.id.openAiApiKeyInput)
        listOf(geminiInput, openAiInput).forEach {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            it.isLongClickable = false
        }

        providerSegment = findViewById(R.id.aiProviderSegment)
        providerSegment.setSegments(listOf("Gemini", "OpenAI"), if (settings.useGemini) 0 else 1)
        providerSegment.setOnSegmentSelectedListener { index ->
            settings.useGemini = index == 0
            render()
        }

        bindKeyActions(geminiInput, openAiInput)
        bindAutomationsLink()
        GeminiCostSettingsBinder.bind(this, findViewById(R.id.aiCostGroup), settings)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onStop() {
        // Nessun segreto digitato deve restare in memoria visibile quando l'utente esce dalla schermata.
        findViewById<TextInputEditText?>(R.id.geminiApiKeyInput)?.text?.clear()
        findViewById<TextInputEditText?>(R.id.openAiApiKeyInput)?.text?.clear()
        super.onStop()
    }

    private fun render() {
        if (!::settings.isInitialized) return
        val geminiConfigured = credentialStore.exists(AiCredentialProvider.GEMINI)
        val openAiConfigured = credentialStore.exists(AiCredentialProvider.OPENAI)
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

        val status = AiProviderStatus.from(
            AiRuntimeConfig(useGemini = settings.useGemini, geminiConfigured = geminiConfigured, openAiConfigured = openAiConfigured),
        )
        renderProviderStatus(status)

        findViewById<TextView>(R.id.geminiKeyStatusText).text = if (geminiConfigured) {
            "Gemini configurato ✓ · chiave nascosta\nModello selezionato: ${AiModelConfig.displayName(settings.selectedGeminiModel)}"
        } else {
            "Chiave Gemini non configurata"
        }
        findViewById<TextView>(R.id.openAiKeyStatusText).text = if (openAiConfigured) {
            "OpenAI configurato ✓ · chiave nascosta\nModello selezionato: ${AiModelConfig.displayName(settings.selectedOpenAiModel)}"
        } else {
            "Chiave OpenAI non configurata"
        }
    }

    private fun renderProviderStatus(status: AiProviderStatus) {
        val text = findViewById<TextView>(R.id.activeProviderText)
        val useOther = findViewById<MaterialButton>(R.id.useOtherProviderButton)
        text.text = when (status.state) {
            AiProviderStatus.State.ACTIVE -> getString(
                R.string.ai_provider_active,
                providerName(status.selected),
                AiModelConfig.displayName(
                    if (status.selected == AiProviderType.GEMINI) settings.selectedGeminiModel else settings.selectedOpenAiModel,
                ),
            )
            AiProviderStatus.State.SELECTED_WITHOUT_KEY -> {
                val other = checkNotNull(status.alternative)
                getString(R.string.ai_provider_missing_key, providerName(status.selected)) + " " +
                    getString(R.string.ai_provider_other_configured, providerName(other))
            }
            AiProviderStatus.State.NONE -> getString(R.string.ai_provider_none)
        }
        val alternative = status.alternative
        if (status.state == AiProviderStatus.State.SELECTED_WITHOUT_KEY && alternative != null) {
            useOther.text = getString(R.string.ai_provider_use_other, providerName(alternative))
            useOther.visibility = View.VISIBLE
            // Un tocco esplicito dell'utente: nessun fallback automatico tra provider.
            useOther.setOnClickListener {
                providerSegment.check(providerSegment.getChildAt(if (alternative == AiProviderType.GEMINI) 0 else 1).id)
            }
        } else {
            useOther.visibility = View.GONE
            useOther.setOnClickListener(null)
        }
    }

    private fun providerName(type: AiProviderType): String = when (type) {
        AiProviderType.GEMINI -> "Gemini"
        AiProviderType.OPENAI -> "OpenAI"
        AiProviderType.NOT_CONFIGURED -> ""
    }

    private fun bindKeyActions(geminiInput: TextInputEditText, openAiInput: TextInputEditText) {
        findViewById<View>(R.id.saveOpenAiKeyButton).setOnClickListener {
            saveCredential(AiCredentialProvider.OPENAI, openAiInput) { candidate ->
                com.myfitai.app.ai.OpenAiProvider(credentialStore).verifyApiKey(candidate)
            }
        }
        findViewById<View>(R.id.pasteOpenAiKeyButton).setOnClickListener { pasteInto(openAiInput) }
        findViewById<View>(R.id.saveGeminiKeyButton).setOnClickListener {
            saveCredential(AiCredentialProvider.GEMINI, geminiInput) { candidate ->
                GeminiByokProvider(credentialStore).verifyApiKey(candidate)
            }
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
            credentialStore.delete(AiCredentialProvider.OPENAI)
            openAiInput.text?.clear()
            render()
        }
    }

    private fun saveCredential(
        provider: AiCredentialProvider,
        input: TextInputEditText,
        verify: suspend (String) -> com.myfitai.app.ai.AiRawResponse,
    ) {
        val candidate = input.text?.toString()?.trim().orEmpty()
        if (candidate.isBlank()) {
            Toast.makeText(this, "Inserisci una API key valida", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            runCatching {
                val response = verify(candidate)
                credentialStore.save(provider, candidate)
                if (provider == AiCredentialProvider.GEMINI) settings.geminiVerifiedModel = response.model
            }
                .onSuccess {
                    input.text?.clear()
                    clearClipboard()
                    Toast.makeText(this@AiSettingsActivity, "Chiave verificata e salvata in modo sicuro", Toast.LENGTH_SHORT).show()
                    render()
                }
                .onFailure {
                    input.text?.clear()
                    clearClipboard()
                    logVerificationOutcome(provider, it)
                    Toast.makeText(this@AiSettingsActivity, providerError(provider, it), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun bindAutomationsLink() {
        val row = findViewById<SettingRowView>(R.id.rowAiAutomations)
        val profileId = data.activeProfileStore.currentIdOrNull()
        if (profileId == null) {
            row.visibility = View.GONE
            return
        }
        row.setOnClickListener {
            startActivity(
                Intent(this, AiAutomationsActivity::class.java)
                    .putExtra(AiAutomationsActivity.EXTRA_FROM_AI_SETTINGS, true),
            )
        }
        lifecycleScope.launch {
            val name = data.userProfileRepository.get(profileId)?.name?.trim().orEmpty()
            if (name.isNotBlank()) row.setDescription(getString(R.string.settings_row_automations_desc_named, name))
        }
    }

    private fun pasteInto(input: TextInputEditText) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Clipboard vuota", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText(text)
        input.setSelection(text.length)
        input.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        input.isLongClickable = false
        input.requestFocus()
        clearClipboard(clipboard)
        Toast.makeText(this, "Chiave incollata. Verifica e salva per registrarla.", Toast.LENGTH_SHORT).show()
    }

    private fun clearClipboard() {
        clearClipboard(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
    }

    private fun clearClipboard(clipboard: ClipboardManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
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
}
