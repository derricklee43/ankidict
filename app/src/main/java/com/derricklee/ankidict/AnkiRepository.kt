package com.derricklee.ankidict

import android.content.Context
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi

data class NoteResult(
    val id: Long,
    val modelId: Long,
    val tags: String,
    val fields: List<String>,
)

private const val FIELD_SEPARATOR = "\u001F"

class AnkiRepository(private val context: Context) {

    fun isAnkiDroidAvailable(): Boolean =
        AddContentApi.getAnkiDroidPackageName(context) != null

    /**
     * Searches every note's fields for [query] and returns the full contents of each match.
     * A query of multiple CJK characters (e.g. pasting a word/phrase) is split into one lookup
     * per character, run and ranked independently, then interleaved (each character's top
     * Japanese + Chinese hits, then each character's next, and so on) -- so each character gets
     * its own top-of-list spot instead of one character's loosely-related tail of matches
     * burying the next character's results. A single character, or any non-CJK query (e.g. an
     * English word), runs as one plain substring search, unchanged.
     */
    fun searchNotes(query: String): List<NoteResult> {
        if (query.isBlank()) return emptyList()

        val characters = query.trim()
        if (characters.length > 1 && characters.all(::isCjkCharacter)) {
            // Each character's own list is already tier+deck ranked (best match first). Round-
            // robin across characters in (deck-sized) chunks, so e.g. char 1's top Japanese +
            // Chinese hits, then char 2's, then char 3's... come before any character's deeper,
            // looser matches -- rather than one character's long tail burying the next character.
            val perCharacterResults = characters.map { searchSingleQuery(it.toString()) }
            return interleave(perCharacterResults, chunkSize = SEARCHABLE_MODEL_IDS.size)
        }
        return searchSingleQuery(query)
    }

    private fun searchSingleQuery(query: String): List<NoteResult> {
        val modelPlaceholders = SEARCHABLE_MODEL_IDS.joinToString(",") { "?" }
        val selection = "flds LIKE ? AND mid IN ($modelPlaceholders)"
        val selectionArgs = (listOf("%$query%") + SEARCHABLE_MODEL_IDS.map { it.toString() }).toTypedArray()

        // CONTENT_URI_V2 runs selection/selectionArgs as parameterized SQL against the notes
        // table. The plain CONTENT_URI instead parses `selection` as Anki's browser search
        // syntax and ignores selectionArgs entirely, which isn't what we want here.
        val cursor = context.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI_V2,
            FlashCardsContract.Note.DEFAULT_PROJECTION,
            selection,
            selectionArgs,
            null,
        ) ?: return emptyList()

        val results = mutableListOf<NoteResult>()
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(FlashCardsContract.Note._ID)
            val midCol = it.getColumnIndexOrThrow(FlashCardsContract.Note.MID)
            val tagsCol = it.getColumnIndexOrThrow(FlashCardsContract.Note.TAGS)
            val fldsCol = it.getColumnIndexOrThrow(FlashCardsContract.Note.FLDS)

            while (it.moveToNext()) {
                results.add(
                    NoteResult(
                        id = it.getLong(idCol),
                        modelId = it.getLong(midCol),
                        tags = it.getString(tagsCol) ?: "",
                        fields = (it.getString(fldsCol) ?: "").split(FIELD_SEPARATOR),
                    )
                )
            }
        }
        // Rank a match on the character/kanji field above a match on the meaning field, above
        // a match found anywhere else (mnemonic, vocab, etc.). Within each tier, interleave the
        // two decks round-robin so a deck with far more raw matches (e.g. NihongoShark's huge,
        // narrative mnemonics matching a common substring) doesn't bury the other deck's results.
        return results
            .groupBy { matchPriority(it, query) }
            .toSortedMap()
            .values
            .flatMap { tier -> interleave(tier.groupBy { it.modelId }.values.toList()) }
    }

    private fun matchPriority(note: NoteResult, query: String): Int {
        if (fieldMatches(note, query, CHARACTER_FIELD_INDEX)) return 0
        if (fieldMatches(note, query, MEANING_FIELD_INDEX)) return 1
        return 2
    }

    private fun fieldMatches(note: NoteResult, query: String, fieldIndexByModel: Map<Long, Int>): Boolean {
        val fieldIndex = fieldIndexByModel[note.modelId] ?: return false
        return note.fields.getOrNull(fieldIndex)?.contains(query, ignoreCase = true) == true
    }

    private fun isCjkCharacter(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }

    // Round-robins [lists] together, taking [chunkSize] items at a time from each list in turn,
    // so every list gets a turn near the top instead of the first list's full length running out
    // before the next list contributes anything.
    private fun interleave(lists: List<List<NoteResult>>, chunkSize: Int = 1): List<NoteResult> {
        val queues = lists.map { ArrayDeque(it) }
        val result = mutableListOf<NoteResult>()
        var addedAny = true
        while (addedAny) {
            addedAny = false
            for (queue in queues) {
                repeat(chunkSize) {
                    queue.removeFirstOrNull()?.let {
                        result.add(it)
                        addedAny = true
                    }
                }
            }
        }
        return result
    }
}
