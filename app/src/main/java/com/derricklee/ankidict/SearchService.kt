package com.derricklee.ankidict

/**
 * Combines your Anki decks with the bundled offline dictionary (KANJIDIC2/JMdict/RADKFILE +
 * CC-CEDICT) for a single query. Per query (or per character, for a multi-character CJK query),
 * results are ordered: your exact/character-field Anki matches, then dictionary exact matches,
 * then everything else from your decks (meaning-field matches, then general substring matches --
 * deck-interleaved as before so one deck's long tail can't bury another's).
 */
class SearchService(
    private val ankiRepository: AnkiRepository,
    private val dictionaryRepository: DictionaryRepository,
) {

    fun search(query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val trimmed = query.trim()
        val queries = if (trimmed.length > 1 && trimmed.all(::isCjkCharacter)) {
            trimmed.map { it.toString() }
        } else {
            listOf(trimmed)
        }

        val perQuery = queries.map { q ->
            val tiered = ankiRepository.searchTiered(q)
            val dictHits = dictionaryRepository.lookupExact(q)
            Triple(tiered.exact, dictHits, tiered.rest)
        }

        val priority = perQuery.flatMap { (exact, dictHits, _) ->
            exact.map { SearchResult.AnkiCard(it) } + dictHits.map { SearchResult.DictionaryHit(it) }
        }
        val rest = interleave(perQuery.map { it.third }, chunkSize = SEARCHABLE_MODEL_IDS.size)
            .map { SearchResult.AnkiCard(it) }

        return priority + rest
    }
}

private fun isCjkCharacter(c: Char): Boolean {
    val block = Character.UnicodeBlock.of(c)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
}
