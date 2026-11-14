package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.food.catalog.FoodCatalog
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Catalogo REALE (assets) e testi MFP2 di prova per i test del lotto MACRO L3. Nessun dato personale. */
internal object FoodCatalogPlanFixtures {
    val catalog: FoodCatalog by lazy {
        val file = listOf("src/main/assets/food/catalog_v1.json", "app/src/main/assets/food/catalog_v1.json", "android/app/src/main/assets/food/catalog_v1.json")
            .map(::File).firstOrNull { it.isFile } ?: error("catalogo non trovato (cwd=${File(".").absolutePath})")
        file.inputStream().use { FoodCatalog.load(it) }
    }

    /** Lunedi' 16/11/2026: il piano di prova genera la sola domenica (1 giorno), con 4 pasti. */
    val monday: LocalDate = LocalDate.of(2026, 11, 16)
    val day: LocalDate = monday.plusDays(6)
    const val MEALS_PER_DAY = 4

    fun targets(kcal: Double = 2000.0, protein: Double = 130.0, carbs: Double = 230.0, fat: Double = 65.0) =
        NutritionBusinessValidator.Targets(kcal = kcal, proteinG = protein, carbsG = carbs, fatG = fat)

    fun context(
        targets: NutritionBusinessValidator.Targets = targets(),
        profile: DietaryProfile = DietaryProfile(),
        sports: SportsNutritionClassifier.Mode = SportsNutritionClassifier.Mode.NORMAL,
    ) = FoodCatalogPlanBuilder.Context(
        monday = monday,
        generationStart = day,
        periodWeeks = 1,
        targets = targets,
        dailyTargets = mapOf(day.toEpochDay() to targets),
        dietaryProfile = profile,
        sportsMode = sports,
        mealsPerDay = MEALS_PER_DAY,
        maintenanceCeilingKcal = null,
    )

    class MealSpec(val type: String, val title: String, val time: Int, val preparation: String, val items: List<Pair<String, String>>)

    /** Pasti di una giornata da circa 1.890 kcal (target 2.000): il modello propone, l'app riscala. */
    val standardMeals: List<MealSpec> = listOf(
        MealSpec("BREAKFAST", "Yogurt greco con avena e banana", 450, "Mescola lo yogurt con i fiocchi e la banana a pezzi.",
            listOf("it.yogurt_greco_0" to "200", "it.fiocchi_avena" to "50", "it.banana" to "100", "it.latte_intero" to "150")),
        MealSpec("LUNCH", "Pasta con pollo e zucchine", 780, "Cuoci la pasta per 10 minuti, rosola il pollo con le zucchine e condisci con l'olio.",
            listOf("it.pasta_semola_secca" to "90", "it.petto_pollo" to "150", "it.olio_evo" to "15", "it.zucchine" to "150")),
        MealSpec("SNACK", "Mela con pane integrale", 990, "Servi la mela a spicchi con il pane.",
            listOf("it.mela" to "150", "it.pane_integrale" to "40")),
        MealSpec("DINNER", "Merluzzo al forno con patate", 1230, "Inforna il merluzzo con le patate a 180 gradi e condisci con l'olio.",
            listOf("it.merluzzo" to "200", "it.patate" to "250", "it.olio_evo" to "10", "it.pane_integrale" to "50")),
    )

    /** Giornata troppo leggera per qualunque target realistico: tutte le voci vicine al minimo di porzione. */
    val tinyMeals: List<MealSpec> = listOf(
        MealSpec("BREAKFAST", "Mela semplice", 450, "Lava e taglia la mela.", listOf("it.mela" to "50")),
        MealSpec("LUNCH", "Zucchine al vapore", 780, "Cuoci le zucchine al vapore.", listOf("it.zucchine" to "50")),
        MealSpec("SNACK", "Mela a spicchi", 990, "Taglia la mela a spicchi.", listOf("it.mela" to "50")),
        MealSpec("DINNER", "Zucchine in padella", 1230, "Rosola le zucchine.", listOf("it.zucchine" to "50")),
    )

    fun payload(
        meals: List<MealSpec> = standardMeals,
        supplements: List<String> = emptyList(),
        weekStart: Long = monday.toEpochDay(),
        date: Long = day.toEpochDay(),
        extraLines: List<String> = emptyList(),
    ): String = buildList {
        add("MFP2")
        add("W|$weekStart")
        add("D|$date")
        meals.forEach { meal ->
            add("M|${meal.type}|${meal.title}|${meal.time}|${meal.preparation}")
            meal.items.forEach { (id, grams) -> add("I|$id|$grams") }
        }
        addAll(supplements)
        add("H|Bevi con regolarita' durante la giornata")
        addAll(extraLines)
        add("V|1|target rispettati")
    }.joinToString("\n")

    fun envelope(payload: String): String = JSONObject().put("data", payload).toString()
}
