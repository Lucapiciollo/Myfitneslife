package com.myfitai.app.ai

import androidx.annotation.StringRes
import com.myfitai.app.R

/**
 * UNICA tabella che traduce un errore IA in una frase italiana per l'utente e nell'azione proposta.
 *
 * - La mappatura per categoria e' un `when` esaustivo su [AiFailure]: aggiungere una categoria senza
 *   descriverla qui non compila (e `AiFailureMessagesTest` fallisce se l'elenco delle categorie cambia).
 * - "Riprova" e' proposto solo se [AiFailure.retryable]; l'azione [Action.OPEN_AI_SETTINGS] solo quando
 *   l'utente puo' risolvere da Impostazioni IA (quota, chiave, modello, provider non configurato).
 * - Il codice tecnico non compare mai nella frase: e' esposto solo come [Presentation.technicalCode], da mostrare in
 *   un "Dettagli" espandibile. Testi liberi o sconosciuti non diventano mai codice visibile.
 * - [resolve] accetta cio' che i job scrivono in WorkManager (`AiJobWorker.KEY_ERROR`): il testo fisso di
 *   [AiFailure.userMessage] o il codice di superficie (vedi `AiJobFailures.safeMessage`).
 */
object AiFailureMessages {

    enum class Action(@StringRes val labelRes: Int?) {
        NONE(null),
        OPEN_AI_SETTINGS(R.string.ai_failure_action_open_settings),
    }

    data class Presentation(
        @StringRes val messageRes: Int,
        val action: Action,
        val canRetry: Boolean,
        /** Codice stabile per "Dettagli"; null se non disponibile o non attendibile. */
        val technicalCode: String?,
    )

    fun forFailure(failure: AiFailure): Presentation {
        val (message, action) = when (failure) {
            AiFailure.Network -> R.string.ai_failure_network to Action.NONE
            AiFailure.RateLimited, AiFailure.ProviderUnavailable -> R.string.ai_failure_unavailable to Action.NONE
            AiFailure.Quota -> R.string.ai_failure_quota to Action.OPEN_AI_SETTINGS
            AiFailure.Auth -> R.string.ai_failure_auth to Action.OPEN_AI_SETTINGS
            AiFailure.NotConfigured -> R.string.ai_failure_not_configured to Action.OPEN_AI_SETTINGS
            AiFailure.ModelUnavailable -> R.string.ai_failure_model to Action.OPEN_AI_SETTINGS
            AiFailure.ProviderProtocol, AiFailure.MalformedJson, AiFailure.SchemaInvalid ->
                R.string.ai_failure_invalid_response to Action.NONE
            is AiFailure.Refused -> R.string.ai_failure_blocked to Action.NONE
            AiFailure.Truncated -> R.string.ai_failure_truncated to Action.NONE
            AiFailure.EmptyOutput -> R.string.ai_failure_empty to Action.NONE
            is AiFailure.BusinessRuleViolated -> R.string.ai_failure_rejected to Action.NONE
            AiFailure.PaidRetryBudgetConsumed -> R.string.ai_failure_paid_retry to Action.NONE
            AiFailure.Cancelled -> R.string.ai_failure_cancelled to Action.NONE
            AiFailure.ContractNotRegistered, AiFailure.Unexpected -> R.string.ai_failure_generic to Action.NONE
        }
        val code = (failure as? AiFailure.BusinessRuleViolated)?.ruleCode ?: failure.code
        return Presentation(message, action, canRetry = failure.retryable, technicalCode = code)
    }

    /** Dal testo o codice scritto dal job al messaggio da mostrare. Mai eccezioni, mai testo libero verso l'utente. */
    fun resolve(raw: String?): Presentation {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return generic(null)
        byUserMessage[text]?.let { return forFailure(it) }
        byCode[text]?.let { return forFailure(it) }
        if (text == REFUSED_CODE) return forFailure(AiFailure.Refused(AiFailure.RefusalReason.MODEL_REFUSAL))
        AiFailure.stableBusinessCode(text)?.let { return forFailure(AiFailure.BusinessRuleViolated(it)) }
        return generic(text.takeIf { CODE_SHAPE.matches(it) })
    }

    /** L'analisi non e' partita (accodamento fallito): si puo' riprovare, nessun codice tecnico. */
    fun couldNotStart() = Presentation(R.string.ai_failure_start, Action.NONE, canRetry = true, technicalCode = null)

    private fun generic(code: String?) =
        Presentation(R.string.ai_failure_generic, Action.NONE, canRetry = false, technicalCode = code)

    private const val REFUSED_CODE = "REFUSED"
    private val CODE_SHAPE = Regex("^[A-Z][A-Z0-9_]{2,59}$")

    private val byUserMessage: Map<String, AiFailure> by lazy {
        AiFailure.ALL_STATIC.filter { it.surface == AiFailure.Surface.MESSAGE }.associateBy { it.userMessage }
    }

    private val byCode: Map<String, AiFailure> by lazy {
        buildMap {
            AiFailure.ALL_STATIC.forEach { put(it.code, it) }
            AiFailure.ALL_STATIC.forEach { putIfAbsent(it.surfaceCode, it) }
        }
    }
}
