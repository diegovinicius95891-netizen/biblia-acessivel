"""Testes da migração de dados pessoais para o perfil do Windows."""

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from biblia.paths import migrate_legacy_personal_database


class PersonalPathTests(unittest.TestCase):
    """Garante migração única, sem substituir uma base já existente."""

    def test_migration_copies_once_and_preserves_destination(self):
        """Copia a base antiga e nunca sobrescreve alterações posteriores."""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            legacy = root / "old" / "user_data.db"
            destination = root / "profile" / "user_data.db"
            legacy.parent.mkdir(); legacy.write_bytes(b"antiga")
            self.assertTrue(migrate_legacy_personal_database(legacy, destination))
            destination.write_bytes(b"nova")
            self.assertFalse(migrate_legacy_personal_database(legacy, destination))
            self.assertEqual(b"nova", destination.read_bytes())


if __name__ == "__main__":
    unittest.main()
