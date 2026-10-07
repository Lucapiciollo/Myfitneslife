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
        val recordStats = lines.drop(1)
            .groupBy { it.substringBefore('|').take(12) }
            .entries
            .sortedBy { it.key }
            .joinToString(",") { (record, rows) ->
                val arities = rows.groupingBy { it.split('|').size }.eachCount().entries
                    .sortedBy { it.key }
                    .joinToString("/") { "${it.key}x${it.value}" }
                "$record:${rows.size}:$arities"
            }
        // Structure only: separator counts and the sequence of single-letter record tags, never the text.
        val tagSequence = data.split('|').map(String::trim).filter { it.length == 1 && it[0].isUpperCase() }.take(48).joinToString("")
        // Code points of the character before each `X|` candidate tag: shows which separator the provider used.
        val separators = Regex("(.)[A-Z]\\|").findAll(data).map { it.groupValues[1].first().code }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6).joinToString(",") { "${it.key}x${it.value}" }
        "dataLength=${data.length} lineCount=${lines.size} pipes=${data.count { it == '|' }} spaces=${data.count { it == ' ' }} tags=$tagSequence sepCodes=$separators expectedVersion=$expectedVersion " +
            "firstLineLength=${first.length} firstLineMatches=${first == expectedVersion} " +
            "firstCharCode=${first.firstOrNull()?.code ?: "-"} lastLineLength=${last.length} " +
            "lastRecord=${last.substringBefore('|').take(24)} recordStats=$recordStats"
    }.getOrElse { "envelopeDiagnostics=unparseable" }
}
