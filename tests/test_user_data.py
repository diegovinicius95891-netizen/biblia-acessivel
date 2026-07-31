"""Testes da persistência privada de marcadores."""

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


if __name__ == "__main__":
    unittest.main()
