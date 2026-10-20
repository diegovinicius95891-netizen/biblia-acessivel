"""Testes da migração de dados pessoais para o perfil do Windows."""

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from biblia.paths import migrate_legacy_personal_database, personal_data_directory


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

    def test_package_smoke_override_is_explicit_and_isolated(self):
        """Permite abrir o EXE em teste sem alterar o perfil pessoal verdadeiro."""
        with TemporaryDirectory() as directory, patch.dict(
            "os.environ", {"BIBLIA_TEST_DATA_DIR": directory}, clear=False
        ):
            self.assertEqual(Path(directory), personal_data_directory())


if __name__ == "__main__":
    unittest.main()
