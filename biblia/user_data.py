"""Persistência local e privada de marcadores e anotações do usuário."""

from __future__ import annotations

import sqlite3
from datetime import datetime
from pathlib import Path


class UserDataDatabase:
    """Gerencia dados pessoais sem misturá-los ao banco bíblico recriável."""

    def __init__(self, path: Path):
        """Cria o arquivo e as tabelas se esta for a primeira execução."""
        path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS bookmarks (
              translation_id TEXT NOT NULL, book_code TEXT NOT NULL,
              chapter INTEGER NOT NULL, verse TEXT NOT NULL,
              created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (translation_id, book_code, chapter, verse)
            )
            """
        )
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS notes (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              translation_id TEXT NOT NULL, book_code TEXT NOT NULL,
              book_name TEXT NOT NULL, chapter INTEGER NOT NULL,
              verse TEXT NOT NULL, verse_text TEXT NOT NULL,
              title TEXT NOT NULL, body TEXT NOT NULL,
              created_at TEXT NOT NULL
            )
            """
        )
        self.connection.commit()

    def is_bookmarked(self, translation_id: str, book_code: str, chapter: int, verse: str) -> bool:
        """Informa se a referência está marcada na tradução indicada."""
        return self.connection.execute(
            "SELECT 1 FROM bookmarks WHERE translation_id=? AND book_code=? AND chapter=? AND verse=?",
            (translation_id, book_code, chapter, str(verse)),
        ).fetchone() is not None

    def toggle_bookmark(self, translation_id: str, book_code: str, chapter: int, verse: str) -> bool:
        """Alterna o marcador e devolve o novo estado (marcado ou não)."""
        key = (translation_id, book_code, chapter, str(verse))
        if self.is_bookmarked(*key):
            self.connection.execute(
                "DELETE FROM bookmarks WHERE translation_id=? AND book_code=? AND chapter=? AND verse=?", key
            )
            marked = False
        else:
            self.connection.execute(
                "INSERT INTO bookmarks(translation_id,book_code,chapter,verse) VALUES (?,?,?,?)", key
            )
            marked = True
        self.connection.commit()
        return marked

    def add_note(
        self,
        translation_id: str,
        book_code: str,
        book_name: str,
        chapter: int,
        verse: str,
        verse_text: str,
        title: str,
        body: str,
    ) -> int:
        """Salva uma anotação vinculada ao texto e devolve seu identificador."""
        cursor = self.connection.execute(
            """
            INSERT INTO notes(
              translation_id, book_code, book_name, chapter, verse,
              verse_text, title, body, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                translation_id,
                book_code,
                book_name,
                int(chapter),
                str(verse),
                verse_text,
                title,
                body,
                datetime.now().astimezone().isoformat(timespec="seconds"),
            ),
        )
        self.connection.commit()
        return int(cursor.lastrowid)

    def notes(self) -> list[dict]:
        """Lista anotações da mais recente para a mais antiga."""
        rows = self.connection.execute(
            "SELECT * FROM notes ORDER BY created_at DESC, id DESC"
        ).fetchall()
        return [dict(row) for row in rows]

    def close(self):
        """Fecha a conexão depois de garantir que alterações foram confirmadas."""
        self.connection.close()
