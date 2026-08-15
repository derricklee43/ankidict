#!/usr/bin/env python3
"""Builds app/src/main/assets/audio.db from Anki Desktop's local collection.

Pulls pronunciation audio straight from Anki Desktop's own collection.media folder --
a normal, fully-accessible folder on this PC, unlike AnkiDroid's on the Pixel, which
Android's scoped storage blocks every other app (even with All Files Access) from
reading. Only Anki Desktop's copy needs to be up to date (open Anki / sync) before
re-running this; the phone isn't involved at all.

audio.db is NOT committed (see .gitignore) -- these are Forvo native-speaker
recordings, not freely redistributable data like the dictionary sources are.

Re-run this any time you've added more audio to cards in Anki.

Usage: python3 scripts/build_audio_db.py
"""
import sqlite3
from pathlib import Path

# Anki Desktop's default profile location on this machine.
ANKI_PROFILE_DIR = Path("/mnt/c/Users/derri/AppData/Roaming/Anki2/User 1")
ANKI_COLLECTION = ANKI_PROFILE_DIR / "collection.anki2"
ANKI_MEDIA_DIR = ANKI_PROFILE_DIR / "collection.media"

# Chinese Hanzi note type ("RSH" in Anki Desktop) -- the only note type with an audio
# field today. Keep in sync with CHINESE_MNEMONICS_MODEL_ID / CHARACTER_FIELD_INDEX in
# app/src/main/java/com/derricklee/ankidict/KnownNoteTypes.kt.
CHINESE_MODEL_ID = 1691967670208
CHARACTER_FIELD_INDEX = 2
AUDIO_FIELD_INDEX = 5

OUT_PATH = Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "audio.db"


def extract_sound_filename(field_text: str) -> str | None:
    if not field_text.startswith("[sound:") or not field_text.endswith("]"):
        return None
    return field_text[len("[sound:"):-1]


def main():
    conn = sqlite3.connect(f"file:{ANKI_COLLECTION}?mode=ro", uri=True)

    rows = conn.execute(
        "SELECT flds FROM notes WHERE mid = ?", (CHINESE_MODEL_ID,)
    ).fetchall()

    out_path = OUT_PATH
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.unlink(missing_ok=True)
    out_conn = sqlite3.connect(out_path)
    out_conn.execute(
        "CREATE TABLE audio (character TEXT PRIMARY KEY, filename TEXT, data BLOB)"
    )

    found, missing_file = 0, 0
    for (flds,) in rows:
        fields = flds.split("\x1f")
        if len(fields) <= AUDIO_FIELD_INDEX:
            continue
        character = fields[CHARACTER_FIELD_INDEX]
        filename = extract_sound_filename(fields[AUDIO_FIELD_INDEX])
        if not character or not filename:
            continue

        audio_path = ANKI_MEDIA_DIR / filename
        if not audio_path.exists():
            missing_file += 1
            print(f"  missing media file for {character!r}: {filename}")
            continue

        out_conn.execute(
            "INSERT OR REPLACE INTO audio (character, filename, data) VALUES (?, ?, ?)",
            (character, filename, audio_path.read_bytes()),
        )
        found += 1

    out_conn.commit()
    out_conn.execute("VACUUM")
    out_conn.close()

    print(f"Wrote {found} audio clips to {out_path} ({out_path.stat().st_size / 1_000_000:.1f} MB)")
    if missing_file:
        print(f"{missing_file} notes referenced audio that wasn't found in collection.media")


if __name__ == "__main__":
    main()
