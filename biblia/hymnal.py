"""Leitura local e pesquisa acessível dos 640 hinos da Harpa Cristã."""

from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Hymn:
    """Representa um hino completo sem áudio, cifra ou formatação proprietária."""

    number: int
    title: str
    lyrics: str

    @property
    def label(self) -> str:
        """Fornece o rótulo curto anunciado na lista de hinos."""
        return f"{self.number}. {self.title}"


def load_hymnal(path: Path) -> tuple[Hymn, ...]:
    """Valida o JSON empacotado e remove somente marcação Markdown visual."""
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, list) or len(raw) != 640:
        raise ValueError("O catálogo da Harpa Cristã está incompleto.")
    result = []
    for expected, item in enumerate(raw, 1):
        if not isinstance(item, dict) or int(item.get("numero", 0)) != expected:
            raise ValueError(f"Numeração inválida no hino {expected}.")
        title = str(item.get("titulo", "")).strip()
        lyrics = re.sub(r"\*+", "", str(item.get("letra", ""))).strip()
        if not title or not lyrics:
            raise ValueError(f"O hino {expected} não possui título ou letra.")
        result.append(Hymn(expected, title, lyrics))
    return tuple(result)


def search_hymns(hymns: tuple[Hymn, ...], query: str) -> tuple[Hymn, ...]:
    """Pesquisa por número, título ou qualquer trecho da letra, sem rede."""
    wanted = _normalize(query)
    if not wanted:
        return hymns
    if wanted.isdigit():
        return tuple(hymn for hymn in hymns if str(hymn.number).startswith(wanted))
    return tuple(
        hymn for hymn in hymns
        if wanted in _normalize(hymn.title) or wanted in _normalize(hymn.lyrics)
    )


def _normalize(value: str) -> str:
    """Ignora caixa e acentos sem modificar o texto exibido ao usuário."""
    decomposed = unicodedata.normalize("NFKD", value.casefold())
    return " ".join("".join(char for char in decomposed if not unicodedata.combining(char)).split())
