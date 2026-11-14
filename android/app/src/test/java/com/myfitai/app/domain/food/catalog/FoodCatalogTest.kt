package com.myfitai.app.domain.food.catalog

import com.myfitai.app.domain.food.DietaryProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Comportamento puro del catalogo su un asset sintetico (l'asset reale e' coperto da FoodCatalogAssetTest). */
class FoodCatalogTest {

    private fun entryJson(
        id: String,
        name: String,
        aliases: List<String>,
        tags: List<String> = emptyList(),
        state: String = "AS_SOLD",
        pair: String? = null,
        season: List<Int> = emptyList(),
        kind: String = "FOOD",
        kcal: Int = 100,
    ): String = """
        {"id":"$id","name":"$name","aliases":[${aliases.joinToString(",") { "\"$it\"" }}],"cat":"TEST","shopCat":"Test",
         "state":"$state",${if (pair != null) "\"pairId\":\"$pair\"," else ""}
         "per100":{"kcal":$kcal,"p":1,"c":10,"f":2},"edible":1.0,
         "units":[{"n":"cucchiaio","g":13.5,"src":"USDA food_portion"}],
         "tags":[${tags.joinToString(",") { "\"$it\"" }}],"role":["CARB"],"season":[${season.joinToString(",")}],
         "minG":10,"maxG":200,"stepG":5,"scalable":true,"confidence":"A",
         "source":{"ente":"USDA FDC","fdcId":1,"dataset":"SR Legacy","data":"2018-04"},"kind":"$kind"}
    """.trimIndent()

    private fun catalogJson(vararg entries: String, version: Int = 7): String =
        """{"catalogVersion":$version,"entries":[${entries.joinToString(",")}]}"""

