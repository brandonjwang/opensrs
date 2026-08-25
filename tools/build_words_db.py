#!/usr/bin/env python3
"""
build_words_db.py — compiles the pre-populated spoken-frequency dictionary.

Input:  tools/corpus/words.csv  (UTF-8)
Output: app/src/main/assets/words.db  (SQLite, schema identical to WordEntity)

CSV columns (header required):
    id,simplified,traditional,pinyin,jyutping,english,mandarin_rank,cantonese_rank,examples,hsk_level

- id: stable content-derived integer (hash of the headword). MUST be kept stable
  across corpus updates so existing SRS progress keeps pointing at the same word.
- mandarin_rank / cantonese_rank: integers from SUBTLEX-CH and HKCAC/utd-cantonese
  respectively; empty string when the word is absent from that corpus.
- hsk_level: minimum HSK 3.0 band (1-7) whose inclusive list contains the word.
- examples: JSON array of {"zh","py","jp","en"} objects; may be empty [].

The generator validates every row and emits a checksum report so CI can detect
dictionary drift. Ids come from the CSV verbatim — they are NOT regenerated here.
"""
from __future__ import annotations

import csv
import json
import hashlib
import sqlite3
import sys
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS words (
    id              INTEGER PRIMARY KEY,
    simplified      TEXT NOT NULL,
    traditional     TEXT NOT NULL,
    pinyin          TEXT NOT NULL,
    jyutping        TEXT NOT NULL,
    english         TEXT NOT NULL,
    mandarinRank    INTEGER,
    cantoneseRank   INTEGER,
    examplesJson    TEXT NOT NULL DEFAULT '[]',
    hskLevel        INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_words_mandarin_rank  ON words(mandarinRank);
CREATE INDEX IF NOT EXISTS idx_words_cantonese_rank ON words(cantoneseRank);
CREATE INDEX IF NOT EXISTS idx_words_simplified     ON words(simplified);
CREATE INDEX IF NOT EXISTS idx_words_traditional    ON words(traditional);
CREATE INDEX IF NOT EXISTS idx_words_hsk_level      ON words(hskLevel);
"""


def validate(rows: list[dict]) -> list[dict]:
    seen = set()
    out = []
    for i, r in enumerate(rows, start=2):  # line numbers for error messages
        simp, trad = r["simplified"].strip(), r["traditional"].strip()
        if not simp or not trad:
            fail(i, "empty headword")
        if simp in seen:
            fail(i, f"duplicate entry {simp}")
        seen.add(simp)

        if not r["pinyin"].strip() or not r["jyutping"].strip():
            fail(i, f"missing romanization for {simp}")

        raw_id = r["id"].strip()
        if not raw_id:
            fail(i, f"missing stable id for {simp}")
        wid = int(raw_id)

        mr = int(r["mandarin_rank"]) if r["mandarin_rank"].strip() else None
        cr = int(r["cantonese_rank"]) if r["cantonese_rank"].strip() else None
        if mr is not None and mr < 1:
            fail(i, "mandarin_rank must be >= 1")
        if cr is not None and cr < 1:
            fail(i, "cantonese_rank must be >= 1")

        lvl = int(r["hsk_level"] or "0")
        if not 0 <= lvl <= 7:
            fail(i, f"hsk_level out of range: {lvl}")

        examples = json.loads(r["examples"] or "[]")
        if not isinstance(examples, list):
            fail(i, "examples must be a JSON array")

        out.append(
            {
                "id": wid,
                "simplified": simp,
                "traditional": trad,
                "pinyin": r["pinyin"].strip(),
                "jyutping": r["jyutping"].strip(),
                "english": r["english"].strip(),
                "mandarinRank": mr,
                "cantoneseRank": cr,
                "examplesJson": json.dumps(examples, ensure_ascii=False),
                "hskLevel": lvl,
            }
        )
    return out


def fail(line: int, msg: str):
    print(f"error: corpus line {line}: {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    src = root / "tools" / "corpus" / "words.csv"
    out_dir = root / "app" / "src" / "main" / "assets"
    out_dir.mkdir(parents=True, exist_ok=True)
    dst = out_dir / "words.db"

    with open(src, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    words = validate(rows)
    # Keep CSV order (already frequency-sorted upstream); ids are stable content hashes.

    if dst.exists():
        dst.unlink()
    conn = sqlite3.connect(dst)
    try:
        conn.executescript(SCHEMA)
        conn.executemany(
            """
            INSERT INTO words (id, simplified, traditional, pinyin, jyutping,
                               english, mandarinRank, cantoneseRank, examplesJson, hskLevel)
            VALUES (:id, :simplified, :traditional, :pinyin, :jyutping,
                    :english, :mandarinRank, :cantoneseRank, :examplesJson, :hskLevel)
            """,
            words,
        )
        conn.commit()
        n = conn.execute("SELECT COUNT(*) FROM words").fetchone()[0]
        assert n == len(words), "insert count mismatch"
    finally:
        conn.close()

    digest = hashlib.sha256(dst.read_bytes()).hexdigest()
    print(f"wrote {dst.relative_to(root)}: {len(words)} words, sha256={digest[:16]}")


if __name__ == "__main__":
    main()
