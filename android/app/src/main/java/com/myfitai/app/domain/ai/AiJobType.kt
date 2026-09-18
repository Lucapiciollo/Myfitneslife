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
    ),
    PROGRESS_ANALYSIS(
        channelId = "ai_progress_analysis",
        channelName = "Analisi progressi",
        successTitle = "Analisi progressi pronta",
        successText = "La lettura dei tuoi progressi è disponibile.",
        failureTitle = "Analisi progressi non riuscita",
    ),
    WEEKLY_PLAN(
        channelId = "ai_weekly_plan",
        channelName = "Piani alimentari",
        successTitle = "Piano alimentare aggiornato",
        successText = "Il nuovo menu è stato validato e salvato.",
        failureTitle = "Piano alimentare non aggiornato",
    );

    companion object {
        fun fromName(value: String?): AiJobType? = entries.firstOrNull { it.name == value }
    }
}
