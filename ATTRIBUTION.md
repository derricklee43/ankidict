# Dictionary data attribution

`app/src/main/assets/dictionary.db` is compiled from the following third-party data sources,
via `scripts/build_dictionary_db.py`. All are licensed **CC BY-SA 4.0**.

- **KANJIDIC2** — kanji readings, meanings, stroke counts, grade, JLPT level.
  Electronic Dictionary Research and Development Group (EDRDG). http://www.edrdg.org/
- **JMdict** — Japanese word/phrase dictionary.
  Electronic Dictionary Research and Development Group (EDRDG). http://www.edrdg.org/
- **RADKFILE** — kanji-to-radical/component decomposition.
  Electronic Dictionary Research and Development Group (EDRDG). http://www.edrdg.org/
- **CC-CEDICT** — Chinese word/character dictionary with pinyin.
  MDBG / CC-CEDICT community project. https://www.mdbg.net/chinese/dictionary?page=cc-cedict

These files are the property of the EDRDG and the CC-CEDICT project respectively, and are used
here in accordance with the Creative Commons Attribution-ShareAlike 4.0 licence
(https://creativecommons.org/licenses/by-sa/4.0/).

To refresh the bundled data with a newer release, re-run `scripts/build_dictionary_db.py` (see
that file's header for the source URLs) and replace `app/src/main/assets/dictionary.db`.
