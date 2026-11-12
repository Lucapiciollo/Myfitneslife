package com.myfitai.app.ui.bia

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.myfitai.app.R
import com.myfitai.app.ai.AiFailureMessages

/** Pannello di attesa IA (layout `view_bia_wait_panel`): annunciato in modo educato, con Annulla se l'operazione e' annullabile. */
class BiaWaitPanel private constructor(val root: View, onCancel: () -> Unit) {
    private val title: TextView = root.findViewById(R.id.biaWaitTitle)
    private val message: TextView = root.findViewById(R.id.biaWaitMessage)
    private val cancel: MaterialButton = root.findViewById(R.id.biaWaitCancel)

    init {
        // Gli stessi id compaiono in piu' pannelli: lo stato non va ripristinato dalla gerarchia (il testo viene riscritto dal codice).
        root.isSaveFromParentEnabled = false
        root.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.setAccessibilityHeading(title, true)
        cancel.setOnClickListener { onCancel() }
    }

    companion object {
        fun inflate(host: ViewGroup, onCancel: () -> Unit): BiaWaitPanel {
            val view = LayoutInflater.from(host.context).inflate(R.layout.view_bia_wait_panel, host, false)
            host.addView(view)
            return BiaWaitPanel(view, onCancel)
        }
    }

    fun show(titleText: String, messageText: String, cancellable: Boolean, cancelDescription: String? = null) {
        title.text = titleText
        message.text = messageText
        cancel.visibility = if (cancellable) View.VISIBLE else View.GONE
        cancelDescription?.let { cancel.contentDescription = it }
        root.visibility = View.VISIBLE
    }

    fun hide() {
        root.visibility = View.GONE
    }
}

/**
 * Errore IA persistente in scheda (layout `view_bia_failure_panel`): frase della tabella centrale [AiFailureMessages],
 * "Riprova" solo se riprovabile, "Apri Impostazioni IA" solo se l'utente puo' risolvere da li', codice tecnico solo in "Dettagli".
 */
class BiaFailurePanel private constructor(val root: View, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    private val message: TextView = root.findViewById(R.id.biaFailureMessage)
    private val retry: MaterialButton = root.findViewById(R.id.biaFailureRetry)
    private val settings: MaterialButton = root.findViewById(R.id.biaFailureSettings)
    private val details: TextView = root.findViewById(R.id.biaFailureDetails)
    private val detailsToggle: MaterialButton = root.findViewById(R.id.biaFailureDetailsToggle)

    companion object {
        fun inflate(host: ViewGroup, onRetry: () -> Unit, onOpenSettings: () -> Unit): BiaFailurePanel {
            val view = LayoutInflater.from(host.context).inflate(R.layout.view_bia_failure_panel, host, false)
            host.addView(view)
            return BiaFailurePanel(view, onRetry, onOpenSettings)
        }
    }

    init {
        root.isSaveFromParentEnabled = false
        retry.setOnClickListener { onRetry() }
        settings.setOnClickListener { onOpenSettings() }
        detailsToggle.setOnClickListener {
            val show = details.visibility != View.VISIBLE
            details.visibility = if (show) View.VISIBLE else View.GONE
            detailsToggle.setText(if (show) R.string.ai_failure_details_hide else R.string.ai_failure_details)
        }
    }

    fun show(presentation: AiFailureMessages.Presentation) =
        show(root.resources.getString(presentation.messageRes), presentation)

    /** Come [show], ma con una frase specifica dell'operazione (stessa logica di azioni e dettagli). */
    fun show(@StringRes messageRes: Int, presentation: AiFailureMessages.Presentation) =
        show(root.resources.getString(messageRes), presentation)

    private fun show(text: String, presentation: AiFailureMessages.Presentation) {
        message.text = text
        retry.visibility = if (presentation.canRetry) View.VISIBLE else View.GONE
        val settingsLabel = presentation.action.labelRes
        settings.visibility = if (settingsLabel != null) View.VISIBLE else View.GONE
        settingsLabel?.let { settings.setText(it) }
        val code = presentation.technicalCode
        details.visibility = View.GONE
        detailsToggle.setText(R.string.ai_failure_details)
        detailsToggle.visibility = if (code != null) View.VISIBLE else View.GONE
        details.text = code?.let { root.resources.getString(R.string.ai_failure_details_code, it) }.orEmpty()
        root.visibility = View.VISIBLE
    }

    fun hide() {
        root.visibility = View.GONE
    }
}
