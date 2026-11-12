package com.myfitai.app.ai

/**
 * Output budget of a request. The cap of every contract lives in [AiContractRegistry] (exact schemaName):
 * short compact agents use bounded responses, while the weekly plan has no fixed application budget because the
 * amount of requested days and meals varies. Null means no explicit application cap; it does not override
 * model/provider limits. A schemaName that is not registered is passed through unchanged here and is rejected
 * by [AiRuntimeService] / [AiExecutionService] (never guessed from its name).
 */
internal object AiOutputTokenPolicy {
    fun effectiveLimit(schemaName: String, requested: Int?): Int? {
        val cap = AiContractRegistry.find(schemaName)?.maxOutputTokens ?: return requested
        return requested?.let { minOf(it, cap) } ?: cap
    }
}
