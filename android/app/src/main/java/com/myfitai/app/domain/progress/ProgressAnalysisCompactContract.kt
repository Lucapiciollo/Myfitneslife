package com.myfitai.app.domain.progress

import com.myfitai.app.ai.AiCompactEnvelope

/** Token-optimized provider-neutral contract for multi-week progress analysis. */
object ProgressAnalysisCompactContract {
    const val SCHEMA_NAME = "myfitai_progress_analysis_pipe_v1"
    const val PROTOCOL = """PA1
C|PR_ST_WL_MR_NT_ID|L_M_H
P|WT_BF_MU_WA_AB_LM_TR_DV_BC|F_U_X|L_M_H
S|summary
V|1_or_0|notes"""

    val schemaJson: String get() = AiCompactEnvelope.schemaJson(PROTOCOL)

    enum class Classification {
        POSITIVE_RECOMPOSITION,
        STABLE,
        WEIGHT_LOSS,
        WEIGHT_LOSS_WITH_MUSCLE_RISK,
        NEGATIVE_TREND,
        INSUFFICIENT_DATA,
    }

    enum class Confidence { LOW, MEDIUM, HIGH }
    enum class PatternCode { WEIGHT, BODY_FAT, MUSCLE, WAIST, ABDOMEN, LIMBS, TRAINING, DEVIATIONS, BODY_COHERENCE }
    enum class Direction { FAVORABLE, UNFAVORABLE, UNCERTAIN }

    data class Pattern(val code: PatternCode, val direction: Direction, val confidence: Confidence)
    data class Response(
        val classification: Classification,
        val confidence: Confidence,
        val patterns: List<Pattern>,
        val summary: String,
        val valid: Boolean,
        val validationNotes: String,
    )

    fun parse(json: String): Response {
        val lines = AiCompactEnvelope.data(json).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull() == "PA1") { "PA1_INVALID" }
        var classification: Classification? = null
        var confidence: Confidence? = null
        var summary: String? = null
        var valid: Boolean? = null
        var notes = ""
        val patterns = mutableListOf<Pattern>()

        lines.drop(1).forEach { line ->
            val p = line.split('|')
            when (p.firstOrNull()) {
                "C" -> {
                    require(p.size == 3 && classification == null) { "PA_CLASSIFICATION_INVALID" }
                    classification = classification(p[1])
                    confidence = confidence(p[2])
                }
                "P" -> {
                    require(p.size == 4 && patterns.size < MAX_PATTERNS) { "PA_PATTERN_INVALID" }
                    patterns += Pattern(patternCode(p[1]), direction(p[2]), confidence(p[3]))
                }
                "S" -> {
                    require(p.size == 2 && summary == null) { "PA_SUMMARY_INVALID" }
                    summary = p[1].trim().also { require(it.isNotBlank() && it.length <= 180) { "PA_SUMMARY_INVALID" } }
                }
                "V" -> {
                    require(p.size == 3 && valid == null && p[1] in setOf("0", "1")) { "PA_VALIDATION_INVALID" }
                    valid = p[1] == "1"
                    notes = p[2].trim()
                }
                else -> error("PA_RECORD_INVALID")
            }
        }

        return Response(
            classification = requireNotNull(classification) { "PA_CLASSIFICATION_MISSING" },
            confidence = requireNotNull(confidence) { "PA_CONFIDENCE_MISSING" },
            patterns = patterns.distinctBy { it.code },
            summary = requireNotNull(summary) { "PA_SUMMARY_MISSING" },
            valid = requireNotNull(valid) { "PA_VALIDATION_MISSING" },
            validationNotes = notes,
        )
    }

    private fun classification(code: String) = when (code) {
        "PR" -> Classification.POSITIVE_RECOMPOSITION
        "ST" -> Classification.STABLE
        "WL" -> Classification.WEIGHT_LOSS
        "MR" -> Classification.WEIGHT_LOSS_WITH_MUSCLE_RISK
        "NT" -> Classification.NEGATIVE_TREND
        "ID" -> Classification.INSUFFICIENT_DATA
        else -> error("PA_CLASSIFICATION_CODE_INVALID")
    }

    private fun confidence(code: String) = when (code) {
        "L" -> Confidence.LOW
        "M" -> Confidence.MEDIUM
        "H" -> Confidence.HIGH
        else -> error("PA_CONFIDENCE_CODE_INVALID")
    }

    private fun patternCode(code: String) = when (code) {
        "WT" -> PatternCode.WEIGHT
        "BF" -> PatternCode.BODY_FAT
        "MU" -> PatternCode.MUSCLE
        "WA" -> PatternCode.WAIST
        "AB" -> PatternCode.ABDOMEN
        "LM" -> PatternCode.LIMBS
        "TR" -> PatternCode.TRAINING
        "DV" -> PatternCode.DEVIATIONS
        "BC" -> PatternCode.BODY_COHERENCE
        else -> error("PA_PATTERN_CODE_INVALID")
    }

    private fun direction(code: String) = when (code) {
        "F" -> Direction.FAVORABLE
        "U" -> Direction.UNFAVORABLE
        "X" -> Direction.UNCERTAIN
        else -> error("PA_DIRECTION_CODE_INVALID")
    }

    const val MAX_PATTERNS = 6
}
