#!/usr/bin/env python3
"""Builds app/src/main/assets/dictionary.db from KANJIDIC2, JMdict, RADKFILE, and CC-CEDICT.

Downloads the source files fresh each run (they're small/gzipped) and rebuilds the SQLite DB
this app bundles as an asset. See ATTRIBUTION.md for licensing -- all sources are CC BY-SA 4.0.
Re-run this occasionally to pick up upstream corrections/additions.

Usage: python3 scripts/build_dictionary_db.py
"""
import gzip
import re
import sqlite3
import urllib.request
import zipfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
DATA = SCRIPT_DIR / "dictdata"
DB_PATH = SCRIPT_DIR.parent / "app" / "src" / "main" / "assets" / "dictionary.db"

SOURCES = {
    "JMdict_e.gz": "http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz",
    "kanjidic2.xml.gz": "http://ftp.edrdg.org/pub/Nihongo/kanjidic2.xml.gz",
    "kradzip.zip": "http://ftp.edrdg.org/pub/Nihongo/kradzip.zip",
    "cedict.txt.gz": "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz",
}


def download_sources():
    DATA.mkdir(exist_ok=True)
    for filename, url in SOURCES.items():
        dest = DATA / filename
        print(f"Downloading {filename}...")
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req) as response, open(dest, "wb") as out:
            out.write(response.read())

    for gz_name, out_name in [
        ("JMdict_e.gz", "JMdict_e"),
        ("kanjidic2.xml.gz", "kanjidic2.xml"),
        ("cedict.txt.gz", "cedict.txt"),
    ]:
        with gzip.open(DATA / gz_name, "rb") as f_in, open(DATA / out_name, "wb") as f_out:
            f_out.write(f_in.read())

    with zipfile.ZipFile(DATA / "kradzip.zip") as z:
        z.extractall(DATA)


def resolve_entities(xml_text: str) -> str:
    entities = dict(re.findall(r'<!ENTITY\s+(\S+)\s+"([^"]*)">', xml_text[:200000]))

    def repl(m):
        return entities.get(m.group(1), m.group(0))

    return re.sub(r"&(\w[\w-]*);", repl, xml_text)


def build_kanjidic(conn: sqlite3.Connection):
    import xml.etree.ElementTree as ET

    print("Parsing KANJIDIC2...")
    text = (DATA / "kanjidic2.xml").read_text(encoding="utf-8")
    root = ET.fromstring(text)
    rows = []
    for char in root.findall("character"):
        literal = char.findtext("literal")
        misc = char.find("misc")
        stroke_count = misc.findtext("stroke_count") if misc is not None else None
        grade = misc.findtext("grade") if misc is not None else None
        jlpt = misc.findtext("jlpt") if misc is not None else None
        freq = misc.findtext("freq") if misc is not None else None

        on_yomi, kun_yomi, meanings = [], [], []
        rm = char.find("reading_meaning")
        if rm is not None:
            for rmgroup in rm.findall("rmgroup"):
                for reading in rmgroup.findall("reading"):
                    rtype = reading.get("r_type")
                    if rtype == "ja_on":
                        on_yomi.append(reading.text)
                    elif rtype == "ja_kun":
                        kun_yomi.append(reading.text)
                for meaning in rmgroup.findall("meaning"):
                    if meaning.get("m_lang") is None:
                        meanings.append(meaning.text)

        rows.append((
            literal,
            "、".join(on_yomi),
            "、".join(kun_yomi),
            "; ".join(meanings),
            int(stroke_count) if stroke_count else None,
            int(grade) if grade else None,
            int(jlpt) if jlpt else None,
            int(freq) if freq else None,
        ))

    conn.executemany(
        "INSERT INTO kanji (character, on_yomi, kun_yomi, meanings, stroke_count, grade, jlpt, freq) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        rows,
    )
    print(f"  {len(rows)} kanji")


