"""Páginas acessíveis dos recursos pessoais e de estudo da versão Windows."""

from __future__ import annotations

import json
import re
import sqlite3
from datetime import date, timedelta
from pathlib import Path

from PySide6.QtCore import QSettings, Qt, QUrl, QUrlQuery
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QApplication,
    QFileDialog,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidgetItem,
    QMessageBox,
    QPlainTextEdit,
    QVBoxLayout,
)

from .dialogs import AccessibleButton, AccessibleResultDialog, AccessibleTextDialog, ActivatableList, ApplicationsDialog
from .hymnal import load_hymnal, search_hymns
from .plans import build_plan_catalog, progress_summary
from .quiz_catalog import categories as quiz_categories, load_quiz_catalog
from .references import parse_reference
from .study_content import CHARACTERS, book_information
from .topics import TOPICS


class ExtendedFeatures:
    """Mantém recursos extensos fora da janela principal sem duplicar seus serviços."""

    DAILY_REFERENCES = (
        "Salmos 23:1", "João 3:16", "Filipenses 4:6", "Isaías 41:10",
        "Romanos 8:28", "Provérbios 3:5", "Mateus 11:28", "1 Pedro 5:7",
        "Salmos 46:1", "Romanos 15:13", "Josué 1:9", "Lamentações 3:22",
    )

    def __init__(self, host):
        """Recebe a janela existente e registra páginas e ações adicionais."""
        self.host = host
        self.db = host.db
        self.data = host.user_data
        self.plan_catalog = build_plan_catalog(self.db, host.current_translation_id())
        self.hymns = load_hymnal(host.install_dir / "data" / "harpa_crista.json")
        self.quiz_questions = load_quiz_catalog(host.install_dir / "data" / "quiz_questions.tsv")
        self._build_pages()
        self._append_menu_options()

    def _register(self, page_id: str, title: str, content, focus):
        """Usa o mesmo contêiner e a mesma política de Escape das páginas antigas."""
        self.host._add_secondary_page(page_id, title, content, focus)

    def _list_section(self, title: str, description: str):
        """Cria uma página de lista com instrução curta e nome acessível explícito."""
        section = QGroupBox(title)
        layout = QVBoxLayout(section)
        layout.addWidget(QLabel(description))
        listing = ActivatableList()
        listing.setAccessibleName(title)
        listing.setAccessibleDescription(description)
        layout.addWidget(listing, 1)
        return section, layout, listing

    def _build_pages(self):
        """Constrói todas as áreas sem guias e com uma rota de foco linear."""
        self._build_home()
        self._build_plans()
        self._build_prayers()
        self._build_daily_devotional()
        self._build_favorites()
        self._build_history()
        self._build_topics()
        self._build_books_info()
        self._build_characters()
        self._build_memorization()
        self._build_hymnal()
        self._build_quiz()
        self._build_quiz_status()
        self._build_statistics()
        self._build_worship_mode()
        self._build_backup()

    def _append_menu_options(self):
        """Acrescenta recursos a Mais opções sem alterar as cinco seções principais."""
        entries = (
            ("Início e resumo do dia", "dashboard"),
            ("Planos de leitura", "plans"),
            ("Diário de oração", "prayers"),
            ("Meu momento com Deus", "daily_devotional"),
            ("Favoritos", "favorites"),
            ("Histórico de leitura", "history"),
            ("Temas bíblicos", "topics"),
            ("Informações sobre livros", "books_info"),
            ("Personagens", "characters"),
            ("Memorização de versículos", "memorization"),
            ("Harpa Cristã", "hymnal"),
            ("Quiz bíblico", "quiz"),
            ("Status do quiz", "quiz_status"),
            ("Estatísticas pessoais", "statistics"),
            ("Modo culto", "worship"),
            ("Backup e restauração", "backup"),
        )
        for label, page_id in entries:
            item = QListWidgetItem(label)
            item.setData(Qt.UserRole, page_id)
            self.host.more_options.addItem(item)

    # Início -----------------------------------------------------------
    def _build_home(self):
        """Cria o painel inicial resumido e ativável pelo teclado."""
        section, _layout, self.dashboard_list = self._list_section(
            "Início", "Use cima e baixo. Espaço ou Enter abre o item escolhido."
        )
        self.dashboard_list.selectionRequested.connect(self._open_dashboard_item)
        self._register("dashboard", "Início", section, self.dashboard_list)

    def refresh_dashboard(self):
        """Resume verso diário, posição, planos e orações sem sobrecarregar a tela."""
        self.dashboard_list.clear()
        reference, _text = self.daily_verse()
        active = [state for state in self.data.plan_states().values() if state["status"] == "Ativo"]
        prayers = self.data.prayer_statistics()
        items = (
            (f"Versículo do dia: {reference}", "daily_verse"),
            (f"Continuar leitura: {self.host.active_book_name} {self.host.active_chapter}:"
             f"{self.host._current_verse_key()[3] if self.host._current_verse_key() else '1'}", "continue"),
            (f"Planos de leitura: {len(active)} ativos", "plans"),
            ("Meu momento com Deus", "daily_devotional"),
            (f"Diário de oração: {prayers['Orando']} em oração; {prayers['Respondida']} respondidas", "prayers"),
            ("Busca bíblica", "search"),
            ("Ir para passagem", "reference"),
        )
        for label, action in items:
            item = QListWidgetItem(label)
            item.setData(Qt.UserRole, action)
            self.dashboard_list.addItem(item)
        self.dashboard_list.setCurrentRow(0)

    def _open_dashboard_item(self, item):
        """Direciona o item resumido para sua página ou para a leitura."""
        action = item.data(Qt.UserRole)
        if action == "daily_verse":
            self.show_daily_verse()
        elif action == "continue":
            self.host.page_stack.setCurrentIndex(self.host.main_page_index)
            self.host.verse_list.setFocus()
        else:
            self.host.open_more_option(action)

    def daily_verse(self):
        """Escolhe uma referência determinística; ela não muda durante o mesmo dia."""
        index = date.today().toordinal() % len(self.DAILY_REFERENCES)
        reference = self.DAILY_REFERENCES[index]
        resolved = self.resolve_reference(reference)
        if not resolved:
            return reference, "Texto indisponível nesta tradução."
        _book, _parsed, rows = resolved
        return reference, " ".join(row["text"] for row in rows)

    def show_daily_verse(self):
        """Oferece leitura, cópia, favorito e abertura do capítulo completo."""
        reference, text = self.daily_verse()
        action = ApplicationsDialog.choose(self.host, f"Versículo do dia — {reference}", (
            ("Ler versículo", "read"), ("Copiar versículo", "copy"), ("Compartilhar", "share"),
            ("Adicionar aos favoritos", "favorite"), ("Abrir capítulo completo", "open"),
        ))
        if action == "read":
            AccessibleTextDialog(self.host, reference, f"Versículo do dia {reference}", text).exec()
        elif action == "copy":
            QApplication.clipboard().setText(f"{reference} — {text}")
        elif action == "share":
            url = QUrl("mailto:")
            query = QUrlQuery()
            query.addQueryItem("subject", f"Versículo do dia — {reference}")
            query.addQueryItem("body", f"{reference} — {text}")
            url.setQuery(query)
            if not QDesktopServices.openUrl(url):
                QApplication.clipboard().setText(f"{reference} — {text}")
                self.host._announce_for(self.dashboard_list, "Não foi possível abrir o compartilhamento. O texto foi copiado.")
        elif action in {"favorite", "open"}:
            self.open_reference(reference)
            if action == "favorite":
                self.host.toggle_bookmark()

    # Referências ------------------------------------------------------
    def resolve_reference(self, text: str):
        """Resolve livro e intervalo usando a tradução atualmente selecionada."""
        try:
            parsed = parse_reference(text)
        except ValueError:
            return None
        book = self.db.resolve_book(self.host.current_translation_id(), parsed.book_query)
        if not book or parsed.chapter > self.db.chapter_count(self.host.current_translation_id(), book["book_code"]):
            return None
        rows = self.db.passage(
            self.host.current_translation_id(), book["book_code"], parsed.chapter,
            parsed.verse_start, parsed.verse_end,
        )
        return book, parsed, rows

    def open_reference(self, text: str) -> bool:
        """Abre a primeira posição de uma referência e devolve sucesso."""
        resolved = self.resolve_reference(text)
        if not resolved:
            self.host._warn("Passagem não encontrada", f"Não foi possível abrir {text}.")
            return False
        book, parsed, _rows = resolved
        self.host._show_location(book["book_code"], parsed.chapter, parsed.verse_start)
        self.host.page_stack.setCurrentIndex(self.host.main_page_index)
        self.host.verse_list.setFocus()
        return True

    def _select_translation(self, translation_id: str):
        """Sincroniza a tradução principal antes de abrir dado pessoal antigo."""
        for index in range(self.host.translation_list.count()):
            item = self.host.translation_list.item(index)
            if item.data(Qt.UserRole) == translation_id:
                self.host.translation_list.setCurrentItem(item)
                self.host.select_current_translation()
                return

    # Planos -----------------------------------------------------------
    def _build_plans(self):
        """Cria a lista de planos e seu botão explícito de atualização."""
        section, layout, self.plans_list = self._list_section(
            "Planos de leitura", "Cada item informa estado e progresso. Ative para abrir as ações do plano."
        )
        self.plans_list.selectionRequested.connect(self.open_plan)
        update = AccessibleButton("&Atualizar planos")
        update.clicked.connect(self.refresh_plans)
        layout.addWidget(update)
        self._register("plans", "Planos de leitura", section, self.plans_list)

    def refresh_plans(self):
        """Apresenta todos os planos e seus progressos salvos localmente."""
        self.plan_catalog = build_plan_catalog(self.db, self.host.current_translation_id())
        states = self.data.plan_states()
        self.plans_list.clear()
        for plan in self.plan_catalog.values():
            state = states.get(plan.plan_id)
            summary = progress_summary(plan, state)
            status = state["status"] if state else "Não iniciado"
            label = (f"{plan.name}. {status}. {summary['completed']} de {plan.days} dias concluídos. "
                     f"{summary['percent']} por cento. Dia atual: {summary['current_day']}.")
            item = QListWidgetItem(label)
            item.setData(Qt.UserRole, plan.plan_id)
            self.plans_list.addItem(item)
        self.plans_list.setCurrentRow(0)

    def open_plan(self, item):
        """Permite iniciar, pausar, continuar, reiniciar, abandonar e abrir leituras."""
        plan = self.plan_catalog[item.data(Qt.UserRole)]
        state = self.data.plan_states().get(plan.plan_id)
        summary = progress_summary(plan, state)
        actions = [("Ouvir descrição e progresso", "details")]
        if not state:
            actions.append(("Iniciar plano", "start"))
        else:
            actions.extend((("Abrir leitura do dia atual", "read"),
                            ("Marcar ou desmarcar o dia atual", "toggle")))
            if state["status"] == "Pausado":
                actions.append(("Continuar plano", "continue"))
            elif state["status"] == "Ativo":
                actions.append(("Pausar plano", "pause"))
            actions.extend((("Reiniciar plano", "restart"), ("Abandonar plano", "abandon")))
        action = ApplicationsDialog.choose(self.host, f"Plano {plan.name}", tuple(actions))
        announcement = ""
        if action == "details":
            expected = summary["expected_end"].strftime("%d/%m/%Y") if summary["expected_end"] else "após iniciar"
            started = summary["start_date"].strftime("%d/%m/%Y") if summary["start_date"] else "ainda não iniciado"
            AccessibleTextDialog(self.host, plan.name, plan.name,
                f"{plan.description}\n\nDuração: {plan.days} dias.\n\n"
                f"Concluídos: {summary['completed']}. Progresso: {summary['percent']} por cento.\n\n"
                f"Data de início: {started}.\n\n"
                f"Data prevista de conclusão: {expected}.").exec()
        elif action in {"start", "continue"}:
            self.data.start_plan(plan.plan_id)
            announcement = "Plano iniciado ou continuado."
        elif action == "pause":
            self.data.set_plan_status(plan.plan_id, "Pausado")
            announcement = "Plano pausado."
        elif action == "restart" and self._confirm("Reiniciar plano", "Apagar o progresso deste plano e começar novamente?"):
            self.data.start_plan(plan.plan_id, restart=True)
            announcement = "Plano reiniciado."
        elif action == "abandon" and self._confirm("Abandonar plano", "Remover este plano e todo o seu progresso?"):
            self.data.abandon_plan(plan.plan_id)
            announcement = "Plano abandonado."
        elif action == "toggle":
            marked = self.data.toggle_plan_day(plan.plan_id, summary["current_day"], plan.days)
            announcement = "Leitura diária concluída." if marked else "Conclusão diária desmarcada."
        elif action == "read":
            readings = plan.readings[summary["current_day"] - 1]
            chapters = self._expand_plan_readings(readings)
            chosen = ApplicationsDialog.choose(self.host, f"Leituras do dia {summary['current_day']}",
                                                tuple((reading, reading) for reading in chapters))
            if chosen:
                self._open_plan_reading(chosen)
        self.refresh_plans()
        if announcement:
            self.host._announce_for(self.plans_list, announcement)

    def _open_plan_reading(self, reading: str):
        """Abre o primeiro capítulo de uma leitura que pode conter intervalo de capítulos."""
        match = re.match(r"^(.+?)\s+(\d+)(?:[-–]\d+)?$", reading)
        if match:
            self.open_reference(f"{match.group(1)} {match.group(2)}")

    @staticmethod
    def _expand_plan_readings(readings):
        """Expande ``Êxodo 13-15`` em três capítulos que podem ser abertos separadamente."""
        expanded = []
        for reading in readings:
            match = re.match(r"^(.+?)\s+(\d+)(?:[-–](\d+))?$", reading)
            if not match:
                expanded.append(reading)
                continue
            book, start, end = match.groups()
            expanded.extend(f"{book} {chapter}" for chapter in range(int(start), int(end or start) + 1))
        return tuple(expanded)

    # Oração -----------------------------------------------------------
    def _build_prayers(self):
        """Cria filtros, pesquisa, estatísticas e lista do diário de oração."""
        section = QGroupBox("Diário de oração")
        layout = QVBoxLayout(section)
        self.prayer_filter = QComboBox()
        self.prayer_filter.setAccessibleName("Filtrar pedidos de oração")
        self.prayer_filter.addItems(("Todas", "Orando", "Respondida", "Arquivada"))
        self.prayer_filter.currentTextChanged.connect(self.refresh_prayers)
        layout.addWidget(self.prayer_filter)
        self.prayer_search = QLineEdit()
        self.prayer_search.setAccessibleName("Pesquisar no diário de oração")
        self.prayer_search.setPlaceholderText("Pesquisar por título ou texto")
        self.prayer_search.returnPressed.connect(self.refresh_prayers)
        layout.addWidget(self.prayer_search)
        self.prayer_stats = QLabel()
        self.prayer_stats.setAccessibleName("Estatísticas do diário de oração")
        layout.addWidget(self.prayer_stats)
        self.prayers_list = ActivatableList()
        self.prayers_list.setAccessibleName("Pedidos de oração")
        self.prayers_list.selectionRequested.connect(self.open_prayer)
        layout.addWidget(self.prayers_list, 1)
        add = AccessibleButton("&Novo pedido de oração")
        add.clicked.connect(self.create_prayer)
        layout.addWidget(add)
        self._register("prayers", "Diário de oração", section, self.prayers_list)

    def refresh_prayers(self):
        """Atualiza filtro, pesquisa e estatísticas sem pontuação."""
        stats = self.data.prayer_statistics()
        self.prayer_stats.setText(
            f"Pedidos registrados: {stats['total']}. Em oração: {stats['Orando']}. "
            f"Orações respondidas: {stats['Respondida']}. Arquivadas: {stats['Arquivada']}."
        )
        rows = self.data.prayers(self.prayer_filter.currentText(), self.prayer_search.text())
        self.prayers_list.clear()
        for prayer in rows:
            created = date.fromisoformat(prayer["created_at"][:10]).strftime("%d/%m/%Y")
            item = QListWidgetItem(f"{prayer['title']}. Status: {prayer['status']}. Criada em {created}.")
            item.setData(Qt.UserRole, prayer)
            self.prayers_list.addItem(item)
        if self.prayers_list.count():
            self.prayers_list.setCurrentRow(0)

    def create_prayer(self):
        """Coleta pedido, categoria e observações em formulário rotulado."""
        dialog = QDialog(self.host)
        dialog.setWindowTitle("Novo pedido de oração")
        dialog.setAccessibleName("Novo pedido de oração, janela")
        form = QFormLayout(dialog)
        title = QLineEdit(); title.setAccessibleName("Título do pedido")
        request = QPlainTextEdit(); request.setAccessibleName("Pedido de oração"); request.setTabChangesFocus(True)
        category = QLineEdit(); category.setAccessibleName("Categoria opcional")
        notes = QPlainTextEdit(); notes.setAccessibleName("Observações"); notes.setTabChangesFocus(True)
        form.addRow("&Título:", title); form.addRow("&Pedido:", request)
        form.addRow("&Categoria opcional:", category); form.addRow("&Observações:", notes)
        buttons = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Save).setText("&Salvar pedido")
        buttons.accepted.connect(dialog.accept); buttons.rejected.connect(dialog.reject)
        form.addRow(buttons); title.setFocus()
        if dialog.exec() != QDialog.Accepted:
            return
        if not title.text().strip() or not request.toPlainText().strip():
            self.host._warn("Pedido incompleto", "Informe o título e o pedido de oração.")
            return
        self.data.add_prayer(title.text(), request.toPlainText(), notes.toPlainText(), category.text())
        self.refresh_prayers()
        self.host._announce_for(self.prayers_list, "Pedido de oração salvo.")

    def open_prayer(self, item):
        """Lê o pedido e oferece resposta, arquivamento ou retomada."""
        prayer = item.data(Qt.UserRole)
        action = ApplicationsDialog.choose(self.host, f"Pedido {prayer['title']}", (
            ("Ler pedido completo", "read"), ("Marcar como oração respondida", "answer"),
            ("Arquivar", "archive"), ("Voltar ao estado Orando", "praying"),
        ))
        if action == "read":
            text = (f"Título: {prayer['title']}\n\nPedido: {prayer['request']}\n\n"
                    f"Status: {prayer['status']}\n\nCategoria: {prayer['category'] or 'sem categoria'}\n\n"
                    f"Observações: {prayer['notes'] or 'nenhuma'}")
            if prayer["answered_at"]:
                text += f"\n\nRespondida em: {prayer['answered_at']}\n\nComo Deus respondeu: {prayer['answer_text']}"
            AccessibleTextDialog(self.host, prayer["title"], prayer["title"], text).exec()
        elif action == "answer":
            self._answer_prayer(prayer)
        elif action == "archive":
            self.data.set_prayer_status(prayer["id"], "Arquivada")
        elif action == "praying":
            self.data.set_prayer_status(prayer["id"], "Orando")
        self.refresh_prayers()

    def _answer_prayer(self, prayer):
        """Solicita data, descrição e observações da resposta."""
        dialog = QDialog(self.host); dialog.setWindowTitle("Marcar oração como respondida")
        dialog.setAccessibleName("Marcar oração como respondida, janela")
        form = QFormLayout(dialog)
        answered = QLineEdit(date.today().isoformat()); answered.setAccessibleName("Data da resposta")
        answer = QPlainTextEdit(); answer.setAccessibleName("Como Deus respondeu"); answer.setTabChangesFocus(True)
        notes = QPlainTextEdit(); notes.setAccessibleName("Observações da resposta"); notes.setTabChangesFocus(True)
        form.addRow("&Data, ano-mês-dia:", answered); form.addRow("&Como Deus respondeu:", answer)
        form.addRow("&Observações:", notes)
        buttons = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        buttons.accepted.connect(dialog.accept); buttons.rejected.connect(dialog.reject); form.addRow(buttons)
        if dialog.exec() == QDialog.Accepted and answer.toPlainText().strip():
            try:
                date.fromisoformat(answered.text().strip())
            except ValueError:
                self.host._warn("Data inválida", "Use o formato ano-mês-dia, por exemplo 2026-10-15.")
                return
            self.data.answer_prayer(prayer["id"], answered.text().strip(), answer.toPlainText(), notes.toPlainText())

    # Meu momento com Deus --------------------------------------------
    def _build_daily_devotional(self):
        """Monta a sequência diária de leitura, reflexão, prática e oração."""
        section = QGroupBox("Meu momento com Deus")
        layout = QFormLayout(section)
        self.moment_plan = QLabel("Leitura do plano: nenhum plano ativo.")
        self.moment_plan.setAccessibleName("Leitura do plano bíblico para hoje")
        self.moment_plan.setWordWrap(True)
        layout.addRow(self.moment_plan)
        open_plan = AccessibleButton("Abrir &leitura do plano")
        open_plan.clicked.connect(lambda: self.host.open_more_option("plans"))
        layout.addRow(open_plan)
        self.moment_verse = QLineEdit(); self.moment_verse.setAccessibleName("Versículo do dia ou outra referência")
        self.moment_learned = QPlainTextEdit(); self.moment_learned.setAccessibleName("O que Deus falou comigo hoje"); self.moment_learned.setTabChangesFocus(True)
        self.moment_practice = QPlainTextEdit(); self.moment_practice.setAccessibleName("O que quero colocar em prática"); self.moment_practice.setTabChangesFocus(True)
        self.moment_prayer = QPlainTextEdit(); self.moment_prayer.setAccessibleName("Minha oração de hoje"); self.moment_prayer.setTabChangesFocus(True)
        self.moment_note = QPlainTextEdit(); self.moment_note.setAccessibleName("Anotação pessoal"); self.moment_note.setTabChangesFocus(True)
        layout.addRow("&Versículo:", self.moment_verse)
        layout.addRow("&O que Deus falou comigo hoje?", self.moment_learned)
        layout.addRow("O que quero colocar em &prática?", self.moment_practice)
        layout.addRow("Minha &oração de hoje:", self.moment_prayer)
        layout.addRow("&Anotação pessoal:", self.moment_note)
        save = AccessibleButton("&Concluir e salvar devocional"); save.clicked.connect(self.save_moment); layout.addRow(save)
        history = AccessibleButton("Abrir &histórico de devocionais"); history.clicked.connect(self.show_devotional_history); layout.addRow(history)
        prayers = AccessibleButton("Abrir &Diário de oração"); prayers.clicked.connect(lambda: self.host.open_more_option("prayers")); layout.addRow(prayers)
        self._register("daily_devotional", "Meu momento com Deus", section, self.moment_verse)

    def prepare_moment(self):
        """Carrega o registro de hoje ou sugere o versículo diário."""
        today = date.today().isoformat()
        existing = next((row for row in self.data.devotionals() if row["devotional_date"] == today), None)
        reference, _text = self.daily_verse()
        active = [state for state in self.data.plan_states().values() if state["status"] == "Ativo"]
        if active and active[0]["plan_id"] in self.plan_catalog:
            state = active[0]
            plan = self.plan_catalog[state["plan_id"]]
            day = max(1, min(plan.days, state["current_day"]))
            self.moment_plan.setText(
                f"Leitura do plano: {plan.name}. Dia {day} de {plan.days}. "
                f"Leitura de hoje: {', '.join(plan.readings[day - 1])}."
            )
        else:
            self.moment_plan.setText("Leitura do plano: nenhum plano ativo.")
        self.moment_verse.setText(existing["verse_reference"] if existing else reference)
        for widget, key in ((self.moment_learned, "learned"), (self.moment_practice, "practice"),
                            (self.moment_prayer, "prayer"), (self.moment_note, "personal_note")):
            widget.setPlainText(existing[key] if existing else "")

    def save_moment(self):
        """Grava automaticamente a data e o texto bíblico resolvido."""
        reference = self.moment_verse.text().strip()
        resolved = self.resolve_reference(reference)
        verse_text = " ".join(row["text"] for row in resolved[2]) if resolved else ""
        self.data.save_daily_devotional(date.today().isoformat(), reference, verse_text,
            self.moment_learned.toPlainText(), self.moment_practice.toPlainText(),
            self.moment_prayer.toPlainText(), self.moment_note.toPlainText())
        self.host._announce_for(self.moment_verse, f"Devocional concluído em {date.today().strftime('%d/%m/%Y')}.")

    def show_devotional_history(self):
        """Pesquisa e abre devocionais anteriores em uma lista acessível."""
        query, accepted = self._text_prompt("Pesquisar devocionais", "Data ou texto, deixe vazio para todos")
        if not accepted:
            return
        rows = self.data.devotionals(query)
        options = tuple((f"{row['devotional_date']}. {row['verse_reference']}", row["id"]) for row in rows)
        chosen = ApplicationsDialog.choose(self.host, "Histórico de devocionais", options) if options else None
        row = next((row for row in rows if row["id"] == chosen), None)
        if row:
            AccessibleTextDialog(self.host, row["devotional_date"], "Devocional salvo",
                f"Referência: {row['verse_reference']}\n\nO que aprendi: {row['learned']}\n\n"
                f"Prática: {row['practice']}\n\nOração: {row['prayer']}\n\nAnotação: {row['personal_note']}").exec()

    # Favoritos e histórico -------------------------------------------
    def _build_favorites(self):
        """Cria pesquisa, lista, passagens e categorias de favoritos."""
        section = QGroupBox("Favoritos")
        layout = QVBoxLayout(section)
        self.favorite_search = QLineEdit(); self.favorite_search.setAccessibleName("Pesquisar favoritos")
        self.favorite_search.returnPressed.connect(self.refresh_favorites); layout.addWidget(self.favorite_search)
        self.favorites_list = ActivatableList(); self.favorites_list.setAccessibleName("Versículos, capítulos e passagens favoritas")
        self.favorites_list.selectionRequested.connect(self.open_favorite); layout.addWidget(self.favorites_list, 1)
        add_current = AccessibleButton("Adicionar &capítulo atual")
        add_current.clicked.connect(self.favorite_current_chapter); layout.addWidget(add_current)
        add_passage = AccessibleButton("Adicionar &passagem digitada")
        add_passage.clicked.connect(self.favorite_typed_passage); layout.addWidget(add_passage)
        category = AccessibleButton("Criar &categoria"); category.clicked.connect(self.create_category); layout.addWidget(category)
        rename = AccessibleButton("&Renomear categoria"); rename.clicked.connect(self.rename_category); layout.addWidget(rename)
        self._register("favorites", "Favoritos", section, self.favorites_list)

    def refresh_favorites(self):
        """Carrega favoritos com categoria e referência clara."""
        self.favorites_list.clear()
        for favorite in self.data.favorites(self.favorite_search.text()):
            book_name = favorite["book_name"] or favorite["book_code"]
            reference = f"{book_name} {favorite['chapter']}"
            if favorite["verse"] != "*":
                reference += f":{favorite['verse']}"
                if favorite["end_verse"]:
                    reference += f"-{favorite['end_verse']}"
            item = QListWidgetItem(
                f"{reference}. Categoria: {favorite['category_name'] or 'Favoritos gerais'}."
            )
            item.setData(Qt.UserRole, favorite); self.favorites_list.addItem(item)
        if self.favorites_list.count(): self.favorites_list.setCurrentRow(0)

    def create_category(self):
        """Solicita e cria uma categoria própria de favoritos."""
        name, accepted = self._text_prompt("Nova categoria", "Nome da categoria")
        if accepted and name.strip():
            self.data.add_favorite_category(name)
            self.host._announce_for(self.favorites_list, "Categoria criada.")

    def rename_category(self):
        """Escolhe uma categoria própria e solicita seu novo nome."""
        categories = [row for row in self.data.favorite_categories() if row["name"] != "Favoritos gerais"]
        if not categories:
            self.host._warn("Nenhuma categoria própria", "Crie uma categoria antes de renomear.")
            return
        chosen = ApplicationsDialog.choose(
            self.host, "Renomear categoria", tuple((row["name"], row["id"]) for row in categories)
        )
        if not chosen:
            return
        name, accepted = self._text_prompt("Novo nome da categoria", "Novo nome")
        if accepted:
            try:
                self.data.rename_favorite_category(chosen, name)
            except (ValueError, sqlite3.IntegrityError) as error:
                self.host._warn("Não foi possível renomear", str(error))
            self.refresh_favorites()

    def favorite_current_chapter(self):
        """Salva o capítulo ativo como favorito geral."""
        self.data.add_favorite(self.host.current_translation_id(), self.host.active_book_code,
                               self.host.active_book_name, self.host.active_chapter, "*", "chapter")
        self.refresh_favorites()
        self.host._announce_for(self.favorites_list, "Capítulo adicionado aos favoritos.")

    def favorite_typed_passage(self):
        """Resolve e salva um capítulo, versículo ou intervalo informado."""
        text, accepted = self._text_prompt("Adicionar passagem favorita", "Referência bíblica")
        if not accepted:
            return
        resolved = self.resolve_reference(text)
        if not resolved:
            self.host._warn("Passagem não encontrada", "Confira a referência digitada.")
            return
        book, parsed, _rows = resolved
        kind = "chapter" if parsed.verse_start is None else ("passage" if parsed.verse_end else "verse")
        self.data.add_favorite(self.host.current_translation_id(), book["book_code"], book["book_name"],
                               parsed.chapter, parsed.verse_start or "*", kind,
                               parsed.chapter, parsed.verse_end)
        self.refresh_favorites()

    def open_favorite(self, item):
        """Abre, recategoriza ou remove o favorito selecionado."""
        favorite = item.data(Qt.UserRole)
        action = ApplicationsDialog.choose(self.host, "Favorito", (
            ("Abrir passagem", "open"), ("Alterar categoria", "category"), ("Remover favorito", "remove"),
        ))
        key = (favorite["translation_id"], favorite["book_code"], favorite["chapter"], favorite["verse"])
        if action == "open":
            self._select_translation(favorite["translation_id"])
            self.host._show_location(favorite["book_code"], favorite["chapter"], favorite["verse"])
        elif action == "category":
            categories = self.data.favorite_categories()
            chosen = ApplicationsDialog.choose(self.host, "Categoria do favorito",
                                                tuple((row["name"], row["id"]) for row in categories))
            if chosen: self.data.set_favorite_category(key, chosen)
        elif action == "remove" and self._confirm("Remover favorito", "Remover esta passagem dos favoritos?"):
            self.data.toggle_bookmark(*key)
        self.refresh_favorites()

    def _build_history(self):
        """Cria a lista cronológica e a ação protegida de limpeza."""
        section, layout, self.history_list = self._list_section(
            "Histórico de leitura", "As passagens mais recentes aparecem agrupáveis pela data anunciada."
        )
        self.history_list.selectionRequested.connect(self.open_history)
        clear = AccessibleButton("&Limpar histórico"); clear.clicked.connect(self.clear_history); layout.addWidget(clear)
        self._register("history", "Histórico de leitura", section, self.history_list)

    def refresh_history(self):
        """Atualiza referências recentes com rótulos Hoje e Ontem."""
        self.history_list.clear()
        today = date.today().isoformat()
        yesterday = (date.today() - timedelta(days=1)).isoformat()
        for row in self.data.reading_history():
            day_value = row["accessed_at"][:10]
            day = "Hoje" if day_value == today else ("Ontem" if day_value == yesterday else day_value)
            item = QListWidgetItem(f"{day}: {row['book_name']} {row['chapter']}:{row['verse'] or '1'}")
            item.setData(Qt.UserRole, row); self.history_list.addItem(item)
        if self.history_list.count(): self.history_list.setCurrentRow(0)

    def open_history(self, item):
        """Retorna exatamente à referência selecionada no histórico."""
        row = item.data(Qt.UserRole)
        self._select_translation(row["translation_id"])
        self.host._show_location(row["book_code"], row["chapter"], row["verse"])

    def clear_history(self):
        """Solicita confirmação antes de apagar todo o histórico."""
        if self._confirm("Limpar histórico", "Apagar todo o histórico de leitura?"):
            self.data.clear_reading_history(); self.refresh_history()

    # Conteúdo de estudo ----------------------------------------------
    def _build_topics(self):
        """Lista temas do mesmo índice usado pela busca inteligente."""
        section, _layout, self.topics_list = self._list_section("Temas bíblicos", "Ative um tema para conhecer e abrir passagens relacionadas.")
        for name in TOPICS: self.topics_list.addItem(QListWidgetItem(name))
        self.topics_list.selectionRequested.connect(self.open_topic)
        self._register("topics", "Temas bíblicos", section, self.topics_list)

    def open_topic(self, item):
        """Oferece as referências relacionadas ao tema escolhido."""
        data = TOPICS[item.text()]
        chosen = ApplicationsDialog.choose(self.host, f"Tema {item.text()}",
                                            tuple((reference, reference) for reference in data["references"]))
        if chosen: self.open_reference(chosen)

    def _build_books_info(self):
        """Cria a lista canônica para a opção Sobre este livro."""
        section, _layout, self.books_info_list = self._list_section("Informações sobre livros", "Ative um livro para ouvir contexto, autoria e resumo.")
        self.books_info_list.selectionRequested.connect(self.open_book_info)
        self._register("books_info", "Informações sobre livros", section, self.books_info_list)

    def refresh_books_info(self):
        """Recarrega nomes conforme a tradução atualmente marcada."""
        self.books_info_list.clear()
        for book in self.db.books(self.host.current_translation_id()):
            item = QListWidgetItem(book["book_name"]); item.setData(Qt.UserRole, dict(book)); self.books_info_list.addItem(item)
        if self.books_info_list.count(): self.books_info_list.setCurrentRow(0)

    def open_book_info(self, item):
        """Apresenta autoria, período, contexto, tema e capítulos."""
        book = item.data(Qt.UserRole)
        info = book_information(book, self.db.chapter_count(self.host.current_translation_id(), book["book_code"]))
        text = (f"Nome: {info['name']}\n\nAutor: {info['author']}.\n\nPeríodo aproximado: {info['period']}.\n\n"
                f"Contexto histórico: {info['context']}\n\nDestinatários: {info['audience']}.\n\n"
                f"Tema principal: {info['theme']}.\n\nResumo: {info['summary']}.\n\nQuantidade de capítulos: {info['chapters']}.")
        AccessibleTextDialog(self.host, f"Sobre {info['name']}", f"Informações sobre {info['name']}", text).exec()

    def _build_characters(self):
        """Cria a área local de personagens e passagens relacionadas."""
        section, _layout, self.characters_list = self._list_section("Personagens", "Ative um personagem para ler a descrição e abrir passagens.")
        for name in CHARACTERS: self.characters_list.addItem(QListWidgetItem(name))
        self.characters_list.selectionRequested.connect(self.open_character)
        self._register("characters", "Personagens", section, self.characters_list)

    def open_character(self, item):
        """Lê o perfil ou abre uma passagem do personagem."""
        description, events, references = CHARACTERS[item.text()]
        action = ApplicationsDialog.choose(self.host, item.text(),
            (("Ler descrição e acontecimentos", "read"), *tuple((f"Abrir {ref}", ref) for ref in references)))
        if action == "read": AccessibleTextDialog(self.host, item.text(), item.text(), f"{description}\n\nAcontecimentos principais: {events}").exec()
        elif action: self.open_reference(action)

    # Memorização, Harpa e quiz ---------------------------------------
    def _build_memorization(self):
        """Monta a lista de versículos escolhidos para memorização."""
        section, layout, self.memory_list = self._list_section("Memorização de versículos", "Adicione o versículo atual ou pratique um item salvo.")
        self.memory_list.selectionRequested.connect(self.practice_memory)
        add = AccessibleButton("Adicionar ou remover o &versículo atual"); add.clicked.connect(self.toggle_memory_current); layout.addWidget(add)
        self._register("memorization", "Memorização de versículos", section, self.memory_list)

    def refresh_memory(self):
        """Atualiza os versículos salvos para prática."""
        self.memory_list.clear()
        for row in self.data.memorized_verses():
            item = QListWidgetItem(f"{row['book_name']} {row['chapter']}:{row['verse']}"); item.setData(Qt.UserRole, row); self.memory_list.addItem(item)
        if self.memory_list.count(): self.memory_list.setCurrentRow(0)

    def toggle_memory_current(self):
        """Adiciona ou remove o versículo selecionado na Bíblia."""
        key = self.host._current_verse_key(); item = self.host.verse_list.currentItem()
        if not key or not item: return
        added = self.data.toggle_memorization(key[0], key[1], self.host.active_book_name, key[2], key[3], item.data(Qt.UserRole))
        self.refresh_memory(); self.host._announce_for(self.memory_list, "Adicionado para memorização." if added else "Removido da memorização.")

    def practice_memory(self, item):
        """Oculta palavras conforme o nível e oferece revelar a resposta."""
        row = item.data(Qt.UserRole)
        level = ApplicationsDialog.choose(self.host, "Nível de memorização", (("Fácil", 3), ("Médio", 2), ("Difícil", 1)))
        if not level: return
        words = row["verse_text"].split(); hidden = ["________" if index % level == 0 and word.isalpha() else word for index, word in enumerate(words)]
        action = ApplicationsDialog.choose(self.host, f"Praticar {row['book_name']} {row['chapter']}:{row['verse']}",
                                            (("Ler texto com palavras ocultas", "hidden"), ("Revelar resposta", "answer")))
        text = " ".join(hidden) if action == "hidden" else row["verse_text"]
        if action: AccessibleTextDialog(self.host, "Memorização", "Exercício de memorização", text).exec()

    def _build_hymnal(self):
        """Cria pesquisa local por número, título ou trecho dos 640 hinos."""
        section = QGroupBox("Harpa Cristã")
        layout = QVBoxLayout(section)
        layout.addWidget(QLabel(
            "Digite um número, título ou trecho da letra e pressione Enter. "
            "Sem pesquisa, todos os 640 hinos são mostrados."
        ))
        self.hymnal_search = QLineEdit()
        self.hymnal_search.setAccessibleName("Pesquisar na Harpa Cristã")
        self.hymnal_search.setPlaceholderText("Exemplo: 15 ou Chuvas de graça")
        self.hymnal_search.returnPressed.connect(self.refresh_hymnal)
        layout.addWidget(self.hymnal_search)
        self.hymnal_status = QLabel()
        self.hymnal_status.setAccessibleName("Quantidade de hinos encontrados")
        layout.addWidget(self.hymnal_status)
        self.hymnal_list = ActivatableList()
        self.hymnal_list.setAccessibleName("Hinos da Harpa Cristã")
        self.hymnal_list.setAccessibleDescription(
            "Use cima e baixo para navegar e Espaço ou Enter para abrir a letra completa."
        )
        self.hymnal_list.selectionRequested.connect(self.open_hymn)
        layout.addWidget(self.hymnal_list, 1)
        self._visible_hymns = self.hymns
        self._register("hymnal", "Harpa Cristã", section, self.hymnal_search)

    def refresh_hymnal(self):
        """Atualiza resultados e leva o foco à lista quando a busca é confirmada."""
        self._visible_hymns = search_hymns(self.hymns, self.hymnal_search.text())
        self.hymnal_list.clear()
        for hymn in self._visible_hymns:
            self.hymnal_list.addItem(QListWidgetItem(hymn.label))
        total = len(self._visible_hymns)
        suffix = "s" if total != 1 else ""
        self.hymnal_status.setText(f"{total} hino{suffix} encontrado{suffix}.")
        if total:
            self.hymnal_list.setCurrentRow(0)
            if self.hymnal_search.hasFocus():
                self.hymnal_list.setFocus()
                self.host._announce_for(self.hymnal_list, self.hymnal_status.text())
        elif self.hymnal_search.hasFocus():
            self.host._announce_for(self.hymnal_search, "Nenhum hino encontrado. Altere a pesquisa.")

    def open_hymn(self, item):
        """Abre a letra em itens navegáveis, para que cima e baixo leiam cada parte."""
        hymn = self._visible_hymns[self.hymnal_list.row(item)]
        AccessibleResultDialog(
            self.host, hymn.label, f"Letra do hino {hymn.number}, {hymn.title}\n\n{hymn.lyrics}"
        ).exec()

    def _build_quiz(self):
        """Cria filtros de dificuldade e categoria e a lista de perguntas."""
        section, layout, self.quiz_list = self._list_section("Quiz bíblico", "Escolha dificuldade e categoria; ative uma pergunta para responder.")
        self.quiz_difficulty = QComboBox(); self.quiz_difficulty.setAccessibleName("Dificuldade do quiz"); self.quiz_difficulty.addItems(("todas", "fácil", "médio", "difícil")); layout.insertWidget(1, self.quiz_difficulty)
        self.quiz_category = QComboBox(); self.quiz_category.setAccessibleName("Categoria do quiz"); self.quiz_category.addItems(("todas", *quiz_categories(self.quiz_questions))); layout.insertWidget(2, self.quiz_category)
        self.quiz_difficulty.currentTextChanged.connect(self.refresh_quiz); self.quiz_category.currentTextChanged.connect(self.refresh_quiz)
        self.quiz_list.selectionRequested.connect(self.answer_quiz)
        self._register("quiz", "Quiz bíblico", section, self.quiz_list)

    def refresh_quiz(self):
        """Aplica os filtros sem alterar o catálogo original."""
        self.quiz_list.clear()
        for index, question in enumerate(self.quiz_questions):
            if self.quiz_difficulty.currentText() not in ("todas", question.difficulty): continue
            if self.quiz_category.currentText() not in ("todas", question.category): continue
            item = QListWidgetItem(f"{question.text} Dificuldade: {question.difficulty}. Categoria: {question.category}.")
            item.setData(Qt.UserRole, index); self.quiz_list.addItem(item)
        if self.quiz_list.count(): self.quiz_list.setCurrentRow(0)

    def answer_quiz(self, item):
        """Informa imediatamente acerto, explicação e referência."""
        question = self.quiz_questions[item.data(Qt.UserRole)]
        if self.data.quiz_question_answered(question.question_id):
            QMessageBox.information(
                self.host, "Pergunta já respondida",
                "Esta pergunta já foi respondida e seu primeiro resultado permanece no status do quiz.",
            )
            return
        chosen = ApplicationsDialog.choose(self.host, question.text, tuple((answer, index) for index, answer in enumerate(question.answers)))
        if chosen is None: return
        correct = chosen == question.correct
        if not self.data.record_quiz_attempt(
                question.question_id, question.difficulty, question.category, correct):
            QMessageBox.information(self.host, "Pergunta já respondida", "O resultado anterior foi mantido.")
            return
        message = ("Resposta correta. " if correct else f"Resposta incorreta. A resposta correta é {question.answers[question.correct]}. ") + question.explanation + f" Referência: {question.reference}."
        QMessageBox.information(self.host, "Resultado do quiz", message)

    def _build_quiz_status(self):
        """Cria a seção dedicada a acertos, erros e aproveitamento."""
        section, _layout, self.quiz_status_list = self._list_section(
            "Status do quiz", "Resultados acumulados somente neste computador."
        )
        self._register("quiz_status", "Status do quiz", section, self.quiz_status_list)

    def refresh_quiz_status(self):
        """Mostra totais gerais e o detalhamento por dificuldade."""
        stats = self.data.quiz_statistics()
        total = stats["quiz_total"]
        accuracy = round(stats["quiz_acertos"] * 100 / total) if total else 0
        self.quiz_status_list.clear()
        for text in (
            f"Perguntas respondidas: {total}", f"Acertos: {stats['quiz_acertos']}",
            f"Erros: {stats['quiz_erros']}", f"Aproveitamento: {accuracy} por cento",
        ):
            self.quiz_status_list.addItem(QListWidgetItem(text))
        for row in self.data.quiz_breakdown():
            correct = int(row["correct"] or 0)
            attempts = int(row["total"] or 0)
            self.quiz_status_list.addItem(QListWidgetItem(
                f"Dificuldade {row['difficulty']}: {correct} acertos e {attempts - correct} erros."
            ))
        self.quiz_status_list.setCurrentRow(0)

    # Estatísticas, culto e backup ------------------------------------
    def _build_statistics(self):
        """Cria uma lista sem pontuação para acompanhamento pessoal."""
        section, _layout, self.statistics_list = self._list_section("Estatísticas pessoais", "Contagens simples para acompanhamento pessoal, sem pontuação.")
        self._register("statistics", "Estatísticas pessoais", section, self.statistics_list)

    def refresh_statistics(self):
        """Recalcula contagens pessoais a partir dos registros locais."""
        stats = self.data.statistics(); self.statistics_list.clear()
        read = self.data.reading_history()
        chapters_by_book = {}
        for row in read:
            chapters_by_book.setdefault((row["translation_id"], row["book_code"]), set()).add(row["chapter"])
        stats["livros_concluidos"] = sum(
            len(chapters) >= self.db.chapter_count(translation_id, book_code)
            for (translation_id, book_code), chapters in chapters_by_book.items()
        )
        labels = (("Capítulos acessados", "capitulos_lidos"), ("Livros concluídos", "livros_concluidos"),
                  ("Dias de leitura", "dias_leitura"), ("Planos concluídos", "planos_concluidos"),
                  ("Planos ativos", "planos_ativos"), ("Versículos favoritos", "favoritos"),
                  ("Anotações", "anotacoes"), ("Pedidos de oração", "pedidos_oracao"),
                  ("Orações respondidas", "oracoes_respondidas"),
                  ("Respostas certas no quiz", "quiz_acertos"),
                  ("Respostas erradas no quiz", "quiz_erros"))
        for label, key in labels: self.statistics_list.addItem(QListWidgetItem(f"{label}: {stats[key]}"))
        self.statistics_list.setCurrentRow(0)

    def _build_worship_mode(self):
        """Cria a tela rápida com busca, texto direto e retorno previsível."""
        section = QGroupBox("Modo culto"); layout = QVBoxLayout(section)
        layout.addWidget(QLabel("Digite uma passagem e pressione Enter."))
        self.worship_edit = QLineEdit(); self.worship_edit.setAccessibleName("Digite uma passagem")
        self.worship_edit.setPlaceholderText("Exemplo: Habacuque 2:4"); self.worship_edit.returnPressed.connect(self.open_worship_reference)
        layout.addWidget(self.worship_edit)
        self.worship_heading = QLabel("Nenhuma passagem carregada.")
        self.worship_heading.setAccessibleName("Estado da passagem no modo culto")
        layout.addWidget(self.worship_heading)
        self.worship_result = ActivatableList(); self.worship_result.setAccessibleName("Texto da passagem no modo culto"); layout.addWidget(self.worship_result, 1)
        self.worship_result.selectionRequested.connect(
            lambda item: self.host._announce_for(self.worship_result, item.text())
        )
        controls = QHBoxLayout()
        self.worship_previous = AccessibleButton("Capítulo &anterior")
        self.worship_previous.clicked.connect(lambda: self.change_worship_chapter(-1))
        controls.addWidget(self.worship_previous)
        self.worship_next = AccessibleButton("&Próximo capítulo")
        self.worship_next.clicked.connect(lambda: self.change_worship_chapter(1))
        controls.addWidget(self.worship_next)
        self.worship_copy = AccessibleButton("&Copiar passagem")
        self.worship_copy.clicked.connect(self.copy_worship_passage)
        controls.addWidget(self.worship_copy)
        self.worship_new = AccessibleButton("&Digitar outra passagem")
        self.worship_new.clicked.connect(self.focus_worship_search)
        controls.addWidget(self.worship_new)
        layout.addLayout(controls)
        self._worship_book = None
        self._worship_chapter = None
        self._register("worship", "Modo culto", section, self.worship_edit)

    def open_worship_reference(self):
        """Carrega imediatamente a passagem e deixa seu texto navegável."""
        resolved = self.resolve_reference(self.worship_edit.text())
        self.worship_result.clear()
        if not resolved:
            self.host._warn("Passagem não encontrada", "Confira a passagem digitada."); self.worship_edit.setFocus(); return
        book, parsed, rows = resolved
        for row in rows: self.worship_result.addItem(QListWidgetItem(f"{row['verse']}. {row['text']}"))
        self._worship_book = book
        self._worship_chapter = parsed.chapter
        reference = f"{book['book_name']} {parsed.chapter}"
        if parsed.verse_start:
            reference += f":{parsed.verse_start}"
            if parsed.verse_end:
                reference += f"-{parsed.verse_end}"
        count = len(rows)
        self.worship_heading.setText(f"Passagem exibida: {reference}. {count} versículo{'s' if count != 1 else ''}.")
        self.worship_result.setAccessibleName(f"{reference}, texto da passagem já exibido")
        self.worship_previous.setEnabled(parsed.chapter > 1)
        maximum = self.db.chapter_count(self.host.current_translation_id(), book["book_code"])
        self.worship_next.setEnabled(parsed.chapter < maximum)
        if self.worship_result.count():
            self.worship_result.setCurrentRow(0)
            self.worship_result.setFocus()
            self.host._announce_for(
                self.worship_result,
                f"Passagem carregada: {reference}. {self.worship_result.item(0).text()}"
            )

    def focus_worship_search(self):
        """Seleciona a referência anterior para uma substituição imediata."""
        self.worship_edit.setFocus()
        self.worship_edit.selectAll()
        self.host._announce_for(self.worship_edit, "Digite outra passagem e pressione Enter.")

    def copy_worship_passage(self):
        """Copia referência e todos os itens atualmente mostrados."""
        if not self.worship_result.count():
            self.host._warn("Nenhuma passagem", "Digite uma passagem e pressione Enter primeiro.")
            return
        text = "\n".join(self.worship_result.item(i).text() for i in range(self.worship_result.count()))
        QApplication.clipboard().setText(f"{self.worship_heading.text()}\n{text}")
        self.host._announce_for(self.worship_copy, "Passagem copiada.")

    def change_worship_chapter(self, direction: int):
        """Abre o capítulo vizinho sem passar pela tela comum de leitura."""
        if self._worship_book is None or self._worship_chapter is None:
            return
        target = self._worship_chapter + direction
        maximum = self.db.chapter_count(
            self.host.current_translation_id(), self._worship_book["book_code"]
        )
        if not 1 <= target <= maximum:
            self.host._announce_for(self.worship_result, "Não há outro capítulo nessa direção.")
            return
        self.worship_edit.setText(f"{self._worship_book['book_name']} {target}")
        self.open_worship_reference()

    def _build_backup(self):
        """Cria as ações separadas de exportação e importação."""
        section, layout, self.backup_list = self._list_section("Backup e restauração", "Escolha exportar ou importar um backup JSON versionado.")
        for label, action in (("Exportar backup", "export"), ("Importar backup", "import")):
            item = QListWidgetItem(label); item.setData(Qt.UserRole, action); self.backup_list.addItem(item)
        self.backup_list.selectionRequested.connect(self.backup_action)
        self._register("backup", "Backup e restauração", section, self.backup_list)

    def backup_action(self, item):
        """Executa a operação de backup destacada."""
        if item.data(Qt.UserRole) == "export": self.export_backup()
        else: self.import_backup()

    def export_backup(self):
        """Grava dados e configurações não secretas em JSON versionado."""
        filename, _ = QFileDialog.getSaveFileName(self.host, "Exportar backup", str(Path.home() / "biblia-acessivel-backup.json"), "Backup JSON (*.json)")
        if not filename: return
        payload = self.data.export_payload()
        settings = QSettings()
        payload["settings"] = {}
        for key in settings.allKeys():
            if "api_key" in key:
                continue
            value = settings.value(key)
            payload["settings"][key] = value if isinstance(value, (str, int, float, bool, list, type(None))) else str(value)
        try: Path(filename).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        except OSError: self.host._warn("Não foi possível exportar", "Confira a pasta escolhida e tente novamente."); return
        QMessageBox.information(self.host, "Backup exportado", f"Backup salvo em {filename}.")

    def import_backup(self):
        """Valida o JSON e restaura tudo numa transação confirmada."""
        filename, _ = QFileDialog.getOpenFileName(self.host, "Importar backup", str(Path.home()), "Backup JSON (*.json)")
        if not filename: return
        if not self._confirm("Importar backup", "Os dados pessoais atuais serão substituídos pelos dados validados do backup. Continuar?"): return
        try:
            payload = json.loads(Path(filename).read_text(encoding="utf-8"))
            setting_values = payload.get("settings", {})
            if not isinstance(setting_values, dict):
                raise ValueError("As configurações do backup são inválidas.")
            self.data.import_payload(payload)
            settings = QSettings()
            for key, value in setting_values.items(): settings.setValue(key, value)
        except (OSError, ValueError, json.JSONDecodeError) as error:
            self.host._warn("Não foi possível importar", str(error)); return
        QMessageBox.information(self.host, "Backup restaurado", "Os dados foram restaurados com sucesso.")

    # Ciclo das páginas e utilidades ----------------------------------
    def prepare_page(self, page_id: str):
        """Atualiza somente a página aberta e mantém o foco previsível."""
        refreshers = {
            "dashboard": self.refresh_dashboard, "plans": self.refresh_plans,
            "prayers": self.refresh_prayers, "daily_devotional": self.prepare_moment,
            "favorites": self.refresh_favorites, "history": self.refresh_history,
            "books_info": self.refresh_books_info, "memorization": self.refresh_memory,
            "hymnal": self.refresh_hymnal, "quiz": self.refresh_quiz,
            "quiz_status": self.refresh_quiz_status, "statistics": self.refresh_statistics,
        }
        if page_id in refreshers: refreshers[page_id]()
        if page_id == "worship": self.worship_edit.selectAll()

    def _text_prompt(self, title: str, label: str):
        """Cria uma caixa de texto rotulada e confirmável com Enter."""
        dialog = QDialog(self.host); dialog.setWindowTitle(title); dialog.setAccessibleName(f"{title}, janela")
        form = QFormLayout(dialog); edit = QLineEdit(); edit.setAccessibleName(label); form.addRow(f"&{label}:", edit)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel); buttons.accepted.connect(dialog.accept); buttons.rejected.connect(dialog.reject); form.addRow(buttons)
        edit.returnPressed.connect(dialog.accept); edit.setFocus()
        accepted = dialog.exec() == QDialog.Accepted
        return edit.text(), accepted

    def _confirm(self, title: str, message: str) -> bool:
        """Centraliza confirmações de ações destrutivas ou irreversíveis."""
        return QMessageBox.question(self.host, title, message, QMessageBox.Yes | QMessageBox.No, QMessageBox.No) == QMessageBox.Yes
