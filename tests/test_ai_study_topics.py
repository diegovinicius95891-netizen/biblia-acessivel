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

    def test_catalog_contains_exactly_two_thousand_unique_themes(self):
        self.assertEqual(2000, len(self.themes))
        self.assertEqual(2000, len({theme.label for theme in self.themes}))

    def test_practical_and_biblical_topics_are_searchable(self):
        self.assertEqual(10, len(search_study_themes(self.themes, "posso beber")))
        self.assertEqual(10, len(search_study_themes(self.themes, "fruto do espirito")))
        love_matches = search_study_themes(self.themes, "amor")
        self.assertGreaterEqual(len(love_matches), 10)
        self.assertTrue(any(theme.label.startswith("Amor —") for theme in love_matches))

    def test_multiple_words_filter_subject_and_angle(self):
        matches = search_study_themes(self.themes, "ansiedade aplicação")
        self.assertEqual(1, len(matches))
        self.assertIn("aplicação prática", matches[0].label)


if __name__ == "__main__":
    unittest.main()
