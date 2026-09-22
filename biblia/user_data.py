"""Persistência local e privada de marcadores e anotações do usuário."""

from __future__ import annotations

import shutil
import sqlite3
from datetime import datetime
from pathlib import Path


class UserDataDatabase:
    """Gerencia dados pessoais sem misturá-los ao banco bíblico recriável."""

    def __init__(self, path: Path):
        """Cria o arquivo e as tabelas se esta for a primeira execução."""
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS bookmarks (
              translation_id TEXT NOT NULL, book_code TEXT NOT NULL,
              chapter INTEGER NOT NULL, verse TEXT NOT NULL,
              created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (translation_id, book_code, chapter, verse)
            )
            """
        )
        self._ensure_notes_schema()
        self._ensure_extended_schema()
        self.connection.commit()

    def _ensure_extended_schema(self):
        """Amplia o banco de forma incremental sem apagar dados das versões antigas."""
        statements = (
            """CREATE TABLE IF NOT EXISTS favorite_categories (
                 id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE,
                 created_at TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS search_history (
                 id INTEGER PRIMARY KEY AUTOINCREMENT, query TEXT NOT NULL,
                 search_mode TEXT NOT NULL DEFAULT 'auto', book_code TEXT,
                 created_at TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS reading_history (
                 id INTEGER PRIMARY KEY AUTOINCREMENT, translation_id TEXT NOT NULL,
                 book_code TEXT NOT NULL, book_name TEXT NOT NULL,
                 chapter INTEGER NOT NULL, verse TEXT, accessed_at TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS reading_plans (
                 plan_id TEXT PRIMARY KEY, status TEXT NOT NULL,
                 started_at TEXT NOT NULL, current_day INTEGER NOT NULL DEFAULT 1,
                 completed_at TEXT)""",
            """CREATE TABLE IF NOT EXISTS reading_plan_days (
                 plan_id TEXT NOT NULL, day_number INTEGER NOT NULL,
                 completed_at TEXT NOT NULL,
                 PRIMARY KEY(plan_id, day_number))""",
            """CREATE TABLE IF NOT EXISTS prayers (
                 id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
                 request TEXT NOT NULL, created_at TEXT NOT NULL,
                 notes TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '',
                 status TEXT NOT NULL DEFAULT 'Orando', answered_at TEXT,
                 answer_text TEXT NOT NULL DEFAULT '')""",
            """CREATE TABLE IF NOT EXISTS devotionals (
                 id INTEGER PRIMARY KEY AUTOINCREMENT, devotional_date TEXT NOT NULL,
                 verse_reference TEXT NOT NULL DEFAULT '', verse_text TEXT NOT NULL DEFAULT '',
                 learned TEXT NOT NULL DEFAULT '', practice TEXT NOT NULL DEFAULT '',
                 prayer TEXT NOT NULL DEFAULT '', personal_note TEXT NOT NULL DEFAULT '',
                 created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                 UNIQUE(devotional_date))""",
            """CREATE TABLE IF NOT EXISTS memorized_verses (
                 translation_id TEXT NOT NULL, book_code TEXT NOT NULL,
                 book_name TEXT NOT NULL, chapter INTEGER NOT NULL,
                 verse TEXT NOT NULL, verse_text TEXT NOT NULL,
                 created_at TEXT NOT NULL,
                 PRIMARY KEY(translation_id, book_code, chapter, verse))""",
            """CREATE TABLE IF NOT EXISTS app_metadata (
                  key TEXT PRIMARY KEY, value TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS quiz_attempts (
                 id INTEGER PRIMARY KEY AUTOINCREMENT, question_id TEXT NOT NULL,
                 difficulty TEXT NOT NULL, category TEXT NOT NULL,
                 is_correct INTEGER NOT NULL CHECK(is_correct IN (0,1)),
                 answered_at TEXT NOT NULL)""",
        )
        for statement in statements:
            self.connection.execute(statement)
        # Versões anteriores aceitavam várias linhas para a mesma pergunta.
        # Preservamos a primeira resposta e impedimos novas duplicações.
        self.connection.execute(
            """DELETE FROM quiz_attempts
               WHERE id NOT IN (
                 SELECT MIN(id) FROM quiz_attempts GROUP BY question_id
               )"""
        )
        self.connection.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS quiz_attempts_question_id_unique "
            "ON quiz_attempts(question_id)"
        )
        now = self._now()
        self.connection.execute(
            "INSERT OR IGNORE INTO favorite_categories(name, created_at) VALUES (?, ?)",
            ("Favoritos gerais", now),
        )
        columns = {
            row["name"] for row in self.connection.execute("PRAGMA table_info(bookmarks)")
        }
        additions = {
            "book_name": "TEXT NOT NULL DEFAULT ''",
            "end_chapter": "INTEGER",
            "end_verse": "TEXT",
            "kind": "TEXT NOT NULL DEFAULT 'verse'",
            "category_id": "INTEGER",
        }
        for name, definition in additions.items():
            if name not in columns:
                self.connection.execute(f"ALTER TABLE bookmarks ADD COLUMN {name} {definition}")
        default_category = self.connection.execute(
            "SELECT id FROM favorite_categories WHERE name='Favoritos gerais'"
        ).fetchone()["id"]
        self.connection.execute(
            "UPDATE bookmarks SET category_id=? WHERE category_id IS NULL", (default_category,)
        )
        self.connection.execute(
            "INSERT OR REPLACE INTO app_metadata(key,value) VALUES('schema_version','2')"
        )

    @staticmethod
    def _now() -> str:
        """Retorna horário local ISO uniforme para todos os registros pessoais."""
        return datetime.now().astimezone().isoformat(timespec="seconds")

    def _create_notes_table(self):
        """Cria o formato atual, capaz de armazenar várias notas por referência."""
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS notes (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              translation_id TEXT NOT NULL, book_code TEXT NOT NULL,
              book_name TEXT NOT NULL, chapter INTEGER NOT NULL,
              verse TEXT NOT NULL, verse_text TEXT NOT NULL,
              title TEXT NOT NULL, body TEXT NOT NULL,
              created_at TEXT NOT NULL
            )
            """
        )

    def _ensure_notes_schema(self):
        """Cria ou migra notas antigas sem perder o texto já escrito."""
        exists = self.connection.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='notes'"
        ).fetchone()
        if not exists:
            self._create_notes_table()
            return
        columns = {
            row["name"] for row in self.connection.execute("PRAGMA table_info(notes)")
        }
        required = {
            "id", "translation_id", "book_code", "book_name", "chapter",
            "verse", "verse_text", "title", "body", "created_at",
        }
        if required.issubset(columns):
            return

        backup = self.path.with_name(f"{self.path.name}.pre_notes_migration.bak")
        if self.path.exists() and not backup.exists():
            shutil.copy2(self.path, backup)

        legacy_name = "notes_legacy"
        suffix = 1
        while self.connection.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (legacy_name,)
        ).fetchone():
            legacy_name = f"notes_legacy_{suffix}"
            suffix += 1

        self.connection.execute(f'ALTER TABLE notes RENAME TO "{legacy_name}"')
        self._create_notes_table()
        legacy_rows = self.connection.execute(f'SELECT * FROM "{legacy_name}"').fetchall()
        for row in legacy_rows:
            data = dict(row)
            book_code = str(data.get("book_code", ""))
            chapter = int(data.get("chapter", 1))
            verse = str(data.get("verse", "1"))
            book_name = str(data.get("book_name") or book_code)
            title = str(data.get("title") or f"Anotação em {book_name} {chapter}:{verse}")
            body = str(data.get("body") or data.get("note") or "")
            created_at = str(
                data.get("created_at")
                or data.get("updated_at")
                or datetime.now().astimezone().isoformat(timespec="seconds")
            )
            self.connection.execute(
                """
                INSERT INTO notes(
                  translation_id, book_code, book_name, chapter, verse,
                  verse_text, title, body, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    str(data.get("translation_id", "bpm")), book_code, book_name,
                    chapter, verse, str(data.get("verse_text") or ""), title, body,
                    created_at.replace(" ", "T", 1),
                ),
            )
        self.connection.execute(f'DROP TABLE "{legacy_name}"')

    def is_bookmarked(self, translation_id: str, book_code: str, chapter: int, verse: str) -> bool:
        """Informa se a referência está marcada na tradução indicada."""
        return self.connection.execute(
            "SELECT 1 FROM bookmarks WHERE translation_id=? AND book_code=? AND chapter=? AND verse=?",
            (translation_id, book_code, chapter, str(verse)),
        ).fetchone() is not None

    def toggle_bookmark(self, translation_id: str, book_code: str, chapter: int, verse: str) -> bool:
        """Alterna o marcador e devolve o novo estado (marcado ou não)."""
        key = (translation_id, book_code, chapter, str(verse))
        if self.is_bookmarked(*key):
            self.connection.execute(
                "DELETE FROM bookmarks WHERE translation_id=? AND book_code=? AND chapter=? AND verse=?", key
            )
            marked = False
        else:
            self.connection.execute(
                "INSERT INTO bookmarks(translation_id,book_code,chapter,verse) VALUES (?,?,?,?)", key
            )
            marked = True
        self.connection.commit()
        return marked

    def add_note(
        self,
        translation_id: str,
        book_code: str,
        book_name: str,
        chapter: int,
        verse: str,
        verse_text: str,
        title: str,
        body: str,
    ) -> int:
        """Salva uma anotação vinculada ao texto e devolve seu identificador."""
        cursor = self.connection.execute(
            """
            INSERT INTO notes(
              translation_id, book_code, book_name, chapter, verse,
              verse_text, title, body, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                translation_id,
                book_code,
                book_name,
                int(chapter),
                str(verse),
                verse_text,
                title,
                body,
                datetime.now().astimezone().isoformat(timespec="seconds"),
            ),
        )
        self.connection.commit()
        return int(cursor.lastrowid)

    def notes(self) -> list[dict]:
        """Lista anotações da mais recente para a mais antiga."""
        rows = self.connection.execute(
            "SELECT * FROM notes ORDER BY created_at DESC, id DESC"
        ).fetchall()
        return [dict(row) for row in rows]

    def search_notes(self, query: str = "") -> list[dict]:
        """Pesquisa títulos, corpos e referências das anotações."""
        value = f"%{query.strip()}%"
        rows = self.connection.execute(
            """SELECT * FROM notes WHERE ?='' OR title LIKE ? OR body LIKE ?
               OR book_name LIKE ? ORDER BY created_at DESC, id DESC""",
            (query.strip(), value, value, value),
        ).fetchall()
        return [dict(row) for row in rows]

    def favorite_categories(self) -> list[dict]:
        """Lista categorias criadas pela pessoa, mantendo a categoria geral primeiro."""
        rows = self.connection.execute(
            "SELECT * FROM favorite_categories ORDER BY CASE name WHEN 'Favoritos gerais' THEN 0 ELSE 1 END, name"
        ).fetchall()
        return [dict(row) for row in rows]

    def add_favorite_category(self, name: str) -> int:
        """Cria uma categoria ou devolve a existente com o mesmo nome."""
        cleaned = name.strip()
        if not cleaned:
            raise ValueError("Informe o nome da categoria.")
        self.connection.execute(
            "INSERT OR IGNORE INTO favorite_categories(name,created_at) VALUES(?,?)",
            (cleaned, self._now()),
        )
        self.connection.commit()
        return int(self.connection.execute(
            "SELECT id FROM favorite_categories WHERE name=?", (cleaned,)
        ).fetchone()["id"])

    def rename_favorite_category(self, category_id: int, name: str):
        """Renomeia uma categoria própria, preservando seus favoritos."""
        cleaned = name.strip()
        if not cleaned:
            raise ValueError("Informe o novo nome da categoria.")
        current = self.connection.execute(
            "SELECT name FROM favorite_categories WHERE id=?", (category_id,)
        ).fetchone()
        if not current or current["name"] == "Favoritos gerais":
            raise ValueError("A categoria Favoritos gerais não pode ser renomeada.")
        self.connection.execute(
            "UPDATE favorite_categories SET name=? WHERE id=?", (cleaned, category_id)
        )
        self.connection.commit()

    def set_favorite_category(self, key: tuple, category_id: int):
        """Move um favorito existente para outra categoria."""
        self.connection.execute(
            """UPDATE bookmarks SET category_id=? WHERE translation_id=? AND
               book_code=? AND chapter=? AND verse=?""",
            (category_id, *key),
        )
        self.connection.commit()

    def favorites(self, query: str = "") -> list[dict]:
        """Lista favoritos com nome de categoria e filtro textual opcional."""
        value = f"%{query.strip()}%"
        rows = self.connection.execute(
            """SELECT b.*, c.name AS category_name FROM bookmarks b
               LEFT JOIN favorite_categories c ON c.id=b.category_id
               WHERE ?='' OR b.book_name LIKE ? OR b.book_code LIKE ? OR c.name LIKE ?
               ORDER BY b.created_at DESC""",
            (query.strip(), value, value, value),
        ).fetchall()
        return [dict(row) for row in rows]

    def enrich_favorite(self, key: tuple, book_name: str, category_id: int | None = None):
        """Completa metadados de um marcador criado pelo formato antigo."""
        if category_id is None:
            category_id = self.favorite_categories()[0]["id"]
        self.connection.execute(
            """UPDATE bookmarks SET book_name=?, category_id=? WHERE translation_id=?
               AND book_code=? AND chapter=? AND verse=?""",
            (book_name, category_id, *key),
        )
        self.connection.commit()

    def add_favorite(self, translation_id: str, book_code: str, book_name: str,
                     chapter: int, verse: str, kind: str = "verse",
                     end_chapter: int | None = None, end_verse: str | None = None,
                     category_id: int | None = None):
        """Salva versículo, capítulo ou passagem sem duplicar sua referência inicial."""
        if category_id is None:
            category_id = self.favorite_categories()[0]["id"]
        self.connection.execute(
            """INSERT OR REPLACE INTO bookmarks(
               translation_id,book_code,chapter,verse,created_at,book_name,
               end_chapter,end_verse,kind,category_id) VALUES(?,?,?,?,?,?,?,?,?,?)""",
            (translation_id, book_code, int(chapter), str(verse), self._now(), book_name,
             end_chapter, end_verse, kind, category_id),
        )
        self.connection.commit()

    def add_search(self, query: str, mode: str = "auto", book_code: str | None = None):
        """Registra uma pesquisa e limita o histórico às cem mais recentes."""
        self.connection.execute(
            "INSERT INTO search_history(query,search_mode,book_code,created_at) VALUES(?,?,?,?)",
            (query.strip(), mode, book_code, self._now()),
        )
        self.connection.execute(
            "DELETE FROM search_history WHERE id NOT IN (SELECT id FROM search_history ORDER BY id DESC LIMIT 100)"
        )
        self.connection.commit()

    def searches(self) -> list[dict]:
        """Devolve pesquisas recentes, incluindo modo e livro opcional."""
        return [dict(row) for row in self.connection.execute(
            "SELECT * FROM search_history ORDER BY id DESC LIMIT 100"
        ).fetchall()]

    def record_reading(self, translation_id: str, book_code: str, book_name: str,
                       chapter: int, verse: str | None):
        """Registra uma passagem, evitando duplicar movimentos dentro da mesma referência."""
        latest = self.connection.execute(
            "SELECT * FROM reading_history ORDER BY id DESC LIMIT 1"
        ).fetchone()
        key = (translation_id, book_code, int(chapter), str(verse or ""))
        if latest and key == (
            latest["translation_id"], latest["book_code"], latest["chapter"],
            str(latest["verse"] or ""),
        ):
            return
        self.connection.execute(
            """INSERT INTO reading_history(translation_id,book_code,book_name,chapter,verse,accessed_at)
               VALUES(?,?,?,?,?,?)""",
            (translation_id, book_code, book_name, int(chapter), verse, self._now()),
        )
        self.connection.execute(
            "DELETE FROM reading_history WHERE id NOT IN (SELECT id FROM reading_history ORDER BY id DESC LIMIT 500)"
        )
        self.connection.commit()

    def reading_history(self, query: str = "") -> list[dict]:
        """Lista o histórico de leitura com filtro por livro ou referência."""
        value = f"%{query.strip()}%"
        rows = self.connection.execute(
            """SELECT * FROM reading_history WHERE ?='' OR book_name LIKE ?
               ORDER BY accessed_at DESC, id DESC""", (query.strip(), value)
        ).fetchall()
        return [dict(row) for row in rows]

    def clear_reading_history(self):
        """Apaga somente o histórico, após confirmação feita pela interface."""
        self.connection.execute("DELETE FROM reading_history")
        self.connection.commit()

    def plan_states(self) -> dict[str, dict]:
        """Mapeia os planos iniciados pelo identificador estável."""
        result = {}
        for row in self.connection.execute("SELECT * FROM reading_plans").fetchall():
            data = dict(row)
            data["completed_days"] = [item["day_number"] for item in self.connection.execute(
                "SELECT day_number FROM reading_plan_days WHERE plan_id=? ORDER BY day_number",
                (row["plan_id"],),
            ).fetchall()]
            result[row["plan_id"]] = data
        return result

    def start_plan(self, plan_id: str, restart: bool = False):
        """Inicia, continua ou reinicia um plano sem afetar outros planos ativos."""
        if restart:
            self.connection.execute("DELETE FROM reading_plan_days WHERE plan_id=?", (plan_id,))
            self.connection.execute("DELETE FROM reading_plans WHERE plan_id=?", (plan_id,))
        self.connection.execute(
            """INSERT INTO reading_plans(plan_id,status,started_at,current_day)
               VALUES(?, 'Ativo', ?, 1)
               ON CONFLICT(plan_id) DO UPDATE SET status='Ativo'""",
            (plan_id, self._now()),
        )
        self.connection.commit()

    def set_plan_status(self, plan_id: str, status: str):
        """Pausa, continua ou abandona um plano existente."""
        self.connection.execute(
            "UPDATE reading_plans SET status=? WHERE plan_id=?", (status, plan_id)
        )
        self.connection.commit()

    def abandon_plan(self, plan_id: str):
        """Remove somente o progresso do plano escolhido."""
        self.connection.execute("DELETE FROM reading_plan_days WHERE plan_id=?", (plan_id,))
        self.connection.execute("DELETE FROM reading_plans WHERE plan_id=?", (plan_id,))
        self.connection.commit()

    def toggle_plan_day(self, plan_id: str, day_number: int, total_days: int) -> bool:
        """Marca ou desmarca um dia e recalcula o dia atual e a conclusão."""
        exists = self.connection.execute(
            "SELECT 1 FROM reading_plan_days WHERE plan_id=? AND day_number=?",
            (plan_id, day_number),
        ).fetchone()
        if exists:
            self.connection.execute(
                "DELETE FROM reading_plan_days WHERE plan_id=? AND day_number=?",
                (plan_id, day_number),
            )
            marked = False
        else:
            self.connection.execute(
                "INSERT INTO reading_plan_days(plan_id,day_number,completed_at) VALUES(?,?,?)",
                (plan_id, day_number, self._now()),
            )
            marked = True
        completed = {row["day_number"] for row in self.connection.execute(
            "SELECT day_number FROM reading_plan_days WHERE plan_id=?", (plan_id,)
        ).fetchall()}
        next_day = next((day for day in range(1, total_days + 1) if day not in completed), total_days)
        finished = len(completed) >= total_days
        self.connection.execute(
            """UPDATE reading_plans SET current_day=?, status=?, completed_at=? WHERE plan_id=?""",
            (next_day, "Concluído" if finished else "Ativo", self._now() if finished else None, plan_id),
        )
        self.connection.commit()
        return marked

    def add_prayer(self, title: str, request: str, notes: str = "", category: str = "") -> int:
        """Cria um pedido de oração com estado inicial Orando."""
        cursor = self.connection.execute(
            """INSERT INTO prayers(title,request,created_at,notes,category,status)
               VALUES(?,?,?,?,?,'Orando')""",
            (title.strip(), request.strip(), self._now(), notes.strip(), category.strip()),
        )
        self.connection.commit()
        return int(cursor.lastrowid)

    def prayers(self, status: str = "Todas", query: str = "") -> list[dict]:
        """Filtra pedidos por estado e pesquisa em todos os campos relevantes."""
        value = f"%{query.strip()}%"
        rows = self.connection.execute(
            """SELECT * FROM prayers WHERE (?='Todas' OR status=?) AND
               (?='' OR title LIKE ? OR request LIKE ? OR notes LIKE ? OR answer_text LIKE ?)
               ORDER BY created_at DESC, id DESC""",
            (status, status, query.strip(), value, value, value, value),
        ).fetchall()
        return [dict(row) for row in rows]

    def answer_prayer(self, prayer_id: int, answered_at: str, answer_text: str, notes: str = ""):
        """Marca uma oração como respondida e preserva observações anteriores."""
        self.connection.execute(
            """UPDATE prayers SET status='Respondida', answered_at=?, answer_text=?,
               notes=CASE WHEN ?='' THEN notes WHEN notes='' THEN ? ELSE notes || char(10) || ? END
               WHERE id=?""",
            (answered_at, answer_text.strip(), notes.strip(), notes.strip(), notes.strip(), prayer_id),
        )
        self.connection.commit()

    def set_prayer_status(self, prayer_id: int, status: str):
        """Altera entre Orando, Respondida e Arquivada."""
        self.connection.execute("UPDATE prayers SET status=? WHERE id=?", (status, prayer_id))
        self.connection.commit()

    def prayer_statistics(self) -> dict[str, int]:
        """Conta pedidos por estado sem criar pontuação ou competição."""
        result = {"total": 0, "Orando": 0, "Respondida": 0, "Arquivada": 0}
        for row in self.connection.execute(
            "SELECT status, COUNT(*) AS total FROM prayers GROUP BY status"
        ).fetchall():
            result[row["status"]] = row["total"]
            result["total"] += row["total"]
        return result

    def save_daily_devotional(self, devotional_date: str, reference: str, verse_text: str,
                               learned: str, practice: str, prayer: str, personal_note: str):
        """Salva ou atualiza o momento com Deus de uma data."""
        now = self._now()
        self.connection.execute(
            """INSERT INTO devotionals(devotional_date,verse_reference,verse_text,learned,
               practice,prayer,personal_note,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)
               ON CONFLICT(devotional_date) DO UPDATE SET verse_reference=excluded.verse_reference,
               verse_text=excluded.verse_text, learned=excluded.learned, practice=excluded.practice,
               prayer=excluded.prayer, personal_note=excluded.personal_note, updated_at=excluded.updated_at""",
            (devotional_date, reference, verse_text, learned, practice, prayer,
             personal_note, now, now),
        )
        self.connection.commit()

    def devotionals(self, query: str = "") -> list[dict]:
        """Pesquisa o histórico devocional por data ou conteúdo."""
        value = f"%{query.strip()}%"
        rows = self.connection.execute(
            """SELECT * FROM devotionals WHERE ?='' OR devotional_date LIKE ? OR
               learned LIKE ? OR practice LIKE ? OR prayer LIKE ? OR personal_note LIKE ?
               ORDER BY devotional_date DESC""",
            (query.strip(), value, value, value, value, value),
        ).fetchall()
        return [dict(row) for row in rows]

    def toggle_memorization(self, translation_id: str, book_code: str, book_name: str,
                            chapter: int, verse: str, verse_text: str) -> bool:
        """Adiciona ou remove um versículo da lista de memorização."""
        key = (translation_id, book_code, int(chapter), str(verse))
        exists = self.connection.execute(
            """SELECT 1 FROM memorized_verses WHERE translation_id=? AND book_code=?
               AND chapter=? AND verse=?""", key
        ).fetchone()
        if exists:
            self.connection.execute(
                "DELETE FROM memorized_verses WHERE translation_id=? AND book_code=? AND chapter=? AND verse=?",
                key,
            )
            result = False
        else:
            self.connection.execute(
                """INSERT INTO memorized_verses VALUES(?,?,?,?,?,?,?)""",
                (*key[:2], book_name, key[2], key[3], verse_text, self._now()),
            )
            result = True
        self.connection.commit()
        return result

    def memorized_verses(self) -> list[dict]:
        """Lista versículos selecionados para prática."""
        return [dict(row) for row in self.connection.execute(
            "SELECT * FROM memorized_verses ORDER BY created_at DESC"
        ).fetchall()]

    def statistics(self) -> dict[str, int]:
        """Calcula contagens pessoais simples usadas na tela de estatísticas."""
        counts = {}
        for name, table in (
            ("favoritos", "bookmarks"), ("anotacoes", "notes"),
            ("devocionais", "devotionals"),
        ):
            counts[name] = self.connection.execute(f"SELECT COUNT(*) AS n FROM {table}").fetchone()["n"]
        counts["capitulos_lidos"] = self.connection.execute(
            "SELECT COUNT(*) AS n FROM (SELECT DISTINCT translation_id,book_code,chapter FROM reading_history)"
        ).fetchone()["n"]
        prayers = self.prayer_statistics()
        plans = self.connection.execute(
            "SELECT status, COUNT(*) AS n FROM reading_plans GROUP BY status"
        ).fetchall()
        counts.update({"pedidos_oracao": prayers["total"], "oracoes_respondidas": prayers["Respondida"]})
        counts["planos_ativos"] = sum(row["n"] for row in plans if row["status"] == "Ativo")
        counts["planos_concluidos"] = sum(row["n"] for row in plans if row["status"] == "Concluído")
        counts["dias_leitura"] = self.connection.execute(
            "SELECT COUNT(DISTINCT substr(accessed_at,1,10)) AS n FROM reading_history"
        ).fetchone()["n"]
        counts["livros_concluidos"] = 0
        counts.update(self.quiz_statistics())
        return counts

    def record_quiz_attempt(self, question_id: str, difficulty: str,
                            category: str, correct: bool) -> bool:
        """Registra a primeira resposta e informa se ela era realmente inédita."""
        cursor = self.connection.execute(
            """INSERT OR IGNORE INTO quiz_attempts(question_id,difficulty,category,is_correct,answered_at)
               VALUES(?,?,?,?,?)""",
            (question_id, difficulty, category, int(correct), self._now()),
        )
        self.connection.commit()
        return cursor.rowcount == 1

    def quiz_question_answered(self, question_id: str) -> bool:
        """Informa se a pergunta já possui um resultado definitivo."""
        return self.connection.execute(
            "SELECT 1 FROM quiz_attempts WHERE question_id=?", (question_id,)
        ).fetchone() is not None

    def quiz_statistics(self) -> dict[str, int]:
        """Conta tentativas, acertos e erros acumulados no quiz."""
        row = self.connection.execute(
            """SELECT COUNT(*) AS total, COALESCE(SUM(is_correct),0) AS correct
               FROM quiz_attempts"""
        ).fetchone()
        correct = int(row["correct"] or 0)
        total = int(row["total"] or 0)
        return {"quiz_total": total, "quiz_acertos": correct, "quiz_erros": total - correct}

    def quiz_breakdown(self) -> list[dict]:
        """Agrupa o desempenho por dificuldade para a seção Status do quiz."""
        rows = self.connection.execute(
            """SELECT difficulty,COUNT(*) AS total,COALESCE(SUM(is_correct),0) AS correct
               FROM quiz_attempts GROUP BY difficulty
               ORDER BY CASE difficulty WHEN 'fácil' THEN 1 WHEN 'médio' THEN 2 ELSE 3 END"""
        ).fetchall()
        return [dict(row) for row in rows]

    def export_payload(self) -> dict:
        """Produz dados pessoais serializáveis em um formato versionado."""
        tables = (
            "favorite_categories", "bookmarks", "notes", "search_history",
            "reading_history", "reading_plans", "reading_plan_days", "prayers",
            "devotionals", "memorized_verses", "quiz_attempts",
        )
        return {
            "format": "biblia-acessivel-backup",
            "version": 2,
            "created_at": self._now(),
            "tables": {
                table: [dict(row) for row in self.connection.execute(f"SELECT * FROM {table}").fetchall()]
                for table in tables
            },
        }

    def import_payload(self, payload: dict):
        """Valida e importa o backup numa transação, revertendo tudo se houver falha."""
        if payload.get("format") != "biblia-acessivel-backup" or payload.get("version") not in (1, 2):
            raise ValueError("Este arquivo não é um backup compatível da Bíblia Acessível.")
        legacy = {
            "favorite_categories", "bookmarks", "notes", "search_history",
            "reading_history", "reading_plans", "reading_plan_days", "prayers",
            "devotionals", "memorized_verses",
        }
        current = legacy | {"quiz_attempts"}
        allowed = legacy if payload.get("version") == 1 else current
        tables = payload.get("tables")
        if not isinstance(tables, dict) or set(tables) != allowed:
            raise ValueError("O backup possui uma estrutura inválida.")
        with self.connection:
            self.connection.execute("PRAGMA defer_foreign_keys=ON")
            for table in current:
                self.connection.execute(f"DELETE FROM {table}")
            for table, rows in tables.items():
                if not isinstance(rows, list):
                    raise ValueError(f"Os dados de {table} são inválidos.")
                valid_columns = {
                    row["name"] for row in self.connection.execute(f"PRAGMA table_info({table})")
                }
                for row in rows:
                    if not isinstance(row, dict) or not row or not set(row).issubset(valid_columns):
                        raise ValueError(f"Um registro de {table} é inválido.")
                    columns = list(row)
                    placeholders = ",".join("?" for _ in columns)
                    operation = "INSERT OR IGNORE" if table == "quiz_attempts" else "INSERT"
                    self.connection.execute(
                        f"{operation} INTO {table}({','.join(columns)}) VALUES({placeholders})",
                        [row[column] for column in columns],
                    )
            self.connection.execute(
                "INSERT OR IGNORE INTO favorite_categories(name,created_at) VALUES(?,?)",
                ("Favoritos gerais", self._now()),
            )

    def close(self):
        """Fecha a conexão depois de garantir que alterações foram confirmadas."""
        self.connection.close()

    def backup_to(self, destination: Path):
        """Cria backup SQLite consistente mesmo enquanto a base está aberta."""
        destination.parent.mkdir(parents=True, exist_ok=True)
        backup = sqlite3.connect(destination)
        try:
            self.connection.commit()
            self.connection.backup(backup)
        finally:
            backup.close()
