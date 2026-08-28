"""Testes da persistência privada de marcadores e anotações."""

from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
import unittest

from biblia.user_data import UserDataDatabase


class UserDataTests(unittest.TestCase):
    """Usa pasta temporária para nunca tocar nos dados reais do usuário."""

    def test_bookmarks(self):
        """Cobre inclusão e exclusão de um marcador."""
        with TemporaryDirectory() as directory:
            database = UserDataDatabase(Path(directory) / "user.db")
            key = ("bpm", "JHN", 3, "16")
            self.assertTrue(database.toggle_bookmark(*key))
            self.assertTrue(database.is_bookmarked(*key))
            self.assertFalse(database.toggle_bookmark(*key))
            database.close()

    def test_notes_are_saved_with_reference_and_date(self):
        """Confere o conteúdo necessário para agrupamento diário e leitura."""
        with TemporaryDirectory() as directory:
            database = UserDataDatabase(Path(directory) / "user.db")
            note_id = database.add_note(
                "bpm", "JHN", "João", 3, "16", "Porque Deus amou...",
                "Amor de Deus", "Minha reflexão.",
            )
            notes = database.notes()
            self.assertEqual(note_id, notes[0]["id"])
            self.assertEqual("João", notes[0]["book_name"])
            self.assertEqual("Minha reflexão.", notes[0]["body"])
            self.assertRegex(notes[0]["created_at"], r"^\d{4}-\d{2}-\d{2}T")
            database.close()

    def test_legacy_notes_are_migrated_and_remain_readable(self):
        """Preserva notas criadas pelo formato antigo que causava a tela vazia."""
        with TemporaryDirectory() as directory:
            path = Path(directory) / "user.db"
            connection = sqlite3.connect(path)
            connection.execute(
                """
                CREATE TABLE notes (
                  translation_id TEXT NOT NULL, book_code TEXT NOT NULL,
                  chapter INTEGER NOT NULL, verse TEXT NOT NULL, note TEXT NOT NULL,
                  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (translation_id, book_code, chapter, verse)
                )
                """
            )
            connection.execute(
                "INSERT INTO notes(translation_id,book_code,chapter,verse,note) VALUES (?,?,?,?,?)",
                ("bpm", "JHN", 3, "16", "Nota antiga preservada."),
            )
            connection.commit()
            connection.close()

            database = UserDataDatabase(path)
            notes = database.notes()
            self.assertEqual(1, len(notes))
            self.assertEqual("Nota antiga preservada.", notes[0]["body"])
            self.assertEqual("Anotação em JHN 3:16", notes[0]["title"])
            self.assertTrue(path.with_name("user.db.pre_notes_migration.bak").exists())
            database.close()

    def test_favorites_categories_and_search_history(self):
        """Cobre categorias próprias e o histórico local de pesquisas."""
        with TemporaryDirectory() as directory:
            database = UserDataDatabase(Path(directory) / "user.db")
            category = database.add_favorite_category("Promessas")
            database.add_favorite("bpm", "JHN", "João", 3, "16", category_id=category)
            favorites = database.favorites("Promessas")
            self.assertEqual("João", favorites[0]["book_name"])
            self.assertEqual("Promessas", favorites[0]["category_name"])
            database.add_search("ansiedade", "topic")
            self.assertEqual("ansiedade", database.searches()[0]["query"])
            database.close()

    def test_plan_progress_can_be_marked_and_unmarked(self):
        """Valida início, conclusão diária e correção de marcação acidental."""
        with TemporaryDirectory() as directory:
            database = UserDataDatabase(Path(directory) / "user.db")
            database.start_plan("john")
            self.assertTrue(database.toggle_plan_day("john", 1, 21))
            self.assertEqual([1], database.plan_states()["john"]["completed_days"])
            self.assertFalse(database.toggle_plan_day("john", 1, 21))
            self.assertEqual([], database.plan_states()["john"]["completed_days"])
            database.close()

    def test_prayer_can_be_answered_and_filtered(self):
        """Cobre criação, resposta, filtros e estatísticas de oração."""
        with TemporaryDirectory() as directory:
            database = UserDataDatabase(Path(directory) / "user.db")
            prayer_id = database.add_prayer("Faculdade", "Dê-me sabedoria.", category="Estudos")
            self.assertEqual(1, len(database.prayers("Orando")))
            database.answer_prayer(prayer_id, "2026-10-15", "Consegui resolver.")
            answered = database.prayers("Respondida")[0]
            self.assertEqual("Consegui resolver.", answered["answer_text"])
            self.assertEqual(1, database.prayer_statistics()["Respondida"])
            database.close()

    def test_backup_round_trip_is_validated(self):
        """Exporta e restaura dados e recusa formatos desconhecidos."""
        with TemporaryDirectory() as directory:
            database = UserDataDatabase(Path(directory) / "user.db")
            database.add_prayer("Pedido", "Texto")
            payload = database.export_payload()
            database.add_prayer("Será substituído", "Texto")
            database.import_payload(payload)
            self.assertEqual(1, len(database.prayers()))
            with self.assertRaises(ValueError):
                database.import_payload({"format": "outro", "version": 1})
            database.close()

    def test_quiz_attempts_are_counted_and_included_in_backup(self):
        """Mantém acertos e erros locais e preserva-os na restauração."""
        with TemporaryDirectory() as directory:
            database = UserDataDatabase(Path(directory) / "user.db")
            database.record_quiz_attempt("q1", "fácil", "Jesus", True)
            database.record_quiz_attempt("q2", "difícil", "ordem bíblica", False)
            self.assertEqual(
                {"quiz_total": 2, "quiz_acertos": 1, "quiz_erros": 1},
                database.quiz_statistics(),
            )
            payload = database.export_payload()
            self.assertEqual(2, payload["version"])
            database.record_quiz_attempt("q3", "médio", "personagens", True)
            database.import_payload(payload)
            self.assertEqual(2, database.quiz_statistics()["quiz_total"])
            database.close()


if __name__ == "__main__":
    unittest.main()
