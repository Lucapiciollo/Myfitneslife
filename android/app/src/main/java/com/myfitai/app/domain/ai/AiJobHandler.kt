package com.myfitai.app.domain.ai

import androidx.work.Data

sealed class AiJobOutcome {
    data class Success(val output: Data) : AiJobOutcome()
    data class Retry(val message: String) : AiJobOutcome()
    data class Failure(val message: String) : AiJobOutcome()
}

interface AiJobHandler {
    suspend fun execute(profileId: Long, jobKey: String): AiJobOutcome
}

class AiJobRegistry(private val handlers: Map<AiJobType, AiJobHandler>) {
    fun handlerFor(type: AiJobType): AiJobHandler? = handlers[type]
}
