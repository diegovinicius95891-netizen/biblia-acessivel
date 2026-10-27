"""Catálogo compartilhado de temas prontos para o Teólogo de IA."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import unicodedata


STUDY_ANGLES = (
    ("visão bíblica", "Explique o que a Bíblia ensina sobre {topic}, citando referências relevantes e respeitando o contexto."),
    ("textos principais", "Apresente e explique os principais textos bíblicos relacionados a {topic}."),
    ("Antigo e Novo Testamento", "Mostre como {topic} aparece no Antigo e no Novo Testamento e como as duas partes se relacionam."),
    ("contexto histórico", "Explique {topic} considerando os contextos históricos e culturais dos textos bíblicos relevantes."),
    ("teologia bíblica", "Desenvolva uma teologia bíblica de {topic}, acompanhando o tema ao longo das Escrituras."),
    ("interpretações cristãs", "Apresente com equilíbrio as principais interpretações cristãs sobre {topic}, indicando concordâncias e diferenças."),
    ("aplicação prática", "Explique como aplicar com responsabilidade o ensino bíblico sobre {topic} na vida atual."),
    ("erros comuns", "Mostre erros comuns ao ensinar ou aplicar {topic} e como evitá-los à luz do contexto bíblico."),
    ("roteiro de estudo", "Crie um roteiro de estudo bíblico sobre {topic}, com introdução, passagens, perguntas e conclusão."),
    ("perguntas para reflexão", "Prepare uma explicação bíblica de {topic} seguida de perguntas pessoais e comunitárias para reflexão."),
)


@dataclass(frozen=True)
class StudyTheme:
    """Um assunto combinado com uma abordagem específica de estudo."""

    label: str
    prompt: str
    search_text: str


def _normalize(value: str) -> str:
    """Normaliza acentos e caixa para a pesquisa tolerante."""
    decomposed = unicodedata.normalize("NFD", value or "")
    return "".join(char for char in decomposed if unicodedata.category(char) != "Mn").casefold()


def load_study_themes(path: Path) -> tuple[StudyTheme, ...]:
    """Expande 200 assuntos em 2.000 estudos pesquisáveis."""
    subjects = tuple(
        line.strip() for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    )
    themes = []
    for subject in subjects:
        for angle, template in STUDY_ANGLES:
            label = f"{subject} — {angle}"
            themes.append(StudyTheme(label, template.format(topic=subject), _normalize(label)))
    return tuple(themes)


def search_study_themes(themes: tuple[StudyTheme, ...], query: str) -> tuple[StudyTheme, ...]:
    """Filtra por qualquer parte do assunto ou da abordagem, ignorando acentos."""
    wanted = _normalize(query.strip())
    if not wanted:
        return themes
    words = tuple(word for word in wanted.split() if word)
    return tuple(theme for theme in themes if all(word in theme.search_text for word in words))
