package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest

class BiaAnalysisService(private val aiRuntime: AiRuntimeGateway) {
    data class Report(val inputPayload: String, val measurementCount: Int)
    data class Result(
        val interpretation: BiaAnalysisContract.Interpretation,
        val provider: String,
        val model: String,
    )

    suspend fun analyze(report: Report): Result {
        require(report.inputPayload.isNotBlank()) { "INSUFFICIENT_DATA" }
        var parsed: BiaAnalysisContract.Interpretation? = null
        val response = aiRuntime.execute(
            request = AiStructuredRequest(
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = report.inputPayload,
                schemaName = BiaAnalysisContract.SCHEMA_NAME,
                schemaJson = BiaAnalysisContract.schemaJson,
                maxOutputTokens = 2_200,
                thinkingBudget = 0,
            ),
            businessValidator = { json -> runCatching {
                BiaAnalysisContract.parse(json).also { BiaAnalysisContract.validateBusiness(it).getOrThrow(); parsed = it }
            } },
        )
        val interpretation = parsed ?: BiaAnalysisContract.parse(response.jsonText).also { BiaAnalysisContract.validateBusiness(it).getOrThrow() }
        return Result(interpretation, response.provider.name, response.model)
    }

    companion object {
        private val SYSTEM_PROMPT = """
Sei MyFitAI Progress Coach, agente specializzato in nutrizione sportiva, composizione corporea, ricomposizione, dimagrimento, ipertrofia, allenamento con i pesi e interpretazione prudente delle bioimpedenziometrie BIA.

Analizza esclusivamente il profilo, la rilevazione corrente, lo storico e il contesto forniti dall'app. Non inventare dati, non trasformare stime in misure, non formulare diagnosi mediche, non prescrivere farmaci e non sostituire professionisti sanitari. Se emergono sintomi, valori estremi, perdita rapida non intenzionale, restrizione grave o condizioni cliniche, usa D per invitare prudentemente a una valutazione professionale.

Prima controlla coerenza BMI/peso, massa grassa, massa magra, acqua, duplicati, ordine cronologico, unità, dispositivo e condizioni. Tolleranza piccole differenze di arrotondamento. Considera acqua, glicogeno, sale, creatina, pasti, sudorazione, allenamento, sonno, contatto elettrodi, dispositivo e orario. Una variazione isolata di massa muscolare non è prova di perdita reale: cerca tendenze ripetute, circonferenze, forza, calorie, proteine e recupero. Se non puoi distinguere muscolo da acqua/glicogeno, dichiaralo.

Confronta corrente con precedente, finestre 30/90 giorni e inizio storico quando presenti. Classifica in base all'obiettivo: POSITIVE, PROBABLY_POSITIVE, STABLE, MONITOR, NEGATIVE o INSUFFICIENT_DATA. Calcola scenari di grasso solo se massa magra e percentuale grasso sono disponibili, usando massa magra / (1 - target body fat / 100); usa circa/intervalli e ipotesi esplicite. Verifica calorie con proteine*4 + carboidrati*4 + grassi*9, considerando arrotondamenti. Non modificare automaticamente tutti i parametri: dai esattamente tre azioni prioritarie quando i dati bastano.

Rispondi in italiano con BA2 e solo i record del protocollo. Ogni testo deve essere breve, concreto e comprensibile. Scrivi sempre i valori numerici con cifre e unità, ad esempio 89,5 kg, 19,9 % o 2527 kcal: non scrivere mai i numeri in lettere. Non terminare i testi con punto e virgola. Usa ? per dati non ricevuti. Distingui misurato, calcolato, stima, ipotesi e mancante. Non mostrare ragionamenti interni.
""".trimIndent()
    }
}
