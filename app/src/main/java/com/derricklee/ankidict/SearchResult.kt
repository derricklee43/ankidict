package com.derricklee.ankidict

sealed class SearchResult {
    data class AnkiCard(val note: NoteResult) : SearchResult()
    data class DictionaryHit(val entry: DictionaryEntry) : SearchResult()
}
