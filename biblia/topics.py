"""Índice temático local compartilhado pela pesquisa e pela área Temas."""

from __future__ import annotations

from .database import _normalize


TOPICS = {
    "Amor": {
        "keywords": ("amor", "amar", "caridade", "compaixão"),
        "references": ("1 Coríntios 13:4-7", "João 3:16", "1 João 4:7-8"),
    },
    "Fé": {
        "keywords": ("fé", "crer", "confiança", "fidelidade"),
        "references": ("Hebreus 11:1", "Romanos 10:17", "Marcos 9:23"),
    },
    "Perdão": {
        "keywords": ("perdão", "perdoar", "misericórdia", "reconciliação"),
        "references": ("Efésios 4:31", "Mateus 6:14-15", "1 João 1:9"),
    },
    "Salvação": {
        "keywords": ("salvação", "salvar", "redenção", "vida eterna"),
        "references": ("João 3:16", "Romanos 10:9-10", "Efésios 2:8-9"),
    },
    "Graça": {
        "keywords": ("graça", "favor", "misericórdia"),
        "references": ("Efésios 2:8-9", "2 Coríntios 12:9", "Tito 2:11"),
    },
    "Oração": {
        "keywords": ("oração", "orar", "pedir", "súplica", "intercessão"),
        "references": ("Filipenses 4:6-7", "Mateus 6:6", "1 Tessalonicenses 5:17"),
    },
    "Espírito Santo": {
        "keywords": ("espírito santo", "consolador", "espírito de deus"),
        "references": ("João 14:26", "Atos 1:8", "Gálatas 5:22-23"),
    },
    "Esperança": {
        "keywords": ("esperança", "esperar", "ânimo", "futuro"),
        "references": ("Romanos 15:13", "Jeremias 29:11", "Lamentações 3:21-23"),
    },
    "Medo": {
        "keywords": ("medo", "temor", "assustado", "coragem", "não temas"),
        "references": ("Isaías 41:10", "Salmos 56:3-4", "2 Timóteo 1:7"),
    },
    "Ansiedade": {
        "keywords": ("ansiedade", "preocupação", "medo", "confiança", "paz", "descanso", "cuidado de deus"),
        "references": ("Filipenses 4:6-7", "1 Pedro 5:7", "Mateus 6:25-34", "Salmos 55:22"),
    },
    "Tristeza": {
        "keywords": ("tristeza", "choro", "aflição", "consolo", "coração quebrantado"),
        "references": ("Salmos 34:18", "Salmos 30:5", "João 16:32"),
    },
    "Família": {
        "keywords": ("família", "filhos", "pais", "casa", "lar"),
        "references": ("Josué 24:15", "Efésios 6:1-4", "Salmos 127:3"),
    },
    "Casamento": {
        "keywords": ("casamento", "marido", "esposa", "matrimônio", "aliança"),
        "references": ("Gênesis 2:24", "Efésios 5:25", "1 Coríntios 13:4-7"),
    },
    "Amizade": {
        "keywords": ("amizade", "amigo", "companheiro", "irmão"),
        "references": ("Provérbios 17:17", "João 15:13", "Eclesiastes 4:9-10"),
    },
    "Sabedoria": {
        "keywords": ("sabedoria", "entendimento", "prudência", "conselho"),
        "references": ("Tiago 1:5", "Provérbios 3:5-7", "Provérbios 9:10"),
    },
    "Pecado": {
        "keywords": ("pecado", "iniquidade", "transgressão", "culpa"),
        "references": ("Romanos 3:23", "Romanos 6:22", "1 João 1:9"),
    },
    "Arrependimento": {
        "keywords": ("arrependimento", "arrepender", "converter", "voltar"),
        "references": ("Atos 3:19", "Lucas 15:7", "2 Coríntios 7:10"),
    },
    "Promessas de Deus": {
        "keywords": ("promessa", "fiel", "cumprir", "aliança", "confiança"),
        "references": ("Números 23:19", "2 Coríntios 1:20", "Josué 21:44"),
    },
}


def resolve_topic(query: str) -> tuple[str, dict] | None:
    """Localiza um tema pelo nome ou por uma palavra relacionada."""
    normalized = _normalize(query)
    for name, data in TOPICS.items():
        if normalized == _normalize(name):
            return name, data
    for name, data in TOPICS.items():
        if normalized in {_normalize(word) for word in data["keywords"]}:
            return name, data
    return None
