package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiCompactEnvelope

/** Canonical BA2 response contract for the Progress Coach. */
object BiaAnalysisContract {
    const val SCHEMA_NAME = "myfitai_bia_progress_coach_pipe_v2"
    private val classifications = setOf("POSITIVE", "PROBABLY_POSITIVE", "STABLE", "MONITOR", "NEGATIVE", "INSUFFICIENT_DATA")
    private val reliabilityLevels = setOf("HIGH", "MEDIUM", "LOW")

    data class Comparison(
        val indicator: String,
        val previous: String,
        val current: String,
        val difference: String,
        val interpretation: String,
        val reliability: String,
    )

    data class HistoricalPeriod(val period: String, val summary: String)
    data class Scenario(
        val label: String,
        val targetBodyFat: String,
        val targetWeight: String,
        val fatToLose: String,
        val assumption: String,
        val reliability: String,
    )

    data class Interpretation(
        val classification: String,
        val verdict: String,
        val whatIsHappening: String,
        val fatLoss: String,
        val objective: String,
        val caloriesAndMacros: String,
        val nextCheck: String,
        val reliability: String,
        val reliabilityReason: String,
        val comparisons: List<Comparison>,
        val historical: List<HistoricalPeriod>,
        val scenarios: List<Scenario>,
        val positives: List<String>,
        val monitor: List<String>,
        val actions: List<String>,
        val safetyNote: String,
        val question: String?,
        val agentValidation: String,
    )

    val schemaJson: String
        get() = AiCompactEnvelope.schemaJson(PROTOCOL)

    fun parse(json: String): Interpretation {
        val data = AiCompactEnvelope.data(json)
        val primary = runCatching { parseLines(normalizeData(data).lineSequence().map(String::trim).filter(String::isNotBlank).toList()) }
        primary.getOrNull()?.let { return it }
        // Some providers join every record with `|` on a single line instead of using newlines.
        pipeStreamLines(data)?.let { streamed ->
            runCatching { parseLines(listOf("BA2") + streamed) }.getOrNull()?.let { return it }
        }
        throw primary.exceptionOrNull() ?: IllegalArgumentException("BIA_PROGRESS_PIPE_INVALID")
    }

