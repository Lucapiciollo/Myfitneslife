package com.myfitai.app.ai

enum class AiProviderType {
    GEMINI,
    OPENAI,
    /** Experimental xAI provider (off by default; not selectable until batch 2). */
    GROK,
    NOT_CONFIGURED,
}