def build_radkfile(conn: sqlite3.Connection):
    print("Parsing RADKFILE...")
    text = (DATA / "radkfile").read_text(encoding="euc-jp", errors="replace")
    rows = []
    current_radical = None
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        if line.startswith("$"):
            parts = line.split()
            current_radical = parts[1]
            continue
        if current_radical:
            for kanji_char in line.strip():
                rows.append((kanji_char, current_radical))
    conn.executemany("INSERT INTO kanji_radicals (character, radical) VALUES (?, ?)", rows)
    print(f"  {len(rows)} kanji-radical links")


def build_jmdict(conn: sqlite3.Connection):
    import xml.etree.ElementTree as ET

    print("Parsing JMdict (this is the big one)...")
    text = (DATA / "JMdict_e").read_text(encoding="utf-8")
    text = resolve_entities(text)
    root = ET.fromstring(text)
    word_rows = []
    for entry in root.findall("entry"):
        kebs = [k.findtext("keb") for k in entry.findall("k_ele")]
        rebs = [r.findtext("reb") for r in entry.findall("r_ele")]
        glosses = []
        for sense in entry.findall("sense"):
            sense_glosses = [
                g.text for g in sense.findall("gloss")
                if g.text and g.get("{http://www.w3.org/XML/1998/namespace}lang") in (None, "eng")
            ]
            if sense_glosses:
                glosses.append("; ".join(sense_glosses))
        if not glosses:
            continue
        headword = kebs[0] if kebs else (rebs[0] if rebs else None)
        reading = rebs[0] if rebs else ""
        is_common = 1 if entry.find("k_ele/ke_pri") is not None or entry.find("r_ele/re_pri") is not None else 0
        if headword:
            word_rows.append((headword, reading, " / ".join(glosses), is_common))

    conn.executemany(
        "INSERT INTO words (headword, reading, glosses, is_common) VALUES (?, ?, ?, ?)",
        word_rows,
    )
    print(f"  {len(word_rows)} words")


def build_cedict(conn: sqlite3.Connection):
    print("Parsing CC-CEDICT...")
    rows = []
    line_re = re.compile(r"^(\S+)\s+(\S+)\s+\[([^\]]+)\]\s+/(.+)/$")
    with open(DATA / "cedict.txt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            m = line_re.match(line)
            if not m:
                continue
            traditional, simplified, pinyin, defs = m.groups()
            defs_clean = "; ".join(d for d in defs.split("/") if d)
            rows.append((traditional, simplified, pinyin, defs_clean))
    conn.executemany(
        "INSERT INTO cedict (traditional, simplified, pinyin, definitions) VALUES (?, ?, ?, ?)",
        rows,
    )
    print(f"  {len(rows)} CC-CEDICT entries")


def main():
    download_sources()

    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    DB_PATH.unlink(missing_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.executescript("""
        CREATE TABLE kanji (
            character TEXT PRIMARY KEY,
            on_yomi TEXT,
            kun_yomi TEXT,
            meanings TEXT,
            stroke_count INTEGER,
            grade INTEGER,
            jlpt INTEGER,
            freq INTEGER
        );
        CREATE TABLE kanji_radicals (
            character TEXT,
            radical TEXT
        );
        CREATE INDEX idx_kanji_radicals_char ON kanji_radicals(character);

        CREATE TABLE words (
            headword TEXT,
            reading TEXT,
            glosses TEXT,
            is_common INTEGER
        );
        CREATE INDEX idx_words_headword ON words(headword);

        CREATE TABLE cedict (
            traditional TEXT,
            simplified TEXT,
            pinyin TEXT,
            definitions TEXT
        );
        CREATE INDEX idx_cedict_trad ON cedict(traditional);
        CREATE INDEX idx_cedict_simp ON cedict(simplified);
    """)

    build_kanjidic(conn)
    build_radkfile(conn)
    build_jmdict(conn)
    build_cedict(conn)

    conn.commit()
    print("Vacuuming...")
    conn.execute("VACUUM")
    conn.close()
    print("Done:", DB_PATH, DB_PATH.stat().st_size / 1_000_000, "MB")


if __name__ == "__main__":
    main()
