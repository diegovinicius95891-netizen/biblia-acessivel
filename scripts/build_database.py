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
import sqlite3
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
# BPM e WEB seguem a mesma tradição textual e não numeram quatro versículos
# preservados por Almeida. A lista explícita impede aceitar novas lacunas por
# acidente sem classificar essas diferenças editoriais legítimas como defeito.
EXPECTED_NUMERIC_GAPS = {
    "bpm": {("LUK", 17, 36), ("ACT", 8, 37), ("ACT", 15, 34), ("ACT", 24, 7)},
    "almeida": set(),
    "web": {("LUK", 17, 36), ("ACT", 8, 37), ("ACT", 15, 34), ("ACT", 24, 7)},
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
        "id": "almeida", "name": "João Ferreira de Almeida (domínio público)", "language": "Português", "display_order": 2,
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
        "id": "web", "name": "World English Bible", "language": "English", "display_order": 3,
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


def parse_usfm_zip(url: str, english=False):
    """Converte um pacote USFM oficial em linhas normalizadas."""
    archive = zipfile.ZipFile(io.BytesIO(download(url)))
    for filename in sorted(archive.namelist()):
        if not filename.lower().endswith(".usfm"):
            continue
        raw = archive.read(filename).decode("utf-8-sig", errors="replace")
        id_match = re.search(r"^\\id\s+([A-Z0-9]{3})", raw, re.M)
        if not id_match or id_match.group(1) not in BOOK_BY_CODE:
            continue
        code = id_match.group(1)
        number, pt_name, en_name = BOOK_BY_CODE[code]
        name = en_name if english else pt_name
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
        for code, expected_count in EXPECTED_CHAPTERS.items():
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


def build():
    """Cria e audita três edições completas antes da troca atômica do banco."""
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
    insert_rows(connection, "bpm", parse_usfm_zip("https://ebible.org/Scriptures/porbrbsl_usfm.zip"))
    print("Importando João Ferreira de Almeida…")
    insert_rows(connection, "almeida", parse_usfx(download("https://raw.githubusercontent.com/seven1m/open-bibles/master/por-almeida.usfx.xml")))
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
    """Constrói o banco usando somente as três fontes completas verificadas."""
    build()


if __name__ == "__main__":
    main()
