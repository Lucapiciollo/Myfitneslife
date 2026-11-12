package com.myfitai.app.ai

/** Provider-neutral AI boundary used by agents and deterministic test doubles. */
interface AiRuntimeGateway {
    suspend fun execute(
        request: AiStructuredRequest,
        maxSchemaRetries: Int = 1,
        businessValidator: (String) -> Result<Unit> = { Result.success(Unit) },
    ): AiExecutionService.ValidatedResponse

    /**
     * Same pipeline as [execute], returning a [ValidatedOutput]<T> built by [business] (parse + app rules).
     *
     * The default implementation keeps every pre-existing gateway (and test double) source compatible: it runs
     * [execute] and then validates the returned JSON again, mapping a rejection to the same typed failures.
     * [AiRuntimeService] overrides it with the single-pass implementation of [AiExecutionService.executeValidated].
     */
    suspend fun <T> executeValidated(
        request: AiStructuredRequest,
        maxSchemaRetries: Int = 1,
        business: (String) -> Result<T>,
    ): AiExecutionService.ValidatedExecution<T> {
        val response = execute(request, maxSchemaRetries) { json -> business(json).map { } }
        val validated = ValidatedOutput.validate(response.jsonText, request.schemaJson, request.schemaName, business)
        val output = validated.getOrNull()
        if (output != null) return AiExecutionService.ValidatedExecution(output, response)
        val rejection = validated.exceptionOrNull()
        throw if (rejection is ValidatedOutput.Rejection) AiExecutionService.Failure.from(rejection) else rejection ?: IllegalStateException("UNEXPECTED")
    }
}