package com.myfitai.app.domain.ai

import java.util.Locale

/**
 * Shared check for claims the AI must not make (diagnoses, certain causes, ...).
 *
 * A forbidden term is accepted only when it is explicitly negated in the same clause
 * ("non è una diagnosi", "senza diagnosi", "nessuna diagnosi"). Terms are matched as whole words
 * and each text field must be checked on its own, so a disclaimer in one field never excuses
 * an assertion in another.
 */
object UnsupportedClaimGuard {
    private val negations = setOf("non", "nessun", "nessuna", "nessuno", "senza", "né", "ne", "no", "not", "without")
    private const val NEGATION_WINDOW = 4
    private val clauseBoundary = Regex("[.;:!?,()\\n\\r|]+")
    private val word = Regex("[\\p{L}\\p{N}]+")

    /** True when [text] contains at least one of [terms] that is not negated within its clause. */
    fun containsAffirmed(text: String, terms: List<String>): Boolean {
        val termWords = terms.map { it.lowercase(Locale.ROOT).trim().split(' ').filter(String::isNotEmpty) }.filter { it.isNotEmpty() }
        return text.lowercase(Locale.ROOT).split(clauseBoundary).any { clause ->
            val words = word.findAll(clause).map { it.value }.toList()
            termWords.any { term -> affirmedIn(words, term) }
        }
    }

    fun containsAffirmed(fields: Iterable<String>, terms: List<String>): Boolean =
        fields.any { containsAffirmed(it, terms) }

    private fun affirmedIn(words: List<String>, term: List<String>): Boolean {
        if (words.size < term.size) return false
        for (start in 0..words.size - term.size) {
            if (term.indices.any { words[start + it] != term[it] }) continue
            val window = words.subList(maxOf(0, start - NEGATION_WINDOW), start)
            if (window.none { it in negations }) return true
        }
        return false
    }
}
