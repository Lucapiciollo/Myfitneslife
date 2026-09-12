package com.myfitai.app.ai

/**
 * Contract only. Network integration intentionally deferred.
 * All providers must return the same domain JSON contracts.
 */
interface AiProvider {
    val type: AiProviderType
}
