"""Gera o catálogo compartilhado de perguntas usado no Windows e Android.

O arquivo TSV evita duas cópias divergentes do quiz. Além das perguntas
curadas manualmente, o gerador cria exercícios determinísticos sobre ordem,
testamento e quantidade de capítulos dos 66 livros.
"""

from __future__ import annotations

import csv
import sys
from pathlib import Path

# Permite executar o script diretamente, independentemente da pasta atual.
PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from biblia.study_content import QUIZ


BOOKS = (
    ("Gênesis", 50, "Antigo Testamento"), ("Êxodo", 40, "Antigo Testamento"),
    ("Levítico", 27, "Antigo Testamento"), ("Números", 36, "Antigo Testamento"),
    ("Deuteronômio", 34, "Antigo Testamento"), ("Josué", 24, "Antigo Testamento"),
    ("Juízes", 21, "Antigo Testamento"), ("Rute", 4, "Antigo Testamento"),
    ("1 Samuel", 31, "Antigo Testamento"), ("2 Samuel", 24, "Antigo Testamento"),
    ("1 Reis", 22, "Antigo Testamento"), ("2 Reis", 25, "Antigo Testamento"),
    ("1 Crônicas", 29, "Antigo Testamento"), ("2 Crônicas", 36, "Antigo Testamento"),
    ("Esdras", 10, "Antigo Testamento"), ("Neemias", 13, "Antigo Testamento"),
    ("Ester", 10, "Antigo Testamento"), ("Jó", 42, "Antigo Testamento"),
    ("Salmos", 150, "Antigo Testamento"), ("Provérbios", 31, "Antigo Testamento"),
    ("Eclesiastes", 12, "Antigo Testamento"), ("Cântico dos Cânticos", 8, "Antigo Testamento"),
    ("Isaías", 66, "Antigo Testamento"), ("Jeremias", 52, "Antigo Testamento"),
    ("Lamentações", 5, "Antigo Testamento"), ("Ezequiel", 48, "Antigo Testamento"),
    ("Daniel", 12, "Antigo Testamento"), ("Oseias", 14, "Antigo Testamento"),
    ("Joel", 3, "Antigo Testamento"), ("Amós", 9, "Antigo Testamento"),
    ("Obadias", 1, "Antigo Testamento"), ("Jonas", 4, "Antigo Testamento"),
    ("Miqueias", 7, "Antigo Testamento"), ("Naum", 3, "Antigo Testamento"),
    ("Habacuque", 3, "Antigo Testamento"), ("Sofonias", 3, "Antigo Testamento"),
    ("Ageu", 2, "Antigo Testamento"), ("Zacarias", 14, "Antigo Testamento"),
    ("Malaquias", 4, "Antigo Testamento"), ("Mateus", 28, "Novo Testamento"),
    ("Marcos", 16, "Novo Testamento"), ("Lucas", 24, "Novo Testamento"),
    ("João", 21, "Novo Testamento"), ("Atos", 28, "Novo Testamento"),
    ("Romanos", 16, "Novo Testamento"), ("1 Coríntios", 16, "Novo Testamento"),
    ("2 Coríntios", 13, "Novo Testamento"), ("Gálatas", 6, "Novo Testamento"),
    ("Efésios", 6, "Novo Testamento"), ("Filipenses", 4, "Novo Testamento"),
    ("Colossenses", 4, "Novo Testamento"), ("1 Tessalonicenses", 5, "Novo Testamento"),
    ("2 Tessalonicenses", 3, "Novo Testamento"), ("1 Timóteo", 6, "Novo Testamento"),
    ("2 Timóteo", 4, "Novo Testamento"), ("Tito", 3, "Novo Testamento"),
    ("Filemom", 1, "Novo Testamento"), ("Hebreus", 13, "Novo Testamento"),
    ("Tiago", 5, "Novo Testamento"), ("1 Pedro", 5, "Novo Testamento"),
    ("2 Pedro", 3, "Novo Testamento"), ("1 João", 5, "Novo Testamento"),
    ("2 João", 1, "Novo Testamento"), ("3 João", 1, "Novo Testamento"),
    ("Judas", 1, "Novo Testamento"), ("Apocalipse", 22, "Novo Testamento"),
)