    private fun parseLines(lines: List<String>): Interpretation {
        require(lines.firstOrNull() == "BA2") { "BIA_PROGRESS_PIPE_INVALID" }
        var classification: String? = null
        var verdict: String? = null
        var what: String? = null
        var fatLoss: String? = null
        var objective: String? = null
        var calories: String? = null
        var nextCheck: String? = null
        var reliability: String? = null
        var reliabilityReason: String? = null
        var safety = ""
        var question: String? = null
        var validation = ""
        val comparisons = mutableListOf<Comparison>()
        val historical = mutableListOf<HistoricalPeriod>()
        val scenarios = mutableListOf<Scenario>()
        val positives = mutableListOf<String>()
        val monitor = mutableListOf<String>()
        val actions = mutableListOf<String>()

        lines.drop(1).forEach { line ->
            // Providers sometimes end a record with a stray separator; an empty tail is never data.
            val parts = line.split('|').dropLastWhile { it.isBlank() }
            when (parts.firstOrNull()) {
                "B" -> { require(parts.size >= 2 && classification == null) { "BA2_B_INVALID" }; classification = normalizeClassification(parts[1]) }
                "S" -> { require(parts.size >= 2 && verdict == null) { "BA2_S_INVALID" }; verdict = parts.drop(1).joinToString("|").cleanText() }
                "C" -> { require(parts.size >= 2 && what == null) { "BA2_C_INVALID" }; what = parts.drop(1).joinToString("|").cleanText() }
                "F" -> { require(parts.size >= 2 && fatLoss == null) { "BA2_F_INVALID" }; fatLoss = parts.drop(1).joinToString("|").cleanText() }
                "O" -> { require(parts.size >= 2 && objective == null) { "BA2_O_INVALID" }; objective = parts.drop(1).joinToString("|").cleanText() }
                "K" -> {
                    require(parts.size >= 2 && calories == null) { "BA2_K_INVALID" }
                    calories = parts.drop(1).joinToString("|").cleanText()
                }
                "P" -> { require(parts.size >= 2 && nextCheck == null) { "BA2_P_INVALID" }; nextCheck = parts.drop(1).joinToString("|").cleanText() }
                "L" -> {
                    require(parts.size >= 2 && reliability == null) { "BA2_L_ARITY_${parts.size}" }
                    reliability = normalizeReliability(parts[1])
                    reliabilityReason = parts.drop(2).joinToString("|").ifBlank { "?" }.cleanText()
                }
                "R" -> {
                    require(parts.size >= 7) { "BA2_R_ARITY_${parts.size}" }
                    comparisons += Comparison(
                        parts[1].cleanText(), parts[2].cleanText(), parts[3].cleanText(), parts[4].cleanText(),
                        parts.subList(5, parts.lastIndex).joinToString("|").cleanText(), normalizeReliability(parts.last()),
                    )
                }
                "H" -> {
                    require(parts.size >= 2) { "BA2_H_ARITY_${parts.size}" }
                    historical += HistoricalPeriod(parts[1].cleanText(), parts.drop(2).joinToString("|").ifBlank { "?" }.cleanText())
                }
                "G" -> {
                    require(parts.size >= 6) { "BA2_G_ARITY_${parts.size}" }
                    val hasReliability = parts.last().uppercase() in setOf("HIGH", "MEDIUM", "LOW", "ALTA", "MEDIA", "BASSA", "?")
                    val assumptionEndExclusive = if (hasReliability) parts.lastIndex else parts.size
                    scenarios += Scenario(
                        parts[1].cleanText(), parts[2].cleanText(), parts[3].cleanText(), parts[4].cleanText(),
                        parts.subList(5, assumptionEndExclusive).joinToString("|").cleanText(),
                        if (hasReliability) normalizeReliability(parts.last()) else "?",
                    )
                }
                "Y" -> { require(parts.size >= 2) { "BA2_Y_ARITY_${parts.size}" }; positives += parts.drop(1).joinToString("|").cleanText() }
                "M" -> { require(parts.size >= 2) { "BA2_M_ARITY_${parts.size}" }; monitor += parts.drop(1).joinToString("|").cleanText() }
                "X" -> { require(parts.size >= 2) { "BA2_X_ARITY_${parts.size}" }; actions += parts.drop(1).joinToString("|").cleanText() }
                "D" -> {
                    require(parts.size >= 2 && safety.isBlank()) { "BA2_D_INVALID" }
                    safety = parts.drop(1).joinToString("|").cleanText()
                }
                "N" -> { require(parts.size >= 2 && question == null) { "BA2_N_INVALID" }; question = parts.drop(1).joinToString("|").cleanText().takeIf { it != "?" } }
                // V is agent self-validation metadata; appValidation remains authoritative.
                "V" -> {
                    require(parts.size >= 2 && parts[1].isNotBlank()) { "BA2_V_INVALID" }
                    validation = parts.drop(2).joinToString("|").ifBlank { "?" }.cleanText()
                }
                // Keep the required BA2 records authoritative while tolerating optional
                // provider metadata/extension records. Required fields and business rules
                // below still reject incomplete or malformed analyses.
                else -> Unit
            }
        }
        return Interpretation(
            classification = requireNotNull(classification) { "BIA_CLASSIFICATION_MISSING" },
            verdict = requireNotNull(verdict) { "BIA_VERDICT_MISSING" },
            whatIsHappening = requireNotNull(what) { "BIA_SUMMARY_MISSING" },
            fatLoss = requireNotNull(fatLoss) { "BIA_FAT_LOSS_MISSING" },
            objective = requireNotNull(objective) { "BIA_OBJECTIVE_MISSING" },
            caloriesAndMacros = requireNotNull(calories) { "BIA_CALORIES_MISSING" },
            nextCheck = requireNotNull(nextCheck) { "BIA_NEXT_CHECK_MISSING" },
            reliability = requireNotNull(reliability) { "BIA_RELIABILITY_MISSING" },
            reliabilityReason = requireNotNull(reliabilityReason) { "BIA_RELIABILITY_REASON_MISSING" },
            comparisons = comparisons,
            historical = historical,
            scenarios = scenarios,
            positives = positives,
            monitor = monitor,
            actions = actions,
            safetyNote = safety,
            question = question,
            agentValidation = validation,
        )
    }

