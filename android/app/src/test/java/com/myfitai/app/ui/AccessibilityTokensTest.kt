package com.myfitai.app.ui

import java.io.File
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K3d: guardie JVM sui token centrali di contrasto e target tocco. Leggono direttamente
 * `colors.xml` e `dimens.xml` (fonte runtime unica di Views e Compose), cosi' un nuovo valore
 * sotto soglia fa fallire la build con l'elenco delle coppie/token non conformi.
 */
class AccessibilityTokensTest {
    private val resDir = listOf("src/main/res", "app/src/main/res", "android/app/src/main/res")
        .map(::File).first { File(it, "values/colors.xml").exists() }

    private val colors: Map<String, String> by lazy { parseValues("colors.xml", "color") }
    private val dimens: Map<String, String> by lazy { parseValues("dimens.xml", "dimen") }

    private fun parseValues(file: String, tag: String): Map<String, String> =
        Regex("""<$tag name="([^"]+)">([^<]+)</$tag>""")
            .findAll(File(resDir, "values/$file").readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

    private fun color(name: String): Int {
        var value = colors[name] ?: error("Token colore mancante: $name")
        while (value.startsWith("@color/")) value = colors[value.removePrefix("@color/")] ?: error("Riferimento colore rotto: $value")
        val hex = value.removePrefix("#")
        require(hex.length == 6 || (hex.length == 8 && hex.startsWith("FF", ignoreCase = true))) { "$name deve essere opaco: $value" }
        return hex.takeLast(6).toInt(16)
    }

    private fun dp(name: String): Float {
        var value = dimens[name] ?: error("Token dimen mancante: $name")
        while (value.startsWith("@dimen/")) value = dimens[value.removePrefix("@dimen/")] ?: error("Riferimento dimen rotto: $value")
        return value.removeSuffix("dp").removeSuffix("sp").toFloat()
    }

    private fun luminance(rgb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((rgb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(foreground: String, background: String): Double {
        val a = luminance(color(foreground))
        val b = luminance(color(background))
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    @Test
    fun textTokensMeetWcagAaOnAllMainSurfaces() {
        val surfaces = listOf("bg_primary", "bg_secondary", "surface_primary", "surface_secondary", "surface_soft")
        val texts = listOf("text_primary", "text_secondary", "text_muted", "accent_green_dark")
        val pairs = texts.flatMap { t -> surfaces.map { s -> Triple(t, s, 4.5) } } +
            // Colori semantici usati anche come TESTO (delta, stato, badge, errori) sulle superfici neutre e sui loro fondi soft.
            listOf("semantic_error", "semantic_warning", "semantic_positive").flatMap { t ->
                listOf("surface_primary", "bg_primary", "surface_secondary").map { s -> Triple(t, s, 4.5) }
            } +
            listOf(
                Triple("semantic_error", "surface_error_soft", 4.5),
                Triple("semantic_warning", "surface_warning_soft", 4.5),
                Triple("semantic_positive", "surface_positive_soft", 4.5),
                Triple("white", "accent_green", 4.5),
                Triple("segment_selected_text", "segment_selected_bg", 4.5),
                // Icone/componenti grafici: soglia 3:1.
                Triple("accent_purple", "surface_primary", 3.0),
                Triple("accent_green", "surface_primary", 3.0),
            )
        val failures = pairs.mapNotNull { (fg, bg, min) ->
            val ratio = contrast(fg, bg)
            if (ratio < min) "$fg su $bg = ${"%.2f".format(ratio)}:1 (< $min:1)" else null
        }
        assertTrue("Coppie sotto soglia di contrasto:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun interactiveControlTokensAreAtLeast48dp() {
        val tokens = listOf(
            "control_min_height", "control_compact_min_height", "screen_header_icon_size",
            "body_trend_range_control_height", "week_navigation_icon_size", "shopping_filter_segment_height",
            "icon_button_size", "button_secondary_min_height", "button_primary_min_height",
        )
        val failures = tokens.filter { dp(it) < 48f }.map { "$it = ${dp(it)}dp (< 48dp)" }
        assertTrue("Token di controlli interattivi sotto 48dp:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun informativeTextTokensAreAtLeast12sp() {
        val failures = listOf("text_micro", "text_caption").filter { dp(it) < 12f }.map { "$it = ${dp(it)}sp (< 12sp)" }
        assertTrue("Testo informativo sotto 12sp:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun interactiveLayoutsDoNotUseDecorativePillHeight() {
        // dashboard_pill_height (32dp) resta solo per elementi decorativi; i controlli usano control_min_height.
        val offenders = listOf("activity_physical_evolution.xml", "activity_shopping_list.xml")
            .filter { File(resDir, "layout/$it").readText().contains("@dimen/dashboard_pill_height") }
        assertTrue("Layout con controlli a 32dp: $offenders", offenders.isEmpty())
    }
}
