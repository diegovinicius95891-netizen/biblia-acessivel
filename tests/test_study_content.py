"""Cobertura estrutural do catálogo ampliado de quiz."""

from pathlib import Path
import unittest

from biblia.study_content import QUIZ
from biblia.hymnal import load_hymnal, search_hymns
from biblia.quiz_catalog import load_quiz_catalog


DATA = Path(__file__).resolve().parent.parent / "data"


class QuizCatalogTests(unittest.TestCase):
    """Confere variedade e integridade de todas as perguntas."""

    def test_every_category_has_every_difficulty_and_multiple_questions(self):
        """Exige duas perguntas por combinação de categoria e dificuldade."""
        categories = {
            "Antigo Testamento", "Novo Testamento", "Jesus", "personagens",
            "livros da Bíblia", "perguntas gerais",
        }
        difficulties = {"fácil", "médio", "difícil"}
        self.assertEqual(36, len(QUIZ))
        for category in categories:
            for difficulty in difficulties:
                matches = [item for item in QUIZ if item[0] == difficulty and item[1] == category]
                self.assertEqual(2, len(matches), (difficulty, category))

    def test_answers_explanations_and_references_are_valid(self):
        """Evita perguntas sem resposta, explicação ou referência legível."""
        for question in QUIZ:
            self.assertEqual(4, len(question[3]))
            self.assertIn(question[4], range(4))
            self.assertTrue(question[5].strip())
            self.assertTrue(question[6].strip())

    def test_shared_catalog_has_298_unique_study_questions(self):
        """Confere o catálogo final empacotado para os dois aplicativos."""
        questions = load_quiz_catalog(DATA / "quiz_questions.tsv")
        self.assertEqual(298, len(questions))
        self.assertEqual(298, len({question.question_id for question in questions}))
        self.assertIn("ordem bíblica", {question.category for question in questions})
        self.assertIn("estrutura da Bíblia", {question.category for question in questions})


class HymnalCatalogTests(unittest.TestCase):
    """Valida a integridade e a pesquisa offline dos 640 hinos."""

    @classmethod
    def setUpClass(cls):
        cls.hymns = load_hymnal(DATA / "harpa_crista.json")

    def test_hymnal_is_complete_and_ordered(self):
        self.assertEqual(640, len(self.hymns))
        self.assertEqual(list(range(1, 641)), [hymn.number for hymn in self.hymns])
        self.assertTrue(all(hymn.title and hymn.lyrics for hymn in self.hymns))

    def test_hymnal_search_accepts_number_title_and_unaccented_text(self):
        self.assertEqual(1, search_hymns(self.hymns, "1")[0].number)
        self.assertIn(1, [hymn.number for hymn in search_hymns(self.hymns, "chuvas de graca")])


if __name__ == "__main__":
    unittest.main()
