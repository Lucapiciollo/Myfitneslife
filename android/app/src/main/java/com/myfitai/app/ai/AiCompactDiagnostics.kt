package com.myfitai.app.ai

import org.json.JSONObject

/** Safe diagnostics for compact agent payloads. Never logs payload contents. */
object AiCompactDiagnostics {
    fun describe(jsonText: String, expectedVersion: String): String = runCatching {
        val envelope = JSONObject(jsonText)
        val data = envelope.optString("data", "")
        val lines = data.lines().filter { it.isNotBlank() }
        val first = lines.firstOrNull()?.trim().orEmpty()
        val last = lines.lastOrNull()?.trim().orEmpty()
        "dataLength=${data.length} lineCount=${lines.size} expectedVersion=$expectedVersion " +
            "firstLineLength=${first.length} firstLineMatches=${first == expectedVersion} " +
            "firstCharCode=${first.firstOrNull()?.code ?: "-"} lastLineLength=${last.length} " +
            "lastRecord=${last.substringBefore('|').take(24)}"
    }.getOrElse { "envelopeDiagnostics=unparseable" }
}
