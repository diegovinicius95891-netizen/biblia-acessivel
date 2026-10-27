"""Catálogo compartilhado de temas prontos para o Teólogo de IA."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import unicodedata


@dataclass(frozen=True)
class StudyTheme:
    """Um assunto cotidiano pronto para receber o escopo escolhido."""

    label: str
    prompt: str
    search_text: str


def _normalize(value: str) -> str:
    """Normaliza acentos e caixa para a pesquisa tolerante."""
    decomposed = unicodedata.normalize("NFD", value or "")
    return "".join(char for char in decomposed if unicodedata.category(char) != "Mn").casefold()


def load_study_themes(path: Path) -> tuple[StudyTheme, ...]:
    """Carrega a lista curta de assuntos úteis no cotidiano."""
    subjects = tuple(
        line.strip() for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    )
    return tuple(
        StudyTheme(
            subject,
            f"Explique biblicamente o tema {subject}, cite e contextualize passagens relevantes, "
            "apresente aplicações responsáveis e reconheça interpretações cristãs diferentes quando existirem.",
            _normalize(subject),
        )
        for subject in subjects
    )


def search_study_themes(themes: tuple[StudyTheme, ...], query: str) -> tuple[StudyTheme, ...]:
    """Filtra por qualquer parte do assunto ou da abordagem, ignorando acentos."""
    wanted = _normalize(query.strip())
    if not wanted:
        return themes
    words = tuple(word for word in wanted.split() if word)
    return tuple(theme for theme in themes if all(word in theme.search_text for word in words))
