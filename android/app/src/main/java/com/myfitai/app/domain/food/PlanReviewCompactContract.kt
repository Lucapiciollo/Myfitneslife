package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiCompactEnvelope

/** Token-efficient transport contract for the independent plan quality reviewer. */
object PlanReviewCompactContract {
    const val SCHEMA_NAME = "myfitai_plan_review_pipe_v1"
    const val PROTOCOL = """PR1
S|A_R_N|score_0_100
I|issueCode|I_W_M_C|dayOffset_or_-|mealType_or_-"""
    val schemaJson: String get() = AiCompactEnvelope.schemaJson(PROTOCOL)

    enum class Status(val code: String) {
        APPROVED("A"), REJECTED("R"), NEEDS_INPUT("N");

        companion object {
            fun fromCode(code: String): Status = entries.firstOrNull { it.code == code }
                ?: error("PR_STATUS_INVALID")
        }
    }

    enum class Severity(val code: String, val blocking: Boolean) {
        INFO("I", false), WARNING("W", false), MAJOR("M", true), CRITICAL("C", true);

        companion object {
            fun fromCode(code: String): Severity = entries.firstOrNull { it.code == code }
                ?: error("PR_SEVERITY_INVALID")
        }
    }

    enum class IssueCode(val code: String) {
        PROTEIN_DISTRIBUTION("PD"),
        MEAL_TIMING("MT"),
        PREWORKOUT_LOAD("PW"),
        WEEKLY_VARIETY("WV"),
        DUPLICATE_MEAL("DM"),
        FOOD_CONSTRAINT("FC"),
        DIGESTIVE_COMFORT("DC"),
        TRAINING_COHERENCE("TC"),
        BODY_CONTEXT_COHERENCE("BC"),
        FRUIT_VEGETABLE_VARIETY("FV");

        companion object {
            fun fromCode(code: String): IssueCode = entries.firstOrNull { it.code == code }
                ?: error("PR_ISSUE_CODE_INVALID")
        }
    }

    data class Issue(
        val code: IssueCode,
        val severity: Severity,
        val dayOffset: Int?,
        val mealType: String?,
    )

    data class Result(
        val status: Status,
        val score: Int,
        val issues: List<Issue>,
    ) {
        val hasBlockingIssues: Boolean get() = issues.any { it.severity.blocking }
        val accepted: Boolean get() = status == Status.APPROVED && !hasBlockingIssues
    }

    fun parse(json: String): Result {
        val lines = AiCompactEnvelope.data(json)
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull() == "PR1") { "PR1_INVALID" }

        var status: Status? = null
        var score: Int? = null
        val issues = mutableListOf<Issue>()
        lines.drop(1).forEach { line ->
            val p = line.split('|')
            when (p.firstOrNull()) {
                "S" -> {
                    require(p.size == 3 && status == null) { "PR_STATUS_RECORD_INVALID" }
                    status = Status.fromCode(p[1])
                    score = p[2].toIntOrNull()?.takeIf { it in 0..100 }
                        ?: error("PR_SCORE_INVALID")
                }
                "I" -> {
                    require(p.size == 5 && status != null) { "PR_ISSUE_RECORD_INVALID" }
                    val dayOffset = when (p[3]) {
                        "-" -> null
                        else -> p[3].toIntOrNull()?.takeIf { it in 0..6 }
                            ?: error("PR_DAY_INVALID")
                    }
                    val mealType = p[4].takeUnless { it == "-" }?.trim()?.takeIf { it.isNotEmpty() }
                    issues += Issue(
                        code = IssueCode.fromCode(p[1]),
                        severity = Severity.fromCode(p[2]),
                        dayOffset = dayOffset,
                        mealType = mealType,
                    )
                }
                else -> error("PR_RECORD_INVALID")
            }
        }

        val result = Result(
            status = requireNotNull(status) { "PR_STATUS_MISSING" },
            score = requireNotNull(score) { "PR_SCORE_MISSING" },
            issues = issues,
        )
        when (result.status) {
            Status.APPROVED -> require(!result.hasBlockingIssues) { "PR_APPROVED_WITH_BLOCKING_ISSUE" }
            Status.REJECTED -> require(result.hasBlockingIssues) { "PR_REJECTED_WITHOUT_BLOCKING_ISSUE" }
            Status.NEEDS_INPUT -> Unit
        }
        return result
    }
}
