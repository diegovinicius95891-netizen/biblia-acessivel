"""Baixa, normaliza e valida as fontes autorizadas de ``data/biblia.db``.

O banco antigo só é substituído depois que todas as traduções passam pela
auditoria estrutural. Assim, uma fonte incompleta ou um erro no importador não
entra silenciosamente nos pacotes Windows e Android.
"""

from __future__ import annotations

import html
import io
import os
import re
import shutil
import sqlite3
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB_PATH = ROOT / "data" / "biblia.db"

BOOKS = [
    ("GEN", "Gênesis", "Genesis"), ("EXO", "Êxodo", "Exodus"),
    ("LEV", "Levítico", "Leviticus"), ("NUM", "Números", "Numbers"),
    ("DEU", "Deuteronômio", "Deuteronomy"), ("JOS", "Josué", "Joshua"),
    ("JDG", "Juízes", "Judges"), ("RUT", "Rute", "Ruth"),
    ("1SA", "1 Samuel", "1 Samuel"), ("2SA", "2 Samuel", "2 Samuel"),
    ("1KI", "1 Reis", "1 Kings"), ("2KI", "2 Reis", "2 Kings"),
    ("1CH", "1 Crônicas", "1 Chronicles"), ("2CH", "2 Crônicas", "2 Chronicles"),
    ("EZR", "Esdras", "Ezra"), ("NEH", "Neemias", "Nehemiah"),
    ("EST", "Ester", "Esther"), ("JOB", "Jó", "Job"),
    ("PSA", "Salmos", "Psalms"), ("PRO", "Provérbios", "Proverbs"),
    ("ECC", "Eclesiastes", "Ecclesiastes"), ("SNG", "Cântico dos Cânticos", "Song of Solomon"),
    ("ISA", "Isaías", "Isaiah"), ("JER", "Jeremias", "Jeremiah"),
    ("LAM", "Lamentações", "Lamentations"), ("EZK", "Ezequiel", "Ezekiel"),
    ("DAN", "Daniel", "Daniel"), ("HOS", "Oséias", "Hosea"),
    ("JOL", "Joel", "Joel"), ("AMO", "Amós", "Amos"),
    ("OBA", "Obadias", "Obadiah"), ("JON", "Jonas", "Jonah"),
    ("MIC", "Miquéias", "Micah"), ("NAM", "Naum", "Nahum"),
    ("HAB", "Habacuque", "Habakkuk"), ("ZEP", "Sofonias", "Zephaniah"),
    ("HAG", "Ageu", "Haggai"), ("ZEC", "Zacarias", "Zechariah"),
    ("MAL", "Malaquias", "Malachi"), ("MAT", "Mateus", "Matthew"),
    ("MRK", "Marcos", "Mark"), ("LUK", "Lucas", "Luke"),
    ("JHN", "João", "John"), ("ACT", "Atos", "Acts"),
    ("ROM", "Romanos", "Romans"), ("1CO", "1 Coríntios", "1 Corinthians"),
    ("2CO", "2 Coríntios", "2 Corinthians"), ("GAL", "Gálatas", "Galatians"),
    ("EPH", "Efésios", "Ephesians"), ("PHP", "Filipenses", "Philippians"),
    ("COL", "Colossenses", "Colossians"), ("1TH", "1 Tessalonicenses", "1 Thessalonians"),
    ("2TH", "2 Tessalonicenses", "2 Thessalonians"), ("1TI", "1 Timóteo", "1 Timothy"),
    ("2TI", "2 Timóteo", "2 Timothy"), ("TIT", "Tito", "Titus"),
    ("PHM", "Filemom", "Philemon"), ("HEB", "Hebreus", "Hebrews"),
    ("JAS", "Tiago", "James"), ("1PE", "1 Pedro", "1 Peter"),
    ("2PE", "2 Pedro", "2 Peter"), ("1JN", "1 João", "1 John"),
    ("2JN", "2 João", "2 John"), ("3JN", "3 João", "3 John"),
    ("JUD", "Judas", "Jude"), ("REV", "Apocalipse", "Revelation"),
]
BOOK_BY_CODE = {code: (i + 1, pt, en) for i, (code, pt, en) in enumerate(BOOKS)}
EXPECTED_CHAPTER_COUNTS = (
    50, 40, 27, 36, 34, 24, 21, 4, 31, 24, 22, 25, 29, 36, 10, 13, 10,
    42, 150, 31, 12, 8, 66, 52, 5, 48, 12, 14, 3, 9, 1, 4, 7, 3, 3, 3,
    2, 14, 4, 28, 16, 24, 21, 28, 16, 16, 13, 6, 6, 4, 4, 5, 3, 6, 4,
    3, 1, 13, 5, 5, 3, 5, 1, 1, 1, 22,
)
EXPECTED_CHAPTERS = {
    code: count for (code, _pt, _en), count in zip(BOOKS, EXPECTED_CHAPTER_COUNTS)
}
# Edição católica da BPM: os sete deuterocanônicos entram na ordem católica do
# Antigo Testamento com números fracionários (16.3 fica entre Neemias e Ester),
# o que preserva book_number <= 39 para o AT e os números dos 66 livros comuns.
CATHOLIC_EXTRA_BOOKS = {
    "TOB": (16.3, "Tobias", 14), "JDT": (16.6, "Judite", 16),
    "1MA": (17.3, "1 Macabeus", 16), "2MA": (17.6, "2 Macabeus", 15),
    "WIS": (22.3, "Sabedoria", 19), "SIR": (22.6, "Eclesiástico", 51),
    "BAR": (25.5, "Baruc", 6),
}
# Ester e Daniel com as partes gregas substituem as versões curtas.
CATHOLIC_REPLACED_BOOKS = {"ESG": "EST", "DAG": "DAN"}
CATHOLIC_EXPECTED_CHAPTERS = {
    **EXPECTED_CHAPTERS, "DAN": 14,
    **{code: chapters for code, (_number, _name, chapters) in CATHOLIC_EXTRA_BOOKS.items()},
}
# BPM e WEB seguem a mesma tradição textual e não numeram quatro versículos
# preservados por Almeida. A lista explícita impede aceitar novas lacunas por
# acidente sem classificar essas diferenças editoriais legítimas como defeito.
EXPECTED_NUMERIC_GAPS = {
    "bpm": {("LUK", 17, 36), ("ACT", 8, 37), ("ACT", 15, 34), ("ACT", 24, 7)},
    "almeida": set(),
    "blivre": set(),
    "onbv": set(),
    "web": {("LUK", 17, 36), ("ACT", 8, 37), ("ACT", 15, 34), ("ACT", 24, 7)},
    # Além das lacunas da BPM, a fonte segue a numeração da Vulgata em Eclesiástico
    # e a do texto grego em Ester, que não numeram estes versículos.
    "bpmcat": {("LUK", 17, 36), ("ACT", 8, 37), ("ACT", 15, 34), ("ACT", 24, 7),
               ("EST", 4, 6), ("EST", 9, 5), ("EST", 9, 30)} | {
        ("SIR", chapter, verse) for chapter, verse in (
            (1, 5), (1, 7), (1, 21), (3, 19), (10, 21), (11, 15), (11, 16), (13, 14), (16, 15), (16, 16),
            (17, 5), (17, 9), (17, 16), (17, 18), (17, 21), (18, 3), (19, 18), (19, 19), (19, 21), (20, 3),
            (22, 9), (22, 10), (24, 18), (24, 24), (25, 12), *((26, verse) for verse in range(19, 28)),
        )
    },
}

