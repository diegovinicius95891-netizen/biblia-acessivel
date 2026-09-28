"""Gera arquivos JSON pequenos para a versão web da Bíblia Acessível."""

from __future__ import annotations

import csv
import json
import shutil
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = Path(__file__).resolve().parents[1] / "dist" / "data"


def dump(path: Path, value) -> None:
    """Grava JSON UTF-8 compacto para reduzir o tráfego da hospedagem."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )


def build_bible() -> None:
    """Divide o SQLite por tradução e livro para carregar sob demanda."""
    connection = sqlite3.connect(ROOT / "data" / "biblia.db")
    connection.row_factory = sqlite3.Row
    translations = []
    for translation in connection.execute(
        "SELECT id,name,language,license,source,source_url,display_order "
        "FROM translations ORDER BY display_order"
    ):
        books = []
        for book in connection.execute(
            "SELECT book_number,book_code,book_name,MAX(chapter) AS chapters "
            "FROM verses WHERE translation_id=? GROUP BY book_number,book_code,book_name "
            "ORDER BY book_number",
            (translation["id"],),
        ):
            rows = connection.execute(
                "SELECT chapter,verse,text FROM verses "
                "WHERE translation_id=? AND book_code=? "
                "ORDER BY chapter,verse_sort,verse",
                (translation["id"], book["book_code"]),
            )
            chapters = [[] for _ in range(book["chapters"])]
            for row in rows:
                chapters[row["chapter"] - 1].append(
                    {"number": row["verse"], "text": row["text"]}
                )
            dump(
                OUTPUT / translation["id"] / f"{book['book_code']}.json",
                {
                    "code": book["book_code"],
                    "name": book["book_name"],
                    "number": book["book_number"],
                    "chapters": chapters,
                },
            )
            books.append(
                {
                    "code": book["book_code"],
                    "name": book["book_name"],
                    "number": book["book_number"],
                    "chapters": book["chapters"],
                    "testament": "Antigo Testamento"
                    if book["book_number"] <= 39
                    else "Novo Testamento",
                }
            )
        translations.append({**dict(translation), "books": books})
    connection.close()
    dump(OUTPUT / "manifest.json", {"translations": translations})


def build_shared_content() -> None:
    """Publica Harpa e quiz em formatos simples para o navegador."""
    hymns = json.loads((ROOT / "data" / "harpa_crista.json").read_text(encoding="utf-8"))
    for hymn in hymns:
        hymn["letra"] = hymn["letra"].replace("**", "")
    dump(OUTPUT / "harpa.json", hymns)

    with (ROOT / "data" / "quiz_questions.tsv").open(encoding="utf-8", newline="") as source:
        questions = []
        for row in csv.DictReader(source, delimiter="\t"):
            questions.append(
                {
                    "id": row["id"],
                    "difficulty": row["difficulty"],
                    "category": row["category"],
                    "question": row["question"],
                    "answers": [row[f"answer{i}"] for i in range(1, 5)],
                    "correct": int(row["correct"]),
                    "explanation": row["explanation"],
                    "reference": row["reference"],
                }
            )
    dump(OUTPUT / "quiz.json", questions)
    shutil.copy2(ROOT / "THIRD_PARTY_NOTICES.md", OUTPUT / "THIRD_PARTY_NOTICES.md")


if __name__ == "__main__":
    build_bible()
    build_shared_content()
    print(f"Dados web gerados em {OUTPUT}")
