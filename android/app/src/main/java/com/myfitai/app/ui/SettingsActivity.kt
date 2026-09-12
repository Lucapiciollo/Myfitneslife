package com.myfitai.app.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.myfitai.app.R
import com.myfitai.app.ai.AiRuntimeConfig
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.security.SecureOpenAiKeyStore

class SettingsActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()

        val secureKeyStore = SecureOpenAiKeyStore(this)
        val useGeminiSwitch = findViewById<Switch>(R.id.useGeminiSwitch)
        val openAiKeyContainer = findViewById<View>(R.id.openAiKeyContainer)
        val openAiKeyInput = findViewById<EditText>(R.id.openAiApiKeyInput)
        val activeProviderText = findViewById<TextView>(R.id.activeProviderText)
        val saveKeyButton = findViewById<View>(R.id.saveOpenAiKeyButton)
        val deleteKeyButton = findViewById<View>(R.id.deleteOpenAiKeyButton)
        val keyStatusText = findViewById<TextView>(R.id.openAiKeyStatusText)

        fun renderProviderState() {
            val useGemini = useGeminiSwitch.isChecked
            openAiKeyContainer.visibility = if (useGemini) View.GONE else View.VISIBLE
            val config = AiRuntimeConfig(useGemini = useGemini, openAiConfigured = secureKeyStore.hasKey())
            activeProviderText.text = "Provider attivo: ${config.selectedProvider().name}"
            keyStatusText.text = if (secureKeyStore.hasKey()) "Chiave OpenAI salvata in modo protetto con Android Keystore" else "Nessuna chiave OpenAI salvata"
            deleteKeyButton.isEnabled = secureKeyStore.hasKey()
        }

        saveKeyButton.setOnClickListener {
            val candidate = openAiKeyInput.text?.toString()?.trim().orEmpty()
            if (candidate.isBlank()) {
                Toast.makeText(this, "Inserisci una chiave OpenAI valida", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runCatching { secureKeyStore.save(candidate) }
                .onSuccess { openAiKeyInput.text?.clear(); Toast.makeText(this, "Chiave salvata in modo sicuro", Toast.LENGTH_SHORT).show(); renderProviderState() }
                .onFailure { Toast.makeText(this, "Impossibile salvare la chiave", Toast.LENGTH_SHORT).show() }
        }

        deleteKeyButton.setOnClickListener { secureKeyStore.delete(); openAiKeyInput.text?.clear(); Toast.makeText(this, "Chiave OpenAI rimossa", Toast.LENGTH_SHORT).show(); renderProviderState() }
        useGeminiSwitch.setOnCheckedChangeListener { _, _ -> renderProviderState() }
        renderProviderState()
        findViewById<View>(R.id.title).setOnLongClickListener { go(ExportActivity::class.java); true }
    }
}