GENERAL_LAW = """
<h2>Base legal brasileira comum</h2>
<p><b>Lei nº 9.610/1998 (Lei de Direitos Autorais):</b></p>
<ul>
 <li><b>Art. 41:</b> os direitos patrimoniais duram setenta anos contados de 1º de janeiro do ano seguinte ao falecimento do autor.</li>
 <li><b>Art. 45:</b> pertencem ao domínio público, entre outras, as obras cujo prazo de proteção terminou.</li>
 <li><b>Art. 46, I, d:</b> não constitui ofensa a reprodução de obras para uso exclusivo de pessoas com deficiência visual, sem fins comerciais, em Braille ou por outro procedimento e suporte destinado a essas pessoas.</li>
 <li><b>Arts. 24 e 33:</b> preservam autoria e integridade e impedem que uma obra protegida seja reproduzida sob o pretexto de melhorá-la sem autorização.</li>
</ul>
<p>Texto oficial: <a href="https://www.planalto.gov.br/ccivil_03/leis/l9610.htm">Lei nº 9.610/1998</a>.</p>
<p><b>Tratado de Marraqueche:</b> promulgado pelo Decreto nº 9.522/2018 e regulamentado pelo Decreto nº 10.882/2021, busca facilitar o acesso a obras publicadas por pessoas cegas, com deficiência visual ou com outras dificuldades de acesso ao texto impresso.</p>
<p>Textos oficiais: <a href="https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/decreto/d9522.htm">Decreto nº 9.522/2018</a> e <a href="https://www.planalto.gov.br/ccivil_03/_ato2019-2022/2021/decreto/d10882.htm">Decreto nº 10.882/2021</a>.</p>
<p><b>Critério deste aplicativo:</b> a exceção de acessibilidade reforça a finalidade social, mas não é usada sozinha. Cada texto incluído também possui declaração de domínio público ou licença aberta específica. Isso permite distribuir o aplicativo para o público geral sem depender da caracterização de cada usuário.</p>
<p><i>Esta seção documenta as decisões do projeto e não substitui parecer jurídico.</i></p>
"""


