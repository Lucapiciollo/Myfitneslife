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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.myfitai.app.R
import com.myfitai.app.ai.AiModelConfig
import com.myfitai.app.ai.AiProviderStatus
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRuntimeConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.ai.GeminiByokProvider
import com.myfitai.app.ai.GrokProvider
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
    private var segmentProviders: List<AiProviderType> = emptyList()
    private var grokCostViews: List<View> = emptyList()
    private var renderingGrokSwitch = false

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
        val grokInput = findViewById<TextInputEditText>(R.id.grokApiKeyInput)
        listOf(geminiInput, openAiInput, grokInput).forEach {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            it.isLongClickable = false
        }

        providerSegment = findViewById(R.id.aiProviderSegment)
        providerSegment.setOnSegmentSelectedListener { index ->
            // Nessun passaggio silenzioso: la scelta cambia solo per un tocco esplicito sulla voce.
            segmentProviders.getOrNull(index)?.let { settings.selectedProvider = it }
            render()
        }
        bindGrokSwitch()

        bindKeyActions(geminiInput, openAiInput, grokInput)
        bindAutomationsLink()
        // Grok va aggiunto prima: i binder inseriscono la propria riga in testa (ordine finale Gemini, OpenAI, Grok).
        grokCostViews = GrokCostSettingsBinder.bind(this, findViewById(R.id.aiCostGroup))
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
        findViewById<TextInputEditText?>(R.id.grokApiKeyInput)?.text?.clear()
        super.onStop()
    }

    private fun bindGrokSwitch() {
        findViewById<MaterialSwitch>(R.id.aiGrokSwitch).setOnCheckedChangeListener { button, checked ->
            if (renderingGrokSwitch) return@setOnCheckedChangeListener
            if (checked) {
                // Il consenso e' esplicito: l'interruttore resta spento finche' l'utente non conferma.
                renderingGrokSwitch = true
                button.isChecked = false
                renderingGrokSwitch = false
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.ai_grok_confirm_title)
                    .setMessage(R.string.ai_grok_data_notice)
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton(R.string.ai_grok_confirm_positive) { _, _ ->
                        settings.grokEnabled = true
                        settings.selectedProvider = AiProviderType.GROK
                        Toast.makeText(this, R.string.ai_grok_enabled_toast, Toast.LENGTH_SHORT).show()
                        render()
                    }
                    .show()
            } else {
                settings.grokEnabled = false
                // Torna al provider storico, quello che il runtime userebbe comunque: stato e interfaccia restano coerenti.
                if (settings.selectedProvider == AiProviderType.GROK) {
                    settings.selectedProvider = if (settings.useGemini) AiProviderType.GEMINI else AiProviderType.OPENAI
                }
                render()
            }
        }
    }

    /** Ricostruisce il segmento solo se cambiano le voci (Grok acceso/spento), mai dentro il proprio callback di selezione. */
    private fun renderProviderSegment(selected: AiProviderType, grokEnabled: Boolean) {
        val providers = AiProviderStatus.selectableProviders(grokEnabled)
        if (providers != segmentProviders) {
            segmentProviders = providers
            providerSegment.setSegments(
                providers.map { providerName(it) },
                providers.indexOf(selected).coerceAtLeast(0),
            )
            return
        }
        val index = providers.indexOf(selected)
        if (index >= 0) {
            val id = providerSegment.getChildAt(index).id
            if (providerSegment.checkedButtonId != id) providerSegment.check(id)
        }
    }

    private fun activeModel(type: AiProviderType): String = when (type) {
        AiProviderType.GEMINI -> settings.selectedGeminiModel
        AiProviderType.GROK -> settings.selectedGrokModel
        else -> settings.selectedOpenAiModel
    }

    private fun render() {
        if (!::settings.isInitialized) return
        val geminiConfigured = credentialStore.exists(AiCredentialProvider.GEMINI)
        val openAiConfigured = credentialStore.exists(AiCredentialProvider.OPENAI)
        val grokConfigured = credentialStore.exists(AiCredentialProvider.GROK)
        val grokEnabled = settings.grokEnabled
        findViewById<View>(R.id.grokInputLayout).visibility = if (grokConfigured) View.GONE else View.VISIBLE
        findViewById<View>(R.id.pasteGrokKeyButton).visibility = if (grokConfigured) View.GONE else View.VISIBLE
        findViewById<View>(R.id.saveGrokKeyButton).visibility = if (grokConfigured) View.GONE else View.VISIBLE
        findViewById<View>(R.id.replaceGrokKeyButton).visibility = if (grokConfigured) View.VISIBLE else View.GONE
        findViewById<View>(R.id.deleteGrokKeyButton).visibility = if (grokConfigured) View.VISIBLE else View.GONE
        // Grok e' sperimentale e spento di default: chiave, modello e costi compaiono solo se e' acceso o ha gia' una chiave da gestire.
        val grokVisible = if (grokEnabled || grokConfigured) View.VISIBLE else View.GONE
        findViewById<View>(R.id.grokKeyGroup).visibility = grokVisible
        findViewById<View>(R.id.grokModelGroup).visibility = grokVisible
        grokCostViews.forEach { it.visibility = grokVisible }
        renderingGrokSwitch = true
        findViewById<MaterialSwitch>(R.id.aiGrokSwitch).isChecked = grokEnabled
        renderingGrokSwitch = false
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
            AiRuntimeConfig(
                useGemini = settings.useGemini,
                geminiConfigured = geminiConfigured,
                openAiConfigured = openAiConfigured,
                grokEnabled = grokEnabled,
                grokSelected = settings.selectedProvider == AiProviderType.GROK,
                grokConfigured = grokConfigured,
            ),
        )
        renderProviderSegment(status.selected, grokEnabled)
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
        findViewById<TextView>(R.id.grokKeyStatusText).text = if (grokConfigured) {
            getString(R.string.ai_grok_key_configured, AiModelConfig.displayName(settings.selectedGrokModel))
        } else {
            getString(R.string.ai_grok_key_missing)
        }
    }

    private fun renderProviderStatus(status: AiProviderStatus) {
        val text = findViewById<TextView>(R.id.activeProviderText)
        val useOther = findViewById<MaterialButton>(R.id.useOtherProviderButton)
        text.text = when (status.state) {
            AiProviderStatus.State.ACTIVE -> getString(
                R.string.ai_provider_active,
                providerName(status.selected),
                AiModelConfig.displayName(activeModel(status.selected)),
            )
            AiProviderStatus.State.SELECTED_WITHOUT_KEY -> {
                val other = checkNotNull(status.alternative)
                missingKeyText(status.selected) + " " +
                    getString(R.string.ai_provider_other_configured, providerName(other))
            }
            AiProviderStatus.State.NONE ->
                if (status.selected == AiProviderType.GROK) getString(R.string.ai_provider_grok_missing_key) else getString(R.string.ai_provider_none)
        }
        val alternative = status.alternative
        if (status.state == AiProviderStatus.State.SELECTED_WITHOUT_KEY && alternative != null) {
            useOther.text = getString(R.string.ai_provider_use_other, providerName(alternative))
            useOther.visibility = View.VISIBLE
            // Un tocco esplicito dell'utente: nessun fallback automatico tra provider.
            useOther.setOnClickListener {
                val index = segmentProviders.indexOf(alternative)
                if (index >= 0) providerSegment.check(providerSegment.getChildAt(index).id)
            }
        } else {
            useOther.visibility = View.GONE
            useOther.setOnClickListener(null)
        }
    }

    private fun missingKeyText(type: AiProviderType): String =
        if (type == AiProviderType.GROK) getString(R.string.ai_provider_grok_missing_key)
        else getString(R.string.ai_provider_missing_key, providerName(type))

    private fun providerName(type: AiProviderType): String =
        if (type == AiProviderType.GROK) getString(R.string.ai_provider_grok_label) else AiProviderStatus.displayName(type)

    private fun bindKeyActions(geminiInput: TextInputEditText, openAiInput: TextInputEditText, grokInput: TextInputEditText) {
        findViewById<View>(R.id.saveGrokKeyButton).setOnClickListener {
            saveCredential(AiCredentialProvider.GROK, grokInput) { candidate ->
                GrokProvider(credentialStore).verifyApiKey(candidate)
            }
        }
        findViewById<View>(R.id.pasteGrokKeyButton).setOnClickListener { pasteInto(grokInput) }
        findViewById<View>(R.id.replaceGrokKeyButton).setOnClickListener {
            grokInput.text?.clear()
            findViewById<View>(R.id.grokInputLayout).visibility = View.VISIBLE
            findViewById<View>(R.id.saveGrokKeyButton).visibility = View.VISIBLE
            findViewById<View>(R.id.replaceGrokKeyButton).visibility = View.GONE
        }
        findViewById<View>(R.id.deleteGrokKeyButton).setOnClickListener {
            credentialStore.delete(AiCredentialProvider.GROK)
            grokInput.text?.clear()
            render()
        }
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
        val providerName = provider.name
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
        val label = when (provider) {
            AiCredentialProvider.GEMINI -> "Gemini"
            AiCredentialProvider.OPENAI -> "OpenAI"
            AiCredentialProvider.GROK -> "Grok"
        }
        val tail = "La chiave precedente non è stata modificata."
        return when (error) {
            is com.myfitai.app.ai.AiTransportException.Http -> when (error.failureKind) {
                com.myfitai.app.ai.AiTransportFailureKind.INVALID_API_KEY -> "API key $label invalida o senza permessi. $tail"
                com.myfitai.app.ai.AiTransportFailureKind.UNSUPPORTED_AUTH_METHOD ->
                    "La chiave $label è stata riconosciuta come Auth Key, ma la richiesta è stata rifiutata (HTTP ${error.statusCode}, ACCESS_TOKEN_TYPE_UNSUPPORTED). " +
                        "Di solito accade quando la Auth Key è legata a un progetto/organizzazione che non consente l'accesso via API key REST. $tail"
                com.myfitai.app.ai.AiTransportFailureKind.MODEL_UNAVAILABLE ->
                    if (provider == AiCredentialProvider.GEMINI) "Modello Gemini non disponibile (nemmeno il fallback). $tail"
                    else "Modello $label non disponibile per questa chiave. $tail"
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
