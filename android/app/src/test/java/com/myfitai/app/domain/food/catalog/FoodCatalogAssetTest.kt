package com.myfitai.app.domain.food.catalog

import com.myfitai.app.domain.food.DietaryProfile
import com.myfitai.app.domain.food.FoodSynonyms
import com.myfitai.app.domain.food.nutrition.DoseFormatter
import com.myfitai.app.domain.food.nutrition.UnitEquivalent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Controlli di qualita' sull'asset REALE src/main/assets/food/catalog_v1.json. */
class FoodCatalogAssetTest {

    private companion object {
        fun firstExisting(vararg paths: String): File =
            paths.map(::File).firstOrNull { it.isFile } ?: error("file non trovato (cwd=${File(".").absolutePath}): ${paths.toList()}")

        val assetFile: File by lazy {
            firstExisting("src/main/assets/food/catalog_v1.json", "app/src/main/assets/food/catalog_v1.json", "android/app/src/main/assets/food/catalog_v1.json")
        }
        val spikeFile: File by lazy {
            firstExisting("../../tools/spike-s1/catalog_mini.json", "../tools/spike-s1/catalog_mini.json", "tools/spike-s1/catalog_mini.json")
        }
        val catalog: FoodCatalog by lazy { assetFile.inputStream().use { FoodCatalog.load(it) } }

        /** Energia: 4P + 4(C - fibra) + 2 fibra + 9F (Atwater con fibra a 2 kcal/g). */
        fun atwater(p: Per100): Double {
            val fiber = p.fiber ?: 0.0
            return 4 * p.protein + 4 * (p.carbs - fiber) + 2 * fiber + 9 * p.fat
        }

        fun deviates(p: Per100): Boolean {
            val diff = abs(atwater(p) - p.kcal)
            return diff > maxOf(0.12 * p.kcal, 10.0)
        }

        /** Voci A/B la cui energia USDA non segue Atwater, con il motivo noto. */
        val ENERGY_EXCEPTIONS = mapOf(
            "it.aceto_vino" to "acido acetico e residui alcolici non sono macro",
            "it.aceto_balsamico" to "acido acetico e zuccheri del mosto",
            "it.lievito_polvere" to "sali minerali: carboidrati non digeribili",
            "it.cacao_amaro" to "fibra e teobromina: l'energia USDA e' sperimentale",
            "it.crusca_avena" to "beta-glucani: energia USDA inferiore ad Atwater",
            "it.paprika" to "spezia ad alto contenuto di fibra",
            "it.cumino" to "spezia ad alto contenuto di fibra",
            "it.ketchup" to "acidi organici",
            "it.proteine_soia" to "energia USDA con fattori specifici per la soia",
            "it.fagioli_borlotti_secchi" to "fattori Atwater specifici per i legumi",
        )

        /** Concetto FoodSynonyms che definisce ciascun tag (nome di vincolo usato dall'utente). MILK_DRINK e' escluso: "latte" come bevanda. */
        val TAG_CONSTRAINT = mapOf(
            "LACTOSE" to "lattosio", "MILK" to "latticini", "CHEESE" to "formaggio", "BUTTER" to "burro", "GLUTEN" to "glutine",
            "TREE_NUTS" to "frutta a guscio", "WALNUT" to "noce", "ALMOND" to "mandorla", "HAZELNUT" to "nocciola",
            "PISTACHIO" to "pistacchio", "CASHEW" to "anacardio", "PINE_NUT" to "pinolo", "PEANUT" to "arachide", "SOY" to "soia",
            "SESAME" to "sesamo", "CELERY" to "sedano", "MUSTARD" to "senape", "LUPIN" to "lupini", "FISH" to "pesce",
            "CRUSTACEANS" to "crostacei", "MOLLUSCS" to "molluschi", "EGG" to "uova", "RICE" to "riso", "MEAT" to "carne",
            "GELATIN" to "gelatina", "HONEY" to "miele",
        )
    }

    @Test
    fun catalogVersionAndSizeArePresent() {
        assertTrue("catalogVersion mancante", catalog.version >= 1)
        assertTrue("voci attese: almeno 250, trovate ${catalog.entries.size}", catalog.entries.size >= 250)
        assertTrue("troppe voci per un catalogo curato: ${catalog.entries.size}", catalog.entries.size <= 400)
    }

