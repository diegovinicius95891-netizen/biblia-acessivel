"""Parser reutilizável de referências bíblicas digitadas em português."""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class ParsedReference:
    """Representa livro, capítulo e intervalo opcional de versículos."""

    book_query: str
    chapter: int
    verse_start: str | None = None
    verse_end: str | None = None


_REFERENCE = re.compile(
    r"^\s*(?P<book>.+?)\s+(?P<chapter>\d+)"
    r"(?::(?P<start>\d+[a-z]?)(?:\s*[-–]\s*(?P<end>\d+[a-z]?))?)?\s*$",
    re.IGNORECASE,
)


def parse_reference(text: str) -> ParsedReference:
    """Aceita referências como ``Rm 8``, ``Jo 3:16`` e ``Sl 23:1-6``."""
    match = _REFERENCE.match(text)
    if not match:
        raise ValueError("Use um formato como João 3:16, Romanos 8 ou Salmos 23:1-6.")
    values = match.groupdict()
    chapter = int(values["chapter"])
    if chapter < 1:
        raise ValueError("O capítulo deve ser maior que zero.")
    start = values["start"]
    end = values["end"]
    if start and end and int(re.match(r"\d+", end).group()) < int(re.match(r"\d+", start).group()):
        raise ValueError("O fim do intervalo deve vir depois do início.")
    return ParsedReference(values["book"].strip(), chapter, start, end)
