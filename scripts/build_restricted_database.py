"""Monta o banco da edição restrita da AGB: o banco público mais traduções protegidas.

A edição restrita vai só para o teste fechado da Play, entregue pela Áudio Games
Brasil a pessoas com deficiência visual. O banco gerado fica em data_restrito/,
que o .gitignore exclui: as traduções protegidas nunca entram no repositório nem
na versão pública do Windows.

Uso: python scripts/build_restricted_database.py [PASTA_COM_OS_SQLITE]
Os arquivos seguem o formato book/verse (book_reference_id de 1 a 66).
"""

from __future__ import annotations

import html
import shutil
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_database import DB_PATH, ROOT, insert_rows, parse_sqlite_bible  # noqa: E402

OUTPUT = ROOT / "data_restrito" / "biblia.db"

# id: (arquivo, nome exibido, detentor dos direitos)
RESTRICTED = {
    "acf": ("ACF", "Almeida Corrigida Fiel", "Sociedade Bíblica Trinitariana do Brasil"),
    "ara": ("ARA", "Almeida Revista e Atualizada", "Sociedade Bíblica do Brasil"),
    "arc": ("ARC", "Almeida Revista e Corrigida", "Sociedade Bíblica do Brasil"),
    "naa": ("NAA", "Nova Almeida Atualizada", "Sociedade Bíblica do Brasil"),
    "ntlh": ("NTLH", "Nova Tradução na Linguagem de Hoje", "Sociedade Bíblica do Brasil"),
    "jfaa": ("JFAA", "Almeida Atualizada", "respectivo detentor dos direitos desta edição"),
    "as21": ("AS21", "Almeida Século 21", "Edições Vida Nova"),
    "nvi": ("NVI", "Nova Versão Internacional", "Biblica, Inc."),
    "nvt": ("NVT", "Nova Versão Transformadora", "Editora Mundo Cristão"),
    "nbv": ("NBV", "Nova Bíblia Viva", "Biblica, Inc. e Editora Mundo Cristão"),
    "kja": ("KJA", "King James Atualizada", "Abba Press"),
    "kjf": ("KJF", "King James Fiel 1611", "respectivo detentor dos direitos desta edição"),
    "mens": ("MENS", "A Mensagem", "Editora Vida"),
    "ol": ("OL", "O Livro", "Biblica, Inc."),
    "vfl": ("VFL", "Versão Fácil de Ler", "Bible League International"),
}


def unique_verses(rows):
    """Algumas fontes repetem um versículo; junta os textos numa referência só."""
    merged = {}
    for number, code, name, chapter, verse, text in rows:
        key = (code, chapter, verse)
        if key in merged:
            if text and text not in merged[key][5]:
                merged[key] = (*merged[key][:5], f"{merged[key][5]} {text}")
        else:
            merged[key] = (number, code, name, chapter, verse, text)
    return merged.values()


def legal(name: str, owner: str) -> str:
    """Registro jurídico da edição restrita, exibido no aplicativo."""
    return f"""
    <h1>{html.escape(name)}</h1>
    <p><b>Situação:</b> obra protegida. Todos os direitos reservados a {html.escape(owner)}.</p>
    <h2>Justificativa específica</h2>
    <p>Esta tradução está somente na edição restrita da Bíblia AGB, distribuída pela Áudio Games Brasil,
    associação sem fins lucrativos, exclusivamente a pessoas com deficiência visual inscritas no teste fechado
    do aplicativo. Ela não faz parte da versão pública do Windows nem do repositório público.</p>
    <p><b>Base legal:</b> art. 46, I, d, da Lei nº 9.610/1998, que permite a reprodução, sem fins comerciais,
    para uso exclusivo de pessoas com deficiência visual, e Tratado de Marraqueche (Decreto nº 9.522/2018,
    regulamentado pelo Decreto nº 10.882/2021).</p>
    <p>O aplicativo não altera o texto e não reivindica nenhum direito sobre ele. Se a editora desejar a
    retirada, a Áudio Games Brasil remove a tradução na versão seguinte.</p>
    <p><i>Esta seção documenta as decisões do projeto e não substitui parecer jurídico.</i></p>
    """


def main() -> None:
    folder = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / "Downloads"
    OUTPUT.parent.mkdir(exist_ok=True)
    pending = OUTPUT.with_suffix(".db.new")
    shutil.copyfile(DB_PATH, pending)
    connection = sqlite3.connect(pending)
    order = connection.execute("SELECT MAX(display_order) FROM translations").fetchone()[0]
    for translation_id, (filename, name, owner) in RESTRICTED.items():
        source = folder / f"{filename}.sqlite"
        if not source.is_file():
            print(f"Ignorada (arquivo não encontrado): {source}")
            continue
        order += 1
        connection.execute("DELETE FROM verses WHERE translation_id=?", (translation_id,))
        connection.execute("DELETE FROM translations WHERE id=?", (translation_id,))
        connection.execute(
            "INSERT INTO translations VALUES (?,?,?,?,?,?,?,?)",
            (translation_id, name, "Português", f"Direitos reservados — {owner}",
             "Edição restrita da Áudio Games Brasil", "", legal(name, owner), order),
        )
        insert_rows(connection, translation_id, unique_verses(parse_sqlite_bible(source)))
        books, chapters = connection.execute(
            "SELECT COUNT(DISTINCT book_code), COUNT(DISTINCT book_code||'-'||chapter) "
            "FROM verses WHERE translation_id=?", (translation_id,)).fetchone()
        if books != 66 or chapters < 1189:
            raise SystemExit(f"{name}: {books} livros e {chapters} capítulos. Banco não gerado.")
        print(f"{name}: {books} livros, {chapters} capítulos")
    connection.commit()
    connection.execute("VACUUM")
    connection.close()
    pending.replace(OUTPUT)
    print(f"Banco restrito criado em {OUTPUT}")


if __name__ == "__main__":
    main()