def legal_html(title, license_name, source, source_url, reason, extra=""):
    """Monta o registro jurídico preservado nos metadados da tradução."""
    return f"""
    <h1>{html.escape(title)}</h1>
    <p><b>Situação:</b> {license_name}</p>
    <p><b>Fonte do texto:</b> <a href="{source_url}">{html.escape(source)}</a>.</p>
    <h2>Justificativa específica</h2>
    <p>{reason}</p>
    {extra}
    {GENERAL_LAW}
    """


TRANSLATIONS = [
    {
        "id": "bpm", "name": "Bíblia Portuguesa Mundial", "language": "Português (Brasil)", "display_order": 1,
        "license": "Domínio público (declaração da fonte)",
        "source": "eBible.org — porbrbsl", "source_url": "https://ebible.org/details.php?id=porbrbsl",
        "legal_html": legal_html(
            "Bíblia Portuguesa Mundial", "Domínio público, conforme declaração do eBible.org",
            "eBible.org — porbrbsl", "https://ebible.org/details.php?id=porbrbsl",
            "O eBible.org identifica expressamente esta edição como public domain. O aplicativo conserva o texto sem adaptação substancial, informa a proveniência e não reivindica exclusividade sobre ele.",
        ),
    },
    {
        "id": "bpmcat", "name": "Bíblia Católica — Portuguesa Mundial (73 livros)", "language": "Português (Brasil)",
        "display_order": 2, "license": "Domínio público (declaração da fonte)",
        "source": "eBible.org — porbrbsl, com deuterocanônicos", "source_url": "https://ebible.org/details.php?id=porbrbsl",
        "legal_html": legal_html(
            "Bíblia Católica — Portuguesa Mundial", "Domínio público, conforme declaração do eBible.org",
            "eBible.org — porbrbsl", "https://ebible.org/details.php?id=porbrbsl",
            "É a mesma Bíblia Portuguesa Mundial, organizada no cânon católico de 73 livros: inclui Tobias, Judite, 1 e 2 Macabeus, Sabedoria, Eclesiástico e Baruc, e Ester e Daniel com as partes gregas, todos publicados na mesma edição de domínio público.",
        ),
    },
    {
        "id": "almeida", "name": "João Ferreira de Almeida (domínio público)", "language": "Português", "display_order": 3,
        "license": "Domínio público", "source": "Open Bibles — por-almeida.usfx.xml",
        "source_url": "https://github.com/seven1m/open-bibles/blob/master/por-almeida.usfx.xml",
        "legal_html": legal_html(
            "João Ferreira de Almeida", "Domínio público",
            "Open Bibles — arquivo por-almeida.usfx.xml", "https://github.com/seven1m/open-bibles/blob/master/por-almeida.usfx.xml",
            "João Ferreira de Almeida faleceu em 1691, portanto o prazo patrimonial previsto no art. 41 da Lei nº 9.610/1998 terminou há muito tempo. Para evitar incorporar direitos de uma revisão moderna, foi usado um arquivo que se declara de domínio público. O Projeto Gutenberg também oferece uma edição histórica de Almeida, declarada de domínio público nos Estados Unidos; ela é referência de proveniência, mas não é o arquivo estruturado utilizado neste banco.",
            '<p>Referência adicional: <a href="https://www.gutenberg.org/ebooks/62383">edição histórica no Projeto Gutenberg</a>.</p>',
        ),
    },
    {
        "id": "blivre", "name": "Bíblia Livre", "language": "Português (Brasil)", "display_order": 4,
        "license": "Creative Commons Atribuição 3.0 Brasil", "source": "Projeto Bíblia Livre — edição de fevereiro de 2018",
        "source_url": "https://github.com/blivre/BibliaLivre",
        "legal_html": legal_html(
            "Bíblia Livre", "Creative Commons Atribuição 3.0 Brasil (CC BY 3.0 BR)",
            "Projeto Bíblia Livre — edição de fevereiro de 2018", "https://github.com/blivre/BibliaLivre",
            "O projeto autoriza copiar, distribuir, transmitir e adaptar o texto, inclusive para fins comerciais, desde que a autoria seja atribuída. O aplicativo mantém o texto sem adaptações e apresenta os créditos exigidos.",
            '<p>Atribuição: Bíblia Livre (BLIVRE), Copyright © Diego Santos, Mario Sérgio e Marco Teles. Licença: <a href="https://creativecommons.org/licenses/by/3.0/br/">CC BY 3.0 Brasil</a>.</p>',
        ),
    },
    {
        "id": "onbv", "name": "Biblica Open Nova Bíblia Viva", "language": "Português (Brasil)", "display_order": 5,
        "license": "Creative Commons Atribuição-CompartilhaIgual 4.0 Internacional", "source": "Biblica / eBible.org — poronbv",
        "source_url": "https://ebible.org/bible/details.php?id=poronbv",
        "legal_html": legal_html(
            "Biblica Open Nova Bíblia Viva", "Creative Commons Atribuição-CompartilhaIgual 4.0 Internacional (CC BY-SA 4.0)",
            "Biblica / eBible.org — poronbv", "https://ebible.org/bible/details.php?id=poronbv",
            "A Biblica disponibiliza esta edição completa sob licença aberta. O aplicativo redistribui o texto sem alterações, preserva o título, o aviso de direitos autorais e a atribuição, e mantém os materiais derivados sob termos compatíveis.",
            '<p>Biblica® Open Nova Bíblia Viva™. Copyright © 2007, 2010 by Biblica, Inc. Licença: <a href="https://creativecommons.org/licenses/by-sa/4.0/">CC BY-SA 4.0</a>. Saiba mais em <a href="https://open.bible/">open.bible</a>.</p>',
        ),
    },
    {
        "id": "web", "name": "World English Bible", "language": "English", "display_order": 6,
        "license": "Public Domain", "source": "eBible.org — engwebp",
        "source_url": "https://worldenglish.bible/",
        "legal_html": legal_html(
            "World English Bible", "Public Domain",
            "World English Bible / eBible.org", "https://worldenglish.bible/",
            "Os responsáveis dedicaram o texto ao domínio público. O nome World English Bible é marca usada aqui somente para identificar uma cópia fiel. O aplicativo não altera o conteúdo da tradução.",
            '<p>A declaração oficial permite copiar e distribuir livremente e pede que textos alterados não sejam chamados World English Bible.</p>',
        ),
    },
]


