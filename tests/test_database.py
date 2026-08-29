"""Testes de integridade e consulta do banco bíblico."""

from pathlib import Path
import sqlite3
import unittest

from biblia.database import BibleDatabase


DB_PATH = Path(__file__).resolve().parents[1] / "data" / "biblia.db"


class DatabaseTests(unittest.TestCase):
    """Valida que as três edições completas podem ser navegadas e pesquisadas."""

    def setUp(self):
        """Abre uma conexão independente antes de cada cenário."""
        self.db = BibleDatabase(DB_PATH)

    def tearDown(self):
        """Fecha a conexão mesmo quando uma asserção falha."""
        self.db.close()

    def test_all_translations_have_66_books(self):
        """Garante o cânon protestante completo em todas as edições."""
        self.assertEqual(
            ["bpm", "almeida", "web"],
            [item["id"] for item in self.db.translations()],
        )
        for translation in self.db.translations():
            with self.subTest(translation=translation["id"]):
                self.assertEqual(66, len(self.db.books(translation["id"])))

    def test_reference_data_exists(self):
        """Confirma uma referência conhecida em todas as traduções."""
        for translation_id in ("bpm", "almeida", "web"):
            rows = self.db.chapter(translation_id, "JHN", 3)
            self.assertTrue(any(row["verse"] == "16" for row in rows))

    def test_portuguese_book_resolution(self):
        """Verifica resolução de nome acentuado digitado em português."""
        book = self.db.resolve_book("bpm", "João")
        self.assertIsNotNone(book)
        self.assertEqual("JHN", book["book_code"])

    def test_search(self):
        """Confirma que a pesquisa por múltiplas palavras retorna itens."""
        self.assertGreater(len(self.db.search("bpm", "bom pastor")), 0)

    def test_phrase_book_filter_and_passage(self):
        """Cobre frase exata, pesquisa dentro de livro e intervalo de números."""
        results = self.db.search("bpm", "bom pastor", book_code="JHN", phrase=True)
        self.assertTrue(results)
        self.assertTrue(all(row["book_code"] == "JHN" for row in results))
        passage = self.db.passage("bpm", "JHN", 3, "16", "18")
        self.assertGreaterEqual(len(passage), 3)

    def test_legal_explanation_is_embedded(self):
        """Mantém justificativas nos metadados de origem do banco."""
        for translation in self.db.translations():
            self.assertIn("Lei nº 9.610/1998", translation["legal_html"])
            self.assertIn("Justificativa específica", translation["legal_html"])

    def test_no_duplicate_or_empty_verses(self):
        """Impede a volta de referências repetidas ou conteúdo em branco."""
        connection = sqlite3.connect(DB_PATH)
        try:
            duplicates = connection.execute("""
                SELECT translation_id, book_code, chapter, verse, COUNT(*)
                FROM verses GROUP BY translation_id, book_code, chapter, verse
                HAVING COUNT(*) > 1
            """).fetchall()
            empty = connection.execute(
                "SELECT COUNT(*) FROM verses WHERE trim(text) = ''"
            ).fetchone()[0]
        finally:
            connection.close()
        self.assertEqual([], duplicates)
        self.assertEqual(0, empty)

    def test_every_translation_has_all_1189_chapters(self):
        """Detecta livros ou capítulos inteiros ausentes no banco publicado."""
        connection = sqlite3.connect(DB_PATH)
        try:
            totals = dict(connection.execute("""
                SELECT translation_id, COUNT(*) FROM (
                    SELECT DISTINCT translation_id, book_code, chapter FROM verses
                ) GROUP BY translation_id
            """).fetchall())
        finally:
            connection.close()
        self.assertEqual({"bpm": 1189, "almeida": 1189, "web": 1189}, totals)

    def test_chapter_boundary_references_are_unique(self):
        """Cobre diretamente a falha que duplicava o fim do capítulo seguinte."""
        connection = sqlite3.connect(DB_PATH)
        try:
            for translation_id in ("bpm", "web"):
                for chapter, verse in ((3, "24"), (4, "24")):
                    count = connection.execute("""
                        SELECT COUNT(*) FROM verses
                        WHERE translation_id=? AND book_code='GEN'
                          AND chapter=? AND verse=?
                    """, (translation_id, chapter, verse)).fetchone()[0]
                    self.assertEqual(1, count, (translation_id, chapter, verse))
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main()
