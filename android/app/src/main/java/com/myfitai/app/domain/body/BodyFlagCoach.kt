package com.myfitai.app.domain.body

import java.util.Locale

/**
 * Vocabolario prudente delle segnalazioni corporee: unica lista dei termini diagnostici/prescrittivi, usata dal
 * test del motore (codici, chiavi, motivi) e dal validatore dell'output del BIA Coach. Sono radici di parola
 * (prefissi), tutte in minuscolo.
 */
object BodyFlagVocabulary {
    private val EATING_DISORDER_STEMS = listOf("disturb", "anoress", "bulim")

    /** Termini che il motore non deve mai contenere nei suoi codici e che il coach non deve mai scrivere. */
    val STEMS: List<String> = listOf(
        "diagnos", "sarcopen", "malatt", "obesit", "obesity", "patolog", "pathol", "disease", "disturb",
        "anoress", "bulim", "ipertension", "diabet", "cura", "terapi", "prescri",
        // Prescrittivi: il coach non da indicazioni terapeutiche ne dosaggi.
        "terapeut", "dosagg", "posolog", "farmac",
    )

    /**
     * Radici controllate sul testo del coach: come [STEMS] senza i disturbi alimentari, che le regole di sicurezza
     * (D, SAFETY_FLAG) chiedono di nominare per rimandare al medico.
     */
    val COACH_OUTPUT_STEMS: List<String> = STEMS - EATING_DISORDER_STEMS.toSet()

    /**
     * Sottoinsieme tollerato quando e negato nella stessa frase ("non e una diagnosi", "senza dosaggi"): i nomi di
     * condizioni (sarcopenia, obesita, malattia...) non sono mai accettati, neppure negati.
     */
    val NEGATABLE_STEMS: Set<String> = setOf("diagnos", "prescri", "terapi", "terapeut", "cura", "dosagg", "posolog", "farmac")
}

/**
 * Collegamento tra i flag deterministici ([BodyFlagEngine]) e il BIA Coach: blocco di contesto, regole di prompt e
 * controlli locali sull'output. Il CODICE decide se un flag esiste; l'IA puo solo spiegarlo.
 */
object BodyFlagCoach {
    const val CONTEXT_LABEL = "FLAG_APP"

    /** Flag (o l'assenza di flag) da passare nel contesto. [report] null = servizio non disponibile. */
    data class Input(val report: BodyFlagReport?)

    /**
     * Regole di prompt: UNA sola fonte per Gemini e OpenAI (il prompt del coach e provider-neutral) e riportate alla
     * lettera in `06-ai/providers/shared/BODY_FLAGS_EXPLAIN_RULES.md`.
     */
    val PROMPT_RULES: List<String> = listOf(
        "Spiega SOLO i flag presenti nella riga FLAG_APP e non inventare altri segnali o soglie.",
        "Non creare, declassare, cancellare o reinterpretare un flag: gravita e affidabilita le decide l'app.",
        "Non fare diagnosi e non usare termini diagnostici (diagnosi, sarcopenia, obesita, malattia, patologia): usa la formula segnale da approfondire con un medico.",
        "Cita solo valori e date dell'evidenza del flag e non commentare valori assenti o non forniti.",
        "Se FLAG_APP indica nessun flag, scrivilo in una sola frase neutra senza aggiungere segnali e senza citare il motivo interno.",
        "Non dare indicazioni terapeutiche ne dosaggi.",
        "Per un flag sul muscolo invita a un test di forza e a un medico: la BIA domestica non misura la forza.",
        "Non scrivere nei testi i codici dei flag (WHTR_HIGH e simili).",
    )

    /** Blocco aggiunto al prompt di sistema del coach. */
    val PROMPT_SECTION: String =
        "Flag calcolati dall'app. La riga FLAG_APP elenca i soli segnali corporei decisi dal codice dell'app, ognuno con codice, " +
            "gravita, affidabilita ed evidenza (valori e date). Tu li spieghi soltanto. L'app rifiuta la risposta che " +
            "rompe una di queste regole: " + PROMPT_RULES.joinToString(" ")

