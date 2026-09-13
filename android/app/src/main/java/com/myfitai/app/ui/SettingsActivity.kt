package com.myfitai.app.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.myfitai.app.R
import com.myfitai.app.ai.AiRuntimeConfig
import com.myfitai.app.ai.AiSettingsStore
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.security.SecureGeminiKeyStore
import com.myfitai.app.security.SecureOpenAiKeyStore

class SettingsActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()

        val settings = AiSettingsStore(this)
        val openAiStore = SecureOpenAiKeyStore(this)
        val geminiStore = SecureGeminiKeyStore(this)

        val useGeminiSwitch = findViewById<MaterialSwitch>(R.id.useGeminiSwitch)
        val geminiContainer = findViewById<View>(R.id.geminiKeyContainer)
        val openAiContainer = findViewById<View>(R.id.openAiKeyContainer)
        val geminiInput = findViewById<TextInputEditText>(R.id.geminiApiKeyInput)
        val openAiInput = findViewById<TextInputEditText>(R.id.openAiApiKeyInput)
        val activeProviderText = findViewById<TextView>(R.id.activeProviderText)
        val geminiStatus = findViewById<TextView>(R.id.geminiKeyStatusText)
        val openAiStatus = findViewById<TextView>(R.id.openAiKeyStatusText)

        useGeminiSwitch.isChecked = settings.useGemini

        fun render() {
            val useGemini = useGeminiSwitch.isChecked
            geminiContainer.visibility = if (useGemini) View.VISIBLE else View.GONE
            openAiContainer.visibility = if (useGemini) View.GONE else View.VISIBLE
            val config = AiRuntimeConfig(
                useGemini = useGemini,
                geminiConfigured = geminiStore.hasKey(),
                openAiConfigured = openAiStore.hasKey(),
            )
            activeProviderText.text = when (val selected = config.selectedProvider()) {
                com.myfitai.app.ai.AiProviderType.NOT_CONFIGURED -> "Provider selezionato ma chiave non configurata"
                else -> "Provider attivo: ${selected.name}"
            }
            geminiStatus.text = if (geminiStore.hasKey()) "Chiave Gemini protetta con Android Keystore" else "Nessuna chiave Gemini salvata"
            openAiStatus.text = if (openAiStore.hasKey()) "Chiave OpenAI protetta con Android Keystore" else "Nessuna chiave OpenAI salvata"
            findViewById<View>(R.id.deleteGeminiKeyButton).isEnabled = geminiStore.hasKey()
            findViewById<View>(R.id.deleteOpenAiKeyButton).isEnabled = openAiStore.hasKey()
        }

        findViewById<View>(R.id.saveGeminiKeyButton).setOnClickListener {
            val candidate = geminiInput.text?.toString()?.trim().orEmpty()
            if (candidate.isBlank()) {
                Toast.makeText(this, "Inserisci una chiave Gemini valida", Toast.LENGTH_SHORT).show()
            } else {
                runCatching { geminiStore.save(candidate) }
                    .onSuccess { geminiInput.text?.clear(); Toast.makeText(this, "Chiave Gemini salvata in modo sicuro", Toast.LENGTH_SHORT).show(); render() }
                    .onFailure { Toast.makeText(this, "Impossibile salvare la chiave Gemini", Toast.LENGTH_SHORT).show() }
            }
        }
        findViewById<View>(R.id.saveOpenAiKeyButton).setOnClickListener {
            val candidate = openAiInput.text?.toString()?.trim().orEmpty()
            if (candidate.isBlank()) {
                Toast.makeText(this, "Inserisci una chiave OpenAI valida", Toast.LENGTH_SHORT).show()
            } else {
                runCatching { openAiStore.save(candidate) }
                    .onSuccess { openAiInput.text?.clear(); Toast.makeText(this, "Chiave OpenAI salvata in modo sicuro", Toast.LENGTH_SHORT).show(); render() }
                    .onFailure { Toast.makeText(this, "Impossibile salvare la chiave OpenAI", Toast.LENGTH_SHORT).show() }
            }
        }
        findViewById<View>(R.id.deleteGeminiKeyButton).setOnClickListener {
            geminiStore.delete(); geminiInput.text?.clear(); render()
        }
        findViewById<View>(R.id.deleteOpenAiKeyButton).setOnClickListener {
            openAiStore.delete(); openAiInput.text?.clear(); render()
        }
        useGeminiSwitch.setOnCheckedChangeListener { _, checked ->
            settings.useGemini = checked
            render()
        }

        render()
        findViewById<View>(R.id.rowExport).setOnClickListener { go(ExportActivity::class.java) }
    }
}
