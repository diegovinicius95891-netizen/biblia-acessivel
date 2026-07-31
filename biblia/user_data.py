"""Persistência local e privada de notas e marcadores do usuário."""

from __future__ import annotations

import sqlite3
from pathlib import Path


class UserDataDatabase:
    """Gerencia dados pessoais sem misturá-los ao banco bíblico recriável.

    A separação é importante por dois motivos: reconstruir ``biblia.db`` não
    apaga anotações e o arquivo pessoal pode ser ignorado ao publicar o código.
    """

    def __init__(self, path: Path):
        """Cria o arquivo e as tabelas se esta for a primeira execução."""
        path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path)
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS notes (
              translation_id TEXT NOT NULL, book_code TEXT NOT NULL,
              chapter INTEGER NOT NULL, verse TEXT NOT NULL, note TEXT NOT NULL,
              updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (translation_id, book_code, chapter, verse)
            )
            """
        )
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
        self.connection.commit()

    def note(self, translation_id: str, book_code: str, chapter: int, verse: str) -> str:
        """Retorna uma nota específica ou texto vazio quando ela não existe."""
        row = self.connection.execute(
            "SELECT note FROM notes WHERE translation_id=? AND book_code=? AND chapter=? AND verse=?",
            (translation_id, book_code, chapter, str(verse)),
        ).fetchone()
        return row[0] if row else ""

    def save_note(self, translation_id: str, book_code: str, chapter: int, verse: str, note: str):
        """Cria/atualiza a nota; conteúdo vazio significa exclusão intencional."""
        key = (translation_id, book_code, chapter, str(verse))
        if note.strip():
            self.connection.execute(
                """
                INSERT INTO notes(translation_id,book_code,chapter,verse,note)
                VALUES (?,?,?,?,?)
                ON CONFLICT(translation_id,book_code,chapter,verse)
                DO UPDATE SET note=excluded.note, updated_at=CURRENT_TIMESTAMP
                """,
                (*key, note.strip()),
            )
        else:
            self.connection.execute(
                "DELETE FROM notes WHERE translation_id=? AND book_code=? AND chapter=? AND verse=?", key
            )
        self.connection.commit()

    def notes(self):
        """Lista todas as notas para montar a seção recolhível por livro."""
        return self.connection.execute(
            """
            SELECT translation_id, book_code, chapter, verse, note, updated_at
            FROM notes
            ORDER BY book_code, chapter, CAST(verse AS INTEGER), verse
            """
        ).fetchall()

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

    def close(self):
        """Fecha a conexão depois de garantir que alterações foram confirmadas."""
        self.connection.close()
