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


if __name__ == "__main__":
    unittest.main()