    fun validateBusiness(value: Interpretation): Result<Unit> = runCatching {
        require(value.classification in classifications) { "BIA_CLASSIFICATION_INVALID" }
        require(value.reliability in reliabilityLevels) { "BIA_RELIABILITY_INVALID_${value.reliability.take(32)}" }
        val text = listOf(
            value.verdict, value.whatIsHappening, value.fatLoss, value.objective,
            value.caloriesAndMacros, value.nextCheck, value.reliabilityReason,
            value.safetyNote, value.question.orEmpty(),
        ) + value.comparisons.flatMap { listOf(it.indicator, it.interpretation) } +
            value.historical.map { it.summary } + value.scenarios.map { it.assumption } +
            value.positives + value.monitor + value.actions
        require(!containsUnsupportedClaim(text.joinToString(" ").lowercase())) { "UNSUPPORTED_MEDICAL_OR_CAUSAL_CLAIM" }
        require(value.comparisons.size <= 12) { "TOO_MANY_COMPARISONS" }
        require(value.historical.size <= 4) { "TOO_MANY_HISTORICAL_PERIODS" }
        require(value.scenarios.size <= 3) { "TOO_MANY_SCENARIOS" }
        require(value.positives.size <= 3) { "TOO_MANY_POSITIVES" }
        require(value.monitor.size <= 3) { "TOO_MANY_MONITOR_ITEMS" }
        require(value.actions.size == 3) { "THREE_ACTIONS_REQUIRED" }
        require(value.actions.all(String::isNotBlank)) { "EMPTY_ACTION" }
        require(value.safetyNote.isNotBlank()) { "SAFETY_NOTE_REQUIRED" }
        require(value.verdict.isNotBlank() && value.whatIsHappening.isNotBlank()) { "EMPTY_BIA_ANALYSIS" }
        value.comparisons.forEach { require(it.reliability == "?" || it.reliability in reliabilityLevels) { "COMPARISON_RELIABILITY_INVALID" } }
        value.scenarios.forEach { require(it.reliability == "?" || it.reliability in reliabilityLevels) { "SCENARIO_RELIABILITY_INVALID" } }
    }

    private fun containsUnsupportedClaim(text: String): Boolean {
        val medicalTerms = listOf("diagnosi", "diagnostica", "patologia", "prescrizione farmacologica", "causa certa", "dimagrimento locale")
        return medicalTerms.any { term ->
            term in text && listOf("non è $term", "non e $term", "non $term", "nessuna $term", "non costituisce $term")
                .none { disclaimer -> disclaimer in text }
        }
    }

    /** Records may end with a stray `;` used as a separator by some providers; it is not part of the text. */
    private fun String.cleanText(): String =
        replace('\n', ' ').replace('\r', ' ').trim().trimEnd(';').trim().also { require(it.isNotBlank()) }

    private fun normalizeClassification(value: String): String = when (val key = value.trim().trimEnd(';', '.', ',').uppercase().replace(' ', '_').replace('-', '_')) {
        "POSITIVO" -> "POSITIVE"
        "PROBABILMENTE_POSITIVO" -> "PROBABLY_POSITIVE"
        "STABILE" -> "STABLE"
        "MONITORARE", "DA_MONITORARE" -> "MONITOR"
        "NEGATIVO" -> "NEGATIVE"
        "DATI_INSUFFICIENTI" -> "INSUFFICIENT_DATA"
        else -> key
    }

