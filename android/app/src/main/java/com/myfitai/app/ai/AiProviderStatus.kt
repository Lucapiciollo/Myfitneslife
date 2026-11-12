package com.myfitai.app.ai

/**
 * Stato del provider IA mostrato in Impostazioni IA, nella rotellina e in Altro.
 * Deriva solo dalla presenza delle credenziali e dal provider scelto: non legge né espone mai una chiave.
 */
data class AiProviderStatus(
    val state: State,
    /** Provider scelto dall'utente (Gemini o OpenAI), anche se privo di chiave. */
    val selected: AiProviderType,
    /** Altro provider con chiave presente, offerto con un tocco esplicito (mai come fallback automatico). */
    val alternative: AiProviderType? = null,
) {
    enum class State {
        /** Il provider scelto ha una chiave: le funzioni IA sono utilizzabili. */
        ACTIVE,

        /** Il provider scelto non ha chiave ma l'altro sì. */
        SELECTED_WITHOUT_KEY,

        /** Nessuna chiave configurata. */
        NONE,
    }

    /** True quando le funzioni IA non sono utilizzabili: la rotellina mostra il punto d'avviso. */
    val needsAttention: Boolean get() = state != State.ACTIVE

    companion object {
        fun from(config: AiRuntimeConfig): AiProviderStatus {
            val selected = if (config.useGemini) AiProviderType.GEMINI else AiProviderType.OPENAI
            val other = if (config.useGemini) AiProviderType.OPENAI else AiProviderType.GEMINI
            val selectedConfigured = if (config.useGemini) config.geminiConfigured else config.openAiConfigured
            val otherConfigured = if (config.useGemini) config.openAiConfigured else config.geminiConfigured
            return when {
                selectedConfigured -> AiProviderStatus(State.ACTIVE, selected)
                otherConfigured -> AiProviderStatus(State.SELECTED_WITHOUT_KEY, selected, alternative = other)
                else -> AiProviderStatus(State.NONE, selected)
            }
        }
    }
}