    private fun load(json: String) = FoodCatalog.load(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))

    private val sample = catalogJson(
        entryJson("it.pasta", "Pasta di semola secca", listOf("pasta", "spaghetti"), tags = listOf("GLUTEN")),
        entryJson("it.riso", "Riso bianco (crudo)", listOf("riso"), tags = listOf("RICE"), state = "RAW", pair = "it.riso_cotto"),
        entryJson("it.riso_cotto", "Riso bianco cotto", listOf("riso cotto"), tags = listOf("RICE"), state = "COOKED", pair = "it.riso"),
        entryJson("it.latte", "Latte intero", listOf("latte", "latte fresco"), tags = listOf("MILK", "LACTOSE", "MILK_DRINK")),
        entryJson("it.mela", "Mela", listOf("mela", "mele"), season = listOf(9, 10, 11, 12, 1, 2, 3, 4)),
        entryJson("it.fragole", "Fragole", listOf("fragole"), season = listOf(4, 5, 6)),
        entryJson("it.pollo", "Petto di pollo (crudo)", listOf("petto di pollo", "pollo"), tags = listOf("MEAT"), state = "RAW"),
        entryJson("it.tonno", "Tonno al naturale", listOf("tonno"), tags = listOf("FISH")),
        entryJson("it.pizza", "Pizza margherita", listOf("pizza"), tags = listOf("GLUTEN", "MILK", "LACTOSE", "CHEESE"), kind = "DISH"),
    )

    @Test
    fun loadParsesVersionAndFields() {
        val catalog = load(sample)
        assertEquals(7, catalog.version)
        assertEquals(9, catalog.entries.size)
        val riso = catalog.byId("it.riso")!!
        assertEquals(FoodState.RAW, riso.state)
        assertEquals("it.riso_cotto", riso.pairId)
        assertEquals(setOf("RICE"), riso.tags)
        assertEquals(FoodConfidence.A, riso.confidence)
        assertEquals(1L, riso.source.fdcId)
        assertEquals("cucchiaio", riso.units.single().name)
        assertEquals(13.5, riso.units.single().grams, 0.0)
        assertNull(riso.density)
        assertNull(riso.per100.fiber)
        assertEquals(FoodKind.DISH, catalog.byId("it.pizza")!!.kind)
    }

    @Test
    fun loadRejectsMissingVersionAndDuplicateIds() {
        assertThrows(IllegalArgumentException::class.java) { load("""{"entries":[]}""") }
        val dup = catalogJson(entryJson("it.a", "A", listOf("a1")), entryJson("it.a", "A bis", listOf("a2")))
        assertThrows(IllegalArgumentException::class.java) { load(dup) }
    }

    @Test
    fun loadRejectsUnknownTag() {
        val bad = catalogJson(entryJson("it.a", "A", listOf("a1"), tags = listOf("NOT_A_TAG")))
        assertThrows(IllegalArgumentException::class.java) { load(bad) }
    }

    @Test
    fun normalizeAliasStripsAccentsArticlesAndPunctuation() {
        assertEquals("olio oliva", FoodCatalog.normalizeAlias("Olio d'Oliva"))
        assertEquals("olio extravergine oliva", FoodCatalog.normalizeAlias("  L'OLIO extravergine di oliva "))
        assertEquals("te verde", FoodCatalog.normalizeAlias("Tè verde"))
        assertEquals("yogurt greco 0", FoodCatalog.normalizeAlias("Yogurt greco 0%"))
        assertEquals("", FoodCatalog.normalizeAlias("il la lo"))
    }

    @Test
    fun aliasLookupUsesNormalizedNameAndAliases() {
        val catalog = load(sample)
        assertEquals("it.pasta", catalog.findByAlias("Pasta")!!.id)
        assertEquals("it.pasta", catalog.findByAlias("la pasta di semola secca")!!.id)
        assertEquals("it.pollo", catalog.findByAlias("Petto di Pollo")!!.id)
        assertEquals("it.riso_cotto", catalog.findByAlias("riso cotto")!!.id)
        assertNull(catalog.findByAlias("ananas"))
        assertNull(catalog.findByAlias(""))
        assertSame(catalog.byId("it.mela"), catalog.findByAlias("mele"))
    }

    @Test
    fun aliasCollisionsAreDeclaredAndFirstEntryWins() {
        val json = catalogJson(
            entryJson("it.a", "Alfa", listOf("comune")),
            entryJson("it.b", "Beta", listOf("comune", "beta uno")),
        )
        val catalog = load(json)
        assertEquals("it.a", catalog.findByAlias("comune")!!.id)
        assertEquals(mapOf("comune" to listOf("it.a", "it.b")), catalog.aliasCollisions)
        assertTrue(load(sample).aliasCollisions.isEmpty())
    }

    @Test
    fun pairOfResolvesBothDirections() {
        val catalog = load(sample)
        assertEquals("it.riso_cotto", catalog.pairOf(catalog.byId("it.riso")!!)!!.id)
        assertEquals("it.riso", catalog.pairOf(catalog.byId("it.riso_cotto")!!)!!.id)
        assertNull(catalog.pairOf(catalog.byId("it.pasta")!!))
    }

    @Test
    fun withoutTagsFiltersAllergensAndKeepsCatalogOrder() {
        val catalog = load(sample)
        val noGluten = catalog.withoutTags(setOf("GLUTEN")).map { it.id }
        assertFalse("it.pasta" in noGluten)
        assertFalse("it.pizza" in noGluten)
        assertEquals(listOf("it.riso", "it.riso_cotto", "it.latte", "it.mela", "it.fragole", "it.pollo", "it.tonno"), noGluten)
        assertEquals(catalog.entries, catalog.withoutTags(emptySet()))
    }

    @Test
    fun inSeasonKeepsYearRoundEntries() {
        val catalog = load(sample)
        val july = catalog.inSeason(7).map { it.id }
        assertFalse("it.mela" in july)
        assertFalse("it.fragole" in july)
        assertTrue("it.pasta" in july)
        val may = catalog.inSeason(5).map { it.id }
        assertTrue("it.fragole" in may)
        assertTrue("it.mela" !in may)
        val january = catalog.inSeason(1).map { it.id }
        assertTrue("it.mela" in january)
        assertThrows(IllegalArgumentException::class.java) { catalog.inSeason(13) }
    }

    @Test
    fun subsetForAllergyUsesTagsAndNamesDeterministically() {
        val catalog = load(sample)
        val profile = DietaryProfile(allergies = listOf("glutine"), intolerances = listOf("lattosio"))
        val ids = catalog.subsetFor(CatalogConstraints(profile = profile)).map { it.id }
        assertEquals(listOf("it.riso", "it.riso_cotto", "it.mela", "it.fragole", "it.pollo", "it.tonno"), ids)
        assertEquals(ids, catalog.subsetFor(CatalogConstraints(profile = profile)).map { it.id })
    }

    @Test
    fun subsetForDietStyleAndExcludedFoods() {
        val catalog = load(sample)
        val vegan = catalog.subsetFor(CatalogConstraints(profile = DietaryProfile(dietStyle = "vegano"))).map { it.id }
        assertEquals(listOf("it.pasta", "it.riso", "it.riso_cotto", "it.mela", "it.fragole"), vegan)
        val pescetarian = catalog.subsetFor(CatalogConstraints(profile = DietaryProfile(dietStyle = "pescetariano"))).map { it.id }
        assertTrue("it.tonno" in pescetarian)
        assertFalse("it.pollo" in pescetarian)
        val noPollo = catalog.subsetFor(CatalogConstraints(profile = DietaryProfile(excludedFoods = listOf("pollo")))).map { it.id }
        assertFalse("it.pollo" in noPollo)
        assertTrue("it.tonno" in noPollo)
    }

    @Test
    fun subsetForUnreadableProfileFailsClosed() {
        val catalog = load(sample)
        val unreadable = DietaryProfile.parse("Allergico alle noci, niente lattosio")
        assertTrue(unreadable.isUnreadable)
        assertTrue(catalog.subsetFor(CatalogConstraints(profile = unreadable)).isEmpty())
    }

    @Test
    fun subsetForSeasonKindsExcludedIdsAndLimit() {
        val catalog = load(sample)
        val july = catalog.subsetFor(CatalogConstraints(month = 7)).map { it.id }
        assertFalse("it.mela" in july)
        assertFalse("it.pizza" in july) // piatti esclusi per default
        val withDishes = catalog.subsetFor(CatalogConstraints(kinds = setOf(FoodKind.FOOD, FoodKind.DISH))).map { it.id }
        assertTrue("it.pizza" in withDishes)
        val withoutPasta = catalog.subsetFor(CatalogConstraints(excludedIds = setOf("it.pasta"))).map { it.id }
        assertFalse("it.pasta" in withoutPasta)
        val limited = catalog.subsetFor(CatalogConstraints(maxEntries = 3, includeIds = setOf("it.tonno"))).map { it.id }
        assertEquals(listOf("it.pasta", "it.riso", "it.tonno"), limited)
        assertTrue(catalog.subsetFor(CatalogConstraints(categories = setOf("NOPE"))).isEmpty())
        assertEquals(9, catalog.subsetFor(CatalogConstraints(kinds = FoodKind.entries.toSet())).size)
        assertTrue(catalog.subsetFor(CatalogConstraints(excludedTags = setOf("MEAT"))).none { it.id == "it.pollo" })
    }

    @Test
    fun loaderCachesTheCatalogAndCanBeFaked() {
        var opened = 0
        val loader = StreamFoodCatalogLoader {
            opened++
            ByteArrayInputStream(sample.toByteArray(Charsets.UTF_8))
        }
        val first = loader.load()
        assertSame(first, loader.load())
        assertEquals(1, opened)
        val fake = FoodCatalogLoader { first }
        assertSame(first, fake.load())
    }
}