    private fun normalizeReliability(value: String): String = when (val key = value.trim().trimEnd(';', '.', ',').uppercase()) {
        "ALTA", "HIGH" -> "HIGH"
        "MEDIA", "MEDIUM" -> "MEDIUM"
        "BASSA", "LOW" -> "LOW"
        "?" -> "?"
        else -> key
    }

    /** Some provider responses collapse the compact records into one line. */
    private fun cleanedBody(value: String): String {
        val cleaned = value
            .replace('\uFEFF'.toString(), "")
            .replace("```", "")
            // Some Gemini responses contain newline escapes as literal text inside `data`.
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace('\r', '\n')
            .trim()
        // The BA2 header is a protocol marker, not data: some responses omit it entirely.
        return cleaned.removePrefix("BA2").trimStart().removePrefix("|").trimStart()
    }

    /** Record tag -> (minimum, maximum) number of fields, from the BA2 protocol. */
    private val RECORD_FIELDS = mapOf(
        "B" to (1 to 1), "S" to (1 to 1), "C" to (1 to 1), "F" to (1 to 1), "O" to (1 to 1), "K" to (1 to 1),
        "P" to (1 to 1), "L" to (1 to 2), "R" to (6 to 6), "H" to (1 to 2), "G" to (5 to 6), "Y" to (1 to 1),
        "M" to (1 to 1), "X" to (1 to 1), "D" to (1 to 1), "N" to (1 to 1), "V" to (1 to 2),
    )

    /**
     * Rebuilds records from `TAG|field|TAG|field|...` written on one line. Surplus tokens that are not
     * a record tag belong to the preceding field (free text that contained a stray separator).
     */
    private fun pipeStreamLines(value: String): List<String>? {
        val body = cleanedBody(value)
        if (body.contains('\n')) return null
        val tokens = body.split('|').map(String::trim).dropLastWhile { it.isEmpty() }
        if (tokens.size < 3 || tokens.first() !in RECORD_FIELDS) return null
        val lines = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val tag = tokens[index]
            val (min, max) = RECORD_FIELDS[tag] ?: return null
            val fields = mutableListOf<String>()
            index++
            while (index < tokens.size && fields.size < max && (fields.size < min || tokens[index] !in RECORD_FIELDS)) {
                fields += tokens[index]
                index++
            }
            if (fields.size < min) return null
            while (index < tokens.size && tokens[index] !in RECORD_FIELDS) {
                fields[fields.lastIndex] = fields.last() + "|" + tokens[index]
                index++
            }
            lines += (listOf(tag) + fields).joinToString("|")
        }
        return lines
    }

    private fun normalizeData(value: String): String {
        val body = cleanedBody(value)
        if (body.isBlank()) return "BA2"
        if (body.contains('\n')) return "BA2\n$body"
        return "BA2\n" + body.replace(
            // A record tag is a single capital letter followed by `|` that does not continue a word or number
            // (any kind of space, `|` or punctuation may precede it). The last letter of a value such as MEDIUM|
            // is preceded by a letter, so it is never mistaken for an `M|` record.
            Regex("(?<![\\p{L}\\p{N}])(?=[BSCFOKPLRHGYMXDNV]\\|)"),
            "\n",
        ).trim()
    }

    private val PROTOCOL = """
BA2 records: B|classification; S|verdict; C|what is happening; F|fat loss estimate; O|objective coherence; K|calories and macros; P|next check; L|reliability|reason; R|indicator|previous|current|difference|interpretation|reliability; H|period|historical trend; G|scenario|target body-fat|target weight|fat to lose|assumption|reliability; Y|positive; M|monitor; X|priority action; D|safety note; N|one final question or ?; V|1_or_0|agent notes. Use ? for unavailable values. Emit exactly three X records unless classification is INSUFFICIENT_DATA. Never put | or newlines inside text.
""".trimIndent()
}
