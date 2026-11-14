package com.myfitai.app.ai

enum class AiProviderType {
    GEMINI,
    OPENAI,
    /** Experimental xAI provider (off by default; not selectable until batch 2). */
    GROK,
    /** Experimental Groq (groq.com inference on open models) provider: off by default, never selected without an explicit choice. */
    GROQ,
    NOT_CONFIGURED,
}
