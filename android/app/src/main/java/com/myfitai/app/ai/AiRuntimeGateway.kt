package com.myfitai.app.ai

/** Provider-neutral AI boundary used by agents and deterministic test doubles. */
interface AiRuntimeGateway {
    suspend fun execute(
        request: AiStructuredRequest,
        maxSchemaRetries: Int = 1,
        businessValidator: (String) -> Result<Unit> = { Result.success(Unit) },
    ): AiExecutionService.ValidatedResponse
}
