"""Validação do catálogo pesquisável do Teólogo de IA."""

from pathlib import Path
import unittest

from biblia.ai_study_topics import load_study_themes, search_study_themes


class AiStudyTopicTests(unittest.TestCase):
    """Garante quantidade, diversidade e pesquisa tolerante a acentos."""

    @classmethod
    def setUpClass(cls):
        path = Path(__file__).resolve().parents[1] / "data" / "ai_study_topics.txt"
        cls.themes = load_study_themes(path)

    def test_catalog_contains_curated_unique_themes(self):
        self.assertEqual(72, len(self.themes))
        self.assertEqual(72, len({theme.label for theme in self.themes}))

    def test_practical_and_biblical_topics_are_searchable(self):
        self.assertEqual(1, len(search_study_themes(self.themes, "alcool")))
        self.assertEqual(1, len(search_study_themes(self.themes, "fruto do espirito")))
        self.assertEqual(1, len(search_study_themes(self.themes, "relacionamento cristao")))
        love_matches = search_study_themes(self.themes, "amor")
        self.assertGreaterEqual(len(love_matches), 1)
        self.assertTrue(any(theme.label == "Amor" for theme in love_matches))

    def test_multiple_words_filter_the_subject(self):
        matches = search_study_themes(self.themes, "dinheiro financas")
        self.assertEqual(1, len(matches))
        self.assertEqual("Dinheiro e finanças", matches[0].label)


if __name__ == "__main__":
    unittest.main()