    fun allowedCodes(report: BodyFlagReport?): Set<BodyFlagCode> = report?.flags?.map { it.code }?.toSet().orEmpty()

    /** Riga compatta del contesto: un flag per voce, oppure `nessun flag: <motivo interno>`. */
    fun contextLine(report: BodyFlagReport?): String {
        if (report == null) return "$CONTEXT_LABEL:nessun flag: dati non disponibili ($SUFFIX)"
        if (report.flags.isEmpty()) return "$CONTEXT_LABEL:nessun flag: ${internalReason(report)} ($SUFFIX)"
        val entries = report.flags.joinToString(";") { flag ->
            "${flag.code.name}|gravita=${flag.severity.name}|affidabilita=${flag.reliability.name}|evidenza=" +
                flag.evidence.joinToString(",") { "${it.key}=${number(it.value)}${if (it.unit.isBlank()) "" else " ${it.unit}"} (${it.date})" }
        }
        return "$CONTEXT_LABEL:$entries ($SUFFIX)"
    }

    private const val SUFFIX = "decisi dal codice dell'app: spiegali soltanto, non crearne altri"

    private fun number(value: Double): String = value.toBigDecimal().stripTrailingZeros().toPlainString()

    private fun internalReason(report: BodyFlagReport): String {
        val skipped = report.skipped.joinToString(",") { "${it.rule.name}:${it.reason.name}" }
        return if (skipped.isEmpty()) "nessuna soglia superata" else "regole non valutabili $skipped"
    }

    // ---- Validatore locale dell'output -----------------------------------------------------------------------

    private enum class Area(val allowed: Set<BodyFlagCode>) {
        WAIST(setOf(BodyFlagCode.WHTR_ABOVE_HALF, BodyFlagCode.WHTR_HIGH, BodyFlagCode.WAIST_FIRST_LEVEL, BodyFlagCode.WAIST_SECOND_LEVEL)),
        VISCERAL(setOf(BodyFlagCode.VISCERAL_FAT_RISING)),
        MUSCLE(setOf(BodyFlagCode.SKELETAL_MUSCLE_DECLINE, BodyFlagCode.LEAN_MASS_LOSS_SHARE)),
        RAPID_WEIGHT_LOSS(setOf(BodyFlagCode.RAPID_WEIGHT_LOSS)),
        BMI(setOf(BodyFlagCode.BMI_BAND_UNDER, BodyFlagCode.BMI_BAND_OVER, BodyFlagCode.BMI_BAND_HIGH)),
    }

    private val sentenceBoundary = Regex("[.;!?\\n\\r|]+")
    private val clauseBoundary = Regex("[.;:!?,()\\n\\r|]+")
    private val word = Regex("[\\p{L}\\p{N}_]+")
    private val negations = setOf("non", "nessun", "nessuna", "nessuno", "senza", "n\u00E9", "ne", "no")
    private const val NEGATION_WINDOW = 4

    /** Espressioni comuni con "vita" che non parlano della circonferenza. */
    private val notWaistPhrases = listOf("stile di vita", "stili di vita", "abitudini di vita", "qualit\u00E0 della vita", "qualita della vita", "vita quotidiana")

    private val markerStems = listOf("segnal", "approfond", "preoccup", "allarm", "rischi", "soglia", "attenzion", "medic")

    private fun words(text: String): List<String> = word.findAll(text).map { it.value }.toList()

    private fun hasStem(words: List<String>, stems: List<String>) = words.any { w -> stems.any { w.startsWith(it) } }

