"""Testa a persistência criptografada da chave com a conta do Windows."""

import unittest

from biblia.secure_store import protect_text, unprotect_text


class SecureStoreTests(unittest.TestCase):
    """Valida que a DPAPI protege e recupera sem guardar texto aberto."""

    def test_round_trip_is_encrypted(self):
        """Recupera a chave original a partir de uma representação diferente."""
        key = "sk-chave-de-teste"
        protected = protect_text(key)
        self.assertNotEqual(key, protected)
        self.assertEqual(key, unprotect_text(protected))


if __name__ == "__main__":
    unittest.main()
