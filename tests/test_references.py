"""Testes do parser de navegação rápida."""

import unittest

from biblia.references import parse_reference


class ReferenceParserTests(unittest.TestCase):
    """Cobre formatos completos, abreviados e intervalos."""

    def test_chapter_verse_and_range(self):
        """Reconhece livro, capítulo, item inicial e item final."""
        self.assertEqual(("Jo", 3, "16", None), tuple(parse_reference("Jo 3:16").__dict__.values()))
        parsed = parse_reference("Salmos 23:1-6")
        self.assertEqual(23, parsed.chapter)
        self.assertEqual("1", parsed.verse_start)
        self.assertEqual("6", parsed.verse_end)

    def test_chapter_only(self):
        """Mantém os números de versículo opcionais."""
        parsed = parse_reference("Romanos 8")
        self.assertIsNone(parsed.verse_start)

    def test_rejects_reversed_range(self):
        """Recusa intervalos cujo fim aparece antes do começo."""
        with self.assertRaises(ValueError):
            parse_reference("João 3:20-10")


if __name__ == "__main__":
    unittest.main()
