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
    ),
    WEEKLY_REVIEW(
        channelId = "ai_weekly_review",
        channelName = "Review settimanale",
        successTitle = "Review settimanale pronta",
        successText = "Il riepilogo della settimana è disponibile.",
        failureTitle = "Review settimanale non riuscita",
    ),
    NUTRITION_ADVICE(
        channelId = "ai_nutrition_advice",
        channelName = "Consigli nutrizionali",
        successTitle = "Risposta nutrizionale pronta",
        successText = "Il consiglio nutrizionale è disponibile.",
        failureTitle = "Consiglio nutrizionale non disponibile",
    ),
    MEAL_ALTERNATIVES(
        channelId = "ai_meal_alternatives",
        channelName = "Alternative pasto",
        successTitle = "Alternative pasto pronte",
        successText = "Scegli un'alternativa per il pasto.",
        failureTitle = "Alternative pasto non disponibili",
    ),
    CHEAT_UNDERSTANDING(
        channelId = "ai_cheat_understanding",
        channelName = "Comprensione sgarro",
        successTitle = "Valutazione sgarro pronta",
        successText = "Controlla la stima prima di confermare.",
        failureTitle = "Valutazione sgarro non disponibile",
    ),
    CHEAT_ADJUSTMENT(
        channelId = "ai_cheat_adjustment",
        channelName = "Adattamento sgarro",
        successTitle = "Piano adattato",
        successText = "I pasti futuri sono stati riequilibrati.",
        failureTitle = "Adattamento sgarro non riuscito",
    ),
    BODY_PROPORTIONS(
        channelId = "ai_body_proportions",
        channelName = "Proporzioni corporee",
        successTitle = "Interpretazione misure pronta",
        successText = "La lettura delle proporzioni è disponibile.",
        failureTitle = "Interpretazione misure non disponibile",
    ),
    BIA_ANALYSIS(
        channelId = "ai_bia_analysis",
        channelName = "Specialista BIA e sport",
        successTitle = "Analisi sportiva BIA pronta",
        successText = "La lettura muscolare e sportiva è disponibile.",
        failureTitle = "Analisi BIA non disponibile",
    ),
    BIA_IMPORT(
        channelId = "ai_bia_import",
        channelName = "Import bioimpedenza",
        successTitle = "Lettura BIA pronta",
        successText = "Controlla i valori letti prima di salvarli.",
        failureTitle = "Import BIA non riuscito",
    );

    companion object {
        fun fromName(value: String?): AiJobType? = entries.firstOrNull { it.name == value }
    }
}
