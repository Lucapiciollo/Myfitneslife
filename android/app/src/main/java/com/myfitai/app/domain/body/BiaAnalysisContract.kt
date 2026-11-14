package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiCompactEnvelope
import com.myfitai.app.domain.ai.UnsupportedClaimGuard
import kotlin.math.abs

/** Canonical BA2 response contract for the Progress Coach. */
object BiaAnalysisContract {
    const val SCHEMA_NAME = "myfitai_bia_progress_coach_pipe_v2"
    private val classifications = setOf("POSITIVE", "PROBABLY_POSITIVE", "STABLE", "MONITOR", "NEGATIVE", "INSUFFICIENT_DATA")
    private val reliabilityLevels = setOf("HIGH", "MEDIUM", "LOW")

    /** Every enum value of the contract: never a valid free-text sentence (see [BiaFreeText]). */
    internal val enumTokens: Set<String> get() = classifications + reliabilityLevels

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
        // Classification recovered from outside the B record (header line, a line of its own): only used when B is absent.
        var looseClassification: String? = null
        val comparisons = mutableListOf<Comparison>()
        val historical = mutableListOf<HistoricalPeriod>()
        val scenarios = mutableListOf<Scenario>()
        val positives = mutableListOf<String>()
        val monitor = mutableListOf<String>()
        val actions = mutableListOf<String>()