def _book_options(correct_index: int) -> tuple[tuple[str, ...], int]:
    """Cria quatro nomes distintos e devolve a posição da resposta certa."""
    indexes = (correct_index, (correct_index + 7) % 66, (correct_index + 19) % 66,
               (correct_index + 37) % 66)
    rotated = indexes[correct_index % 4:] + indexes[:correct_index % 4]
    answers = tuple(BOOKS[index][0] for index in rotated)
    return answers, rotated.index(correct_index)


def _chapter_options(total: int, book_index: int) -> tuple[tuple[str, ...], int]:
    """Produz alternativas positivas e distintas para a quantidade de capítulos."""
    candidates = [total, max(1, total - 1), total + 1, total + (5 if total > 5 else 2)]
    unique = []
    for value in candidates:
        while value in unique:
            value += 2
        unique.append(value)
    shift = book_index % 4
    rotated = unique[shift:] + unique[:shift]
    return tuple(str(value) for value in rotated), rotated.index(total)


def build_questions() -> list[dict[str, object]]:
    """Combina as perguntas curadas com exercícios estruturais verificáveis."""
    questions = []
    for index, question in enumerate(QUIZ, 1):
        difficulty, category, text, answers, correct, explanation, reference = question
        questions.append({
            "id": f"curated-{index:03d}", "difficulty": difficulty,
            "category": category, "question": text, "answers": answers,
            "correct": correct, "explanation": explanation, "reference": reference,
        })

    for index, (name, chapters, testament) in enumerate(BOOKS):
        testament_answers = ("Antigo Testamento", "Novo Testamento", "Livros apócrifos", "Não é livro bíblico")
        questions.append({
            "id": f"testament-{index + 1:02d}", "difficulty": "fácil",
            "category": "estrutura da Bíblia", "question": f"A qual Testamento pertence {name}?",
            "answers": testament_answers, "correct": testament_answers.index(testament),
            "explanation": f"{name} pertence ao {testament} na ordem protestante de 66 livros.",
            "reference": f"Estrutura de {name}",
        })
        chapter_answers, chapter_correct = _chapter_options(chapters, index)
        questions.append({
            "id": f"chapters-{index + 1:02d}", "difficulty": "difícil",
            "category": "estrutura da Bíblia", "question": f"Quantos capítulos possui {name}?",
            "answers": chapter_answers, "correct": chapter_correct,
            "explanation": f"{name} possui {chapters} capítulo{'s' if chapters != 1 else ''}.",
            "reference": f"Estrutura de {name}",
        })
        if index < len(BOOKS) - 1:
            answers, correct = _book_options(index + 1)
            questions.append({
                "id": f"next-{index + 1:02d}", "difficulty": "médio",
                "category": "ordem bíblica", "question": f"Qual livro vem imediatamente depois de {name}?",
                "answers": answers, "correct": correct,
                "explanation": f"{BOOKS[index + 1][0]} vem depois de {name} na ordem bíblica comum.",
                "reference": "Ordem canônica dos 66 livros",
            })
        if index > 0:
            answers, correct = _book_options(index - 1)
            questions.append({
                "id": f"previous-{index + 1:02d}", "difficulty": "médio",
                "category": "ordem bíblica", "question": f"Qual livro vem imediatamente antes de {name}?",
                "answers": answers, "correct": correct,
                "explanation": f"{BOOKS[index - 1][0]} vem antes de {name} na ordem bíblica comum.",
                "reference": "Ordem canônica dos 66 livros",
            })
    return questions


def main() -> None:
    """Grava TSV UTF-8 com cabeçalho estável e campos sem marcação visual."""
    destination = PROJECT_ROOT / "data" / "quiz_questions.tsv"
    fields = ("id", "difficulty", "category", "question", "answer1", "answer2",
              "answer3", "answer4", "correct", "explanation", "reference")
    with destination.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        for question in build_questions():
            row = {key: value for key, value in question.items() if key != "answers"}
            row.update({f"answer{index + 1}": value for index, value in enumerate(question["answers"])})
            writer.writerow(row)
    print(f"{len(build_questions())} perguntas gravadas em {destination}")


if __name__ == "__main__":
    main()
