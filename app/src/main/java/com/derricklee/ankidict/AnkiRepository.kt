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
     * flds LIKE is matched against AnkiDroid's raw field-separated string, so this searches
     * across all fields on the note type at once. Restricted to note types this app has a
     * display mapping for -- otherwise unmapped decks would flood results with raw field dumps.
     */
    fun searchNotes(query: String): List<NoteResult> {
        if (query.isBlank()) return emptyList()

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
        // A match on the character/kanji field itself outranks a match found elsewhere
        // (meaning, mnemonic, vocab, etc.) -- that's what you're searching for a character by.
        return results.sortedByDescending { matchesCharacterField(it, query) }
    }

    private fun matchesCharacterField(note: NoteResult, query: String): Boolean {
        val fieldIndex = CHARACTER_FIELD_INDEX[note.modelId] ?: return false
        return note.fields.getOrNull(fieldIndex)?.contains(query, ignoreCase = true) == true
    }
}
