"""Catálogo compartilhado e validado das perguntas do quiz bíblico."""

from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class QuizQuestion:
    """Pergunta estável usada tanto na interface quanto nas estatísticas."""

    question_id: str
    difficulty: str
    category: str
    text: str
    answers: tuple[str, str, str, str]
    correct: int
    explanation: str
    reference: str


def load_quiz_catalog(path: Path) -> tuple[QuizQuestion, ...]:
    """Lê o TSV UTF-8 e rejeita itens incompletos, duplicados ou ambíguos."""
    result = []
    seen = set()
    with path.open("r", encoding="utf-8", newline="") as source:
        for row in csv.DictReader(source, delimiter="\t"):
            identifier = row["id"].strip()
            answers = tuple(row[f"answer{index}"] for index in range(1, 5))
            correct = int(row["correct"])
            if not identifier or identifier in seen or len(set(answers)) != 4 or not 0 <= correct < 4:
                raise ValueError(f"Pergunta inválida no catálogo: {identifier or 'sem identificador'}.")
            required = (row["difficulty"], row["category"], row["question"],
                        row["explanation"], row["reference"], *answers)
            if any(not value.strip() for value in required):
                raise ValueError(f"A pergunta {identifier} possui campo vazio.")
            seen.add(identifier)
            result.append(QuizQuestion(
                identifier, row["difficulty"], row["category"], row["question"],
                answers, correct, row["explanation"], row["reference"],
            ))
    if len(result) < 200:
        raise ValueError("O catálogo de estudo possui menos de 200 perguntas.")
    return tuple(result)


def categories(questions: tuple[QuizQuestion, ...]) -> tuple[str, ...]:
    """Retorna categorias na primeira ordem em que aparecem no catálogo."""
    return tuple(dict.fromkeys(question.category for question in questions))
