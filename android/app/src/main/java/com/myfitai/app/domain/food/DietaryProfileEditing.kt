package com.myfitai.app.domain.food

/** Le cinque liste modificabili del profilo alimentare. Le prime tre di [isHardConstraint] sono vincoli rigidi. */
enum class DietaryCategory(val isHardConstraint: Boolean) {
    PREFERRED(false),
    DISLIKED(false),
    EXCLUDED(true),
    INTOLERANCE(true),
    ALLERGY(true),
}

/** Esito di un inserimento: nulla viene deciso in silenzio, ogni voce scartata e' riportata. */
data class DietaryAddResult(
    val profile: DietaryProfile,
    val added: List<String>,
    val duplicates: List<String>,
    val tooLong: List<String>,
) {
    val isEmptyInput: Boolean get() = added.isEmpty() && duplicates.isEmpty() && tooLong.isEmpty()
}

const val DIETARY_ITEM_MAX_LENGTH = 40

fun DietaryProfile.items(category: DietaryCategory): List<String> = when (category) {
    DietaryCategory.PREFERRED -> preferredFoods
    DietaryCategory.DISLIKED -> dislikedFoods
    DietaryCategory.EXCLUDED -> excludedFoods
    DietaryCategory.INTOLERANCE -> intolerances
    DietaryCategory.ALLERGY -> allergies
}

fun DietaryProfile.withItems(category: DietaryCategory, items: List<String>): DietaryProfile = when (category) {
    DietaryCategory.PREFERRED -> copy(preferredFoods = items)
    DietaryCategory.DISLIKED -> copy(dislikedFoods = items)
    DietaryCategory.EXCLUDED -> copy(excludedFoods = items)
    DietaryCategory.INTOLERANCE -> copy(intolerances = items)
    DietaryCategory.ALLERGY -> copy(allergies = items)
}

/**
 * Aggiunge una o piu' voci (separate da virgola, punto e virgola o a capo) alla categoria.
 * Deduplica con la stessa normalizzazione usata dai validatori (maiuscole/accenti/punteggiatura ignorati).
 * Le voci uguali a una gia' presente, vuote dopo la normalizzazione o troppo lunghe non vengono aggiunte.
 */
fun DietaryProfile.addItems(category: DietaryCategory, raw: String?): DietaryAddResult {
    val current = items(category)
    val known = current.map(DietaryProfile::normalize).toMutableSet()
    val added = mutableListOf<String>()
    val duplicates = mutableListOf<String>()
    val tooLong = mutableListOf<String>()
    DietaryProfile.csv(raw).forEach { candidate ->
        val key = DietaryProfile.normalize(candidate)
        when {
            key.isBlank() -> Unit
            candidate.length > DIETARY_ITEM_MAX_LENGTH -> tooLong += candidate
            key in known -> duplicates += candidate
            else -> {
                known += key
                added += candidate
            }
        }
    }
    return DietaryAddResult(withItems(category, current + added), added, duplicates, tooLong)
}

/** Rimuove la singola voce (confronto normalizzato) dalla categoria; le altre liste restano invariate. */
fun DietaryProfile.removeItem(category: DietaryCategory, value: String): DietaryProfile {
    val key = DietaryProfile.normalize(value)
    return withItems(category, items(category).filterNot { DietaryProfile.normalize(it) == key })
}

/**
 * Forma canonica usata per confrontare bozza e originale e per salvare: liste deduplicate e senza voci vuote,
 * note ripulite, stile "Nessuno" equivalente a nessuno stile. Non converte mai le note in allergie.
 */
fun DietaryProfile.canonical(): DietaryProfile = copy(
    preferredFoods = canonicalList(preferredFoods),
    dislikedFoods = canonicalList(dislikedFoods),
    excludedFoods = canonicalList(excludedFoods),
    intolerances = canonicalList(intolerances),
    allergies = canonicalList(allergies),
    dietStyle = dietStyle?.trim()?.takeIf { it.isNotBlank() && !it.equals("Nessuno", ignoreCase = true) },
    notes = notes?.trim()?.takeIf { it.isNotBlank() },
)

private fun canonicalList(values: List<String>): List<String> = values
    .map { it.trim() }
    .filter { DietaryProfile.normalize(it).isNotBlank() }
    .distinctBy(DietaryProfile::normalize)