        lines.drop(1).forEach { line ->
            // Providers sometimes end a record with a stray separator; an empty tail is never data.
            val parts = line.split('|').dropLastWhile { it.isBlank() }
            // The tag tolerates case, spaces (`S |`) and markdown/quote noise around it (`**B`, `- B`).
            when (parts.firstOrNull()?.let(::recordTag)) {
                "B" -> {
                    require(classification == null) { "BA2_B_INVALID" }
                    // An empty B is a missing classification (reported as such), not a malformed record.
                    classification = parts.getOrNull(1)?.let { lenientClassification(it) ?: normalizeClassification(it) }
                }
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
                        joinedText(parts, 5, parts.lastIndex).cleanText(), normalizeReliability(parts.last()),
                    )
                }
                "H" -> {
                    require(parts.size >= 2) { "BA2_H_ARITY_${parts.size}" }
                    historical += HistoricalPeriod(parts[1].cleanText(), parts.drop(2).joinToString("|").ifBlank { "?" }.cleanText())
                }
                "G" -> {
                    // G|id|?|?|?|comment|reliability: 7 fields. With 6 there is no reliability and the last field is the
                    // comment. The reliability is only ever the LAST field and never part of the comment.
                    require(parts.size >= 6) { "BA2_G_ARITY_${parts.size}" }
                    val tail = if (parts.size >= 7) reliabilityTail(parts.last()) else null
                    val commentEndExclusive = if (tail != null) parts.lastIndex else parts.size
                    scenarios += Scenario(
                        parts[1].cleanText(), parts[2].cleanText(), parts[3].cleanText(), parts[4].cleanText(),
                        joinedText(parts, 5, commentEndExclusive).ifBlank { "?" }.cleanText(),
                        tail ?: "?",
                    )
                }
                "Y" -> { require(parts.size >= 2) { "BA2_Y_ARITY_${parts.size}" }; positives += parts.drop(1).joinToString("|").cleanText() }
                "M" -> { require(parts.size >= 2) { "BA2_M_ARITY_${parts.size}" }; monitor += parts.drop(1).joinToString("|").cleanText() }
                "X" -> { require(parts.size >= 2) { "BA2_X_ARITY_${parts.size}" }; actions += parts.drop(1).joinToString("|").cleanText() }
                "D" -> {
                    require(safety.isBlank()) { "BA2_D_INVALID" }
                    // The note is optional unless the app-side safety flag is active (see validateBusiness):
                    // an empty `D` or `D|?` is the same as no note.
                    val note = parts.drop(1).joinToString("|").trim().trimEnd(';').trim()
                    if (note.isNotBlank() && note != "?") safety = note.cleanText()
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
                else -> { if (looseClassification == null) looseClassification = looseClassificationLine(line) }
            }
        }
        // Only when the B record is absent: the header line (`BA2|STABLE`), a line of its own (`Stabile`,
        // `Classificazione: Stabile`) or an enum written in the verdict (the validator still rejects that verdict).
        val resolvedClassification = classification ?: looseClassification ?: verdict?.let { lenientClassificationExact(it) }
        return Interpretation(
            classification = requireNotNull(resolvedClassification) { "BIA_CLASSIFICATION_MISSING" },
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

    /**
     * Single action-count rule shared by prompt, parser output and validator: exactly three actions,
     * except for INSUFFICIENT_DATA where 0..2 data-collection actions are accepted and reliability may be `?`.
     */
    private const val INSUFFICIENT_DATA = "INSUFFICIENT_DATA"
    private const val REQUIRED_ACTIONS = 3
    private const val MAX_INSUFFICIENT_DATA_ACTIONS = 2

    /**
     * App-side facts the validator needs. [safetyFlagActive] comes from the local `AiUserContext.safetyFlag`;
     * [biologicalSex] only narrows the plausible body-fat range (unknown sex = widest range).
     */
    data class ValidationContext(
        val safetyFlagActive: Boolean = false,
        val biologicalSex: String? = null,
        /**
         * The exact input sent to the model. When set, every number with a unit (kg, %, kcal, g) written by the model
         * must appear in it ([BiaNumberGuard]); null keeps the check off (old saved results, unit tests).
         */
        val contextText: String? = null,
        /**
         * Flags decided by the app and sent in `FLAG_APP` ([BodyFlagCoach]). When set, the text is also rejected if it uses
         * a diagnostic or prescriptive term, cites a flag code that was not passed, or states a body signal (waist,
         * visceral fat, muscle, rapid weight loss, BMI) without the matching flag. Null keeps these checks off.
         */
        val allowedFlagCodes: Set<BodyFlagCode>? = null,
    )

    /**
     * Replaces the scenarios written by the model with the ones calculated by the app ([BiaScenarioCalculator]).
     * The model only contributes the comment (`assumption`) and the reliability of the scenario it references by id
     * (`S1`..`S3`); its own numbers are discarded. Without app scenarios no scenario is kept. The result keeps
     * the same shape as before, so saved payloads and the UI do not change.
     */
    fun withAppScenarios(value: Interpretation, appScenarios: List<BiaScenarioCalculator.Scenario>): Interpretation {
        val commentary = value.scenarios.associateBy { scenarioId(it.label) }
        return value.copy(
            scenarios = appScenarios.take(MAX_APP_SCENARIOS).map { app ->
                val model = commentary[app.id]
                Scenario(
                    label = "Scenario ${app.id}",
                    targetBodyFat = "${BiaScenarioCalculator.displayPercent(app.targetBodyFatPercent)} %",
                    targetWeight = "${BiaScenarioCalculator.displayNumber(app.targetWeightKg)} kg",
                    fatToLose = "${BiaScenarioCalculator.displayNumber(app.fatToLoseKg)} kg",
                    assumption = model?.assumption?.takeIf { it.isNotBlank() && it != "?" } ?: DEFAULT_SCENARIO_ASSUMPTION,
                    reliability = model?.reliability ?: "?",
                )
            },
        )
    }

    const val DEFAULT_SCENARIO_ASSUMPTION = "Calcolo dell'app: la massa non grassa resta invariata."
    private const val MAX_APP_SCENARIOS = 3
    private val scenarioIdRegex = Regex("(?<![\\p{L}\\p{N}])S([1-9])(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    private fun scenarioId(label: String): String? = scenarioIdRegex.find(label)?.let { "S${it.groupValues[1]}" }

    fun validateBusiness(value: Interpretation): Result<Unit> = validateBusinessWith(value, ValidationContext())

    fun validateBusinessWith(value: Interpretation, context: ValidationContext): Result<Unit> = runCatching {
        require(value.classification in classifications) { "BIA_CLASSIFICATION_INVALID" }
        val insufficientData = value.classification == INSUFFICIENT_DATA
        require(value.reliability in reliabilityLevels || (insufficientData && value.reliability == "?")) {
            "BIA_RELIABILITY_INVALID"
        }
        val text = listOf(
            value.verdict, value.whatIsHappening, value.fatLoss, value.objective,
            value.caloriesAndMacros, value.nextCheck, value.reliabilityReason,
            value.safetyNote, value.question.orEmpty(),
        ) + value.comparisons.flatMap { listOf(it.indicator, it.interpretation) } +
            value.historical.map { it.summary } + value.scenarios.map { it.assumption } +
            value.positives + value.monitor + value.actions
        // Flags: the model only explains what the app decided. The safety note (D) is exempt from the "signal without flag" rule.
        context.allowedFlagCodes?.let { allowedFlags ->
            val violation = BodyFlagCoach.violation(numericText(value), text.filter { it !== value.safetyNote }, allowedFlags)
            violation?.let { throw IllegalArgumentException(it) }
        }
        require(!UnsupportedClaimGuard.containsAffirmed(text, unsupportedClaimTerms)) { "UNSUPPORTED_MEDICAL_OR_CAUSAL_CLAIM" }
        require(value.comparisons.size <= 12) { "TOO_MANY_COMPARISONS" }
        require(value.historical.size <= 4) { "TOO_MANY_HISTORICAL_PERIODS" }
        require(value.scenarios.size <= 3) { "TOO_MANY_SCENARIOS" }
        require(value.positives.size <= 3) { "TOO_MANY_POSITIVES" }
        require(value.monitor.size <= 3) { "TOO_MANY_MONITOR_ITEMS" }
        if (insufficientData) {
            require(value.actions.size <= MAX_INSUFFICIENT_DATA_ACTIONS) { "INSUFFICIENT_DATA_MAX_TWO_ACTIONS" }
        } else {
            require(value.actions.size == REQUIRED_ACTIONS) { "THREE_ACTIONS_REQUIRED" }
        }
        require(value.actions.all(String::isNotBlank)) { "EMPTY_ACTION" }
        // Free text must be sentences, never the enum tokens a model sometimes copies from the protocol (observed on device:
        // `Y|PROBABLY_POSITIVE`, `M|MONITOR`, `S|STABLE`).
        BiaFreeText.validate(value)
        // Safety note: mandatory only when the app raised the safety flag; otherwise empty is accepted
        // (the handler stores a fixed sentence). With the flag active no restriction/weight-loss advice is allowed.
        if (context.safetyFlagActive) {
            require(value.safetyNote.isNotBlank() && BiaAnalysisPresentation.safetyNoteToShow(value.safetyNote) != null) { "SAFETY_NOTE_REQUIRED" }
            val advice = listOf(value.whatIsHappening, value.objective, value.caloriesAndMacros, value.nextCheck) +
                value.positives + value.monitor + value.actions
            require(!UnsupportedClaimGuard.containsAffirmed(advice, restrictionAdviceTerms)) { "SAFETY_FLAG_RESTRICTION_ADVICE" }
        }
        // Numbers: the model comments on the figures supplied by the app (SCENARI_APP, GRASSO_PERSO_STIMA_APP, deltas,
        // targets) and must not introduce new ones. Only numbers followed by kg, %, kcal or g are checked.
        context.contextText?.let { input ->
            val offending = BiaNumberGuard.firstUnsupported(numericText(value), input)
            require(offending == null) { "NUMBER_NOT_IN_CONTEXT:$offending" }
            // Calories and macros are written by the app (see BiaAnalysisReport.caloriesSection): the model only comments.
            BiaFreeText.requireNoCaloriesFigures(value)
        }
        // Plausibility check of the target body fat written by the model in G, kept for callers without app scenarios.
        // With a number context the app replaces every G number with its own calculation (see [withAppScenarios]),
        // so the model's figures there are ignored and not validated.
        if (context.contextText == null) value.scenarios.forEach { scenario ->
            numberRegex.findAll(scenario.targetBodyFat).forEach { match ->
                val number = match.value.replace(',', '.').toDouble()
                require(number in plausibleBodyFatRange(context.biologicalSex)) { "SCENARIO_TARGET_BODY_FAT_IMPLAUSIBLE" }
            }
        }
        require(value.verdict.isNotBlank() && value.whatIsHappening.isNotBlank()) { "EMPTY_BIA_ANALYSIS" }
        value.comparisons.forEach { require(it.reliability == "?" || it.reliability in reliabilityLevels) { "COMPARISON_RELIABILITY_INVALID" } }
        value.scenarios.forEach { require(it.reliability == "?" || it.reliability in reliabilityLevels) { "SCENARIO_RELIABILITY_INVALID" } }
    }

    private val restrictionAdviceTerms = listOf(
        "deficit calorico", "riduci le calorie", "ridurre le calorie", "riduzione calorica", "taglia le calorie",
        "tagliare le calorie", "salta i pasti", "saltare i pasti", "dimagrire", "perdere peso",
    )
    private val numberRegex = Regex("\\d+(?:[.,]\\d+)?")

    /** Plausible target body-fat percent: single source in [BiaScenarioCalculator.plausibleBodyFatRange]. */
    private fun plausibleBodyFatRange(sex: String?): ClosedFloatingPointRange<Double> = BiaScenarioCalculator.plausibleBodyFatRange(sex)

    /** Free text written by the model that may carry numbers (G numeric fields are replaced by the app, not checked). */
    private fun numericText(value: Interpretation): List<String> = listOf(
        value.verdict, value.whatIsHappening, value.fatLoss, value.objective,
        value.caloriesAndMacros, value.nextCheck, value.reliabilityReason, value.safetyNote, value.question.orEmpty(),
    ) + value.comparisons.flatMap { listOf(it.previous, it.current, it.difference, it.interpretation) } +
        value.historical.map { it.summary } + value.scenarios.map { it.assumption } +
        value.positives + value.monitor + value.actions

    private val unsupportedClaimTerms = listOf("diagnosi", "diagnostica", "patologia", "prescrizione farmacologica", "causa certa", "dimagrimento locale")

    /** Records may end with a stray `;` used as a separator by some providers; it is not part of the text. */
    private fun String.cleanText(): String =
        replace('\n', ' ').replace('\r', ' ').trim { it.isWhitespace() || it in textNoise }.trimEnd(';')
            .trim { it.isWhitespace() || it in textNoise }.also { require(it.isNotBlank()) }

    /** Quotes, emphasis and code marks a provider may wrap around a whole field; never part of the text. */
    private val textNoise = setOf('"', '\u201C', '\u201D', '*', '`')
    private val tagNoise = textNoise + setOf('-', '\u2022', '>', '#', '\'', '\u2018', '\u2019')

    /** `S`, ` s `, `**B`, `- B`, `"G` -> the record tag; null when the first field is not a BA2 tag. */
    private fun recordTag(raw: String): String? =
        raw.trim { it.isWhitespace() || it in tagNoise }.uppercase(java.util.Locale.ROOT).takeIf { it in RECORD_FIELDS }

    /** Fields [from, toExclusive) joined back, without the stray separators that a record may leave at the end. */
    private fun joinedText(parts: List<String>, from: Int, toExclusive: Int): String =
        parts.subList(from, toExclusive).joinToString("|").trim().trimEnd('|').trim()

    internal fun normalizeClassification(value: String): String = when (val key = value.trim().trimEnd(';', '.', ',').uppercase().replace(' ', '_').replace('-', '_')) {
        "POSITIVO" -> "POSITIVE"
        "PROBABILMENTE_POSITIVO" -> "PROBABLY_POSITIVE"
        "STABILE" -> "STABLE"
        "MONITORARE", "DA_MONITORARE" -> "MONITOR"
        "NEGATIVO" -> "NEGATIVE"
        "DATI_INSUFFICIENTI" -> "INSUFFICIENT_DATA"
        else -> key
    }

    private val classificationLabelPrefix = Regex("^(?:classification|classificazione|esito)\\s*[:=]\\s*", RegexOption.IGNORE_CASE)
    private val classificationSearch = Regex("(?<![A-Z_])(PROBABLY_POSITIVE|INSUFFICIENT_DATA|POSITIVE|STABLE|MONITOR|NEGATIVE)(?![A-Z_])")

    /** The whole text is one classification (any case, Italian synonym, quotes, an optional `Classificazione:` label). */
    private fun lenientClassificationExact(raw: String): String? {
        val cleaned = raw.trim { it.isWhitespace() || it in tagNoise || it in ".,;:()" }
        val core = classificationLabelPrefix.replace(cleaned, "")
        return normalizeClassification(core).takeIf { it in classifications }
    }

    /** [lenientClassificationExact], or the one classification mentioned in the text (two different ones are ambiguous). */
    internal fun lenientClassification(raw: String): String? {
        lenientClassificationExact(raw)?.let { return it }
        val mentioned = classificationSearch.findAll(raw.uppercase(java.util.Locale.ROOT).replace('-', '_')).map { it.value }.toSet()
        return mentioned.singleOrNull()
    }

    /** A line without a record tag that only states the classification (`Stabile`, `Classificazione: Stabile`). */
    private fun looseClassificationLine(line: String): String? {
        val text = line.trim().trimEnd('|').trim()
        return if (text.isEmpty() || text.contains('|')) null else lenientClassificationExact(text)
    }

    private fun normalizeReliability(value: String): String =
        when (val key = value.trim().trimEnd(';', '.', ',').trim { it in textNoise }.uppercase()) {
            "ALTA", "ALTO", "HIGH" -> "HIGH"
            "MEDIA", "MEDIO", "MODERATE", "MODERATA", "MODERATO", "MEDIUM" -> "MEDIUM"
            "BASSA", "BASSO", "LOW" -> "LOW"
            "?" -> "?"
            else -> key
        }

    private val enumShape = Regex("^[A-Z][A-Z_]{2,}$")

    /**
     * Last field of a G record when it is the reliability: a known value or synonym, or any other enum-shaped token
     * (upper case, e.g. `VERY_HIGH`), which is dropped (reliability unknown) instead of leaking into the comment.
     * Null when the last field is part of the comment.
     */
    private fun reliabilityTail(raw: String): String? {
        val key = normalizeReliability(raw)
        if (key in reliabilityLevels || key == "?") return key
        return if (enumShape.matches(raw.trim().trimEnd(';', '.', ','))) "?" else null
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
BA2 records: B|classification; S|verdict; C|what is happening; F|comment on the app fat-change estimate (an estimate, never a fact); O|objective coherence; K|calories and macros; P|next check; L|reliability|reason; R|indicator|previous|current|difference|interpretation|reliability; H|period|historical trend; G|scenario id S1..S3 from SCENARI_APP|?|?|?|short comment on that scenario|reliability (HIGH, MEDIUM or LOW, written only as the last field, never inside the comment; the app writes the scenario numbers, put ? in the three numeric fields); Y|positive; M|monitor; X|priority action; D|safety note; N|one final question or ?; V|1_or_0|agent notes. Use ? for unavailable values. Every number followed by kg, %, kcal or g must be copied from the input: never calculate or invent new figures. Emit exactly three X records, unless classification is INSUFFICIENT_DATA: then emit 0 to 2 X records (data collection or repeat measurement only) and L|?|reason is allowed. D is mandatory only when the input says SAFETY_FLAG:ACTIVE (then no restriction or weight-loss advice); otherwise omit D or give one short sentence. The B record is mandatory: always emit it as its own line right after BA2, with exactly one of POSITIVE, PROBABLY_POSITIVE, STABLE, MONITOR, NEGATIVE, INSUFFICIENT_DATA; a classification is written only there. S, Y and M are short Italian sentences: never write a classification name or any other code in capitals in a text field (Y and M need at least 3 words). K is a qualitative comment: no kcal or gram figures (the app writes them). Never put | or newlines inside text.
""".trimIndent()
}

/**
 * Presentation rules of a saved analysis, kept pure so they can be unit-tested without the Activity.
 */
object BiaAnalysisPresentation {
    enum class ActionsTitle { THREE_PRIORITY, DO_NOW, DATA_TO_COLLECT }

    /** "Tre azioni prioritarie" only when there really are three; INSUFFICIENT_DATA asks for data instead. */
    fun actionsTitle(classification: String, actionCount: Int): ActionsTitle = when {
        classification.trim().uppercase() == "INSUFFICIENT_DATA" -> ActionsTitle.DATA_TO_COLLECT
        actionCount == 3 -> ActionsTitle.THREE_PRIORITY
        else -> ActionsTitle.DO_NOW
    }

    enum class ReliabilityLabel { HIGH, MEDIUM, LOW, UNKNOWN }

    /** Maps stored values (`HIGH`/`MEDIUM`/`LOW`, Italian synonyms from old results, `?`) to the label shown to the user. */
    fun reliabilityLabel(raw: String): ReliabilityLabel = when (raw.trim().uppercase()) {
        "HIGH", "ALTA" -> ReliabilityLabel.HIGH
        "MEDIUM", "MEDIA" -> ReliabilityLabel.MEDIUM
        "LOW", "BASSA" -> ReliabilityLabel.LOW
        else -> ReliabilityLabel.UNKNOWN
    }

    // ---- Text shown to the user (T4): closed Italian labels, never raw codes or field names ---------------------

    private val classificationLabels = mapOf(
        "POSITIVE" to "Positivo",
        "PROBABLY_POSITIVE" to "Probabilmente positivo",
        "STABLE" to "Stabile",
        "MONITOR" to "Da monitorare",
        "NEGATIVE" to "Negativo",
        "INSUFFICIENT_DATA" to "Dati insufficienti",
    )

    /** Italian label of a BA2 classification (also its Italian synonyms); null for anything outside the closed contract set. */
    fun classificationLabel(raw: String): String? = classificationLabels[BiaAnalysisContract.normalizeClassification(raw)]

    /**
     * Text of the summary section: the classification label ("Esito: Stabile") followed by the model verdict, which is
     * dropped when empty, `?`, a code or just the classification again.
     */
    fun summaryText(classification: String, verdict: String): String {
        val label = classificationLabel(classification)
        val core = verdict.trim().trim { it in ".;,:! " }
        val show = core.isNotEmpty() && core != "?" && !BiaFreeText.isPlaceholder(verdict) &&
            BiaAnalysisContract.normalizeClassification(core) != BiaAnalysisContract.normalizeClassification(classification) &&
            !core.equals(label, ignoreCase = true)
        return listOfNotNull(label?.let { "Esito: $it" }, verdict.trim().takeIf { show }?.let(::displayText)).joinToString("\n")
    }

    /** List entries to show: results saved before T4 may carry enum tokens (`PROBABLY_POSITIVE`) instead of sentences. */
    fun displayItems(items: List<String>): List<String> =
        items.map(String::trim).filter { it.isNotEmpty() && it != "?" && !BiaFreeText.isPlaceholder(it) }.map(::displayText)

    /** Provider-facing field name -> Italian label of the indicator. */
    private val indicatorLabels = linkedMapOf(
        "weightKg" to "Peso",
        "bodyFatPercent" to "Grasso corporeo",
        "visceralFatLevel" to "Grasso viscerale",
        "muscleMassKg" to "Massa muscolare",
        "skeletalMuscleKg" to "Muscolo scheletrico",
        "bodyWaterPercent" to "Acqua corporea (%)",
        "bmrKcal" to "Metabolismo basale",
        "fatMassKg" to "Massa grassa",
        "leanMassKg" to "Massa magra",
        "bodyWaterKg" to "Acqua corporea (kg)",
        "subcutaneousFatPercent" to "Grasso sottocutaneo",
        "boneMassKg" to "Massa ossea",
        "proteinPercent" to "Proteine (%)",
        "proteinKg" to "Proteine (kg)",
        "bodyAgeYears" to "Et\u00E0 corporea",
        "bmi" to "BMI",
    )
    private val indicatorByKey = indicatorLabels.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }

    /** Label of a comparison indicator: raw field names (`weightKg`, `weightKg:`) become Italian, anything else is kept. */
    fun indicatorLabel(raw: String): String {
        val core = raw.trim().trimEnd(':').trim()
        return indicatorByKey[core.lowercase(java.util.Locale.ROOT)] ?: core
    }

    private fun isPercentIndicator(raw: String): Boolean {
        val label = indicatorLabel(raw)
        return label.contains('%') || label.contains("percent", ignoreCase = true) ||
            label.equals("Grasso corporeo", ignoreCase = true) || label.equals("Grasso sottocutaneo", ignoreCase = true)
    }

    private val percentSign = Regex("\\s*%")

    /** Difference of a comparison: decimal comma, and percentage points (`pp`) when the indicator is itself a percentage. */
    fun comparisonDifference(indicator: String, difference: String): String {
        val text = normalizeDecimals(difference.trim())
        return if (isPercentIndicator(indicator)) text.replace(percentSign, " pp") else text
    }

    private val proseFieldNames = indicatorLabels.filterKeys { it != "bmi" }.map { (key, label) ->
        Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(key)}(?![\\p{L}\\p{N}_])") to label.substringBefore(" (")
    }

    private val decimalWithUnit = Regex("(?<![\\p{N}.,])(\\d+)\\.(\\d{1,2})(?=\\s?(?:kg\\b|kcal\\b|cm\\b|%))")

    /**
     * Dot decimals followed by kg, kcal, cm or % become comma decimals ("1.8 kg" -> "1,8 kg"). Deliberately narrow:
     * thousands separators ("2.528 kcal"), dates, versions and numbers without those units are never touched.
     */
    fun normalizeDecimals(text: String): String =
        decimalWithUnit.replace(text) { "${it.groupValues[1]},${it.groupValues[2]}" }

    /** Text written by the model as shown to the user: raw field names in Italian, decimal commas. */
    fun displayText(text: String): String = normalizeDecimals(proseFieldNames.fold(text) { acc, (regex, label) -> regex.replace(acc, label) })

    /** Scenario comment as shown: like [displayText], without a reliability value or separator glued to the end (results before T5). */
    fun scenarioComment(text: String): String = displayText(BiaFreeText.displayComment(text))

    private val neutralSafetyStarts = listOf("nessuna nota", "nessun avviso", "nessuna segnalazione", "nessun alert", "nessuna avvertenza")
    private val nonLetters = Regex("[^\\p{L}\\p{N}]+")

    private fun plainWords(text: String): String = text.lowercase(java.util.Locale.ROOT).replace(nonLetters, " ").trim()

    /**
     * Safety note to show, or null when it is empty or only says there is no note ("Nessuna nota di sicurezza attiva."):
     * a sentence without information is not a note. The fixed sentence stored before T3 is not a note either.
     */
    fun safetyNoteToShow(note: String): String? {
        val words = plainWords(note)
        if (words.isEmpty() || words == plainWords(BiaAnalysisAiJobHandler.DEFAULT_SAFETY_NOTE) ||
            words == plainWords(BiaAnalysisAiJobHandler.LEGACY_SAFETY_NOTE)
        ) return null
        val neutral = neutralSafetyStarts.any { words.startsWith(it) } && words.split(' ').size <= 8 && " ma " !in " $words "
        return if (neutral) null else displayText(note.trim())
    }
}

/** Rules on the free text of a BA2 answer that do not depend on the app numbers. */
internal object BiaFreeText {
    private const val MIN_LIST_WORDS = 3
    private val token = Regex("^[A-Z][A-Z0-9_]+(?:[ ,/]+[A-Z][A-Z0-9_]+)*$")
    private val whitespace = Regex("\\s+")

    private fun core(text: String): String = text.trim().trim { it in ".;,:! " }

    private val separators = Regex("[\\s-]+")
    private fun codeKey(text: String): String = core(text).uppercase(java.util.Locale.ROOT).replace(separators, "_")

    /** An uppercase token (`PROBABLY_POSITIVE`, `FAT_LOSS_WITH_STABLE_MUSCLE`) or a contract enum value in any case. `?` and blank are not text. */
    fun isPlaceholder(text: String): Boolean {
        val trimmed = core(text)
        if (trimmed.isEmpty() || trimmed == "?") return false
        if (token.matches(trimmed)) return true
        return codeKey(trimmed) in BiaAnalysisContract.enumTokens
    }

    private const val ENUM_VALUES = "HIGH|MEDIUM|MODERATE|LOW|POSITIVE|PROBABLY_POSITIVE|STABLE|MONITOR|NEGATIVE|INSUFFICIENT_DATA"
    private const val ITALIAN_LEVELS = "ALTA|ALTO|MEDIA|MEDIO|MODERATA|MODERATO|BASSA|BASSO"

    /** After a separator: any enum value in any case, an Italian level, or nothing at all (a trailing `|`). */
    private val tailAfterSeparator = Regex("\\|\\s*(?:$ENUM_VALUES|$ITALIAN_LEVELS)?\\s*[.;]*\\s*$", RegexOption.IGNORE_CASE)

    /** After a space: only the upper-case English enum value, so that `affidabilita alta` stays an ordinary sentence. */
    private val tailAfterSpace = Regex("\\s(?:$ENUM_VALUES)\\s*[.;]*\\s*$")

    /** True when the comment ends with a separator or with an enum value (`...corporeo|MODERATE`, `...corporeo MODERATE`). */
    fun endsWithEnumTail(text: String): Boolean = text.trim().let { tailAfterSeparator.containsMatchIn(it) || tailAfterSpace.containsMatchIn(it) }

    /**
     * Comment of a scenario as shown to the user. Results saved before T5 can carry the reliability glued to the comment
     * (`...corporeo|MODERATE`): the stored payload is not rewritten, the text shown is cleaned.
     */
    fun displayComment(text: String): String {
        var current = text.trim()
        if (isPlaceholder(current)) return ""
        while (true) {
            val cleaned = current.replace(tailAfterSeparator, "").let { if (it == current) it.replace(tailAfterSpace, "") else it }.trim()
            if (cleaned == current) return current
            current = cleaned
        }
    }

    fun validate(value: BiaAnalysisContract.Interpretation) {
        // Only the contract code itself: an Italian word such as "Stabile" is a (poor) sentence, not a leaked enum.
        require(codeKey(value.verdict) != codeKey(value.classification)) { "BIA_VERDICT_EQUALS_CLASSIFICATION" }
        val fields = listOf(
            "verdict" to listOf(value.verdict),
            "whatIsHappening" to listOf(value.whatIsHappening),
            "fatLoss" to listOf(value.fatLoss),
            "objective" to listOf(value.objective),
            "caloriesAndMacros" to listOf(value.caloriesAndMacros),
            "nextCheck" to listOf(value.nextCheck),
            "reliabilityReason" to listOf(value.reliabilityReason),
            "positives" to value.positives,
            "monitor" to value.monitor,
            "actions" to value.actions,
            "safetyNote" to listOf(value.safetyNote),
            "question" to listOfNotNull(value.question),
            "comparisons" to value.comparisons.map { it.interpretation },
            "historical" to value.historical.map { it.summary },
            "scenarios" to value.scenarios.map { it.assumption },
        )
        fields.forEach { (name, texts) -> require(texts.none(::isPlaceholder)) { "BIA_PLACEHOLDER_TEXT:$name" } }
        // The comment of a pipe record (R, G) never ends with an enum value or a separator: the reliability is the LAST field,
        // not the end of the sentence (observed on device: `...corporeo|MODERATE` shown to the user).
        listOf(
            "comparisons" to value.comparisons.map { it.interpretation },
            "scenarios" to value.scenarios.map { it.assumption },
        ).forEach { (name, texts) -> require(texts.none(::endsWithEnumTail)) { "BIA_TEXT_ENUM_TAIL:$name" } }
        listOf("positives" to value.positives, "monitor" to value.monitor).forEach { (name, texts) ->
            require(texts.all { core(it).split(whitespace).size >= MIN_LIST_WORDS }) { "BIA_TEXT_TOO_SHORT:$name" }
        }
    }

    fun requireNoCaloriesFigures(value: BiaAnalysisContract.Interpretation) {
        listOf("objective" to value.objective, "caloriesAndMacros" to value.caloriesAndMacros).forEach { (name, text) ->
            require(BiaNumberGuard.quantities(text).none { it.unit == "kcal" || it.unit == "g" }) { "BIA_CALORIES_TEXT_HAS_FIGURES:$name" }
        }
    }
}

/**
 * Checks that the numbers the model writes were supplied by the app. Deliberately simple: a number followed by
 * kg, %, kcal or g (or a range `a-b` before the unit) must match, within the rounding of its own decimals, a number
 * found in the input. Numbers without those units (`3 azioni`, `1 settimana`) and 0 / 100 % are never checked.
 */
object BiaNumberGuard {
    data class Quantity(val value: Double, val decimals: Int, val unit: String) {
        override fun toString(): String = "${raw()} $unit"
        private fun raw(): String = String.format(java.util.Locale.US, "%.${decimals}f", value)
    }

    private const val NUM = "\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+(?:[.,]\\d+)?"
    private val quantityRegex = Regex(
        "(?<![\\p{L}\\p{N}.,])($NUM)(?:\\s*[-\\u2013\\u2014]\\s*($NUM))?\\s*(kcal|kg|cm|%|g(?![\\p{L}]))",
        RegexOption.IGNORE_CASE,
    )
    private val contextNumberRegex = Regex("\\d+(?:[.,]\\d+)?")
    private val isoDate = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val countFields = Regex("(?<![\\p{L}\\p{N}_])(?:giorni_tra_misure|giorni|score|n)=\\d+(?:/100)?")
    private val thousands = Regex("^\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?$")

    fun quantities(text: String): List<Quantity> = quantityRegex.findAll(text).flatMap { match ->
        val unit = match.groupValues[3].lowercase()
        listOf(match.groupValues[1], match.groupValues[2]).filter { it.isNotEmpty() }.map { parse(it, unit) }
    }.toList()

    private fun parse(token: String, unit: String): Quantity {
        if (thousands.matches(token)) {
            val normalized = token.replace(".", "").replace(',', '.')
            return Quantity(normalized.toDouble(), token.substringAfter(',', "").length, unit)
        }
        val normalized = token.replace(',', '.')
        return Quantity(normalized.toDouble(), normalized.substringAfter('.', "").length, unit)
    }

    /** Numbers present in the input, with dates and bookkeeping counters (days, n, score) removed. */
    fun contextNumbers(context: String): List<Double> = contextNumberRegex
        .findAll(context.replace(isoDate, " ").replace(countFields, " "))
        .mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }
        .toList()

    /** First quantity of [texts] not found in [context], or null when every number is supported. */
    fun firstUnsupported(texts: List<String>, context: String): Quantity? {
        val known = contextNumbers(context)
        return texts.asSequence().flatMap { quantities(it).asSequence() }.firstOrNull { !isSupported(it, known) }
    }

    private fun isSupported(quantity: Quantity, known: List<Double>): Boolean {
        if (quantity.value == 0.0) return true
        if (quantity.unit == "%" && quantity.value == 100.0) return true
        val tolerance = 0.5 * Math.pow(10.0, -quantity.decimals.toDouble()) + 1e-6
        return known.any { abs(it - quantity.value) <= tolerance }
    }
}
