package com.derricklee.ankidict

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ItemDictionaryEntryBinding
import com.derricklee.ankidict.databinding.ItemNoteBinding

/**
 * Field-index layout for one AnkiDroid note type (model). Every deck can lay its fields out
 * differently, so each known model gets its own mapping here. Note types not listed fall back
 * to a raw "Field N: value" dump so new decks are still readable (and inspectable) immediately.
 */
private data class NoteLayout(
    val characterField: Int? = null,
    val pinyinField: Int? = null,
    val meaningField: Int? = null,
    val mnemonicField: Int? = null,
    val frequencyField: Int? = null,
    val knownFields: Set<Int> = emptySet(),
    // Starts collapsed to just the character, tap to reveal the rest -- mirrors decks that are
    // actually studied front/back in Anki, as opposed to decks meant to be read all at once.
    val revealable: Boolean = false,
    val deckTagLabel: String? = null,
    val deckTagColorRes: Int? = null,
)

private val NIHONGO_SHARK_FIELD_NAMES = listOf(
    "id", "frameNoV4", "frameNoV6", "keyword", "kanji", "strokeDiagram", "hint", "constituent",
    "strokeCount", "lessonNo", "myStory", "heisigStory", "heisigComment", "koohiiStory1",
    "koohiiStory2", "jouYou", "jlpt", "words", "readingExamples", "onYomi", "kunYomi",
    "kunYomi Count", "onYomi Count",
)

private val NOTE_LAYOUTS: Map<Long, NoteLayout> = mapOf(
    CHINESE_MNEMONICS_MODEL_ID to NoteLayout(
        meaningField = MEANING_FIELD_INDEX.getValue(CHINESE_MNEMONICS_MODEL_ID),
        frequencyField = 1,
        characterField = CHARACTER_FIELD_INDEX.getValue(CHINESE_MNEMONICS_MODEL_ID),
        mnemonicField = 3,
        pinyinField = 4,
        // 5 unconfirmed, 6/7 are the mnemonic image (html/filename, not renderable yet), 8-11 unconfirmed.
        knownFields = setOf(0, 1, 2, 3, 4, 6, 7),
        revealable = true,
        deckTagLabel = "中文",
        deckTagColorRes = R.color.tag_chinese_red,
    ),
    NIHONGO_SHARK_KANJI_MODEL_ID to NoteLayout(
        characterField = CHARACTER_FIELD_INDEX.getValue(NIHONGO_SHARK_KANJI_MODEL_ID), // kanji
        meaningField = MEANING_FIELD_INDEX.getValue(NIHONGO_SHARK_KANJI_MODEL_ID), // keyword
        mnemonicField = 10, // myStory -- the field the deck's own template actually renders
        knownFields = setOf(2, 3, 4, 5, 7, 8, 9, 10, 15, 16, 17, 18, 19, 20),
        revealable = true,
        deckTagLabel = "日本語",
        deckTagColorRes = R.color.tag_japanese_blue,
    ),
)

private const val VIEW_TYPE_ANKI_CARD = 0
private const val VIEW_TYPE_DICTIONARY_ENTRY = 1

class SearchResultAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var results: List<SearchResult> = emptyList()
    private val expandedNoteIds = mutableSetOf<Long>()

    fun submitList(newResults: List<SearchResult>) {
        results = newResults
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (results[position]) {
        is SearchResult.AnkiCard -> VIEW_TYPE_ANKI_CARD
        is SearchResult.DictionaryHit -> VIEW_TYPE_DICTIONARY_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            VIEW_TYPE_DICTIONARY_ENTRY -> {
                val binding = ItemDictionaryEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                DictionaryViewHolder(binding)
            }
            else -> {
                val binding = ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                AnkiCardViewHolder(binding, expandedNoteIds)
            }
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val result = results[position]) {
            is SearchResult.AnkiCard -> (holder as AnkiCardViewHolder).bind(result.note)
            is SearchResult.DictionaryHit -> (holder as DictionaryViewHolder).bind(result.entry)
        }
    }

    override fun getItemCount(): Int = results.size

    class DictionaryViewHolder(private val binding: ItemDictionaryEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: DictionaryEntry) {
            when (entry) {
                is DictionaryEntry.Kanji -> bindKanji(entry)
                is DictionaryEntry.JapaneseWord -> bindJapaneseWord(entry)
                is DictionaryEntry.ChineseWord -> bindChineseWord(entry)
            }
        }

        private fun bindKanji(entry: DictionaryEntry.Kanji) {
            binding.dictHeadwordText.text = entry.character
            val reading = listOfNotNull(
                entry.onYomi.takeIf { it.isNotEmpty() }?.joinToString("、"),
                entry.kunYomi.takeIf { it.isNotEmpty() }?.joinToString("、"),
            ).joinToString("  ")
            binding.dictReadingText.text = reading
            binding.dictMeaningText.text = entry.meanings
            val detailParts = listOfNotNull(
                entry.strokeCount?.let { "$it strokes" },
                entry.grade?.let { "grade $it" },
                entry.jlpt?.let { "JLPT N$it" },
                entry.radicals.takeIf { it.isNotEmpty() }?.let { "radicals: ${it.joinToString("、")}" },
            )
            binding.dictDetailText.text = detailParts.joinToString("  ·  ")
        }

        private fun bindJapaneseWord(entry: DictionaryEntry.JapaneseWord) {
            binding.dictHeadwordText.text = entry.headword
            binding.dictReadingText.text = entry.reading
            binding.dictMeaningText.text = entry.glosses
            binding.dictDetailText.text = if (entry.isCommon) "common word" else ""
        }

        private fun bindChineseWord(entry: DictionaryEntry.ChineseWord) {
            binding.dictHeadwordText.text = entry.simplified
            binding.dictReadingText.text = entry.pinyin
            binding.dictMeaningText.text = entry.definitions
            binding.dictDetailText.text =
                if (entry.traditional != entry.simplified) "traditional: ${entry.traditional}" else ""
        }
    }

    class AnkiCardViewHolder(
        private val binding: ItemNoteBinding,
        private val expandedNoteIds: MutableSet<Long>,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(note: NoteResult) {
            val layout = NOTE_LAYOUTS[note.modelId]

            bindDeckTag(layout)
            bindOptional(binding.characterText, layout?.characterField?.let { note.fields.getOrNull(it) })
            bindOptional(binding.pinyinText, layout?.pinyinField?.let { note.fields.getOrNull(it) })
            bindOptional(binding.meaningText, layout?.meaningField?.let { note.fields.getOrNull(it) })
            bindOptional(binding.mnemonicText, layout?.mnemonicField?.let { note.fields.getOrNull(it) })

            if (note.modelId == NIHONGO_SHARK_KANJI_MODEL_ID) {
                bindNihongoSharkExtras(note)
            } else {
                binding.readingText.visibility = View.GONE
                bindOptional(binding.vocabText, null)
            }

            // Unmapped model: dump every non-empty field raw. Mapped model: dump only the
            // fields we haven't identified the purpose of yet, so nothing silently disappears.
            val known = layout?.knownFields.orEmpty()
            val fieldNames = if (note.modelId == NIHONGO_SHARK_KANJI_MODEL_ID) NIHONGO_SHARK_FIELD_NAMES else null
            val extras = note.fields
                .mapIndexedNotNull { index, value ->
                    if (index !in known && value.isNotBlank()) {
                        val label = fieldNames?.getOrNull(index) ?: "Field ${index + 1}"
                        "$label: $value"
                    } else {
                        null
                    }
                }
                .joinToString("\n")
            bindOptional(binding.extraText, extras)

            val frequency = layout?.frequencyField?.let { note.fields.getOrNull(it) }
            val metaParts = listOfNotNull(
                "Note #${note.id}",
                "model ${note.modelId}".takeIf { layout == null },
                frequency?.takeIf { it.isNotBlank() }?.let { "rank $it" },
                note.tags.trim().takeIf { it.isNotBlank() }?.let { "tags: $it" },
            )
            binding.metaText.text = metaParts.joinToString("  ·  ")

            applyRevealState(layout, note.id)
        }

        private fun bindDeckTag(layout: NoteLayout?) {
            if (layout?.deckTagLabel == null || layout.deckTagColorRes == null) {
                binding.deckTagText.visibility = View.GONE
                return
            }
            binding.deckTagText.visibility = View.VISIBLE
            binding.deckTagText.text = layout.deckTagLabel
            binding.deckTagText.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(binding.root.context, layout.deckTagColorRes))
        }

        private fun bindNihongoSharkExtras(note: NoteResult) {
            val onYomi = note.fields.getOrNull(19)
            val kunYomi = note.fields.getOrNull(20)
            val reading = listOfNotNull(
                onYomi?.takeIf { it.isNotBlank() }?.let { "On: $it" },
                kunYomi?.takeIf { it.isNotBlank() }?.let { "Kun: $it" },
            ).joinToString("   ")
            bindOptional(binding.readingText, reading)

            val constituent = note.fields.getOrNull(7)?.takeIf { it.isNotBlank() }
            val words = note.fields.getOrNull(17)?.takeIf { it.isNotBlank() }?.replace("<br>", "\n")
            val examples = note.fields.getOrNull(18)?.takeIf { it.isNotBlank() }
            val vocab = listOfNotNull(
                constituent?.let { "Components: $it" },
                words,
                examples?.let { "Examples: $it" },
            ).joinToString("\n\n")
            bindOptional(binding.vocabText, vocab)
        }

        private fun applyRevealState(layout: NoteLayout?, noteId: Long) {
            if (layout?.revealable != true) {
                binding.revealGroup.visibility = View.VISIBLE
                binding.revealHint.visibility = View.GONE
                binding.root.setOnClickListener(null)
                return
            }

            fun render(expanded: Boolean) {
                binding.revealGroup.visibility = if (expanded) View.VISIBLE else View.GONE
                binding.revealHint.visibility = if (expanded) View.GONE else View.VISIBLE
            }

            render(noteId in expandedNoteIds)
            binding.root.setOnClickListener {
                val expanded = noteId in expandedNoteIds
                if (expanded) expandedNoteIds.remove(noteId) else expandedNoteIds.add(noteId)
                render(!expanded)
            }
        }

        private fun bindOptional(view: TextView, value: String?) {
            if (value.isNullOrBlank()) {
                view.visibility = View.GONE
            } else {
                view.visibility = View.VISIBLE
                view.text = value
            }
        }
    }
}
