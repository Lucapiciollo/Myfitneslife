package com.myfitai.app.domain.ai

import androidx.work.Data

/** Outcome of a single background AI job run. */
sealed class AiJobOutcome {
    /** [payloadJson] is the canonical, already validated contract JSON the UI can reattach to. */
    data class Success(val payloadJson: String?, val provider: String?) : AiJobOutcome()

    /** Transient provider/network condition: worth retrying without burning extra quota now. */
    data class Retry(val reason: String) : AiJobOutcome()

    data class Failure(val message: String) : AiJobOutcome()
}

/**
 * Executes one AI job type. Handlers live in the domain layer and are registered in the composition
 * root, so adding a new AI operation does not require touching the worker.
 */
interface AiJobHandler {
    suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome
}

class AiJobRegistry(private val handlers: Map<AiJobType, AiJobHandler>) {
    fun handlerFor(type: AiJobType): AiJobHandler? = handlers[type]
}
