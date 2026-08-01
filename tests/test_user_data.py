"""Testes da persistência privada de marcadores e anotações."""

from pathlib import Path
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


if __name__ == "__main__":
    unittest.main()
