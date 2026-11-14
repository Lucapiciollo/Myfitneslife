package com.myfitai.app.ai

/**
 * Stato del provider IA mostrato in Impostazioni IA, nella rotellina e in Altro.
 * Deriva solo dalla presenza delle credenziali e dal provider scelto: non legge né espone mai una chiave.
 */
data class AiProviderStatus(
    val state: State,
    /** Provider scelto dall'utente (Gemini, OpenAI o Grok sperimentale), anche se privo di chiave. */
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
        /** Nome breve del provider per badge e testi di stato (nomi di prodotto, non tradotti). */
        fun displayName(type: AiProviderType): String = when (type) {
            AiProviderType.GEMINI -> "Gemini"
            AiProviderType.OPENAI -> "OpenAI"
            AiProviderType.GROK -> "Grok"
            AiProviderType.GROQ -> "Groq"
            AiProviderType.NOT_CONFIGURED -> ""
        }

        /** Voci del selettore provider: Grok e Groq compaiono solo con il rispettivo interruttore sperimentale acceso (spenti di default). */
        fun selectableProviders(grokEnabled: Boolean, groqEnabled: Boolean = false): List<AiProviderType> =
            listOf(AiProviderType.GEMINI, AiProviderType.OPENAI) +
                listOfNotNull(AiProviderType.GROK.takeIf { grokEnabled }, AiProviderType.GROQ.takeIf { groqEnabled })

        fun from(config: AiRuntimeConfig): AiProviderStatus {
            val experimental = when {
                config.grokActive -> AiProviderType.GROK to config.grokConfigured
                config.groqActive -> AiProviderType.GROQ to config.groqConfigured
                else -> null
            }
            if (experimental != null) {
                // Experimental provider chosen and enabled: state consistent with selectedProvider(); the alternative is a historical provider with a key.
                val (type, configured) = experimental
                val previous = if (config.useGemini) AiProviderType.GEMINI else AiProviderType.OPENAI
                val previousConfigured = if (config.useGemini) config.geminiConfigured else config.openAiConfigured
                val other = if (config.useGemini) AiProviderType.OPENAI else AiProviderType.GEMINI
                val otherConfigured = if (config.useGemini) config.openAiConfigured else config.geminiConfigured
                return when {
                    configured -> AiProviderStatus(State.ACTIVE, type)
                    previousConfigured -> AiProviderStatus(State.SELECTED_WITHOUT_KEY, type, alternative = previous)
                    otherConfigured -> AiProviderStatus(State.SELECTED_WITHOUT_KEY, type, alternative = other)
                    else -> AiProviderStatus(State.NONE, type)
                }
            }
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
