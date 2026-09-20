package com.myfitai.app.ai

/**
 * Short compact agents use bounded responses; nutrition plans do not have a fixed
 * app-level output budget, because the amount of requested days and meals varies.
 * Null means no explicit application cap; it does not override model/provider limits.
 */
internal object AiOutputTokenPolicy {
    fun effectiveLimit(schemaName: String, requested: Int?): Int? {
        if (!schemaName.contains("_pipe_") || schemaName.contains("weekly_nutrition")) {
            return requested
        }
        val compactCap = when {
            "cheat_adjustment" in schemaName -> 2_500
            "meal_alternatives" in schemaName -> 2_200
            "nutrition_advice" in schemaName -> 900
            "weekly_review" in schemaName -> 700
            "cheat_understanding" in schemaName -> 500
            "body_proportion" in schemaName -> 500
            "bia" in schemaName -> 350
            else -> 1_500
        }
        return requested?.let { minOf(it, compactCap) }
    }
}
