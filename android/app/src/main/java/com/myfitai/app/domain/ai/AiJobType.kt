package com.myfitai.app.domain.ai

enum class AiJobType(
    val channelId: String,
    val channelName: String,
    val successTitle: String,
    val successText: String,
    val failureTitle: String,
) {
    NUTRITION_PATH(
        channelId = "ai_nutrition_path",
        channelName = "Percorso nutrizionale",
        successTitle = "Suggerimento nutrizionale pronto",
        successText = "Scegli il percorso nutrizionale da seguire.",
        failureTitle = "Suggerimento nutrizionale non disponibile",
    );

    companion object {
        fun fromName(value: String?): AiJobType? = entries.firstOrNull { it.name == value }
    }
}
