package com.myfitai.app.ai

import android.content.Context
import com.myfitai.app.security.SecureGeminiKeyStore
import com.myfitai.app.security.SecureOpenAiKeyStore

/** Composition boundary used by agents: resolves provider without exposing credentials. */
class AiRuntimeService(context: Context) {
    private val appContext = context.applicationContext
    private val settings = AiSettingsStore(appContext)
    private val geminiStore = SecureGeminiKeyStore(appContext)
    private val openAiStore = SecureOpenAiKeyStore(appContext)
    private val execution = AiExecutionService()

    fun config(): AiRuntimeConfig = AiRuntimeConfig(
        useGemini = settings.useGemini,
        geminiConfigured = geminiStore.hasKey(),
        openAiConfigured = openAiStore.hasKey(),
    )

    fun selectedProviderType(): AiProviderType = config().selectedProvider()

    suspend fun execute(
        request: AiStructuredRequest,
        maxSchemaRetries: Int = 1,
        businessValidator: (String) -> Result<Unit> = { Result.success(Unit) },
    ): AiExecutionService.ValidatedResponse {
        val provider = AiProviderSelector.create(appContext, config())
            ?: throw AiTransportException.NotConfigured(AiProviderType.NOT_CONFIGURED)
        return execution.execute(provider, request, maxSchemaRetries, businessValidator)
    }
}