    @Test
    fun idsAreUniqueAsciiAndPrefixed() {
        val ids = catalog.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue("id non valido: $it", Regex("^it\\.[a-z0-9_]+$").matches(it)) }
    }

    @Test
    fun aliasesAreUniqueAndNormalized() {
        assertEquals("collisioni di alias non dichiarate: ${catalog.aliasCollisions}", emptyMap<String, List<String>>(), catalog.aliasCollisions)
        catalog.entries.forEach { e ->
            assertTrue("${e.id}: nessun alias", e.aliases.isNotEmpty())
            e.aliases.forEach { a ->
                assertTrue("${e.id}: alias vuoto", a.isNotEmpty())
                assertEquals("${e.id}: alias non normalizzato '$a'", FoodCatalog.normalizeAlias(a), a)
                assertEquals("${e.id}: alias '$a' non risolve sulla propria voce", e.id, catalog.findByAlias(a)?.id)
            }
            assertEquals(e.id, catalog.findByAlias(e.name)?.id)
        }
    }

    @Test
    fun energyMatchesAtwaterForSourcedEntries() {
        val failures = catalog.entries
            .filter { it.confidence != FoodConfidence.C && it.id !in ENERGY_EXCEPTIONS }
            .filter { deviates(it.per100) }
            .map { "${it.id}: kcal=${it.per100.kcal} atwater=${"%.0f".format(atwater(it.per100))}" }
        assertTrue("kcal fuori da Atwater +-12% (o 10 kcal): $failures", failures.isEmpty())
        // le eccezioni dichiarate devono essere vere, altrimenti vanno tolte
        ENERGY_EXCEPTIONS.keys.forEach { id ->
            val e = catalog.byId(id)
            assertNotNull("eccezione su voce inesistente: $id", e)
            assertTrue("eccezione non piu' necessaria: $id", deviates(e!!.per100))
        }
    }

    @Test
    fun estimatedEntriesAreCoherentAndNotCovered() {
        catalog.entries.filter { it.confidence == FoodConfidence.C }.forEach { e ->
            // anche le stime devono avere macro coerenti con le kcal dichiarate (+-12%)
            assertFalse("${e.id}: stima incoerente con Atwater", deviates(e.per100))
        }
    }

    @Test
    fun waterPlusMacrosAreAtMost100Point5Grams() {
        catalog.entries.forEach { e ->
            val p = e.per100
            val macros = p.protein + p.carbs + p.fat
            assertTrue("${e.id}: macro=$macros", macros <= 100.5)
            p.water?.let { w -> assertTrue("${e.id}: acqua+macro=${w + macros}", w + macros <= 100.5) }
            listOf(p.kcal, p.protein, p.carbs, p.fat).forEach { assertTrue("${e.id}: valore negativo", it >= 0.0) }
            p.fiber?.let { assertTrue("${e.id}: fibra > carboidrati", it <= p.carbs + 0.5) }
        }
    }

    @Test
    fun pairsAreSymmetricAndYieldIsCoherent() {
        catalog.entries.forEach { e ->
            val pairId = e.pairId
            if (pairId == null) {
                assertEquals("${e.id}: resa senza coppia", null, e.yieldCookedPerRaw)
                return@forEach
            }
            val other = catalog.byId(pairId)
            assertNotNull("${e.id}: pairId inesistente $pairId", other)
            assertEquals("${e.id}: pairId non simmetrico", e.id, other!!.pairId)
            assertEquals("${e.id}: stato non complementare", setOf(FoodState.RAW, FoodState.COOKED), setOf(e.state, other.state))
            assertEquals("${e.id}: resa diversa nella coppia", e.yieldCookedPerRaw, other.yieldCookedPerRaw)
            e.yieldCookedPerRaw?.let { y ->
                val raw = if (e.state == FoodState.RAW) e else other
                val cooked = if (e.state == FoodState.RAW) other else e
                assertTrue("${e.id}: resa fuori range $y", y > 0.3 && y <= 6.0)
                val expected = raw.per100.kcal / cooked.per100.kcal
                assertTrue("${e.id}: resa $y lontana dal rapporto kcal $expected", abs(y - expected) / expected <= 0.02)
            }
        }
        assertTrue(catalog.entries.count { it.yieldCookedPerRaw != null } >= 20)
    }

    @Test
    fun unitsAndPortionsAreSane() {
        catalog.entries.forEach { e ->
            e.units.forEach { u ->
                assertTrue("${e.id}: unita' ${u.name} con grammi <= 0", u.grams > 0.0)
                assertTrue("${e.id}: unita' senza nome", u.name.isNotBlank())
                assertTrue("${e.id}: unita' ${u.name} senza fonte", u.source.isNotBlank())
                u.ml?.let { ml -> assertTrue("${e.id}: ml <= 0", ml > 0.0) }
            }
            assertEquals("${e.id}: unita' duplicate", e.units.size, e.units.map { it.name }.toSet().size)
            assertTrue("${e.id}: minG > maxG", e.minGrams <= e.maxGrams)
            assertTrue("${e.id}: minG <= 0", e.minGrams > 0.0)
            assertTrue("${e.id}: stepG <= 0", e.stepGrams > 0.0)
            assertTrue("${e.id}: stepG > maxG", e.stepGrams <= e.maxGrams)
            assertTrue("${e.id}: edible", e.edible > 0.0 && e.edible <= 1.0)
            e.density?.let { assertTrue("${e.id}: densita'", it > 0.3 && it < 2.0) }
            assertTrue("${e.id}: mesi fuori range", e.seasonMonths.all { it in 1..12 })
            assertTrue("${e.id}: nessun ruolo", e.roles.isNotEmpty())
            assertTrue("${e.id}: shopCat vuota", e.shopCategory.isNotBlank())
            assertTrue("${e.id}: nome vuoto", e.name.isNotBlank())
        }
        val olio = catalog.byId("it.olio_evo")!!
        assertEquals(13.5, olio.units.first { it.name == "cucchiaio" }.grams, 0.01)
        assertEquals(0.91, olio.density!!, 0.0)
        assertFalse(catalog.byId("it.sale")!!.scalable)
        assertFalse(catalog.byId("it.proteine_polvere")!!.scalable)
    }

    @Test
    fun everyTagIsInTheFoodSynonymsVocabulary() {
        val used = catalog.entries.flatMap { it.tags }.toSet()
        assertTrue("tag non nel vocabolario: ${used - FoodCatalog.TAG_VOCABULARY}", FoodCatalog.TAG_VOCABULARY.containsAll(used))
        // il vocabolario del catalogo e' esattamente l'insieme dei concetti verificati con FoodSynonyms (+ MILK_DRINK)
        assertEquals(FoodCatalog.TAG_VOCABULARY - "MILK_DRINK", TAG_CONSTRAINT.keys)
    }

    @Test
    fun tagsCoverEverythingFoodSynonymsDetectsInTheName() {
        val missing = mutableListOf<String>()
        TAG_CONSTRAINT.forEach { (tag, constraint) ->
            val matcher = FoodSynonyms.constraintMatcher(constraint, strict = true)
            catalog.entries.forEach { e ->
                if (matcher.matches(FoodSynonyms.tokenize(e.name)) && tag !in e.tags) missing += "${e.id} (${e.name}) manca $tag"
            }
        }
        assertTrue("tag mancanti rispetto al validatore: $missing", missing.isEmpty())
    }

    @Test
    fun confidenceIsHonest() {
        catalog.entries.forEach { e ->
            when (e.confidence) {
                FoodConfidence.C -> {
                    assertEquals("${e.id}: C deve essere senza fdcId", null, e.source.fdcId)
                    assertEquals("${e.id}: fonte C", "stima non USDA", e.source.entity)
                    assertTrue("${e.id}: C senza nota 'da verificare con etichetta'", e.note.orEmpty().contains("da verificare con etichetta"))
                }
                FoodConfidence.A, FoodConfidence.B -> {
                    assertNotNull("${e.id}: voce ${e.confidence} senza fdcId USDA", e.source.fdcId)
                    assertEquals("${e.id}: fonte", "USDA FDC", e.source.entity)
                    assertTrue("${e.id}: dataset", e.source.dataset in setOf("SR Legacy", "Foundation"))
                    assertTrue("${e.id}: data", e.source.date.orEmpty().isNotBlank())
                    if (e.confidence == FoodConfidence.B) assertTrue("${e.id}: proxy B senza nota", e.note.orEmpty().isNotBlank())
                }
            }
        }
        // bresaola: il caso di scuola, mai A
        assertEquals(FoodConfidence.C, catalog.byId("it.bresaola")!!.confidence)
        assertTrue(catalog.entries.count { it.confidence == FoodConfidence.C } in 1..15)
    }

    @Test
    fun allSpikeEntriesAreCovered() {
        val spike = JSONObject(spikeFile.readText()).getJSONArray("entries")
        assertTrue("lo spike ha almeno 62 voci", spike.length() >= 62)
        // skyr: lo spike lo mappava sul greco 0%, qui e' una voce B separata (proxy dichiarato)
        val remapped = mapOf("skyr" to "it.skyr")
        for (i in 0 until spike.length()) {
            val s = spike.getJSONObject(i)
            val id = "it." + s.getString("id")
            assertNotNull("voce spike mancante: $id", catalog.byId(id))
            val aliases = s.getJSONArray("aliases")
            for (j in 0 until aliases.length()) {
                val alias = aliases.getString(j)
                val expected = remapped[alias] ?: id
                assertEquals("alias spike '$alias'", expected, catalog.findByAlias(alias)?.id)
            }
        }
    }

    @Test
    fun sampleOfSourcedEntriesMatchesUsdaCsvWhenPresent() {
        val root = listOfNotNull(System.getenv("MYFITAI_USDA_DIR"), File(System.getProperty("java.io.tmpdir"), "myfitai-usda-fdc").path)
            .map(::File).firstOrNull { it.isDirectory }
        assumeTrue("CSV USDA non presenti (MYFITAI_USDA_DIR): controllo a campione saltato", root != null)
        val files = root!!.walkTopDown().filter { it.name == "food_nutrient.csv" }.toList()
        assumeTrue("food_nutrient.csv non trovato sotto $root", files.isNotEmpty())

        val sample = catalog.entries.filter { it.confidence == FoodConfidence.A }.filterIndexed { i, _ -> i % 5 == 0 }
        assertTrue("campione troppo piccolo: ${sample.size}", sample.size >= 40)
        val wanted = sample.associateBy { it.source.fdcId.toString() }
        val values = HashMap<String, HashMap<Int, MutableList<Double>>>()
        files.forEach { file ->
            file.bufferedReader().use { reader ->
                val header = reader.readLine().split(',').map { it.trim('"') }
                val iFdc = header.indexOf("fdc_id")
                val iNutrient = header.indexOf("nutrient_id")
                val iAmount = header.indexOf("amount")
                reader.lineSequence().forEach { line ->
                    val cols = line.split(',')
                    val fdc = cols[iFdc].trim('"')
                    if (fdc in wanted) {
                        val amount = cols[iAmount].trim('"').toDoubleOrNull() ?: return@forEach
                        values.getOrPut(fdc) { HashMap() }.getOrPut(cols[iNutrient].trim('"').toInt()) { mutableListOf() }.add(amount)
                    }
                }
            }
        }
        fun check(e: FoodEntry, label: String, actual: Double?, vararg nutrientIds: Int) {
            val candidates = nutrientIds.flatMap { values[e.source.fdcId.toString()]?.get(it).orEmpty() }
            if (actual == null) {
                assertTrue("${e.id}: $label assente nel catalogo ma presente in USDA $candidates", candidates.isEmpty())
            } else {
                assertTrue("${e.id}: $label=$actual non in USDA $candidates", candidates.any { abs(it - actual) <= 0.01 })
            }
        }
        sample.forEach { e ->
            assertNotNull("${e.id}: fdcId ${e.source.fdcId} senza nutrienti nel CSV", values[e.source.fdcId.toString()])
            check(e, "kcal", e.per100.kcal, 1008, 2047, 2048)
            check(e, "proteine", e.per100.protein, 1003)
            check(e, "carboidrati", e.per100.carbs, 1005)
            check(e, "grassi", e.per100.fat, 1004)
            check(e, "fibra", e.per100.fiber, 1079)
            check(e, "sodio", e.per100.sodiumMg, 1093)
            check(e, "acqua", e.per100.water, 1051)
        }
    }

    @Test
    fun subsetForRealProfilesIsSafeAndDeterministic() {
        val celiac = catalog.subsetFor(CatalogConstraints(profile = DietaryProfile(allergies = listOf("glutine"))))
        assertTrue(celiac.none { "GLUTEN" in it.tags })
        assertTrue(celiac.any { it.id == "it.riso_basmati" })
        assertTrue(celiac.none { it.id == "it.pasta_semola_secca" })

        val vegan = catalog.subsetFor(CatalogConstraints(profile = DietaryProfile(dietStyle = "vegano")))
        val animalTags = setOf("MEAT", "FISH", "CRUSTACEANS", "MOLLUSCS", "GELATIN", "MILK", "EGG", "HONEY")
        assertTrue(vegan.none { e -> e.tags.any { it in animalTags } })
        assertTrue(vegan.any { it.id == "it.ceci_secchi" })

        val lactose = catalog.subsetFor(CatalogConstraints(profile = DietaryProfile(intolerances = listOf("lattosio"))))
        assertTrue(lactose.none { it.id == "it.latte_ps" || it.id == "it.parmigiano" || it.id == "it.yogurt_magro" })
        assertTrue(lactose.any { it.id == "it.latte_soia_non_zuccherato" })

        val nuts = catalog.subsetFor(CatalogConstraints(profile = DietaryProfile(allergies = listOf("frutta a guscio"))))
        assertTrue(nuts.none { "TREE_NUTS" in it.tags })
        assertTrue(nuts.any { it.id == "it.arachidi" }) // le arachidi sono legumi, non frutta a guscio

        val constraints = CatalogConstraints(profile = DietaryProfile(allergies = listOf("glutine")), month = 7, maxEntries = 120)
        assertEquals(catalog.subsetFor(constraints).map { it.id }, catalog.subsetFor(constraints).map { it.id })
        assertTrue(catalog.subsetFor(constraints).size <= 120)
        assertTrue(catalog.subsetFor(constraints).none { it.id == "it.arancia" })
    }

    /** Condimenti e grassi dosati a cucchiaino/cucchiaio: il passo di porzione deve cadere su un'unita' pratica (L6c). */
    private val SPOON_STEP_IDS = listOf(
        "olio_evo", "olio_girasole", "olio_arachide", "olio_sesamo", "olio_cocco", "olio_lino", "burro", "maionese", "zucchero",
        "zucchero_velo", "miele", "sciroppo_acero", "aceto_vino", "aceto_balsamico", "salsa_soia", "ketchup", "concentrato_pomodoro",
        "crema_mandorle", "crema_anacardi", "tahin", "marmellata", "marmellata_senza_zuccheri", "cacao_amaro", "semi_sesamo", "semi_canapa",
    ).map { "it.$it" }

    private val PLURALS = mapOf("cucchiaio" to "cucchiai", "cucchiaino" to "cucchiaini")

    private fun equivalentsOf(id: String): List<UnitEquivalent> =
        catalog.byId(id)!!.units.map { UnitEquivalent(it.name, it.grams, PLURALS[it.name] ?: it.name) }

    @Test
    fun spoonDosedEntriesHaveStepsOnPracticalUnits() {
        SPOON_STEP_IDS.forEach { id ->
            val e = catalog.byId(id)!!
            assertTrue("$id: scalabile", e.scalable)
            val spoons = e.units.filter { it.name == "cucchiaino" || it.name == "cucchiaio" }
            assertTrue("$id: senza cucchiaino/cucchiaio", spoons.isNotEmpty())
            // passo = multiplo di mezza unita' (mezzo cucchiaino/cucchiaio) entro l'1%
            assertTrue("$id: stepG ${e.stepGrams} non multiplo di un'unita' ${spoons.map { it.grams }}", spoons.any { isMultiple(e.stepGrams, it.grams / 2.0) })
            assertEquals("$id: minG = un passo", e.stepGrams, e.minGrams, 1e-9)
            assertTrue("$id: maxG ${e.maxGrams} non multiplo del passo ${e.stepGrams}", isMultiple(e.maxGrams, e.stepGrams))
        }
    }

    private fun isMultiple(value: Double, base: Double): Boolean {
        val ratio = value / base
        return ratio >= 1.0 - 1e-9 && abs(ratio - Math.round(ratio)) <= 0.01 * ratio
    }

    @Test
    fun realOliveOilDosesLandOnSpoons() {
        val eq = equivalentsOf("it.olio_evo")
        assertEquals("1 cucchiaino", DoseFormatter.format(4.5, eq))
        assertEquals("2 cucchiaini", DoseFormatter.format(9.0, eq))
        assertEquals("1 cucchiaio", DoseFormatter.format(13.5, eq))
        assertEquals("2 cucchiai", DoseFormatter.format(27.0, eq))
    }

    @Test
    fun everyStepMultipleOfSpoonEntriesIsReadableNotGramsOnly() {
        SPOON_STEP_IDS.forEach { id ->
            val e = catalog.byId(id)!!
            val eq = equivalentsOf(id)
            // oltre le 5 unita' del cucchiaio/cucchiaino piu' grande la griglia del formatter e' a interi: li' i grammi sono ammessi
            val limit = 5.0 * e.units.filter { it.name == "cucchiaino" || it.name == "cucchiaio" }.maxOf { it.grams }
            var k = 1
            while (k * e.stepGrams <= minOf(e.maxGrams, limit) + 1e-9) {
                val grams = k * e.stepGrams
                val dose = DoseFormatter.format(grams, eq)
                assertFalse("$id: $grams g -> \"$dose\" (solo grammi)", dose.endsWith(" g"))
                k++
            }
        }
    }
}