    private fun mentions(area: Area, sentence: String, words: List<String>): Boolean = when (area) {
        Area.WAIST -> words.any { it == "vita" || it == "girovita" || it == "addome" || it == "whtr" || it.startsWith("addomin") }
        Area.VISCERAL -> words.any { it.startsWith("viscera") }
        Area.MUSCLE -> words.any { it.startsWith("muscol") } || sentence.contains("massa magra")
        Area.RAPID_WEIGHT_LOSS -> words.any { it.startsWith("rapid") } && hasStem(words, listOf("calo", "perdit", "dimagr"))
        Area.BMI -> words.any { it == "bmi" || it == "sottopeso" || it == "sovrappeso" }
    }

    /**
     * Primo termine diagnostico o prescrittivo affermato in [texts], o null. Le radici di [BodyFlagVocabulary.NEGATABLE_STEMS]
     * sono tollerate quando negate nella stessa frase.
     */
    fun firstForbiddenTerm(texts: List<String>): String? {
        texts.forEach { text ->
            text.lowercase(Locale.ROOT).split(clauseBoundary).forEach { clause ->
                val ws = words(clause)
                ws.forEachIndexed { index, w ->
                    val stem = BodyFlagVocabulary.COACH_OUTPUT_STEMS.firstOrNull { w.startsWith(it) } ?: return@forEachIndexed
                    val negated = stem in BodyFlagVocabulary.NEGATABLE_STEMS &&
                        ws.subList(maxOf(0, index - NEGATION_WINDOW), index).any { it in negations }
                    if (!negated) return w
                }
            }
        }
        return null
    }

    /** Primo codice di flag scritto nel testo ma non presente tra i flag passati, o null. */
    fun firstUnknownFlagCode(texts: List<String>, allowed: Set<BodyFlagCode>): String? {
        val byToken = BodyFlagCode.entries.filter { it !in allowed }
            .flatMap { listOf(it.name.lowercase(Locale.ROOT) to it, it.messageKey to it) }.toMap()
        texts.forEach { text -> words(text.lowercase(Locale.ROOT)).forEach { w -> byToken[w]?.let { return it.name } } }
        return null
    }

    /**
     * Prima area di "segnalazione" (vita/addome, viscerale, muscolo, calo di peso rapido, BMI) scritta come segnale
     * (frase con un marcatore tipo "segnale", "da approfondire", "rischio", "medico") senza il flag corrispondente
     * tra [allowed]; null se coerente.
     */
    fun firstUnprovidedSignal(texts: List<String>, allowed: Set<BodyFlagCode>): String? {
        texts.forEach { text ->
            var normalized = text.lowercase(Locale.ROOT)
            notWaistPhrases.forEach { normalized = normalized.replace(it, " ") }
            normalized.split(sentenceBoundary).forEach { sentence ->
                val ws = words(sentence)
                if (ws.isEmpty() || !hasStem(ws, markerStems)) return@forEach
                Area.entries.firstOrNull { area -> area.allowed.none { it in allowed } && mentions(area, sentence, ws) }
                    ?.let { return it.name }
            }
        }
        return null
    }

    /**
     * @param signalTexts testi in cui un segnale senza flag viene rifiutato (la nota di sicurezza D resta fuori: puo
     *   rimandare al medico per BMI o sintomi anche se il motore non ha un flag).
     * @param allTexts ogni testo scritto dal modello (termini vietati e codici).
     * @return motivo di rifiuto stabile, o null se l'output e coerente con i flag passati.
     */
    fun violation(allTexts: List<String>, signalTexts: List<String>, allowed: Set<BodyFlagCode>): String? {
        firstForbiddenTerm(allTexts)?.let { return "BODY_FLAG_FORBIDDEN_TERM:$it" }
        firstUnknownFlagCode(allTexts, allowed)?.let { return "BODY_FLAG_CODE_NOT_PROVIDED:$it" }
        firstUnprovidedSignal(signalTexts, allowed)?.let { return "BODY_FLAG_SIGNAL_NOT_PROVIDED:$it" }
        return null
    }
}
