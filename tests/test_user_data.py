"""Testes da persistência privada de notas e marcadores."""

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from biblia.user_data import UserDataDatabase


class UserDataTests(unittest.TestCase):
    """Usa pasta temporária para nunca tocar nos dados reais do usuário."""

    def test_notes_and_bookmarks(self):
        """Cobre criação, atualização, alternância e exclusão."""
        with TemporaryDirectory() as directory:
            database = UserDataDatabase(Path(directory) / "user.db")
            key = ("bpm", "JHN", 3, "16")
            self.assertEqual("", database.note(*key))
            database.save_note(*key, "Minha nota")
            self.assertEqual("Minha nota", database.note(*key))
            self.assertTrue(database.toggle_bookmark(*key))
            self.assertTrue(database.is_bookmarked(*key))
            self.assertFalse(database.toggle_bookmark(*key))
            database.save_note(*key, "")
            self.assertEqual("", database.note(*key))
            database.close()


if __name__ == "__main__":
    unittest.main()
