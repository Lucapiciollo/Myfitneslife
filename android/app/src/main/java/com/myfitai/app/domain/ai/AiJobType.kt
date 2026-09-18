package com.myfitai.app.domain.ai

/**
 * Every AI call is a background job: provider round trips take tens of seconds, so keeping them
 * attached to a screen means losing the work when the user navigates away or the process is killed.
 *
 * Each type declares its own notification channel, as agreed, so the user can silence one kind of
 * AI result without losing the others.
 */
enum class AiJobType(
    val channelId: String,
    val channelName: String,
    val notificationTitle: String,
    val successText: String,
    val failureTitle: String,
) {
    WEEKLY_PLAN(
        channelId = "ai_weekly_plan",
        channelName = "Piani alimentari",
        notificationTitle = "Piano alimentare aggiornato",
        successText = "Il nuovo menu è stato validato e salvato.",
        failureTitle = "Piano alimentare non aggiornato",
    ),
    PROGRESS_ANALYSIS(
        channelId = "ai_progress_analysis",
        channelName = "Analisi progressi",
        notificationTitle = "Analisi progressi pronta",
        successText = "La lettura dei tuoi progressi è disponibile.",
        failureTitle = "Analisi progressi non riuscita",
    ),
    WEEKLY_REVIEW(
        channelId = "ai_weekly_review",
        channelName = "Review settimanale",
        notificationTitle = "Review settimanale pronta",
        successText = "Il riepilogo della settimana è disponibile.",
        failureTitle = "Review settimanale non riuscita",
    ),
    BODY_PROPORTIONS(
        channelId = "ai_body_proportions",
        channelName = "Proporzioni corporee",
        notificationTitle = "Lettura misure pronta",
        successText = "L'interpretazione delle tue misure è disponibile.",
        failureTitle = "Lettura misure non riuscita",
    ),
    CHEAT_UNDERSTANDING(
        channelId = "ai_cheat",
        channelName = "Extra e adattamenti",
        notificationTitle = "Extra analizzato",
        successText = "L'analisi dell'extra è pronta da confermare.",
        failureTitle = "Analisi extra non riuscita",
    ),
    CHEAT_ADJUSTMENT(
        channelId = "ai_cheat",
        channelName = "Extra e adattamenti",
        notificationTitle = "Piano adattato",
        successText = "I pasti futuri sono stati riequilibrati.",
        failureTitle = "Adattamento non riuscito",
    ),
    NUTRITION_ADVICE(
        channelId = "ai_nutrition_advice",
        channelName = "Consigli nutrizionali",
        notificationTitle = "Risposta pronta",
        successText = "Il consiglio nutrizionale è disponibile.",
        failureTitle = "Consiglio non disponibile",
    ),
    MEAL_ALTERNATIVES(
        channelId = "ai_meal_alternatives",
        channelName = "Alternative pasto",
        notificationTitle = "Alternative pasto pronte",
        successText = "Le alternative per il pasto sono disponibili da scegliere.",
        failureTitle = "Alternative non disponibili",
    ),
    BIA_IMPORT(
        channelId = "ai_bia_import",
        channelName = "Import bioimpedenza",
        notificationTitle = "Lettura bioimpedenza pronta",
        successText = "I valori letti dalla foto sono pronti da confermare.",
        failureTitle = "Lettura bioimpedenza non riuscita",
    ),
    NUTRITION_PATH(
        channelId = "ai_nutrition_path",
        channelName = "Percorso nutrizionale",
        notificationTitle = "Suggerimento percorso pronto",
        successText = "L'analisi nutrizionale ha preparato un suggerimento da scegliere.",
        failureTitle = "Suggerimento percorso non riuscito",
    ),
    ;

    companion object {
        fun fromNameOrNull(value: String?): AiJobType? = entries.firstOrNull { it.name == value }
    }
}
