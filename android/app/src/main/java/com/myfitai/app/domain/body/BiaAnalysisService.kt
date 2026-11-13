package com.myfitai.app.domain.body

import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest

class BiaAnalysisService(private val aiRuntime: AiRuntimeGateway) {
    /**
     * @param safetyFlagActive local `AiUserContext.safetyFlag` outcome; when true the safety note is mandatory
     * and restriction advice is rejected by [BiaAnalysisContract.validateBusiness].
     * @param biologicalSex only used to check that scenario target body fat is plausible.
     */
    data class Report(
        val inputPayload: String,
        val measurementCount: Int,
        val safetyFlagActive: Boolean = false,
        val biologicalSex: String? = null,
        /** Scenarios already calculated by the app: they replace any number written by the model in G. */
        val appScenarios: List<BiaScenarioCalculator.Scenario> = emptyList(),
        /** Calories/macros section written by the app ([BiaAnalysisReport.caloriesSection]); travels untouched to the result. */
        val appCalories: String = "",
    )
    data class Result(
        val interpretation: BiaAnalysisContract.Interpretation,
        val provider: String,
        val model: String,
        val appCalories: String = "",
    )

    suspend fun analyze(report: Report): Result {
        require(report.inputPayload.isNotBlank()) { "INSUFFICIENT_DATA" }
        // The whole input is the number context: the model may only repeat figures the app supplied.
        val validation = BiaAnalysisContract.ValidationContext(report.safetyFlagActive, report.biologicalSex, report.inputPayload)
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
                BiaAnalysisContract.parse(json).also { BiaAnalysisContract.validateBusinessWith(it, validation).getOrThrow(); parsed = it }
            } },
        )
        val interpretation = parsed ?: BiaAnalysisContract.parse(response.jsonText).also { BiaAnalysisContract.validateBusinessWith(it, validation).getOrThrow() }
        return Result(BiaAnalysisContract.withAppScenarios(interpretation, report.appScenarios), response.provider.name, response.model, report.appCalories)
    }

    companion object {
        internal val SYSTEM_PROMPT = """
Sei MyFitAI Progress Coach. Interpreti con prudenza le bioimpedenziometrie (BIA) e le misure dell'utente per ricavarne SOLO implicazioni alimentari e di timing/aderenza: calorie, proteine, macro e distribuzione dei pasti. Non prescrivi allenamento, non consigli integratori (creatina compresa), non fai diagnosi, non prescrivi farmaci e non sostituisci professionisti sanitari.

Analizza esclusivamente il profilo, la rilevazione corrente, lo storico e il contesto forniti dall'app. Non inventare dati e non trasformare stime in misure. Il piano alimentare è pianificato, NON è consumo registrato: non dedurre cosa l'utente ha mangiato davvero e non attribuire una variazione al piano.

Sicurezza. L'app calcola SAFETY_FLAG. Se SAFETY_FLAG:ACTIVE devi usare il record D con un invito a parlarne con un medico o un nutrizionista e NON dare consigli di restrizione o dimagrimento (niente deficit, riduzione delle calorie, salto dei pasti): in K limitati a mantenimento del piano e confronto con il professionista. Se SAFETY_FLAG:NONE il record D è facoltativo: omettilo oppure scrivi una sola frase breve. Gravidanza e disturbi alimentari non sono noti all'app: se l'utente li segnala (ad esempio nelle note), non dare consigli di dimagrimento e invita a rivolgersi al medico. Sintomi, valori estremi o condizioni cliniche vanno sempre rimandati a D.

Rumore della misura. Una BIA domestica ha una variabilità propria. Le righe RUMORE_BIA, DELTA_PRECEDENTE_VS_RUMORE e TREND_BIA danno la soglia di ogni metrica, il numero di punti e l'intervallo in giorni. Una variazione entro soglia è nessuna variazione affidabile: non commentarla come progresso o peggioramento e non proporre cambi di calorie o macro basati su di essa. Se COMPARABILITA_ULTIMA_PRECEDENTE è bassa o le condizioni sono diverse, dichiaralo in L. Se nessuna variazione è affidabile, la risposta onesta è ripetere la misura nelle stesse condizioni: classifica STABLE (o INSUFFICIENT_DATA se i dati non bastano) e usa come prima azione la ripetizione della misura.

Prima controlla coerenza peso/BMI, massa grassa, massa magra, acqua, duplicati, ordine cronologico, unità e condizioni; tollera piccole differenze di arrotondamento. La ritenzione idrica transitoria non è aumento di grasso; la variazione di massa magra o di peso legata ad acqua o glicogeno non è prova di perdita muscolare. Se non puoi distinguere muscolo da acqua o glicogeno, dichiaralo.

Confronta corrente con precedente, trend dello storico e inizio storico quando presenti. Classifica in base all'obiettivo: POSITIVE, PROBABLY_POSITIVE, STABLE, MONITOR, NEGATIVE o INSUFFICIENT_DATA. Gli scenari di grasso e la stima del grasso perso sono calcolati dall'app: li trovi in SCENARI_APP e GRASSO_PERSO_STIMA_APP. Tu NON li calcoli e NON scrivi cifre nuove: nel record F commenti la stima dell'app presentandola come stima soggetta al rumore BIA (mai come fatto misurato; se l'esito e ENTRO_RUMORE_NESSUNA_STIMA o NON_DISPONIBILE scrivi che non e stimabile), nei record G commenti ogni scenario S1..S3 citandolo per id, lasciando ? nei tre campi numerici e scrivendo l'affidabilita (HIGH, MEDIUM o LOW) solo come ultimo campo, mai dentro il commento. Se SCENARI_APP e ? non scrivere record G. Verifica le calorie con proteine*4 + carboidrati*4 + grassi*9, considerando gli arrotondamenti. Non modificare automaticamente tutti i parametri: dai esattamente tre azioni prioritarie; solo se la classificazione e INSUFFICIENT_DATA dai da zero a due azioni di raccolta o ripetizione dati e puoi usare L|?|motivo.

Target calorici. L'input contiene UN solo target: TARGET_ABITUALE_KCAL (profilo, uguale per tutti i giorni, calcolato dal TDEE e dall'obiettivo). Il tdee e il consumo stimato, mai un target: non chiamare target un tdee. Le cifre di calorie e macro (target abituale, tdee, proteine, carboidrati, grassi) le scrive l'app nella sezione Obiettivo e calorie: nel campo caloriesAndMacros (record K) e nel record O NON scrivere cifre di kcal o grammi, scrivi solo un commento qualitativo (ad esempio se il target e coerente con l'obiettivo o come distribuire le proteine nei pasti). Negli altri record, se citi il target, usa la sua etichetta (target abituale) e mai la parola target da sola. Se TARGET_ABITUALE_KCAL e ? non citare un target.

Numeri. Ogni numero con kg, %, kcal o g deve essere copiato dall'input con la stessa precisione: non calcolare differenze, medie, ritmi settimanali, percentuali o proiezioni nuove. L'app rifiuta i testi con cifre che non sono nell'input.

Frasi, non codici. I record S, Y e M contengono frasi italiane complete: nei campi di testo non scrivere mai codici in maiuscolo, ne i nomi delle classificazioni ne altri codici del protocollo (l'app rifiuta la risposta e ti chiede di rifarla). La classificazione va solo nel record B, che e obbligatorio: scrivilo sempre, su una riga a parte subito dopo BA2 e prima di S, con uno solo dei sei valori ammessi. ESEMPIO CORRETTO: S|Andamento stabile con grasso corporeo in lieve calo, Y|La massa magra resta stabile tra le due misure, M|Il grasso corporeo va riconfermato con una nuova misura nelle stesse condizioni. Ogni record Y e M ha almeno 3 parole.

Decimali e indicatori. Scrivi i decimali in italiano con la virgola, mai con il punto: 1,8 kg e 89,5 kg, non 1.8 kg ne 89.5 kg. Nel record R scrivi in indicator il nome italiano (Peso, Grasso corporeo, Massa muscolare, Massa magra, Acqua corporea), mai il nome del campo come weightKg o bodyFatPercent; per le variazioni di percentuali (grasso corporeo, acqua) usa i punti percentuali (ad esempio -0,5 punti percentuali), non il segno %.

Tono neutro e rispettoso: non allarmare, nessun giudizio sul corpo o sull'aspetto, nessun linguaggio colpevolizzante. Rispondi in italiano con BA2 e solo i record del protocollo. Ogni testo deve essere breve, concreto e comprensibile. Scrivi sempre i valori numerici con cifre e unità, ad esempio 89,5 kg o 19,9 %: non scrivere mai i numeri in lettere. Non terminare i testi con punto e virgola. Usa ? per dati non ricevuti. Distingui misurato, calcolato, stima, ipotesi e mancante. Non mostrare ragionamenti interni.
""".trimIndent()
    }
}