def download(url: str) -> bytes:
    """Baixa uma fonte identificando o projeto no cabeçalho HTTP."""
    request = urllib.request.Request(url, headers={"User-Agent": "Biblia-Acessivel/1.0"})
    with urllib.request.urlopen(request, timeout=90) as response:
        return response.read()


def verse_sort(value: str) -> float:
    """Obtém a parte numérica para ordenar referências combinadas."""
    match = re.match(r"\d+", value)
    return float(match.group()) if match else 0.0


def clean_usfm(text: str) -> str:
    """Remove marcação USFM sem reescrever o conteúdo bíblico."""
    text = re.sub(r"\\f\s.*?\\f\*", "", text, flags=re.S)
    text = re.sub(r"\\x\s.*?\\x\*", "", text, flags=re.S)
    text = re.sub(r"\\\+?w\s+([^|\\]+)(?:\|[^\\]+)?\\\+?w\*", r"\1", text)
    text = re.sub(r"\\zaln-s\s+.*?\\\*", "", text)
    text = re.sub(r"\\zaln-e\\\*", "", text)
    text = re.sub(r"\\\+?[a-zA-Z0-9_-]+\*?", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return re.sub(r"\s+([,.;:!?])", r"\1", text)


def protestant_book(code: str, english=False):
    """Mapeia somente os 66 livros comuns."""
    if code not in BOOK_BY_CODE:
        return None
    number, pt_name, en_name = BOOK_BY_CODE[code]
    return number, code, en_name if english else pt_name


def catholic_book(code: str, english=False):
    """Mapeia o cânon católico: 66 livros, deuterocanônicos e Ester e Daniel gregos."""
    if code in CATHOLIC_EXTRA_BOOKS:
        number, name, _chapters = CATHOLIC_EXTRA_BOOKS[code]
        return number, code, name
    if code in CATHOLIC_REPLACED_BOOKS:
        return protestant_book(CATHOLIC_REPLACED_BOOKS[code], english)
    if code in CATHOLIC_REPLACED_BOOKS.values():
        return None
    return protestant_book(code, english)


def parse_usfm_zip(url: str, english=False, book_map=protestant_book, data: bytes | None = None):
    """Converte um pacote USFM oficial em linhas normalizadas."""
    archive = zipfile.ZipFile(io.BytesIO(data if data is not None else download(url)))
    for filename in sorted(archive.namelist()):
        if not filename.lower().endswith(".usfm"):
            continue
        raw = archive.read(filename).decode("utf-8-sig", errors="replace")
        id_match = re.search(r"^\\id\s+([A-Z0-9]{3})", raw, re.M)
        mapped = book_map(id_match.group(1), english) if id_match else None
        if not mapped:
            continue
        number, code, name = mapped
        chapter = 0
        current_verse = None
        buffer = []
        for line in raw.splitlines():
            chapter_match = re.match(r"\\c\s+(\d+)", line)
            verse_match = re.match(r"\\v\s+([^\s]+)\s*(.*)", line)
            if chapter_match:
                # O último versículo pertence ao capítulo anterior. Fechá-lo
                # antes de trocar ``chapter`` evita referências duplicadas.
                if current_verse is not None:
                    yield number, code, name, chapter, current_verse, clean_usfm(" ".join(buffer))
                    current_verse, buffer = None, []
                chapter = int(chapter_match.group(1))
            elif verse_match:
                if current_verse is not None:
                    yield number, code, name, chapter, current_verse, clean_usfm(" ".join(buffer))
                current_verse = verse_match.group(1)
                buffer = [verse_match.group(2)]
            elif current_verse is not None and not re.match(r"\\(?:c|id)\b", line):
                buffer.append(line)
        if current_verse is not None:
            yield number, code, name, chapter, current_verse, clean_usfm(" ".join(buffer))


def parse_usfx(data: bytes):
    """Percorre o XML USFX de Almeida preservando capítulos e números."""
    root = ET.fromstring(data)
    for book in root.findall(".//book"):
        code = book.attrib.get("id", "")
        if code not in BOOK_BY_CODE:
            continue
        number, name, _ = BOOK_BY_CODE[code]
        state = {"chapter": 0, "verse": None, "parts": []}

        def finish():
            """Fecha o item atual e o adiciona somente quando possui texto."""
            if state["verse"] is not None:
                text = re.sub(r"\s+", " ", "".join(state["parts"])).strip()
                if text:
                    rows.append((number, code, name, state["chapter"], state["verse"], text))
            state["verse"], state["parts"] = None, []

        def walk(element):
            """Percorre elementos mistos do USFX e ignora notas editoriais."""
            if element.text and state["verse"] is not None:
                state["parts"].append(element.text)
            for child in element:
                tag = child.tag.split("}")[-1]
                if tag == "c":
                    finish()
                    state["chapter"] = int(child.attrib.get("id", 0))
                elif tag == "v":
                    finish()
                    state["verse"] = child.attrib.get("id", "")
                elif tag == "ve":
                    finish()
                elif tag not in {"f", "x", "note"}:
                    walk(child)
                if child.tail and state["verse"] is not None:
                    state["parts"].append(child.tail)

        rows = []
        walk(book)
        finish()
        yield from rows


def insert_rows(connection, translation_id, rows):
    """Insere em lotes para reduzir tempo e memória durante a construção."""
    prepared = []
    for book_number, code, name, chapter, verse, text in rows:
        if text:
            prepared.append((translation_id, book_number, code, name, chapter, str(verse), verse_sort(str(verse)), text))
        if len(prepared) >= 2000:
            connection.executemany("INSERT INTO verses VALUES (?,?,?,?,?,?,?,?)", prepared)
            prepared.clear()
    if prepared:
        connection.executemany("INSERT INTO verses VALUES (?,?,?,?,?,?,?,?)", prepared)


def validate_database(connection: sqlite3.Connection):
    """Interrompe a construção se qualquer edição estiver incompleta ou ambígua."""
    translation_ids = [item["id"] for item in TRANSLATIONS]
    stored_ids = [row[0] for row in connection.execute(
        "SELECT id FROM translations ORDER BY display_order"
    )]
    if stored_ids != translation_ids:
        raise ValueError(f"Traduções inesperadas: {stored_ids}")

    for translation_id in translation_ids:
        empty = connection.execute(
            "SELECT COUNT(*) FROM verses WHERE translation_id=? AND trim(text)=''",
            (translation_id,),
        ).fetchone()[0]
        if empty:
            raise ValueError(f"{translation_id}: {empty} textos vazios")

        duplicates = connection.execute("""
            SELECT book_code, chapter, verse, COUNT(*)
            FROM verses WHERE translation_id=?
            GROUP BY book_code, chapter, verse HAVING COUNT(*) > 1
        """, (translation_id,)).fetchall()
        if duplicates:
            raise ValueError(f"{translation_id}: referências duplicadas: {duplicates[:5]}")

        actual_gaps = set()
        expected_books = CATHOLIC_EXPECTED_CHAPTERS if translation_id == "bpmcat" else EXPECTED_CHAPTERS
        book_total = connection.execute(
            "SELECT COUNT(DISTINCT book_code) FROM verses WHERE translation_id=?", (translation_id,)
        ).fetchone()[0]
        if book_total != len(expected_books):
            raise ValueError(f"{translation_id}: {book_total} livros; esperado {len(expected_books)}")
        for code, expected_count in expected_books.items():
            chapters = [row[0] for row in connection.execute("""
                SELECT DISTINCT chapter FROM verses
                WHERE translation_id=? AND book_code=? ORDER BY chapter
            """, (translation_id, code))]
            expected = list(range(1, expected_count + 1))
            if chapters != expected:
                raise ValueError(
                    f"{translation_id}/{code}: capítulos {chapters}; esperado {expected}"
                )
            for chapter in chapters:
                numbers = sorted({int(match.group()) for (verse,) in connection.execute("""
                    SELECT verse FROM verses
                    WHERE translation_id=? AND book_code=? AND chapter=?
                """, (translation_id, code, chapter)) if (match := re.match(r"\d+", verse))})
                missing = set(range(1, numbers[-1] + 1)) - set(numbers)
                actual_gaps.update((code, chapter, number) for number in missing)
        if actual_gaps != EXPECTED_NUMERIC_GAPS[translation_id]:
            raise ValueError(
                f"{translation_id}: lacunas {sorted(actual_gaps)}; esperado "
                f"{sorted(EXPECTED_NUMERIC_GAPS[translation_id])}"
            )


BPM_URL = "https://ebible.org/Scriptures/porbrbsl_usfm.zip"


def add_catholic_only():
    """Acrescenta a edição católica ao banco atual sem baixar de novo as outras traduções."""
    pending_path = DB_PATH.with_suffix(".db.new")
    shutil.copyfile(DB_PATH, pending_path)
    connection = sqlite3.connect(pending_path)
    connection.execute("DELETE FROM verses WHERE translation_id='bpmcat'")
    connection.execute("DELETE FROM translations")
    for item in TRANSLATIONS:
        connection.execute(
            "INSERT INTO translations VALUES (:id,:name,:language,:license,:source,:source_url,:legal_html,:display_order)", item
        )
    print("Importando Bíblia Católica (BPM, 73 livros)…")
    insert_rows(connection, "bpmcat", parse_usfm_zip(BPM_URL, book_map=catholic_book))
    connection.commit()
    validate_database(connection)
    connection.execute("VACUUM")
    count = connection.execute("SELECT COUNT(*) FROM verses WHERE translation_id='bpmcat'").fetchone()[0]
    connection.close()
    os.replace(pending_path, DB_PATH)
    print(f"Edição católica adicionada: {count} versículos")


def build():
    """Cria e audita cinco edições completas antes da troca atômica do banco."""
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    pending_path = DB_PATH.with_suffix(".db.new")
    if pending_path.exists():
        pending_path.unlink()
    connection = sqlite3.connect(pending_path)
    connection.executescript("""
        CREATE TABLE translations (
          id TEXT PRIMARY KEY, name TEXT NOT NULL, language TEXT NOT NULL,
          license TEXT NOT NULL, source TEXT NOT NULL, source_url TEXT NOT NULL,
          legal_html TEXT NOT NULL, display_order INTEGER NOT NULL
        );
        CREATE TABLE verses (
          translation_id TEXT NOT NULL, book_number INTEGER NOT NULL,
          book_code TEXT NOT NULL, book_name TEXT NOT NULL,
          chapter INTEGER NOT NULL, verse TEXT NOT NULL,
          verse_sort REAL NOT NULL, text TEXT NOT NULL
        );
        CREATE UNIQUE INDEX reference_unique_idx
          ON verses(translation_id, book_code, chapter, verse);
        CREATE INDEX chapter_idx ON verses(translation_id, book_number, chapter, verse_sort);
        CREATE INDEX book_idx ON verses(translation_id, book_code, chapter);
    """)
    for item in TRANSLATIONS:
        connection.execute(
            "INSERT INTO translations VALUES (:id,:name,:language,:license,:source,:source_url,:legal_html,:display_order)", item
        )

    print("Importando Bíblia Portuguesa Mundial…")
    bpm_zip = download(BPM_URL)
    insert_rows(connection, "bpm", parse_usfm_zip(BPM_URL, data=bpm_zip))
    print("Importando Bíblia Católica (BPM, 73 livros)…")
    insert_rows(connection, "bpmcat", parse_usfm_zip(BPM_URL, book_map=catholic_book, data=bpm_zip))
    print("Importando João Ferreira de Almeida…")
    insert_rows(connection, "almeida", parse_usfx(download("https://raw.githubusercontent.com/seven1m/open-bibles/master/por-almeida.usfx.xml")))
    print("Importando Bíblia Livre…")
    insert_rows(connection, "blivre", parse_usfm_zip("https://ebible.org/Scriptures/porbr2018_usfm.zip"))
    print("Importando Biblica Open Nova Bíblia Viva…")
    insert_rows(connection, "onbv", parse_usfm_zip("https://ebible.org/Scriptures/poronbv_usfm.zip"))
    print("Importando World English Bible…")
    insert_rows(connection, "web", parse_usfm_zip("https://ebible.org/Scriptures/engwebp_usfm.zip", english=True))
    connection.commit()
    validate_database(connection)
    connection.execute("VACUUM")
    counts = connection.execute("SELECT translation_id, COUNT(*) FROM verses GROUP BY translation_id ORDER BY translation_id").fetchall()
    connection.close()
    os.replace(pending_path, DB_PATH)
    print(f"Banco criado em {DB_PATH}")
    for translation, count in counts:
        print(f"  {translation}: {count} versículos")


def main():
    """Constrói o banco; ``--somente-catolica`` só acrescenta a edição católica."""
    if "--somente-catolica" in sys.argv:
        add_catholic_only()
    else:
        build()


if __name__ == "__main__":
    main()
