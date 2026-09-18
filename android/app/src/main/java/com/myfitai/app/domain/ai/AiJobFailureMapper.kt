package com.myfitai.app.domain.ai

import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiTransportException
import com.myfitai.app.ai.AiTransportFailureKind

/**
 * Single place deciding what is worth retrying. Schema or business rejections are deterministic
 * for the same input, so retrying them only burns paid provider quota without changing the result.
 */
object AiJobFailureMapper {
    fun map(error: Throwable): AiJobOutcome = when (error) {
        is AiTransportException.Network -> AiJobOutcome.Retry("Rete non disponibile")
        is AiTransportException.Http -> when (error.failureKind) {
            AiTransportFailureKind.RATE_LIMITED -> AiJobOutcome.Retry("Provider temporaneamente occupato")
            AiTransportFailureKind.PROVIDER_UNAVAILABLE -> AiJobOutcome.Retry("Provider temporaneamente non disponibile")
            AiTransportFailureKind.QUOTA_EXHAUSTED -> AiJobOutcome.Failure("Quota provider esaurita: attendi il reset o verifica il billing")
            else -> AiJobOutcome.Failure("Errore provider HTTP ${error.statusCode}")
        }
        is AiTransportException.NotConfigured -> AiJobOutcome.Failure("Provider IA non configurato")
        is AiExecutionService.Failure.InvalidSchema -> AiJobOutcome.Failure("Formato IA non valido: nessun dato salvato")
        is AiExecutionService.Failure.BusinessRejected -> AiJobOutcome.Failure("Risultato rifiutato dalla validazione locale: nessun dato salvato")
        else -> AiJobOutcome.Failure(error.message ?: "Operazione IA non riuscita")
    }
}
