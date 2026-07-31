"""Camada de leitura do banco bíblico SQLite.

Este módulo não altera os textos. Ele concentra consultas de traduções,
livros, capítulos, referências e pesquisa para manter a interface desacoplada
do formato físico do banco.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path


class BibleDatabase:
    """Fornece consultas somente leitura usadas pelas seções do aplicativo."""

    def __init__(self, path: Path):
        """Abre o banco e configura linhas acessíveis por nome de coluna."""
        self.connection = sqlite3.connect(path)
        self.connection.row_factory = sqlite3.Row

    def close(self) -> None:
        """Libera explicitamente a conexão ao fechar o aplicativo."""
        self.connection.close()

    def translations(self):
        """Retorna traduções na ordem definida pelo projeto."""
        return self.connection.execute(
            "SELECT * FROM translations ORDER BY display_order"
        ).fetchall()

    def translation(self, translation_id: str):
        """Obtém metadados e licença de uma única tradução."""
        return self.connection.execute(
            "SELECT * FROM translations WHERE id = ?", (translation_id,)
        ).fetchone()

    def books(self, translation_id: str):
        """Lista os livros existentes na tradução em ordem canônica."""
        return self.connection.execute(
            """
            SELECT DISTINCT book_number, book_code, book_name
            FROM verses WHERE translation_id = ? ORDER BY book_number
            """,
            (translation_id,),
        ).fetchall()

    def chapter_count(self, translation_id: str, book_code: str) -> int:
        """Informa quantos capítulos estão disponíveis para determinado livro."""
        row = self.connection.execute(
            "SELECT MAX(chapter) AS total FROM verses WHERE translation_id=? AND book_code=?",
            (translation_id, book_code),
        ).fetchone()
        return int(row["total"] or 1)

    def chapter(self, translation_id: str, book_code: str, chapter: int):
        """Carrega os itens de um capítulo na ordem numérica original."""
        return self.connection.execute(
            """
            SELECT verse, text FROM verses
            WHERE translation_id=? AND book_code=? AND chapter=? ORDER BY verse_sort, verse
            """,
            (translation_id, book_code, chapter),
        ).fetchall()

    def resolve_book(self, translation_id: str, query: str):
        """Resolve nome completo, prefixo ou abreviação digitada pelo usuário."""
        normalized = _normalize(query)
        books = self.books(translation_id)
        exact = [b for b in books if _normalize(b["book_name"]) == normalized]
        if exact:
            return exact[0]
        aliases = {
            "gn": "GEN", "gen": "GEN", "genesis": "GEN",
            "ex": "EXO", "exo": "EXO", "exodo": "EXO",
            "sl": "PSA", "sal": "PSA", "salmos": "PSA", "ps": "PSA",
            "mt": "MAT", "mat": "MAT", "mateus": "MAT", "matthew": "MAT",
            "mc": "MRK", "marcos": "MRK", "mark": "MRK",
            "lc": "LUK", "lucas": "LUK", "luke": "LUK",
            "jo": "JHN", "joao": "JHN", "john": "JHN",
            "at": "ACT", "atos": "ACT", "acts": "ACT",
            "rm": "ROM", "rom": "ROM", "romanos": "ROM", "romans": "ROM",
            "ap": "REV", "apocalipse": "REV", "revelation": "REV",
        }
        code = aliases.get(normalized)
        if code:
            return next((b for b in books if b["book_code"] == code), None)
        starts = [b for b in books if _normalize(b["book_name"]).startswith(normalized)]
        return starts[0] if len(starts) == 1 else None

    def search(self, translation_id: str, query: str, limit: int = 500):
        """Pesquisa todas as palavras informadas e limita resultados excessivos."""
        words = [word for word in query.strip().split() if word]
        if not words:
            return []
        conditions = " AND ".join("lower(v.text) LIKE lower(?)" for _ in words)
        params = [translation_id, *[f"%{word}%" for word in words], limit]
        return self.connection.execute(
            f"""
            SELECT v.book_code, v.book_name, v.chapter, v.verse, v.text
            FROM verses v
            WHERE v.translation_id=? AND {conditions}
            ORDER BY v.book_number, v.chapter, v.verse_sort LIMIT ?
            """,
            params,
        ).fetchall()


def _normalize(value: str) -> str:
    """Remove caixa, acentos e ponto final para comparar nomes de livros."""
    import unicodedata

    value = unicodedata.normalize("NFKD", value.casefold())
    return "".join(c for c in value if not unicodedata.combining(c)).strip().rstrip(".")
