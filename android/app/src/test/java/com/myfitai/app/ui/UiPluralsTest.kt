package com.myfitai.app.ui

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX6/D2: singolare/plurale italiano dei conteggi di elementi. Legge i plurals reali da strings.xml e applica la
 * regola italiana (1 -> "one", altrimenti "other", 0 compreso) come fa Android per `it`.
 */
class UiPluralsTest {
    private val resDir: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun plural(name: String, quantity: Int, vararg args: Any): String {
        val xml = File(resDir, "values/strings.xml").readText()
        val block = Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: error("plurals $name mancante")
        val items = Regex("""<item quantity="(\w+)">(.*?)</item>""").findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
        val template = if (quantity == 1) items.getValue("one") else items.getValue("other")
        return String.format(Locale.ITALIAN, template.replace("\\'", "'"), *args)
    }

    @Test
    fun homeAllConsumedNote() {
        assertEquals("Totale di 1 elemento segnato come consumato.", plural("home_ux5a_note_all_consumed", 1, 1))
        assertEquals("Totale di 2 elementi segnati come consumati.", plural("home_ux5a_note_all_consumed", 2, 2))
        assertEquals("Totale di 0 elementi segnati come consumati.", plural("home_ux5a_note_all_consumed", 0, 0))
    }

    @Test
    fun foodTotalsNotes() {
        assertEquals("Totale dei 2 elementi segnati come consumati.", plural("food_ux6_total_all_consumed", 2, 2))
        assertEquals("Totale di 1 elemento segnato come consumato.", plural("food_ux6_total_all_consumed", 1, 1))
        assertEquals("Totale dei soli 2 elementi segnati come consumati.", plural("food_ux6_total_only_consumed", 2, 2))
        assertTrue(plural("food_ux6_total_only_consumed", 1, 1).contains("elemento"))
    }

    @Test
    fun foodCoverageLines() {
        assertEquals("Nessuna registrazione su 0 elementi pianificati", plural("food_ux6_coverage_none", 0, 0))
        assertEquals("Nessuna registrazione su 1 elemento pianificato", plural("food_ux6_coverage_none", 1, 1))
        assertEquals("Nessuna registrazione su 2 elementi pianificati", plural("food_ux6_coverage_none", 2, 2))
        assertEquals(
            "Registrati: 1 di 1 elemento · consumati 1 · saltati 0",
            plural("food_ux6_coverage_recorded", 1, 1, 1, 1, 0),
        )
        assertEquals(
            "Registrati: 1 di 2 elementi · consumati 1 · saltati 0",
            plural("food_ux6_coverage_recorded", 2, 2, 1, 1, 0),
        )
    }
}
