"""Catálogo extensível e geração determinística dos planos de leitura."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta


@dataclass(frozen=True)
class ReadingPlan:
    """Plano imutável com uma descrição e leituras indexadas por dia."""

    plan_id: str
    name: str
    description: str
    readings: tuple[tuple[str, ...], ...]

    @property
    def days(self) -> int:
        """Quantidade total de dias do plano."""
        return len(self.readings)


def _distribute(chapters: list[tuple[str, int]], days: int) -> tuple[tuple[str, ...], ...]:
    """Distribui capítulos em dias equilibrados e compacta intervalos por livro."""
    result = []
    for day in range(days):
        start = round(day * len(chapters) / days)
        end = round((day + 1) * len(chapters) / days)
        portion = chapters[start:end]
        readings = []
        index = 0
        while index < len(portion):
            book, first = portion[index]
            last = first
            index += 1
            while index < len(portion) and portion[index][0] == book and portion[index][1] == last + 1:
                last = portion[index][1]
                index += 1
            readings.append(f"{book} {first}" if first == last else f"{book} {first}-{last}")
        result.append(tuple(readings) or ("Dia de revisão",))
    return tuple(result)


def _thematic(days: int, references: tuple[str, ...]) -> tuple[tuple[str, ...], ...]:
    """Repete uma base temática apenas quando o plano exige mais dias que referências."""
    return tuple((references[index % len(references)],) for index in range(days))


def build_plan_catalog(database, translation_id: str) -> dict[str, ReadingPlan]:
    """Cria planos usando os capítulos realmente disponíveis na tradução atual."""
    books = list(database.books(translation_id))
    def available_chapters(book):
        """Ignora lacunas ocasionais existentes em uma fonte distribuída."""
        return database.chapter_numbers(translation_id, book["book_code"])

    all_chapters = [
        (book["book_name"], chapter)
        for book in books
        for chapter in available_chapters(book)
    ]
    new_testament = [item for book in books if int(book["book_number"]) > 39
                     for item in [(book["book_name"], chapter)
                                  for chapter in available_chapters(book)]]
    codes = {book["book_code"]: book for book in books}

    def chapters_for(*book_codes):
        """Expande códigos canônicos em pares de livro e capítulo."""
        return [(codes[code]["book_name"], chapter) for code in book_codes if code in codes
                for chapter in available_chapters(codes[code])]

    definitions = [
        ReadingPlan("bible_365", "Bíblia inteira em 1 ano", "Leitura de toda a Bíblia em 365 dias.", _distribute(all_chapters, 365)),
        ReadingPlan("bible_180", "Bíblia inteira em 6 meses", "Leitura intensiva de toda a Bíblia em 180 dias.", _distribute(all_chapters, 180)),
        ReadingPlan("nt_90", "Novo Testamento em 90 dias", "Percurso completo do Novo Testamento.", _distribute(new_testament, 90)),
        ReadingPlan("nt_30", "Novo Testamento em 30 dias", "Percurso intensivo do Novo Testamento.", _distribute(new_testament, 30)),
        ReadingPlan("psalms_30", "Salmos em 30 dias", "Cinco salmos por dia.", _distribute(chapters_for("PSA"), 30)),
        ReadingPlan("proverbs_31", "Provérbios em 31 dias", "Um capítulo de Provérbios por dia.", _distribute(chapters_for("PRO"), 31)),
        ReadingPlan("gospels_30", "Evangelhos em 30 dias", "Mateus, Marcos, Lucas e João em trinta dias.", _distribute(chapters_for("MAT", "MRK", "LUK", "JHN"), 30)),
        ReadingPlan("john", "Evangelho de João", "Um capítulo do Evangelho de João por dia.", _distribute(chapters_for("JHN"), 21)),
        ReadingPlan("four_gospels", "Os quatro Evangelhos", "Leitura sequencial dos quatro Evangelhos.", _distribute(chapters_for("MAT", "MRK", "LUK", "JHN"), 45)),
        ReadingPlan("life_jesus", "Vida de Jesus", "Passagens centrais do nascimento, ministério, morte e ressurreição de Jesus.", _thematic(30, ("Lucas 2", "Mateus 3", "João 1", "Mateus 5", "Marcos 5", "Lucas 15", "João 11", "Mateus 26", "João 19", "João 20"))),
        ReadingPlan("paul_letters", "Cartas de Paulo", "Leitura das cartas tradicionalmente associadas a Paulo.", _distribute(chapters_for("ROM", "1CO", "2CO", "GAL", "EPH", "PHP", "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM"), 60)),
        ReadingPlan("women", "Mulheres da Bíblia", "Narrativas e textos relacionados a mulheres bíblicas.", _thematic(14, ("Gênesis 16", "Gênesis 24", "Juízes 4", "Rute 1", "1 Samuel 1", "Ester 4", "Lucas 1", "João 4", "Atos 16"))),
        ReadingPlan("men", "Homens da Bíblia", "Narrativas de homens bíblicos e sua caminhada com Deus.", _thematic(14, ("Gênesis 12", "Gênesis 37", "Êxodo 3", "1 Samuel 17", "1 Reis 18", "Daniel 6", "Mateus 14", "Atos 9"))),
        ReadingPlan("faith", "Fé", "Quatorze dias sobre fé e confiança.", _thematic(14, ("Hebreus 11", "Romanos 4", "Marcos 5", "Tiago 2", "Salmos 37"))),
        ReadingPlan("prayer", "Oração", "Quatorze dias sobre oração.", _thematic(14, ("Mateus 6", "Lucas 11", "Filipenses 4", "Salmos 5", "1 Samuel 1"))),
        ReadingPlan("promises", "Promessas de Deus", "Trinta dias de textos sobre fidelidade e promessas.", _thematic(30, ("Gênesis 12", "Josué 1", "Salmos 23", "Isaías 41", "Jeremias 29", "João 14", "Romanos 8", "2 Coríntios 1"))),
        ReadingPlan("hard_psalms", "Salmos para momentos difíceis", "Trinta dias de consolo, oração e esperança nos Salmos.", _thematic(30, ("Salmos 3", "Salmos 13", "Salmos 23", "Salmos 27", "Salmos 34", "Salmos 42", "Salmos 46", "Salmos 55", "Salmos 91", "Salmos 121"))),
    ]
    return {plan.plan_id: plan for plan in definitions}


def progress_summary(plan: ReadingPlan, state: dict | None) -> dict:
    """Calcula progresso, dia atual e conclusão prevista para apresentação."""
    completed = set(state.get("completed_days", [])) if state else set()
    current = int(state.get("current_day", 1)) if state else 1
    started_text = state.get("started_at") if state else None
    started = date.fromisoformat(started_text[:10]) if started_text else None
    return {
        "completed": len(completed),
        "percent": round(100 * len(completed) / max(1, plan.days)),
        "current_day": max(1, min(plan.days, current)),
        "start_date": started,
        "expected_end": started + timedelta(days=plan.days - 1) if started else None,
    }
