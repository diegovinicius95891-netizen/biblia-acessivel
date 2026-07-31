"""Testes de integridade e consulta do banco bíblico."""

from pathlib import Path
import unittest

from biblia.database import BibleDatabase


DB_PATH = Path(__file__).resolve().parents[1] / "data" / "biblia.db"


class DatabaseTests(unittest.TestCase):
    """Valida que as quatro edições podem ser navegadas e pesquisadas."""

    def setUp(self):
        """Abre uma conexão independente antes de cada cenário."""
        self.db = BibleDatabase(DB_PATH)

    def tearDown(self):
        """Fecha a conexão mesmo quando uma asserção falha."""
        self.db.close()

    def test_all_translations_have_66_books(self):
        """Garante o cânon protestante completo em todas as edições."""
        for translation in self.db.translations():
            with self.subTest(translation=translation["id"]):
                self.assertEqual(66, len(self.db.books(translation["id"])))

    def test_reference_data_exists(self):
        """Confirma uma referência conhecida em todas as traduções."""
        for translation_id in ("bpm", "almeida", "otb", "web"):
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

    def test_legal_explanation_is_embedded(self):
        """Mantém justificativas nos metadados de origem do banco."""
        for translation in self.db.translations():
            self.assertIn("Lei nº 9.610/1998", translation["legal_html"])
            self.assertIn("Justificativa específica", translation["legal_html"])


if __name__ == "__main__":
    unittest.main()
