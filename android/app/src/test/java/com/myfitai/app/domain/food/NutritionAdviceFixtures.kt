package com.myfitai.app.domain.food

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared D1 fixtures: the SAME advice content rendered as the pipe protocol NA1 (inside the `{"data":...}` envelope),
 * as the native typed JSON of [NutritionAdviceJsonContract] and as a "light" JSON (ingredient names only, i.e. exactly
 * the information the pipe carries). Content parity is by construction: one [Sample] -> three encodings.
 */
internal object NutritionAdviceFixtures {
    data class Ingredient(val name: String, val quantity: Double, val unit: String)

    data class Option(
        val title: String,
        val reason: String,
        val kcal: Int,
        val proteinG: Double,
        val carbsG: Double,
        val fatG: Double,
        val ingredients: List<Ingredient>,
    )

    data class Sample(
        val id: String,
        val inScope: Boolean,
        val answer: String,
        val options: List<Option>,
        val assumptions: String,
    )

    const val VALIDATION_NOTES = "Controlli interni superati."

    private fun num(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    /** `NA1` text exactly as a model writes it (one record per line). */
    fun Sample.pipeText(): String = buildString {
        append("NA1\nS|").append(if (inScope) 1 else 0)
        append("\nA|").append(answer)
        options.forEach { o ->
            append("\nO|").append(o.title).append('|').append(o.reason).append('|').append(o.kcal)
                .append('|').append(num(o.proteinG)).append('|').append(num(o.carbsG)).append('|').append(num(o.fatG))
                .append('|').append(o.ingredients.joinToString(",") { it.name })
        }
        append("\nQ|").append(assumptions)
        append("\nV|1|").append(VALIDATION_NOTES)
    }

    /** What the provider returns today: one JSON object with one `data` string. */
    fun Sample.pipeEnvelope(): String = JSONObject().put("data", pipeText()).toString()

    /** Native typed JSON (schema of [NutritionAdviceJsonContract]); ingredients carry quantity and unit. */
    fun Sample.jsonFull(): String = JSONObject()
        .put("inScope", inScope)
        .put("answer", answer)
        .put("options", JSONArray().also { array ->
            options.forEach { o ->
                array.put(
                    JSONObject().put("title", o.title).put("reason", o.reason).put("kcal", o.kcal)
                        .put("proteinG", o.proteinG).put("carbsG", o.carbsG).put("fatG", o.fatG)
                        .put("ingredients", JSONArray().also { list ->
                            o.ingredients.forEach { i -> list.put(JSONObject().put("name", i.name).put("quantity", i.quantity).put("unit", i.unit)) }
                        }),
                )
            }
        })
        .put("assumptions", assumptions)
        .toString()

    /** Typed JSON with only the information NA1 carries (food names, no quantity/unit, no agentValidation). */
    fun Sample.jsonLight(): String = JSONObject()
        .put("inScope", inScope)
        .put("answer", answer)
        .put("options", JSONArray().also { array ->
            options.forEach { o ->
                array.put(
                    JSONObject().put("title", o.title).put("reason", o.reason).put("kcal", o.kcal)
                        .put("proteinG", o.proteinG).put("carbsG", o.carbsG).put("fatG", o.fatG)
                        .put("foods", JSONArray(o.ingredients.map { it.name })),
                )
            }
        })
        .put("assumptions", assumptions)
        .toString()

    /** Schema of [jsonLight] (measurement only): same as the real one with `foods:[string]` instead of `ingredients`. */
    val lightSchemaJson: String = """{"type":"object","additionalProperties":false,"properties":{""" +
        """"inScope":{"type":"boolean"},"answer":{"type":"string"},""" +
        """"options":{"type":"array","minItems":0,"maxItems":5,"items":{"type":"object","additionalProperties":false,"properties":{""" +
        """"title":{"type":"string"},"reason":{"type":"string"},"kcal":{"type":"integer","minimum":1,"maximum":2500},""" +
        """"proteinG":{"type":"number","minimum":0,"maximum":250},"carbsG":{"type":"number","minimum":0,"maximum":500},""" +
        """"fatG":{"type":"number","minimum":0,"maximum":250},""" +
        """"foods":{"type":"array","minItems":1,"maxItems":10,"items":{"type":"string"}}},""" +
        """"required":["title","reason","kcal","proteinG","carbsG","fatG","foods"]}},""" +
        """"assumptions":{"type":"string"}},"required":["inScope","answer","options","assumptions"]}"""

    /** The domain value every encoding must parse to (agentValidation aside: only the pipe carries one). */
    fun Sample.expectedResponse(agentValidation: NutritionPlanContract.AgentValidation): NutritionAdviceContract.Response =
        NutritionAdviceContract.Response(
            inScope = inScope,
            answer = answer,
            suggestions = options.map { NutritionAdviceContract.Suggestion(it.title, it.reason, it.kcal, it.proteinG.toFloat(), it.carbsG.toFloat(), it.fatG.toFloat(), it.ingredients.map(Ingredient::name)) },
            assumptions = assumptions,
            agentValidation = agentValidation,
        )

    private val pool = listOf(
        Ingredient("petto di pollo", 150.0, "g"), Ingredient("riso basmati", 80.0, "g"),
        Ingredient("olio extravergine di oliva", 1.0, "cucchiaio"), Ingredient("zucchine", 200.0, "g"),
        Ingredient("parmigiano reggiano", 10.0, "g"), Ingredient("pane integrale", 2.0, "fetta"),
        Ingredient("uova", 2.0, "pz"), Ingredient("yogurt greco 0%", 170.0, "g"),
        Ingredient("mela", 1.0, "pz"), Ingredient("tonno al naturale", 80.0, "g"),
        Ingredient("insalata mista", 100.0, "g"), Ingredient("pomodori", 100.0, "g"),
        Ingredient("succo di limone", 5.0, "ml"), Ingredient("merluzzo", 180.0, "g"),
        Ingredient("patate", 200.0, "g"), Ingredient("latte scremato", 200.0, "ml"),
        Ingredient("fiocchi d'avena", 40.0, "g"), Ingredient("miele", 1.0, "cucchiaino"),
        Ingredient("noci", 15.0, "g"), Ingredient("ceci lessati", 120.0, "g"),
    )

    private fun ingredients(optionIndex: Int, count: Int): List<Ingredient> =
        (0 until count).map { pool[(optionIndex * 3 + it) % pool.size] }

    private fun option(title: String, reason: String, index: Int, ingredientCount: Int) = Option(
        title, reason, 380 + index * 25, 28.0 + index, 40.0 + index * 2.5, 9.0 + index,
        ingredients(index, ingredientCount),
    )

    private fun five(titles: List<String>, reasons: List<String>, ingredientCount: Int) =
        (0 until 5).map { option(titles[it], reasons[it], it, ingredientCount) }

    const val OUT_OF_SCOPE = "Posso rispondere solo a richieste di consiglio alimentare e nutrizionale."

    /** golden_short is the NA1 valid response of `ai_golden_fixtures.json` (pinned by a test); the others are new length variants. */
    val samples: List<Sample> = listOf(
        Sample(
            "golden_short", true, "Scegli proteine magre e verdure ai pasti.",
            listOf(
                Option("Pollo e riso", "Proteine e carboidrati bilanciati", 450, 35.0, 45.0, 10.0, listOf(Ingredient("pollo", 150.0, "g"), Ingredient("riso", 80.0, "g"))),
                Option("Merluzzo e patate", "Pasto leggero ricco di proteine", 400, 32.0, 40.0, 8.0, listOf(Ingredient("merluzzo", 180.0, "g"), Ingredient("patate", 200.0, "g"))),
                Option("Uova e pane", "Colazione proteica", 380, 22.0, 30.0, 18.0, listOf(Ingredient("uova", 2.0, "pz"), Ingredient("pane", 2.0, "fetta"))),
                Option("Yogurt e frutta", "Spuntino fresco", 250, 18.0, 30.0, 4.0, listOf(Ingredient("yogurt", 170.0, "g"), Ingredient("mela", 1.0, "pz"))),
                Option("Tonno e insalata", "Cena rapida", 350, 30.0, 15.0, 18.0, listOf(Ingredient("tonno", 80.0, "g"), Ingredient("insalata", 100.0, "g"))),
            ),
            "Porzioni standard senza vincoli dichiarati.",
        ),
        Sample(
            "medium", true, "Hai ancora spazio per un pasto completo: punta su proteine e verdura.",
            five(
                listOf("Pollo, riso e zucchine", "Merluzzo con patate al forno", "Uova strapazzate con pane", "Yogurt greco con avena", "Tonno, ceci e insalata"),
                listOf("Sazia e rientra nel residuo di kcal", "Leggero e ricco di proteine magre", "Veloce, proteico e pratico", "Spuntino fresco con buoni carboidrati", "Fibre e proteine per la sera"),
                4,
            ),
            "Porzioni standard, nessun vincolo dichiarato.",
        ),
        Sample(
            "long", true, "Per il pasto serale hai circa 650 kcal: ecco cinque soluzioni complete e bilanciate.",
            five(
                listOf("Petto di pollo con riso basmati e zucchine", "Merluzzo al forno con patate e pomodorini", "Frittata di uova con pane integrale e insalata", "Yogurt greco 0% con fiocchi d'avena e noci", "Insalata di tonno, ceci e pomodori con olio"),
                listOf("Alto contenuto proteico, carboidrati nella norma per la sera", "Pasto leggero e digeribile, ottimo apporto di proteine magre", "Pratico e veloce da preparare, buon equilibrio di macro", "Fresco e saziante, grassi buoni dalle noci e fibre dall'avena", "Molte fibre e proteine, mantiene il residuo di kcal nei limiti"),
                7,
            ),
            "Quantita' indicative per una porzione singola, olio incluso nel conteggio.",
        ),
        Sample(
            "xlong", true, "Per il pasto serale hai circa 650 kcal: ecco cinque soluzioni complete e bilanciate.",
            five(
                listOf("Petto di pollo con riso basmati e zucchine", "Merluzzo al forno con patate e pomodorini", "Frittata di uova con pane integrale e insalata", "Yogurt greco 0% con fiocchi d'avena e noci", "Insalata di tonno, ceci e pomodori con olio"),
                listOf("Alto contenuto proteico, carboidrati nella norma per la sera", "Pasto leggero e digeribile, ottimo apporto di proteine magre", "Pratico e veloce da preparare, buon equilibrio di macro", "Fresco e saziante, grassi buoni dalle noci e fibre dall'avena", "Molte fibre e proteine, mantiene il residuo di kcal nei limiti"),
                10,
            ),
            "Quantita' indicative per una porzione singola, olio incluso nel conteggio.",
        ),
        Sample("out_of_scope", false, OUT_OF_SCOPE, emptyList(), ""),
    )
}
