#!/usr/bin/env python3
"""
build_words_db.py — compiles the pre-populated spoken-frequency dictionary.

Input:  tools/corpus/words.csv  (UTF-8)
Output: app/assets/words.db     (SQLite, schema identical to WordEntity)

CSV columns (header required):
    simplified,traditional,pinyin,jyutping,english,mandarin_rank,cantonese_rank,examples

- mandarin_rank / cantonese_rank: integers from SUBTLEX-CH and HKCAC/utd-cantonese
  respectively; empty string when the word is absent from that corpus.
- examples: JSON array of {"zh","py","jp","en"} objects; may be empty [].

The generator:
  1. validates every row (romanization sanity, rank positivity, unique entries),
  2. assigns ids by ascending effective spoken rank so `ORDER BY id` is a stable
     frequency tiebreak for the DAO queue queries,
  3. writes FTS-free plain tables + indices matching the Room schema exactly,
  4. emits a checksum report so CI can detect dictionary drift.
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
    examplesJson    TEXT NOT NULL DEFAULT '[]'
);
CREATE INDEX IF NOT EXISTS idx_words_mandarin_rank  ON words(mandarinRank);
CREATE INDEX IF NOT EXISTS idx_words_cantonese_rank ON words(cantoneseRank);
CREATE INDEX IF NOT EXISTS idx_words_simplified     ON words(simplified);
CREATE INDEX IF NOT EXISTS idx_words_traditional    ON words(traditional);
"""

# Room stores enums as their name; CardState lives in the other DB. Nothing else needed.


def validate(rows: list[dict]) -> list[dict]:
    seen = set()
    out = []
    for i, r in enumerate(rows, start=2):  # line numbers for error messages
        simp, trad = r["simplified"].strip(), r["traditional"].strip()
        if not simp or not trad:
            fail(i, "empty headword")
        key = (simp, trad)
        if key in seen:
            fail(i, f"duplicate entry {key}")
        seen.add(key)

        if not r["pinyin"].strip() or not r["jyutping"].strip():
            fail(i, f"missing romanization for {simp}")

        mr = int(r["mandarin_rank"]) if r["mandarin_rank"].strip() else None
        cr = int(r["cantonese_rank"]) if r["cantonese_rank"].strip() else None
        if mr is not None and mr < 1:
            fail(i, "mandarin_rank must be >= 1")
        if cr is not None and cr < 1:
            fail(i, "cantonese_rank must be >= 1")

        examples = json.loads(r["examples"] or "[]")
        if not isinstance(examples, list):
            fail(i, "examples must be a JSON array")

        out.append(
            {
                "simplified": simp,
                "traditional": trad,
                "pinyin": r["pinyin"].strip(),
                "jyutping": r["jyutping"].strip(),
                "english": r["english"].strip(),
                "mandarinRank": mr,
                "cantoneseRank": cr,
                "examplesJson": json.dumps(examples, ensure_ascii=False),
            }
        )
    return out


def fail(line: int, msg: str):
    print(f"error: corpus line {line}: {msg}", file=sys.stderr)
    sys.exit(1)


def effective_sort_key(w: dict) -> tuple:
    """Spoken utility ordering: whichever corpus ranks it, best first."""
    mr, cr = w["mandarinRank"], w["cantoneseRank"]
    if mr is not None and cr is not None:
        return (0, min(mr, cr))
    if mr is not None:
        return (1, mr)
    if cr is not None:
        return (2, cr)
    return (3, 10**9)


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    src = root / "tools" / "corpus" / "words.csv"
    out_dir = root / "app" / "assets"
    out_dir.mkdir(parents=True, exist_ok=True)
    dst = out_dir / "words.db"

    with open(src, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    words = validate(rows)

    # ids assigned in spoken-frequency order => ORDER BY id == frequency order.
    words.sort(key=effective_sort_key)

    if dst.exists():
        dst.unlink()
    conn = sqlite3.connect(dst)
    try:
        conn.executescript(SCHEMA)
        conn.executemany(
            """
            INSERT INTO words (id, simplified, traditional, pinyin, jyutping,
                               english, mandarinRank, cantoneseRank, examplesJson)
            VALUES (:id, :simplified, :traditional, :pinyin, :jyutping,
                    :english, :mandarinRank, :cantoneseRank, :examplesJson)
            """,
            [{**w, "id": i} for i, w in enumerate(words, start=1)],
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
