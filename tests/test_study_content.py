"""Cobertura estrutural do catálogo ampliado de quiz."""

import unittest

from biblia.study_content import QUIZ


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


if __name__ == "__main__":
    unittest.main()
