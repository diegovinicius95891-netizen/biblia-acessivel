"""Interface acessível com uma tela bíblica limpa e páginas internas.

As classes de lista especializam somente o comportamento do teclado. A classe
``MainWindow`` coordena apresentação, navegação, persistência e ações sobre o
texto selecionado. Regras de dados permanecem nos módulos ``database`` e
``user_data``.
"""

from __future__ import annotations

import re
import threading
from collections import defaultdict
from datetime import date, datetime
from pathlib import Path

from PySide6.QtCore import QObject, QSettings, Qt, QUrl, Signal
from PySide6.QtGui import (
    QAccessible,
    QAccessibleAnnouncementEvent,
    QAction,
    QDesktopServices,
    QKeySequence,
    QShortcut,
)
from PySide6.QtWidgets import (
    QApplication,
    QComboBox,
    QFileDialog,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QStackedWidget,
    QVBoxLayout,
    QWidget,
)

from .database import BibleDatabase
from .dialogs import (
    AccessibleResultDialog,
    AccessibleTextDialog,
    AccessibleButton,
    ActivatableList,
    ApiKeyDialog,
    ApplicationsDialog,
    ChoiceDialog,
    ModelDialog,
    NoteEditorDialog,
)
from .gemini_client import GeminiServiceError, create_bible_analysis
from .legal import LEGAL_TEXT
from .references import parse_reference
from .secure_store import SecureStoreError, protect_text, unprotect_text
from .topics import resolve_topic
from .user_data import UserDataDatabase
from .version import APP_VERSION

try:
    from PySide6.QtTextToSpeech import QTextToSpeech
except ImportError:  # pragma: no cover
    QTextToSpeech = None


GOOGLE_API_KEYS_URL = "https://aistudio.google.com/apikey"
DETAIL_OPTIONS = (("Curto", "curto"), ("Médio", "médio"), ("Detalhado", "detalhado"))
FONT_SIZE_OPTIONS = tuple((f"{size} pontos", size) for size in (8, 10, 12, 14, 16, 18, 20, 24, 28))
SPEECH_RATE_OPTIONS = (
    ("Bem lenta", -0.6),
    ("Lenta", -0.3),
    ("Normal", 0.0),
    ("Rápida", 0.3),
    ("Bem rápida", 0.6),
)
CONTRAST_OPTIONS = (("Desativado", False), ("Ativado", True))
UPDATE_AUTOMATIC_OPTIONS = (("Ativada", True), ("Desativada", False))
UPDATE_NOTIFICATION_OPTIONS = (("Ativada", True), ("Desativada", False))
VOICE_OFF = "__off__"
SHORTCUTS = {
    "search": "Ctrl+F", "reference": "Ctrl+G", "favorite": "Ctrl+D",
    "note": "Ctrl+M", "plans": "Ctrl+L", "prayers": "Ctrl+O",
    "history": "Ctrl+H", "daily_devotional": "Ctrl+Shift+D",
    "copy_verse": "Ctrl+Shift+C",
}


class TranslationList(QListWidget):
    """Setas navegam; Espaço ou Enter marca a tradução atual."""

    chooseRequested = Signal()

    def keyPressEvent(self, event):
        """Marca a edição com Espaço/Enter e preserva setas para navegação."""
        if event.key() in (Qt.Key_Space, Qt.Key_Return, Qt.Key_Enter):
            self.chooseRequested.emit()
            event.accept()
            return
        super().keyPressEvent(event)


class BookList(QListWidget):
    """Esquerda/direita trocam testamento sem abandonar a seção."""

    testamentRequested = Signal(int)
    chaptersRequested = Signal()
    applicationsRequested = Signal()

    def keyPressEvent(self, event):
        """Converte setas horizontais em troca de testamento."""
        if event.key() == Qt.Key_Left:
            self.testamentRequested.emit(-1)
            event.accept()
            return
        if event.key() == Qt.Key_Right:
            self.testamentRequested.emit(1)
            event.accept()
            return
        applications_key = event.key() == Qt.Key_Menu
        shift_f10 = event.key() == Qt.Key_F10 and bool(event.modifiers() & Qt.ShiftModifier)
        if applications_key or shift_f10:
            self.applicationsRequested.emit()
            event.accept()
            return
        if event.key() in (Qt.Key_Space, Qt.Key_Return, Qt.Key_Enter):
            self.chaptersRequested.emit()
            event.accept()
            return
        super().keyPressEvent(event)


class ChapterList(QListWidget):
    """Lista em que Espaço ou Enter abre o capítulo destacado."""

    openRequested = Signal()
    applicationsRequested = Signal()

    def keyPressEvent(self, event):
        """Solicita a abertura sem alterar o comportamento das setas."""
        applications_key = event.key() == Qt.Key_Menu
        shift_f10 = event.key() == Qt.Key_F10 and bool(event.modifiers() & Qt.ShiftModifier)
        if applications_key or shift_f10:
            self.applicationsRequested.emit()
            event.accept()
            return
        if event.key() in (Qt.Key_Return, Qt.Key_Enter, Qt.Key_Space):
            self.openRequested.emit()
            event.accept()
            return
        super().keyPressEvent(event)


class VerseList(QListWidget):
    """Lista de leitura que reconhece Aplicações e Shift+F10."""

    applicationsRequested = Signal()
    chapterRequested = Signal(int)
    activateRequested = Signal()

    def keyPressEvent(self, event):
        """Abre o menu contextual pelo teclado ou delega a tecla à lista."""
        if event.key() == Qt.Key_Left:
            self.chapterRequested.emit(-1)
            event.accept()
            return
        if event.key() == Qt.Key_Right:
            self.chapterRequested.emit(1)
            event.accept()
            return
        applications_key = event.key() == Qt.Key_Menu
        shift_f10 = event.key() == Qt.Key_F10 and bool(event.modifiers() & Qt.ShiftModifier)
        if applications_key or shift_f10:
            self.applicationsRequested.emit()
            event.accept()
            return
        if event.key() in (Qt.Key_Space, Qt.Key_Return, Qt.Key_Enter):
            self.activateRequested.emit()
            event.accept()
            return
        super().keyPressEvent(event)


class ReadingTextList(QListWidget):
    """Apresenta textos longos como parágrafos navegáveis com cima/baixo.

    ``QPlainTextEdit`` pode fazer certos leitores anunciarem apenas o nome do
    grupo repetidamente. Itens de lista expõem cada parágrafo como texto
    acessível independente e seguem o mesmo padrão de navegação do aplicativo.
    """

    def __init__(self, accessible_name: str, parent=None):
        """Configura uma lista de leitura sem edição ou seleção múltipla."""
        super().__init__(parent)
        self.setAccessibleName(accessible_name)
        self.setAccessibleDescription(
            "Use as setas cima e baixo para ler o texto por parágrafos."
        )
        self.setWordWrap(True)

    def set_text(self, text: str):
        """Divide o documento em parágrafos que leitores de tela anunciam."""
        self.clear()
        for paragraph in re.split(r"\n\s*\n", text.strip()):
            normalized = " ".join(paragraph.split())
            if normalized:
                self.addItem(QListWidgetItem(normalized))
        if self.count():
            self.setCurrentRow(0)


class AiSignals(QObject):
    """Transporta o resultado da tarefa de rede para a interface Qt."""

    finished = Signal(str)
    failed = Signal(str)


class MainWindow(QMainWindow):
    """Coordena as seções locais e os recursos opcionais de IA."""

    OLD_TESTAMENT = 0
    NEW_TESTAMENT = 1

    def __init__(
        self,
        database_path: Path,
        user_data_path: Path | None = None,
        app_data_dir: Path | None = None,
        install_dir: Path | None = None,
    ):
        """Abre bancos, constrói controles e restaura a última posição."""
        super().__init__()
        self.db = BibleDatabase(database_path)
        self.user_data = UserDataDatabase(user_data_path or database_path.with_name("user_data.db"))
        self.app_data_dir = app_data_dir or self.user_data.path.parent
        self.install_dir = install_dir or database_path.parent.parent
        self.settings = QSettings()
        self.api_key = self._load_saved_api_key()
        self.pending_api_key = self.api_key
        self.ai_model = self.settings.value("gemini/model", "gemini-2.5-flash-lite")
        self.pending_ai_model = self.ai_model
        self.ai_detail = self.settings.value("gemini/detail", "médio")
        self.pending_ai_detail = self.ai_detail
        self.font_size = int(self.settings.value("accessibility/font_size", 10))
        self.pending_font_size = self.font_size
        self.speech_rate = float(self.settings.value("accessibility/speech_rate", 0.0))
        self.pending_speech_rate = self.speech_rate
        self.voice_name = str(self.settings.value("accessibility/voice_name", ""))
        self.pending_voice_name = self.voice_name
        self.high_contrast = self.settings.value("accessibility/high_contrast", False, type=bool)
        self.pending_high_contrast = self.high_contrast
        self.update_automatic = self.settings.value("updates/automatic", True, type=bool)
        self.pending_update_automatic = self.update_automatic
        self.update_notify = self.settings.value("updates/notify", True, type=bool)
        self.pending_update_notify = self.update_notify
        self.tts = None
        self._tts_checked = False
        self._loading = False
        self._ai_busy = False
        self.ai_signals = AiSignals(self)
        self.ai_signals.finished.connect(self._ai_finished)
        self.ai_signals.failed.connect(self._ai_failed)
        self.testament = self.OLD_TESTAMENT
        self.active_book_code = ""
        self.active_book_name = ""
        self.active_chapter = 1
        self._ai_result_title = "Resultado da inteligência artificial"

        self.setWindowTitle(f"Bíblia Acessível {APP_VERSION}")
        self.resize(1100, 760)
        self.setAccessibleName("Bíblia Acessível")
        self._build_ui()
        self._build_menu()
        self._build_shortcuts()
        self._apply_accessibility_settings()
        self._load_translations_and_restore()
        from .update_ui import UpdateController
        self.update_controller = UpdateController(self, self.app_data_dir, self.install_dir)
        self.book_list.setFocus()
        self.statusBar().showMessage(
            "Pronto. Tab muda de seção; setas navegam dentro da seção. F1 abre a ajuda."
        )

    # Interface ---------------------------------------------------------
    def _build_ui(self):
        """Cria uma tela bíblica limpa e páginas internas sem usar guias."""
        self.page_stack = QStackedWidget()
        self.page_stack.setAccessibleName("Telas do aplicativo")
        self.secondary_pages = {}

        self.sections_widget = self._build_reader_section()
        self.main_page_index = self.page_stack.addWidget(self.sections_widget)

        self.translation_section = self._build_translation_section()
        self.reference_section = self._build_reference_section()
        self.search_section = self._build_search_section()
        self.devotional_section = self._build_devotional_section()
        self.notes_section = self._build_notes_section()
        self.settings_section = self._build_settings_section()
        self.legal_section = self._build_legal_section()
        self.help_section = self._build_help_section()

        self._add_secondary_page("translations", "Traduções", self.translation_section, self.translation_list)
        self._add_secondary_page("reference", "Ir para uma referência", self.reference_section, self.reference_edit)
        self._add_secondary_page("search", "Pesquisa", self.search_section, self.search_edit)
        self._add_secondary_page("devotional", "Escrever devocional", self.devotional_section, self.devotional_reference)
        self._add_secondary_page("notes", "Anotações por dia", self.notes_section, self.notes_list)
        self._add_secondary_page("settings", "Configurações", self.settings_section, self.settings_options)
        self._add_secondary_page("legal", "Licenças, leis e justificativa", self.legal_section, self.legal_text)
        self._add_secondary_page("help", "Ajuda e atalhos", self.help_section, self.help_text)
        # Recursos extensos permanecem num controlador separado para evitar
        # transformar esta janela em um arquivo ainda mais monolítico.
        from .extended_features import ExtendedFeatures
        self.extended = ExtendedFeatures(self)
        self.setCentralWidget(self.page_stack)

    def _build_reader_section(self):
        """Monta somente Livros, Capítulos, Versículos, Leitura e Mais opções."""
        section = QWidget()
        layout = QVBoxLayout(section)
        self.start_summary = QLabel("Continuar leitura será carregado em instantes.")
        self.start_summary.setAccessibleName("Resumo inicial e continuar leitura")
        self.start_summary.setWordWrap(True)
        layout.addWidget(self.start_summary)
        selection_row = QHBoxLayout()

        books_group = QGroupBox("Seção &Livros")
        books_layout = QVBoxLayout(books_group)
        self.testament_label = QLabel("Antigo Testamento")
        self.testament_label.setAccessibleName("Testamento exibido")
        books_layout.addWidget(self.testament_label)
        books_layout.addWidget(
            QLabel("Cima/baixo navegam; esquerda/direita trocam Antigo e Novo Testamento.")
        )
        self.book_list = BookList()
        self.book_list.setAccessibleName("Seção Livros")
        self.book_list.setAccessibleDescription(
            "Mostra somente o testamento atual. Esquerda e direita trocam o testamento. "
            "Aplicações ou Shift F10 permite gerar resumo do livro."
        )
        self.book_list.testamentRequested.connect(self.switch_testament)
        self.book_list.chaptersRequested.connect(self.focus_chapters)
        self.book_list.applicationsRequested.connect(self.show_book_menu)
        self.book_list.currentItemChanged.connect(self._book_highlighted)
        self.book_list.itemDoubleClicked.connect(lambda _item: self.focus_chapters())
        self.book_list.setContextMenuPolicy(Qt.CustomContextMenu)
        self.book_list.customContextMenuRequested.connect(self.show_book_menu_at)
        books_layout.addWidget(self.book_list)
        books_group.setFocusProxy(self.book_list)
        selection_row.addWidget(books_group, 1)

        chapters_group = QGroupBox("Seção &Capítulos")
        chapters_layout = QVBoxLayout(chapters_group)
        chapters_layout.addWidget(QLabel("Escolha com cima/baixo e pressione Enter para começar a ler."))
        self.chapter_list = ChapterList()
        self.chapter_list.setAccessibleName("Seção Capítulos")
        self.chapter_list.setAccessibleDescription(
            "Use cima e baixo para escolher e Enter para abrir. "
            "Aplicações ou Shift F10 permite gerar resumo do capítulo."
        )
        self.chapter_list.openRequested.connect(self.open_selected_chapter)
        self.chapter_list.applicationsRequested.connect(self.show_chapter_menu)
        self.chapter_list.itemDoubleClicked.connect(lambda _item: self.open_selected_chapter())
        self.chapter_list.setContextMenuPolicy(Qt.CustomContextMenu)
        self.chapter_list.customContextMenuRequested.connect(self.show_chapter_menu_at)
        chapters_layout.addWidget(self.chapter_list)
        chapters_group.setFocusProxy(self.chapter_list)
        selection_row.addWidget(chapters_group, 1)

        verses_group = QGroupBox("Seção &Versículos")
        verses_layout = QVBoxLayout(verses_group)
        self.chapter_heading = QLabel("Escolha um livro e um capítulo")
        self.chapter_heading.setAccessibleName("Título da leitura atual")
        heading_font = self.chapter_heading.font()
        heading_font.setBold(True)
        heading_font.setPointSize(heading_font.pointSize() + 2)
        self.chapter_heading.setFont(heading_font)
        verses_layout.addWidget(self.chapter_heading)
        self.verse_list = VerseList()
        self.verse_list.setAccessibleName("Seção Versículos")
        self.verse_list.setAccessibleDescription(
            "Cima e baixo navegam. Esquerda e direita mudam o capítulo. "
            "Enter lê em voz alta. Aplicações ou Shift F10 abre as ações do versículo."
        )
        self.verse_list.activateRequested.connect(self.speak_current_verse)
        self.verse_list.itemDoubleClicked.connect(lambda _item: self.speak_current_verse())
        self.verse_list.currentItemChanged.connect(self._verse_changed)
        self.verse_list.chapterRequested.connect(self.change_chapter_from_reading)
        self.verse_list.applicationsRequested.connect(self.show_verse_menu)
        self.verse_list.setContextMenuPolicy(Qt.CustomContextMenu)
        self.verse_list.customContextMenuRequested.connect(self.show_verse_menu_at)
        verses_layout.addWidget(self.verse_list, 1)
        verses_group.setFocusProxy(self.verse_list)
        selection_row.addWidget(verses_group, 2)
        layout.addLayout(selection_row, 2)

        reading_group = QGroupBox("Seção Área de &leitura")
        reading_layout = QVBoxLayout(reading_group)
        self.reading_area = ReadingTextList("Área de leitura do versículo selecionado")
        self.reading_area.setAccessibleDescription(
            "Mostra somente a referência e o texto selecionado na seção Versículos."
        )
        reading_layout.addWidget(self.reading_area)
        reading_group.setFocusProxy(self.reading_area)
        layout.addWidget(reading_group, 1)

        more_group = QGroupBox("Seção &Mais opções")
        more_layout = QVBoxLayout(more_group)
        more_layout.addWidget(QLabel("Use cima e baixo e pressione Espaço ou Enter para abrir."))
        self.more_options = ActivatableList()
        self.more_options.setAccessibleName("Seção Mais opções")
        self.more_options.setAccessibleDescription(
            "Cima e baixo navegam. Espaço ou Enter abre a opção em uma nova tela. Escape volta."
        )
        options = (
            ("Traduções", "translations"),
            ("Ir para uma referência", "reference"),
            ("Pesquisar na Bíblia", "search"),
            ("Fazer devocional", "devotional"),
            ("Anotações por dia", "notes"),
            ("Configurações", "settings"),
            ("Licenças, leis e justificativa de uso", "legal"),
            ("Ajuda e atalhos", "help"),
        )
        for label, page_id in options:
            item = QListWidgetItem(label)
            item.setData(Qt.UserRole, page_id)
            self.more_options.addItem(item)
        self.more_options.setCurrentRow(0)
        self.more_options.selectionRequested.connect(self.open_more_option)
        more_layout.addWidget(self.more_options)
        more_group.setFocusProxy(self.more_options)
        layout.addWidget(more_group, 1)

        QWidget.setTabOrder(self.book_list, self.chapter_list)
        QWidget.setTabOrder(self.chapter_list, self.verse_list)
        QWidget.setTabOrder(self.verse_list, self.reading_area)
        QWidget.setTabOrder(self.reading_area, self.more_options)
        return section

    def _build_translation_section(self):
        """Cria a tela de escolha exclusiva da tradução bíblica."""
        section = QGroupBox("Traduções")
        layout = QVBoxLayout(section)
        layout.addWidget(QLabel("Cima/baixo navegam; Espaço ou Enter marca a tradução."))
        self.translation_list = TranslationList()
        self.translation_list.setAccessibleName("Traduções, caixas de seleção")
        self.translation_list.setAccessibleDescription(
            "Use cima e baixo para navegar. Pressione Espaço ou Enter para marcar uma tradução."
        )
        self.translation_list.chooseRequested.connect(self.select_current_translation)
        self.translation_list.itemClicked.connect(lambda _item: self.select_current_translation())
        layout.addWidget(self.translation_list)
        section.setFocusProxy(self.translation_list)
        return section

    def _build_reference_section(self):
        """Cria o campo de navegação direta isolado da tela principal."""
        section = QGroupBox("Ir para uma referência")
        layout = QVBoxLayout(section)
        layout.addWidget(QLabel("Digite, por exemplo, João 3:16 e pressione Enter."))
        self.reference_edit = QLineEdit()
        self.reference_edit.setAccessibleName("Ir para referência")
        self.reference_edit.setPlaceholderText("Exemplo: João 3:16")
        self.reference_edit.returnPressed.connect(self.go_to_reference)
        layout.addWidget(self.reference_edit)
        self.go_button = AccessibleButton("&Ir para referência")
        self.go_button.clicked.connect(self.go_to_reference)
        layout.addWidget(self.go_button)
        return section

    def _add_secondary_page(self, page_id: str, title: str, content: QWidget, focus_widget: QWidget):
        """Registra uma tela interna com aviso uniforme para retornar por Escape."""
        page = QWidget()
        layout = QVBoxLayout(page)
        heading = QLabel(title)
        heading.setAccessibleName(f"Tela {title}")
        font = heading.font()
        font.setBold(True)
        font.setPointSize(font.pointSize() + 3)
        heading.setFont(font)
        layout.addWidget(heading)
        layout.addWidget(QLabel("Pressione Escape para voltar ao menu principal da Bíblia."))
        layout.addWidget(content, 1)
        index = self.page_stack.addWidget(page)
        self.secondary_pages[page_id] = (index, focus_widget)

    def open_more_option(self, item_or_id):
        """Abre a opção destacada como única tela visível do aplicativo."""
        page_id = item_or_id.data(Qt.UserRole) if isinstance(item_or_id, QListWidgetItem) else item_or_id
        if page_id == "notes":
            self.refresh_notes()
        elif page_id == "devotional":
            self.prepare_devotional()
        elif page_id == "settings":
            self._refresh_settings_options()
        elif hasattr(self, "extended"):
            self.extended.prepare_page(page_id)
        index, focus_widget = self.secondary_pages[page_id]
        self.page_stack.setCurrentIndex(index)
        focus_widget.setFocus()
        if isinstance(focus_widget, QListWidget) and focus_widget.count():
            focus_widget.setCurrentRow(max(0, focus_widget.currentRow()))
        self.statusBar().showMessage("Nova tela aberta. Pressione Escape para voltar à Bíblia.")

    def show_main_page(self):
        """Fecha a tela interna e devolve o foco à seção Mais opções."""
        self.page_stack.setCurrentIndex(self.main_page_index)
        self.more_options.setFocus()
        self.statusBar().showMessage("Menu principal da Bíblia.")

    def _build_search_section(self):
        """Cria pesquisa por palavra, frase, tema e livro, com histórico local."""
        section = QGroupBox("Pesquisa")
        layout = QVBoxLayout(section)
        form = QFormLayout()
        self.search_translation = QComboBox()
        self.search_translation.setAccessibleName("Tradução para pesquisa")
        self.search_translation.currentIndexChanged.connect(self._populate_search_books)
        form.addRow("&Tradução:", self.search_translation)
        self.search_mode = QComboBox()
        self.search_mode.setAccessibleName("Tipo de pesquisa")
        self.search_mode.addItem("Automática, palavra ou tema", "auto")
        self.search_mode.addItem("Todas as palavras", "words")
        self.search_mode.addItem("Frase exata", "phrase")
        self.search_mode.addItem("Tema bíblico", "topic")
        self.search_mode.addItem("Livro pelo nome", "book")
        form.addRow("&Tipo:", self.search_mode)
        self.search_book = QComboBox()
        self.search_book.setAccessibleName("Pesquisar dentro de um livro específico")
        self.search_book.addItem("Todos os livros", None)
        form.addRow("&Dentro do livro:", self.search_book)
        self.search_edit = QLineEdit()
        self.search_edit.setAccessibleName("Texto a pesquisar")
        self.search_edit.returnPressed.connect(self.perform_search)
        form.addRow("&Pesquisar:", self.search_edit)
        layout.addLayout(form)
        self.search_button = AccessibleButton("&Executar pesquisa")
        self.search_button.clicked.connect(self.perform_search)
        layout.addWidget(self.search_button)
        self.search_status = QLabel("Digite uma ou mais palavras.")
        self.search_status.setAccessibleName("Estado da pesquisa")
        layout.addWidget(self.search_status)
        self.search_results = ActivatableList()
        self.search_results.setAccessibleName("Resultados da pesquisa")
        self.search_results.selectionRequested.connect(self.open_search_result)
        layout.addWidget(self.search_results, 1)
        actions = QHBoxLayout()
        self.copy_search_button = AccessibleButton("&Copiar resultado selecionado")
        self.copy_search_button.clicked.connect(self.copy_search_result)
        actions.addWidget(self.copy_search_button)
        self.favorite_search_button = AccessibleButton("Adicionar resultado aos &favoritos")
        self.favorite_search_button.clicked.connect(self.favorite_search_result)
        actions.addWidget(self.favorite_search_button)
        layout.addLayout(actions)
        layout.addWidget(QLabel("Histórico de pesquisas. Ative um item para pesquisar novamente:"))
        self.search_history_list = ActivatableList()
        self.search_history_list.setAccessibleName("Histórico de pesquisas")
        self.search_history_list.setMaximumHeight(120)
        self.search_history_list.selectionRequested.connect(self.repeat_search)
        layout.addWidget(self.search_history_list)
        return section

    def _build_devotional_section(self):
        """Cria o editor de devocional com fonte bíblica substituível."""
        section = QGroupBox("Fazer devocional")
        layout = QVBoxLayout(section)
        layout.addWidget(
            QLabel(
                "A referência atual já vem preenchida. Edite-a e pressione Enter para carregar outro texto."
            )
        )
        form = QFormLayout()
        self.devotional_reference = QLineEdit()
        self.devotional_reference.setAccessibleName("Referência bíblica do devocional")
        self.devotional_reference.setPlaceholderText("Exemplo: João 3:16")
        self.devotional_reference.returnPressed.connect(self.load_devotional_reference)
        form.addRow("&Referência:", self.devotional_reference)
        self.devotional_title = QLineEdit()
        self.devotional_title.setAccessibleName("Título do devocional")
        form.addRow("&Título:", self.devotional_title)
        layout.addLayout(form)
        self.devotional_source = ReadingTextList("Texto bíblico usado no devocional")
        self.devotional_source.setMaximumHeight(120)
        layout.addWidget(self.devotional_source)
        self.devotional_editor = QPlainTextEdit()
        self.devotional_editor.setTabChangesFocus(True)
        self.devotional_editor.setAccessibleName("Texto do devocional")
        self.devotional_editor.setAccessibleDescription(
            "Escreva a reflexão. Use Tab para chegar ao botão Salvar como arquivo de texto."
        )
        layout.addWidget(self.devotional_editor, 1)
        self.save_devotional_button = AccessibleButton("&Salvar devocional como arquivo de texto")
        self.save_devotional_button.clicked.connect(self.save_devotional)
        layout.addWidget(self.save_devotional_button)
        return section

    def _build_notes_section(self):
        """Cria a lista de dias e títulos das anotações pessoais."""
        section = QGroupBox("Anotações por dia")
        layout = QVBoxLayout(section)
        layout.addWidget(
            QLabel(
                "Cima e baixo navegam. Abra um dia para ler todas as anotações ou um título para ler apenas uma."
            )
        )
        self.notes_search = QLineEdit()
        self.notes_search.setAccessibleName("Pesquisar nas anotações")
        self.notes_search.setPlaceholderText("Pesquisar por título, texto ou livro")
        self.notes_search.returnPressed.connect(self.refresh_notes)
        layout.addWidget(self.notes_search)
        self.notes_list = ActivatableList()
        self.notes_list.setAccessibleName("Anotações organizadas por dia")
        self.notes_list.setAccessibleDescription(
            "Os dias aparecem antes de seus títulos. Espaço ou Enter abre o item selecionado."
        )
        self.notes_list.selectionRequested.connect(self.open_note_item)
        layout.addWidget(self.notes_list, 1)
        self.note_passage_button = AccessibleButton("Criar anotação sobre uma &passagem")
        self.note_passage_button.clicked.connect(self.create_passage_note)
        layout.addWidget(self.note_passage_button)
        return section

    def _build_settings_section(self):
        """Cria uma lista simples para chave/modelo e um único botão de salvar."""
        section = QGroupBox("Seção &Configurações")
        layout = QVBoxLayout(section)
        layout.addWidget(
            QLabel("Use cima e baixo para escolher. Pressione Espaço ou Enter para abrir.")
        )
        self.settings_options = ActivatableList()
        self.settings_options.setAccessibleName("Opções de configurações")
        self.settings_options.setAccessibleDescription(
            "Cima e baixo navegam. Espaço ou Enter abre o diálogo da opção selecionada."
        )
        self.settings_options.selectionRequested.connect(self.open_settings_option)
        layout.addWidget(self.settings_options)
        self._refresh_settings_options()
        self.get_api_key_button = AccessibleButton("&Obter chave da API do Google")
        self.get_api_key_button.setAccessibleName("Obter chave da API do Google no navegador")
        self.get_api_key_button.clicked.connect(self.open_google_api_keys_page)
        layout.addWidget(self.get_api_key_button)
        self.get_api_key_button.setVisible(not bool(self.pending_api_key))
        self.save_settings_button = AccessibleButton("&Salvar configurações")
        self.save_settings_button.clicked.connect(self.save_ai_settings)
        layout.addWidget(self.save_settings_button)
        return section

    def _build_legal_section(self):
        """Expõe a fundamentação como parágrafos acessíveis por setas."""
        section = QGroupBox("Seção Licenças e &leis")
        layout = QVBoxLayout(section)
        layout.addWidget(QLabel("Use cima e baixo para ler legislação e justificativas:"))
        self.legal_text = ReadingTextList("Leis e justificativa de uso")
        self.legal_text.set_text(LEGAL_TEXT)
        self.legal_text.setMinimumHeight(220)
        layout.addWidget(self.legal_text, 1)
        return section

    def _build_help_section(self):
        """Mantém a ajuda acessível como parágrafos numa tela exclusiva."""
        section = QGroupBox("A&juda")
        layout = QVBoxLayout(section)
        self.help_text = ReadingTextList("Ajuda e atalhos")
        self.help_text.set_text(
            "BÍBLIA ACESSÍVEL\n\n"
            "NAVEGAÇÃO POR SEÇÕES\n"
            "Na tela principal, Tab e Shift+Tab percorrem Livros, Capítulos, Versículos, Área de leitura "
            "e Mais opções. Cima e baixo navegam dentro da seção atual. Em Livros, esquerda mostra o "
            "Antigo Testamento e direita mostra o Novo. Enter em Livros leva aos capítulos; Enter em "
            "Capítulos abre os versículos. Mais opções abre cada recurso numa tela limpa. Escape volta "
            "ao menu principal da Bíblia.\n\n"
            "INTELIGÊNCIA ARTIFICIAL\n"
            "Aplicações ou Shift+F10 abre uma lista acessível. Cima e baixo navegam; Espaço ou "
            "Enter executa. Em Livros há resumo do livro; em Capítulos, resumo do capítulo; e "
            "em Versículos, explicação do número selecionado. Os resumos têm tamanho controlado para "
            "concluir sem gerar um texto excessivo. O mesmo menu permite criar uma anotação.\n\n"
            "DEVOCIONAIS E ANOTAÇÕES\n"
            "Fazer devocional começa com a referência atual, permite carregar outra referência e salvar "
            "o resultado como arquivo de texto na pasta escolhida. As anotações ficam guardadas localmente "
            "e aparecem em Mais opções, organizadas por dia e título. Meu momento com Deus reúne versículo "
            "do dia, leitura do plano, reflexão, prática, oração e histórico. O Diário de oração possui filtros "
            "para pedidos em oração, respondidos e arquivados.\n\n"
            "RECURSOS LOCAIS\n"
            "Mais opções também contém planos de leitura, favoritos com categorias, histórico, temas, "
            "informações sobre livros, personagens, memorização, Harpa Cristã com 640 hinos, quiz com "
            "298 perguntas, Status do quiz, estatísticas, Modo culto e backup. "
            "A busca Automática encontra palavras ou temas relacionados sem serviço pago.\n\n"
            "ATALHOS\n"
            "Ctrl+F: pesquisa. Ctrl+G: ir para passagem. Ctrl+D: favorito. Ctrl+M: anotação. "
            "Ctrl+L: planos de leitura. Ctrl+O: Diário de oração. Ctrl+H: histórico. "
            "Ctrl+Shift+D: Meu momento com Deus. Ctrl+Shift+C: copiar versículo. "
            "Ctrl+Seta esquerda/direita: capítulo anterior ou seguinte. F5: ouvir. "
            "Ctrl+Alt+R: copiar referência e texto. Escape: voltar ou parar voz. F1: ajuda.\n\n"
            "CONTINUIDADE E PRIVACIDADE\n"
            "A posição, os marcadores e as anotações permanecem locais. Em Configurações, cima e baixo navegam "
            "por chave, modelo, detalhamento, fonte, voz SAPI, velocidade e contraste. A voz pode ser "
            "desativada; nesse modo, Enter repete o texto pelo leitor de tela. Espaço ou Enter abre a opção; "
            "Tab leva aos botões e Espaço ou Enter os aciona. "
            "A chave do Google é gravada criptografada para a conta atual do Windows."
        )
        self.help_text.setMinimumHeight(180)
        layout.addWidget(self.help_text)
        self.check_updates_button = AccessibleButton("&Verificar atualizações")
        self.check_updates_button.setAccessibleName("Verificar atualizações agora")
        self.check_updates_button.clicked.connect(self.check_updates_now)
        layout.addWidget(self.check_updates_button)
        self.changelog_button = AccessibleButton("&Novidades da versão")
        self.changelog_button.clicked.connect(self.show_current_changelog)
        layout.addWidget(self.changelog_button)
        return section

    def _build_menu(self):
        """Cria menus tradicionais como rota alternativa aos atalhos."""
        file_menu = self.menuBar().addMenu("&Arquivo")
        exit_action = QAction("&Sair", self)
        exit_action.setShortcut(QKeySequence.Quit)
        exit_action.triggered.connect(self.close)
        file_menu.addAction(exit_action)

        navigate = self.menuBar().addMenu("&Navegar")
        previous = QAction("Capítulo anterior", self)
        previous.setShortcut("Ctrl+Left")
        previous.triggered.connect(self.previous_chapter)
        navigate.addAction(previous)
        following = QAction("Próximo capítulo", self)
        following.setShortcut("Ctrl+Right")
        following.triggered.connect(self.next_chapter)
        navigate.addAction(following)

        item_menu = self.menuBar().addMenu("&Item atual")
        self.action_copy_text = QAction("&Copiar texto", self)
        self.action_copy_text.setShortcut(SHORTCUTS["copy_verse"])
        self.action_copy_text.triggered.connect(self.copy_verse_text)
        item_menu.addAction(self.action_copy_text)
        self.action_copy_reference = QAction("Copiar &referência e texto", self)
        self.action_copy_reference.setShortcut("Ctrl+Alt+R")
        self.action_copy_reference.triggered.connect(self.copy_verse_with_reference)
        item_menu.addAction(self.action_copy_reference)
        item_menu.addSeparator()
        self.action_bookmark = QAction("Adicionar ou remover &marcador", self)
        self.action_bookmark.setShortcut(SHORTCUTS["favorite"])
        self.action_bookmark.triggered.connect(self.toggle_bookmark)
        item_menu.addAction(self.action_bookmark)
        self.action_create_note = QAction("Criar &anotação", self)
        self.action_create_note.setShortcut(SHORTCUTS["note"])
        self.action_create_note.triggered.connect(self.create_note)
        item_menu.addAction(self.action_create_note)
        self.action_speak = QAction("&Ouvir", self)
        self.action_speak.triggered.connect(self.speak_current_verse)
        item_menu.addAction(self.action_speak)

        help_menu = self.menuBar().addMenu("A&juda")
        help_action = QAction("Ajuda e atalhos", self)
        help_action.setShortcut(QKeySequence.HelpContents)
        help_action.triggered.connect(self._focus_help)
        help_menu.addAction(help_action)
        check_updates = QAction("&Verificar atualizações", self)
        check_updates.triggered.connect(self.check_updates_now)
        help_menu.addAction(check_updates)
        changelog = QAction("&Novidades da versão", self)
        changelog.triggered.connect(self.show_current_changelog)
        help_menu.addAction(changelog)

    def _build_shortcuts(self):
        """Registra atalhos globais sem substituir comandos de edição comuns."""
        QShortcut(QKeySequence(SHORTCUTS["reference"]), self, activated=self._focus_reference)
        QShortcut(QKeySequence(SHORTCUTS["search"]), self, activated=self._focus_search)
        QShortcut(QKeySequence(SHORTCUTS["plans"]), self, activated=lambda: self.open_more_option("plans"))
        QShortcut(QKeySequence(SHORTCUTS["prayers"]), self, activated=lambda: self.open_more_option("prayers"))
        QShortcut(QKeySequence(SHORTCUTS["history"]), self, activated=lambda: self.open_more_option("history"))
        QShortcut(QKeySequence(SHORTCUTS["daily_devotional"]), self, activated=lambda: self.open_more_option("daily_devotional"))
        QShortcut(QKeySequence("F5"), self, activated=self.speak_current_verse)
        QShortcut(QKeySequence("Escape"), self, activated=self._handle_escape)
        QShortcut(QKeySequence("Ctrl++"), self, activated=lambda: self.change_font_size(1))
        QShortcut(QKeySequence("Ctrl+-"), self, activated=lambda: self.change_font_size(-1))

    # Traduções, livros e capítulos ------------------------------------
    def _load_translations_and_restore(self):
        """Preenche traduções e retoma livro, capítulo e item salvos."""
        translations = self.db.translations()
        saved_translation = self.settings.value("position/translation", "bpm")
        self._loading = True
        for translation in translations:
            item = QListWidgetItem(translation["name"])
            item.setData(Qt.UserRole, translation["id"])
            item.setFlags(item.flags() | Qt.ItemIsUserCheckable)
            item.setCheckState(Qt.Checked if translation["id"] == saved_translation else Qt.Unchecked)
            self.translation_list.addItem(item)
        for translation in translations:
            self.search_translation.addItem(translation["name"], translation["id"])
        checked_row = next(
            (i for i in range(self.translation_list.count()) if self.translation_list.item(i).checkState() == Qt.Checked),
            0,
        )
        self.translation_list.setCurrentRow(checked_row)
        if not self._checked_translation_item():
            self.translation_list.item(0).setCheckState(Qt.Checked)
        self._loading = False

        saved_book = self.settings.value("position/book", "GEN")
        saved_chapter = int(self.settings.value("position/chapter", 1))
        saved_verse = self.settings.value("position/verse", "1")
        all_books = self.db.books(self.current_translation_id())
        book_row = next((book for book in all_books if book["book_code"] == saved_book), all_books[0])
        self.testament = self.OLD_TESTAMENT if int(book_row["book_number"]) <= 39 else self.NEW_TESTAMENT
        self._populate_books(preferred_code=book_row["book_code"], preferred_chapter=saved_chapter)
        self.open_selected_chapter(focus_verse=saved_verse, focus_reading=False)
        self._populate_search_books()
        self.refresh_search_history()
        self._refresh_start_summary()
        self._update_tab_order()

    def _refresh_start_summary(self):
        """Anuncia posição, versículo diário e primeiro plano ativo na tela inicial."""
        key = self._current_verse_key()
        verse = key[3] if key else "1"
        reference, _text = self.extended.daily_verse()
        active = [state for state in self.user_data.plan_states().values() if state["status"] == "Ativo"]
        plan_text = "Nenhum plano ativo."
        if active:
            state = active[0]
            plan = self.extended.plan_catalog.get(state["plan_id"])
            if plan:
                day = max(1, min(plan.days, int(state["current_day"])))
                readings = ", ".join(plan.readings[day - 1])
                plan_text = (
                    f"Continuar plano: {plan.name}, dia {day} de {plan.days}. "
                    f"Leitura de hoje: {readings}."
                )
        self.start_summary.setText(
            f"Versículo do dia: {reference}. Continuar leitura: {self.active_book_name} "
            f"{self.active_chapter}:{verse}. {plan_text}"
        )

    def _populate_search_books(self):
        """Atualiza o filtro de livro segundo a tradução escolhida para pesquisa."""
        current = self.search_book.currentData() if hasattr(self, "search_book") else None
        self.search_book.clear()
        self.search_book.addItem("Todos os livros", None)
        for book in self.db.books(self.search_translation.currentData() or self.current_translation_id()):
            self.search_book.addItem(book["book_name"], book["book_code"])
        index = self.search_book.findData(current)
        self.search_book.setCurrentIndex(max(0, index))

    def _update_tab_order(self):
        """Garante a rota principal de cinco seções e rotas locais nas telas internas."""
        chain: list[QWidget] = [
            self.book_list,
            self.chapter_list,
            self.verse_list,
            self.reading_area,
            self.more_options,
        ]
        for current, following in zip(chain, chain[1:]):
            QWidget.setTabOrder(current, following)
        QWidget.setTabOrder(self.devotional_reference, self.devotional_title)
        QWidget.setTabOrder(self.devotional_title, self.devotional_source)
        QWidget.setTabOrder(self.devotional_source, self.devotional_editor)
        QWidget.setTabOrder(self.devotional_editor, self.save_devotional_button)

    def _checked_translation_item(self):
        """Localiza a única caixa de tradução atualmente marcada."""
        for index in range(self.translation_list.count()):
            item = self.translation_list.item(index)
            if item.checkState() == Qt.Checked:
                return item
        return None

    def current_translation_id(self) -> str:
        """Retorna o identificador estável da tradução marcada."""
        item = self._checked_translation_item()
        return item.data(Qt.UserRole) if item else "bpm"

    def current_translation_name(self) -> str:
        """Retorna o nome exibido da tradução marcada."""
        item = self._checked_translation_item()
        return item.text() if item else ""

    def select_current_translation(self):
        """Aplica seleção exclusiva e recarrega a mesma referência."""
        item = self.translation_list.currentItem()
        if not item:
            return
        old_book = self.book_list.currentItem().data(Qt.UserRole) if self.book_list.currentItem() else "GEN"
        old_chapter = self.chapter_list.currentItem().data(Qt.UserRole) if self.chapter_list.currentItem() else 1
        self._loading = True
        for index in range(self.translation_list.count()):
            other = self.translation_list.item(index)
            other.setCheckState(Qt.Checked if other is item else Qt.Unchecked)
        self._loading = False
        self.settings.setValue("position/translation", item.data(Qt.UserRole))
        self.settings.setValue("translation", item.data(Qt.UserRole))
        self._populate_books(preferred_code=old_book, preferred_chapter=int(old_chapter))
        self.open_selected_chapter(focus_reading=False)
        self.statusBar().showMessage(f"Tradução marcada: {item.text()}.")

    def switch_testament(self, direction: int):
        """Mostra apenas Antigo à esquerda ou Novo à direita."""
        requested = self.OLD_TESTAMENT if direction < 0 else self.NEW_TESTAMENT
        if requested == self.testament:
            name = "Antigo Testamento" if requested == self.OLD_TESTAMENT else "Novo Testamento"
            self.statusBar().showMessage(f"A seção Livros já está no {name}.")
            return
        self.testament = requested
        self.settings.setValue("position/testament", requested)
        self._populate_books()
        self.book_list.setFocus()
        name = "Antigo Testamento" if requested == self.OLD_TESTAMENT else "Novo Testamento"
        self.statusBar().showMessage(f"{name}: {self.book_list.count()} livros exibidos.")

    def _populate_books(self, preferred_code=None, preferred_chapter=1):
        """Preenche somente os 39 ou 27 livros do testamento ativo."""
        books = self.db.books(self.current_translation_id())
        visible = [
            book for book in books
            if (int(book["book_number"]) <= 39) == (self.testament == self.OLD_TESTAMENT)
        ]
        self._loading = True
        self.book_list.clear()
        selected_row = 0
        for index, book in enumerate(visible):
            item = QListWidgetItem(book["book_name"])
            item.setData(Qt.UserRole, book["book_code"])
            item.setData(Qt.UserRole + 1, int(book["book_number"]))
            self.book_list.addItem(item)
            if book["book_code"] == preferred_code:
                selected_row = index
        self.book_list.setCurrentRow(selected_row)
        self.testament_label.setText(
            "Antigo Testamento — 39 livros" if self.testament == self.OLD_TESTAMENT
            else "Novo Testamento — 27 livros"
        )
        self._loading = False
        self._populate_chapters(preferred_chapter)

    def _book_highlighted(self, _current=None, _previous=None):
        """Atualiza capítulos quando a pessoa muda o livro com as setas."""
        if not self._loading:
            self._populate_chapters(1)

    def _populate_chapters(self, preferred_chapter=1):
        """Cria números de capítulo para o livro destacado."""
        item = self.book_list.currentItem()
        if not item:
            return
        total = self.db.chapter_count(self.current_translation_id(), item.data(Qt.UserRole))
        self._loading = True
        self.chapter_list.clear()
        for chapter in range(1, total + 1):
            chapter_item = QListWidgetItem(str(chapter))
            chapter_item.setData(Qt.UserRole, chapter)
            self.chapter_list.addItem(chapter_item)
        self.chapter_list.setCurrentRow(max(0, min(total - 1, int(preferred_chapter) - 1)))
        self._loading = False

    def focus_chapters(self):
        """Move o foco de Livros para Capítulos após Enter."""
        if self.chapter_list.count():
            self.chapter_list.setFocus()
            self.statusBar().showMessage(
                f"Capítulos de {self.book_list.currentItem().text()}. Escolha e pressione Enter."
            )

    def open_selected_chapter(self, focus_verse=None, focus_reading=True):
        """Define a referência ativa e carrega seu texto."""
        book_item = self.book_list.currentItem()
        chapter_item = self.chapter_list.currentItem()
        if not book_item or not chapter_item:
            return
        self.active_book_code = book_item.data(Qt.UserRole)
        self.active_book_name = book_item.text()
        self.active_chapter = int(chapter_item.data(Qt.UserRole))
        self.load_chapter(focus_verse)
        if focus_reading:
            self.verse_list.setFocus()

    # Leitura e posição -------------------------------------------------
    def load_chapter(self, focus_verse=None):
        """Monta a lista de leitura e registra a nova posição."""
        rows = self.db.chapter(
            self.current_translation_id(), self.active_book_code, self.active_chapter
        )
        self._loading = True
        self.verse_list.clear()
        selected_row = 0
        for index, row in enumerate(rows):
            # Na leitura aparece somente o número, sem a palavra “versículo”.
            item = QListWidgetItem(f"{row['verse']}. {row['text']}")
            item.setData(Qt.UserRole, row["text"])
            item.setData(Qt.UserRole + 1, row["verse"])
            self.verse_list.addItem(item)
            if focus_verse is not None and str(row["verse"]).split("-")[0] == str(focus_verse):
                selected_row = index
        if not rows:
            item = QListWidgetItem(
                "Capítulo indisponível nesta fonte. Consulte Licenças e leis ou escolha outra tradução."
            )
            item.setFlags(item.flags() & ~Qt.ItemIsSelectable)
            self.verse_list.addItem(item)
        else:
            # O item final faz o leitor de tela anunciar claramente o limite.
            maximum = self.db.chapter_count(
                self.current_translation_id(), self.active_book_code
            )
            ending = (
                "Fim do livro. Não há capítulos seguintes."
                if self.active_chapter >= maximum
                else "Fim do capítulo."
            )
            ending_item = QListWidgetItem(ending)
            ending_item.setData(Qt.UserRole, ending)
            ending_item.setData(Qt.UserRole + 1, None)
            ending_item.setData(Qt.UserRole + 2, "ending")
            self.verse_list.addItem(ending_item)
        if rows and self.verse_list.count():
            self.verse_list.setCurrentRow(selected_row)
        self._loading = False
        self.chapter_heading.setText(
            f"{self.active_book_name}, capítulo {self.active_chapter} — {self.current_translation_name()}"
        )
        self.settings.setValue("position/translation", self.current_translation_id())
        self.settings.setValue("position/testament", self.testament)
        self.settings.setValue("position/book", self.active_book_code)
        self.settings.setValue("position/chapter", self.active_chapter)
        if rows:
            self._verse_changed(self.verse_list.currentItem())
            current = self.verse_list.currentItem()
            self.user_data.record_reading(
                self.current_translation_id(), self.active_book_code,
                self.active_book_name, self.active_chapter,
                str(current.data(Qt.UserRole + 1)) if current else None,
            )
        else:
            self.reading_area.set_text(
                "Capítulo indisponível nesta fonte. Escolha outra tradução em Mais opções."
            )
            self.statusBar().showMessage(
                f"Capítulo indisponível na fonte: {self.active_book_name}, capítulo {self.active_chapter}."
            )

    def _verse_changed(self, current, _previous=None):
        """Salva o número atual, atualiza a leitura e anuncia marcador."""
        if self._loading or not current:
            return
        if current.data(Qt.UserRole + 2) == "ending":
            self.reading_area.set_text(current.data(Qt.UserRole))
            self.statusBar().showMessage(current.data(Qt.UserRole))
            return
        if current.data(Qt.UserRole + 1) is None:
            return
        verse = str(current.data(Qt.UserRole + 1))
        self.reading_area.set_text(
            f"{self.active_book_name} {self.active_chapter}:{verse}\n\n{current.data(Qt.UserRole)}"
        )
        self.settings.setValue("position/verse", verse)
        marked = self.user_data.is_bookmarked(
            self.current_translation_id(), self.active_book_code, self.active_chapter, verse
        )
        suffix = "; marcado" if marked else ""
        self.statusBar().showMessage(
            f"{self.active_book_name} {self.active_chapter}:{verse}{suffix}."
        )

    def go_to_reference(self):
        """Interpreta livro, capítulo, número ou intervalo e navega até o início."""
        text = self.reference_edit.text().strip()
        try:
            parsed = parse_reference(text)
        except ValueError as error:
            self._warn("Referência inválida", str(error))
            return
        book = self.db.resolve_book(self.current_translation_id(), parsed.book_query)
        if not book:
            self._warn("Livro não encontrado", f"Não foi possível localizar o livro “{parsed.book_query}”.")
            return
        maximum = self.db.chapter_count(self.current_translation_id(), book["book_code"])
        chapter = parsed.chapter
        if chapter > maximum:
            self._warn("Capítulo não encontrado", f"{book['book_name']} possui {maximum} capítulos.")
            return
        if parsed.verse_start and not self.db.passage(
            self.current_translation_id(), book["book_code"], chapter,
            parsed.verse_start, parsed.verse_end,
        ):
            self._warn("Passagem não encontrada", "Confira os números informados.")
            return
        self._show_location(book["book_code"], chapter, parsed.verse_start)
        self.reference_edit.clear()
        self.page_stack.setCurrentIndex(self.main_page_index)
        self.verse_list.setFocus()

    def _show_location(self, book_code: str, chapter: int, verse=None, focus_reading=True):
        """Sincroniza tradução, testamento, livro, capítulo e leitura."""
        books = self.db.books(self.current_translation_id())
        book = next(book for book in books if book["book_code"] == book_code)
        self.testament = self.OLD_TESTAMENT if int(book["book_number"]) <= 39 else self.NEW_TESTAMENT
        self._populate_books(preferred_code=book_code, preferred_chapter=chapter)
        self.open_selected_chapter(focus_verse=verse, focus_reading=focus_reading)

    def previous_chapter(self):
        """Vai ao capítulo anterior do mesmo livro ou anuncia o limite."""
        if not self.active_book_code:
            return
        if self.active_chapter > 1:
            self._show_location(self.active_book_code, self.active_chapter - 1)
        else:
            self._announce("Início do livro. Não há capítulos anteriores.")

    def next_chapter(self):
        """Vai ao capítulo seguinte do mesmo livro ou anuncia o fim do livro."""
        if not self.active_book_code:
            return
        maximum = self.db.chapter_count(self.current_translation_id(), self.active_book_code)
        if self.active_chapter < maximum:
            self._show_location(self.active_book_code, self.active_chapter + 1)
        else:
            self._announce("Fim do livro. Não há capítulos seguintes.")

    def change_chapter_from_reading(self, direction: int):
        """Conecta esquerda/direita da Leitura à navegação entre capítulos."""
        if direction < 0:
            self.previous_chapter()
        else:
            self.next_chapter()
        self.verse_list.setFocus()

    # Menu Aplicações ---------------------------------------------------
    def show_book_menu_at(self, position):
        """Seleciona o livro apontado pelo mouse antes de abrir suas ações."""
        item = self.book_list.itemAt(position)
        if item:
            self.book_list.setCurrentItem(item)
        self.show_book_menu(position)

    def show_book_menu(self, position=None):
        """Oferece resumo do livro num diálogo acessível por Espaço/Enter."""
        if not self.book_list.currentItem():
            return
        selected = ApplicationsDialog.choose(
            self,
            f"Menu do livro {self.book_list.currentItem().text()}",
            (
                ("Gerar resumo do livro com inteligência artificial", "ai_book"),
                ("Sobre este livro", "about_book"),
            ),
        )
        if selected == "ai_book":
            self.generate_ai_for_scope("book")
        elif selected == "about_book":
            book = next(
                dict(row) for row in self.db.books(self.current_translation_id())
                if row["book_code"] == self.book_list.currentItem().data(Qt.UserRole)
            )
            fake = QListWidgetItem(book["book_name"])
            fake.setData(Qt.UserRole, book)
            self.extended.open_book_info(fake)

    def show_chapter_menu_at(self, position):
        """Seleciona o capítulo apontado pelo mouse antes de abrir suas ações."""
        item = self.chapter_list.itemAt(position)
        if item:
            self.chapter_list.setCurrentItem(item)
        self.show_chapter_menu(position)

    def show_chapter_menu(self, position=None):
        """Oferece resumo do capítulo num diálogo acessível por Espaço/Enter."""
        if not self.chapter_list.currentItem():
            return
        selected = ApplicationsDialog.choose(
            self,
            f"Menu do capítulo {self.chapter_list.currentItem().text()}",
            (
                ("Gerar resumo do capítulo com inteligência artificial", "ai_chapter"),
                ("Adicionar capítulo aos favoritos", "favorite_chapter"),
                ("Criar anotação sobre o capítulo", "note_chapter"),
            ),
        )
        if selected == "ai_chapter":
            self.generate_ai_for_scope("chapter")
        elif selected == "favorite_chapter":
            book_item = self.book_list.currentItem()
            chapter = int(self.chapter_list.currentItem().data(Qt.UserRole))
            self.user_data.add_favorite(self.current_translation_id(), book_item.data(Qt.UserRole),
                                        book_item.text(), chapter, "*", "chapter")
            self._announce_for(self.chapter_list, "Capítulo adicionado aos favoritos.")
        elif selected == "note_chapter":
            self.create_chapter_note()

    def _current_verse_key(self):
        """Produz a chave composta usada pelos marcadores e ações do texto."""
        item = self.verse_list.currentItem()
        if not item or item.data(Qt.UserRole + 1) is None:
            return None
        return (
            self.current_translation_id(), self.active_book_code,
            self.active_chapter, str(item.data(Qt.UserRole + 1)),
        )

    def show_verse_menu_at(self, position):
        """Seleciona o item clicado antes de abrir seu menu."""
        item = self.verse_list.itemAt(position)
        if item:
            self.verse_list.setCurrentItem(item)
        self.show_verse_menu(position)

    def show_verse_menu(self, position=None):
        """Apresenta cópia, marcador, voz e IA em lista explicitamente acessível."""
        key = self._current_verse_key()
        if not key:
            self._warn(
                "Nenhum texto selecionado",
                "Abra um capítulo e selecione um item na seção Versículos.",
            )
            return
        marked = self.user_data.is_bookmarked(*key)
        marker_label = "Remover marcador" if marked else "Adicionar marcador"
        selected = ApplicationsDialog.choose(
            self,
            f"Menu do versículo {self.active_book_name} {self.active_chapter}:{key[3]}",
            (
                ("Gerar explicação do versículo com inteligência artificial", "ai_verse"),
                ("Copiar texto", "copy_text"),
                ("Copiar referência e texto", "copy_reference"),
                (marker_label, "bookmark"),
                ("Criar anotação", "note"),
                ("Adicionar ou remover da memorização", "memorize"),
                ("Ouvir com a voz interna", "speak"),
            ),
        )
        if selected == "copy_text":
            self.copy_verse_text()
        elif selected == "copy_reference":
            self.copy_verse_with_reference()
        elif selected == "bookmark":
            self.toggle_bookmark()
        elif selected == "note":
            self.create_note()
        elif selected == "memorize":
            self.extended.toggle_memory_current()
        elif selected == "speak":
            self.speak_current_verse()
        elif selected == "ai_verse":
            self.generate_ai_for_scope("verse")

    def copy_verse_text(self):
        """Copia apenas o texto, sem referência ou nome da edição."""
        item = self.verse_list.currentItem()
        if item and item.data(Qt.UserRole) is not None:
            QApplication.clipboard().setText(item.data(Qt.UserRole))
            self.statusBar().showMessage("Texto copiado.")

    def copy_verse_with_reference(self):
        """Copia referência, conteúdo e tradução para a área de transferência."""
        key = self._current_verse_key()
        item = self.verse_list.currentItem()
        if key and item:
            QApplication.clipboard().setText(
                f"{self.active_book_name} {self.active_chapter}:{key[3]} — {item.data(Qt.UserRole)} "
                f"({self.current_translation_name()})"
            )
            self.statusBar().showMessage("Referência e texto copiados.")

    def toggle_bookmark(self):
        """Adiciona ou remove o marcador da referência atual."""
        key = self._current_verse_key()
        if key:
            marked = self.user_data.toggle_bookmark(*key)
            if marked:
                self.user_data.enrich_favorite(key, self.active_book_name)
            self.statusBar().showMessage("Marcador adicionado." if marked else "Marcador removido.")
            self._verse_changed(self.verse_list.currentItem())

    def create_note(self):
        """Abre a edição e grava uma anotação vinculada ao versículo atual."""
        key = self._current_verse_key()
        item = self.verse_list.currentItem()
        if not key or not item:
            self._warn("Nenhum versículo selecionado", "Selecione um versículo antes de criar a anotação.")
            return
        reference = f"{self.active_book_name} {self.active_chapter}:{key[3]}"
        title, body, accepted = NoteEditorDialog.get_note(self, reference)
        if not accepted:
            return
        self.user_data.add_note(
            key[0], key[1], self.active_book_name, key[2], key[3],
            str(item.data(Qt.UserRole)), title, body,
        )
        self.statusBar().showMessage(
            "Anotação salva. Consulte Mais opções, Anotações por dia."
        )

    def create_chapter_note(self):
        """Grava uma anotação vinculada ao capítulo inteiro selecionado."""
        book_item = self.book_list.currentItem()
        chapter_item = self.chapter_list.currentItem()
        if not book_item or not chapter_item:
            return
        chapter = int(chapter_item.data(Qt.UserRole))
        reference = f"{book_item.text()} {chapter}"
        title, body, accepted = NoteEditorDialog.get_note(self, reference)
        if not accepted:
            return
        text = " ".join(
            f"{row['verse']}. {row['text']}"
            for row in self.db.chapter(self.current_translation_id(), book_item.data(Qt.UserRole), chapter)
        )
        self.user_data.add_note(self.current_translation_id(), book_item.data(Qt.UserRole),
                                book_item.text(), chapter, "*", text, title, body)
        self._announce_for(self.chapter_list, "Anotação do capítulo salva.")

    def create_passage_note(self):
        """Solicita uma referência e salva uma anotação sobre todo o intervalo."""
        reference, accepted = self.extended._text_prompt(
            "Anotar passagem", "Referência, por exemplo Salmos 23:1-6"
        )
        if not accepted:
            return
        resolved = self.extended.resolve_reference(reference)
        if not resolved:
            self._warn("Passagem não encontrada", "Confira a referência digitada.")
            return
        book, parsed, rows = resolved
        normalized = f"{book['book_name']} {parsed.chapter}"
        verse_key = "*"
        if parsed.verse_start:
            verse_key = parsed.verse_start
            normalized += f":{parsed.verse_start}"
            if parsed.verse_end:
                verse_key += f"-{parsed.verse_end}"
                normalized += f"-{parsed.verse_end}"
        title, body, confirmed = NoteEditorDialog.get_note(self, normalized)
        if not confirmed:
            return
        passage_text = " ".join(f"{row['verse']}. {row['text']}" for row in rows)
        self.user_data.add_note(self.current_translation_id(), book["book_code"], book["book_name"],
                                parsed.chapter, verse_key, passage_text, title, body)
        self.refresh_notes()
        self._announce_for(self.notes_list, "Anotação da passagem salva.")

    @staticmethod
    def _format_note_day(iso_day: str) -> str:
        """Transforma uma data ISO em descrição completa em português."""
        months = (
            "janeiro", "fevereiro", "março", "abril", "maio", "junho",
            "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
        )
        parsed = date.fromisoformat(iso_day)
        return f"{parsed.day} de {months[parsed.month - 1]} de {parsed.year}"

    def refresh_notes(self):
        """Recria a lista acessível agrupando títulos abaixo de cada dia."""
        self.notes_list.clear()
        grouped = defaultdict(list)
        for note in self.user_data.search_notes(self.notes_search.text()):
            grouped[note["created_at"][:10]].append(note)
        if not grouped:
            empty = QListWidgetItem("Nenhuma anotação salva.")
            empty.setFlags(empty.flags() & ~Qt.ItemIsSelectable)
            self.notes_list.addItem(empty)
            return
        for iso_day, notes in grouped.items():
            count = len(notes)
            noun = "anotação" if count == 1 else "anotações"
            label = f"{self._format_note_day(iso_day)} — {count} {noun}"
            day_item = QListWidgetItem(label)
            day_item.setData(Qt.UserRole, {"type": "day", "date": iso_day, "notes": notes})
            self.notes_list.addItem(day_item)
            for note in notes:
                title_item = QListWidgetItem(
                    f"Título: {note['title']}. Referência: {note['book_name']} {note['chapter']}:{note['verse']}"
                )
                title_item.setData(Qt.UserRole, {"type": "note", "note": note})
                self.notes_list.addItem(title_item)
        self.notes_list.setCurrentRow(0)

    @staticmethod
    def _note_text(note: dict) -> str:
        """Monta a visualização completa de uma anotação sem marcas visuais."""
        moment = datetime.fromisoformat(note["created_at"])
        return (
            f"{note['title']}\n\n"
            f"Referência: {note['book_name']} {note['chapter']}:{note['verse']}\n\n"
            f"Texto bíblico: {note['verse_text']}\n\n"
            f"Anotação: {note['body']}\n\n"
            f"Criada em {moment.strftime('%d/%m/%Y às %H:%M')}."
        )

    def open_note_item(self, item: QListWidgetItem):
        """Abre um título isolado ou todas as anotações do dia selecionado."""
        payload = item.data(Qt.UserRole)
        if not payload:
            return
        if payload["type"] == "day":
            title = f"Anotações de {self._format_note_day(payload['date'])}"
            text = "\n\n--------------------\n\n".join(
                self._note_text(note) for note in payload["notes"]
            )
        else:
            note = payload["note"]
            title = note["title"]
            text = self._note_text(note)
        AccessibleTextDialog(self, title, title, text).exec()

    def prepare_devotional(self):
        """Inicia um devocional com a referência bíblica selecionada no momento."""
        key = self._current_verse_key()
        item = self.verse_list.currentItem()
        if key and item:
            reference = f"{self.active_book_name} {self.active_chapter}:{key[3]}"
            self.devotional_reference.setText(reference)
            self._set_devotional_source(reference, str(item.data(Qt.UserRole)))
        if not self.devotional_editor.toPlainText().strip():
            self.devotional_editor.setPlainText(
                "Reflexão:\n\n\nAplicação para hoje:\n\n\nOração:\n"
            )

    def _set_devotional_source(self, reference: str, verse_text: str):
        """Atualiza fonte, título sugerido e texto bíblico do devocional."""
        self.devotional_reference.setText(reference)
        self.devotional_source.set_text(f"{reference}\n\n{verse_text}")
        self.devotional_source_text = verse_text
        suggested_title = f"Devocional sobre {reference}"
        current_title = self.devotional_title.text().strip()
        if not current_title or current_title == getattr(self, "_devotional_auto_title", ""):
            self.devotional_title.setText(suggested_title)
        self._devotional_auto_title = suggested_title

    def load_devotional_reference(self):
        """Resolve a referência digitada sem alterar a posição principal da Bíblia."""
        text = self.devotional_reference.text().strip()
        match = re.match(r"^(.+?)\s+(\d+):(\d+)$", text)
        if not match:
            self._warn("Referência inválida", "No devocional, use o formato João 3:16.")
            return
        book_query, chapter_text, verse = match.groups()
        book = self.db.resolve_book(self.current_translation_id(), book_query)
        if not book:
            self._warn("Livro não encontrado", f"Não foi possível localizar o livro “{book_query}”.")
            return
        chapter = int(chapter_text)
        rows = self.db.chapter(self.current_translation_id(), book["book_code"], chapter)
        row = next(
            (candidate for candidate in rows if str(candidate["verse"]).split("-")[0] == verse),
            None,
        )
        if not row:
            self._warn("Referência não encontrada", "Confira o capítulo e o número informados.")
            return
        reference = f"{book['book_name']} {chapter}:{row['verse']}"
        self._set_devotional_source(reference, row["text"])
        self.devotional_editor.setFocus()
        self.statusBar().showMessage(f"Texto carregado: {reference}.")

    def save_devotional(self):
        """Solicita um destino e salva o devocional completo como texto UTF-8."""
        title = self.devotional_title.text().strip() or "Meu devocional"
        body = self.devotional_editor.toPlainText().strip()
        reference = self.devotional_reference.text().strip()
        if not body:
            self._warn("Devocional vazio", "Escreva o devocional antes de salvar.")
            self.devotional_editor.setFocus()
            return
        safe_name = re.sub(r"[^\w -]+", "", title, flags=re.UNICODE).strip() or "devocional"
        suggested = Path.home() / f"{safe_name}.txt"
        filename, _filter = QFileDialog.getSaveFileName(
            self, "Salvar devocional", str(suggested), "Arquivo de texto (*.txt)"
        )
        if not filename:
            return
        content = (
            f"{title}\n\nReferência: {reference}\n\n"
            f"Texto bíblico: {getattr(self, 'devotional_source_text', '')}\n\n{body}\n"
        )
        try:
            Path(filename).write_text(content, encoding="utf-8")
        except OSError as error:
            self._warn("Não foi possível salvar", str(error))
            return
        self.statusBar().showMessage(f"Devocional salvo em {filename}.")
        QMessageBox.information(self, "Devocional salvo", f"Arquivo salvo em:\n{filename}")

    # Pesquisa e demais seções -----------------------------------------
    def perform_search(self):
        """Executa pesquisa local por palavras, frase, tema ou nome de livro."""
        query = self.search_edit.text().strip()
        if not query:
            self._warn("Pesquisa vazia", "Digite uma ou mais palavras para pesquisar.")
            return
        translation_id = self.search_translation.currentData()
        mode = self.search_mode.currentData()
        book_code = self.search_book.currentData()
        topic = resolve_topic(query) if mode in ("auto", "topic") else None
        if mode == "topic" and not topic:
            self._warn("Tema não encontrado", "Escolha um tema conhecido ou use a pesquisa automática.")
            return
        if topic:
            rows = self._thematic_search(translation_id, topic, book_code)
            effective_mode = "topic"
        elif mode == "book":
            book = self.db.resolve_book(translation_id, query)
            if not book:
                self._warn("Livro não encontrado", f"Não foi possível localizar o livro “{query}”.")
                return
            rows = [
                {"book_code": book["book_code"], "book_name": book["book_name"],
                 "chapter": row["chapter"], "verse": row["verse"], "text": row["text"]}
                for row in self.db.book(translation_id, book["book_code"])[:500]
            ]
            effective_mode = "book"
        else:
            rows = [dict(row) for row in self.db.search(
                translation_id, query, book_code=book_code, phrase=mode == "phrase"
            )]
            effective_mode = mode
        self.user_data.add_search(query, effective_mode, book_code)
        self.search_results.clear()
        for row in rows:
            item = QListWidgetItem(f"{row['book_name']} {row['chapter']}:{row['verse']} — {row['text']}")
            item.setData(Qt.UserRole, dict(row))
            self.search_results.addItem(item)
        suffix = " O limite de 500 resultados foi atingido." if len(rows) == 500 else ""
        self.search_status.setText(f"{len(rows)} resultados encontrados.{suffix}")
        self.statusBar().showMessage(self.search_status.text())
        if rows:
            self.search_results.setCurrentRow(0)
            self.search_results.setFocus()
        self.refresh_search_history()

    def _thematic_search(self, translation_id: str, topic, book_code=None):
        """Combina referências curadas e palavras relacionadas sem serviços pagos."""
        _name, data = topic
        found = {}
        for reference in data["references"]:
            try:
                parsed = parse_reference(reference)
            except ValueError:
                continue
            book = self.db.resolve_book(translation_id, parsed.book_query)
            if not book or (book_code and book["book_code"] != book_code):
                continue
            for row in self.db.passage(translation_id, book["book_code"], parsed.chapter,
                                       parsed.verse_start, parsed.verse_end):
                data_row = {"book_code": book["book_code"], "book_name": book["book_name"],
                            "chapter": parsed.chapter, "verse": row["verse"], "text": row["text"]}
                found[(book["book_code"], parsed.chapter, row["verse"])] = data_row
        for keyword in data["keywords"]:
            for row in self.db.search(translation_id, keyword, limit=50, book_code=book_code, phrase=" " in keyword):
                found.setdefault((row["book_code"], row["chapter"], row["verse"]), dict(row))
                if len(found) >= 500:
                    break
        return list(found.values())[:500]

    def refresh_search_history(self):
        """Mostra pesquisas recentes e permite repeti-las por Enter ou Espaço."""
        if not hasattr(self, "search_history_list"):
            return
        self.search_history_list.clear()
        for row in self.user_data.searches()[:20]:
            item = QListWidgetItem(f"{row['query']}. Tipo: {row['search_mode']}.")
            item.setData(Qt.UserRole, row)
            self.search_history_list.addItem(item)

    def repeat_search(self, item):
        """Restaura uma consulta do histórico e a executa novamente."""
        row = item.data(Qt.UserRole)
        self.search_edit.setText(row["query"])
        mode_index = self.search_mode.findData(row["search_mode"])
        self.search_mode.setCurrentIndex(max(0, mode_index))
        book_index = self.search_book.findData(row["book_code"])
        self.search_book.setCurrentIndex(max(0, book_index))
        self.perform_search()

    def copy_search_result(self):
        """Copia referência e texto do resultado destacado."""
        item = self.search_results.currentItem()
        if not item:
            return
        row = item.data(Qt.UserRole)
        QApplication.clipboard().setText(
            f"{row['book_name']} {row['chapter']}:{row['verse']} — {row['text']}"
        )
        self._announce_for(self.search_results, "Resultado copiado.")

    def favorite_search_result(self):
        """Adiciona o resultado destacado aos Favoritos gerais."""
        item = self.search_results.currentItem()
        if not item:
            return
        row = item.data(Qt.UserRole)
        self.user_data.add_favorite(
            self.search_translation.currentData(), row["book_code"], row["book_name"],
            row["chapter"], row["verse"], "verse",
        )
        self._announce_for(self.search_results, "Resultado adicionado aos favoritos.")

    def open_search_result(self, item):
        """Muda tradução e leitura para o resultado ativado."""
        row = item.data(Qt.UserRole)
        translation_id = self.search_translation.currentData()
        target = next(
            self.translation_list.item(i) for i in range(self.translation_list.count())
            if self.translation_list.item(i).data(Qt.UserRole) == translation_id
        )
        self.translation_list.setCurrentItem(target)
        self.select_current_translation()
        self._show_location(row["book_code"], int(row["chapter"]), row["verse"])
        self.page_stack.setCurrentIndex(self.main_page_index)
        self.verse_list.setFocus()

    # Configurações e IA -----------------------------------------------
    def _load_saved_api_key(self) -> str:
        """Recupera a chave criptografada, mantendo o aplicativo utilizável em caso de falha."""
        encrypted = self.settings.value("gemini/api_key_protected", "")
        if not encrypted:
            return ""
        try:
            return unprotect_text(str(encrypted))
        except SecureStoreError:
            return ""

    def _refresh_settings_options(self):
        """Atualiza as opções organizadas na única lista da seção."""
        selected = self.settings_options.currentRow()
        self.settings_options.clear()
        key_state = "configurada" if self.pending_api_key else "não configurada"
        model_name = next(
            (label for label, model_id in ModelDialog.MODELS if model_id == self.pending_ai_model),
            self.pending_ai_model,
        )
        entries = (
            (f"Colar ou alterar chave da API do Google — {key_state}", "api_key"),
            (f"Escolher modelo — {model_name}", "model"),
            (f"Detalhamento das respostas de IA — {self._choice_label(DETAIL_OPTIONS, self.pending_ai_detail)}", "detail"),
            (f"Tamanho do texto — {self.pending_font_size} pontos", "font_size"),
            (f"Voz SAPI — {self._voice_label(self.pending_voice_name)}", "voice"),
            (f"Velocidade da voz interna — {self._choice_label(SPEECH_RATE_OPTIONS, self.pending_speech_rate)}", "speech_rate"),
            (f"Alto contraste — {self._choice_label(CONTRAST_OPTIONS, self.pending_high_contrast)}", "high_contrast"),
            (f"Atualizações automáticas — {self._choice_label(UPDATE_AUTOMATIC_OPTIONS, self.pending_update_automatic)}", "update_automatic"),
            (f"Notificar nova versão — {self._choice_label(UPDATE_NOTIFICATION_OPTIONS, self.pending_update_notify)}", "update_notify"),
        )
        for label, option_id in entries:
            item = QListWidgetItem(label)
            item.setData(Qt.UserRole, option_id)
            self.settings_options.addItem(item)
        self.settings_options.setCurrentRow(max(0, selected))
        if hasattr(self, "get_api_key_button"):
            self.get_api_key_button.setVisible(not bool(self.pending_api_key))

    def open_settings_option(self, item: QListWidgetItem):
        """Abre o diálogo correspondente ao item ativado com Espaço ou Enter."""
        option_id = item.data(Qt.UserRole)
        if option_id == "api_key":
            key, accepted = ApiKeyDialog.get_key(self, self.pending_api_key)
            if accepted:
                self.pending_api_key = key
                self._refresh_settings_options()
            return
        if option_id == "model":
            value, accepted = ModelDialog.get_model(self, self.pending_ai_model)
        elif option_id == "detail":
            value, accepted = ChoiceDialog.get_choice(
                self, "Detalhamento das respostas de IA", DETAIL_OPTIONS, self.pending_ai_detail
            )
        elif option_id == "font_size":
            value, accepted = ChoiceDialog.get_choice(
                self, "Tamanho do texto", FONT_SIZE_OPTIONS, self.pending_font_size
            )
        elif option_id == "voice":
            value, accepted = ChoiceDialog.get_choice(
                self, "Escolher voz SAPI", self._voice_options(), self.pending_voice_name
            )
        elif option_id == "speech_rate":
            value, accepted = ChoiceDialog.get_choice(
                self, "Velocidade da voz interna", SPEECH_RATE_OPTIONS, self.pending_speech_rate
            )
        elif option_id == "update_automatic":
            value, accepted = ChoiceDialog.get_choice(
                self, "Verificação automática de atualizações",
                UPDATE_AUTOMATIC_OPTIONS, self.pending_update_automatic
            )
        elif option_id == "update_notify":
            value, accepted = ChoiceDialog.get_choice(
                self, "Notificação de nova versão",
                UPDATE_NOTIFICATION_OPTIONS, self.pending_update_notify
            )
        else:
            value, accepted = ChoiceDialog.get_choice(
                self, "Alto contraste", CONTRAST_OPTIONS, self.pending_high_contrast
            )
        if accepted:
            if option_id == "model":
                self.pending_ai_model = value
            elif option_id == "detail":
                self.pending_ai_detail = value
            elif option_id == "font_size":
                self.pending_font_size = int(value)
            elif option_id == "voice":
                self.pending_voice_name = str(value)
            elif option_id == "speech_rate":
                self.pending_speech_rate = float(value)
            elif option_id == "update_automatic":
                self.pending_update_automatic = bool(value)
            elif option_id == "update_notify":
                self.pending_update_notify = bool(value)
            else:
                self.pending_high_contrast = bool(value)
            self._refresh_settings_options()

    @staticmethod
    def _choice_label(options, current_value):
        """Encontra o rótulo legível associado a um valor de configuração."""
        return next((label for label, value in options if value == current_value), str(current_value))

    def _ensure_tts(self):
        """Inicializa uma única vez o mecanismo SAPI fornecido pelo Windows."""
        if not self._tts_checked:
            self._tts_checked = True
            if QTextToSpeech:
                self.tts = QTextToSpeech(self)
        return self.tts

    def _voice_options(self):
        """Lista vozes SAPI instaladas e oferece desativação explícita."""
        options = [
            ("Desativada — Enter repete pelo leitor de tela", VOICE_OFF),
            ("Voz padrão do Windows", ""),
        ]
        tts = self._ensure_tts()
        if tts:
            seen = set()
            for voice in tts.availableVoices():
                name = voice.name().strip()
                if name and name not in seen:
                    seen.add(name)
                    options.append((name, name))
        if self.pending_voice_name not in {value for _label, value in options}:
            options.append((f"Voz salva, indisponível agora — {self.pending_voice_name}", self.pending_voice_name))
        return tuple(options)

    @staticmethod
    def _voice_label(voice_name: str) -> str:
        """Apresenta o estado da voz sem precisar inicializar o sintetizador."""
        if voice_name == VOICE_OFF:
            return "desativada; Enter repete pelo leitor de tela"
        return voice_name or "padrão do Windows"

    def _apply_selected_voice(self):
        """Aplica a voz salva pelo nome estável exposto pelo SAPI."""
        if self.voice_name == VOICE_OFF:
            if self.tts:
                self.tts.stop()
            return
        tts = self._ensure_tts()
        if not tts or not self.voice_name:
            return
        for voice in tts.availableVoices():
            if voice.name() == self.voice_name:
                tts.setVoice(voice)
                break

    def open_google_api_keys_page(self):
        """Abre a página oficial do Google AI Studio para criar ou copiar uma chave."""
        opened = QDesktopServices.openUrl(QUrl(GOOGLE_API_KEYS_URL))
        message = (
            "Página de chaves do Google aberta no navegador."
            if opened
            else "Não foi possível abrir o navegador. Acesse aistudio.google.com/apikey."
        )
        self._announce_for(self.get_api_key_button, message)

    def save_ai_settings(self):
        """Salva chave, IA e preferências acessíveis para os próximos inícios."""
        try:
            encrypted = protect_text(self.pending_api_key) if self.pending_api_key else ""
        except SecureStoreError as error:
            self._warn("Não foi possível salvar", str(error))
            return
        self.settings.setValue("gemini/api_key_protected", encrypted)
        self.settings.setValue("gemini/model", self.pending_ai_model)
        self.settings.setValue("gemini/detail", self.pending_ai_detail)
        self.settings.setValue("accessibility/font_size", self.pending_font_size)
        self.settings.setValue("accessibility/voice_name", self.pending_voice_name)
        self.settings.setValue("accessibility/speech_rate", self.pending_speech_rate)
        self.settings.setValue("accessibility/high_contrast", self.pending_high_contrast)
        self.settings.setValue("updates/automatic", self.pending_update_automatic)
        self.settings.setValue("updates/notify", self.pending_update_notify)
        self.settings.sync()
        self.api_key = self.pending_api_key
        self.ai_model = self.pending_ai_model
        self.ai_detail = self.pending_ai_detail
        self.font_size = self.pending_font_size
        self.voice_name = self.pending_voice_name
        self.speech_rate = self.pending_speech_rate
        self.high_contrast = self.pending_high_contrast
        self.update_automatic = self.pending_update_automatic
        self.update_notify = self.pending_update_notify
        self._apply_accessibility_settings()
        self._refresh_settings_options()
        self._announce_for(self.settings_options, "Configurações salvas.")

    def _apply_accessibility_settings(self):
        """Aplica fonte, contraste e velocidade da voz sem recriar a interface."""
        font = self.font()
        font.setPointSize(max(8, min(28, int(self.font_size))))
        self.setFont(font)
        self.setStyleSheet(
            "QWidget { background: #000000; color: #ffffff; } "
            "QLineEdit, QListWidget, QComboBox { background: #000000; color: #ffffff; "
            "border: 2px solid #ffffff; } "
            "QPushButton { background: #000000; color: #ffffff; border: 2px solid #ffffff; padding: 5px; }"
            if self.high_contrast
            else ""
        )
        if self.tts:
            self.tts.setRate(self.speech_rate)
        self._apply_selected_voice()

    def generate_ai_for_scope(self, task: str):
        """Prepara o escopo do menu atual e inicia a requisição sem bloquear a interface."""
        if self._ai_busy:
            self.statusBar().showMessage("Aguarde a solicitação de IA atual terminar.")
            return
        if not self.api_key:
            self._warn(
                "Chave necessária",
                "Configure e salve sua chave da API do Google Gemini em Mais opções, Configurações.",
            )
            return
        prepared = self._prepare_ai_task(task)
        if prepared is None:
            return
        task_instruction, bible_text, title = prepared
        self._ai_result_title = title
        self._ai_busy = True
        source_widget = {
            "book": self.book_list,
            "chapter": self.chapter_list,
            "verse": self.verse_list,
        }[task]
        self._announce_for(
            source_widget,
            "Gerando conteúdo com o Google Gemini. Aguarde alguns instantes.",
        )
        threading.Thread(
            target=self._run_ai_request,
            args=(self.api_key, self.ai_model, task_instruction, bible_text, self.ai_detail),
            daemon=True,
        ).start()

    def _prepare_ai_task(self, task: str):
        """Monta instrução e texto do livro, capítulo ou item atualmente selecionado."""
        book_item = self.book_list.currentItem()
        chapter_item = self.chapter_list.currentItem()
        if not book_item:
            self._warn("Livro necessário", "Selecione um livro antes de usar a IA.")
            return None
        book_code = book_item.data(Qt.UserRole)
        book_name = book_item.text()
        if task == "book":
            word_limit = {"curto": 300, "médio": 550, "detalhado": 850}.get(
                self.ai_detail, 550
            )
            rows = self.db.book(self.current_translation_id(), book_code)
            text = "\n".join(
                f"Capítulo {row['chapter']}, {row['verse']}. {row['text']}" for row in rows
            )
            instruction = (
                f"Resuma o livro de {book_name}, destacando sua progressão e temas "
                "principais sem substituir a leitura do texto completo. Não faça um comentário "
                f"versículo por versículo. Use no máximo {word_limit} palavras, organize em poucos "
                "parágrafos e termine obrigatoriamente com uma conclusão completa."
            )
            return instruction, text, f"Resumo de {book_name}"
        if task == "chapter":
            if not chapter_item:
                self._warn("Capítulo necessário", "Selecione um capítulo antes de usar a IA.")
                return None
            chapter = int(chapter_item.data(Qt.UserRole))
            word_limit = {"curto": 180, "médio": 320, "detalhado": 500}.get(
                self.ai_detail, 320
            )
            rows = self.db.chapter(
                self.current_translation_id(), book_code, chapter
            )
            text = "\n".join(f"{row['verse']}. {row['text']}" for row in rows)
            instruction = (
                f"Resuma {book_name}, capítulo {chapter}, apresentando "
                f"a sequência do texto e suas ideias centrais em no máximo {word_limit} palavras. "
                "Termine obrigatoriamente com uma conclusão completa."
            )
            return instruction, text, f"Resumo de {book_name}, capítulo {chapter}"
        item = self.verse_list.currentItem()
        if not item or item.data(Qt.UserRole + 1) is None:
            self._warn("Texto necessário", "Selecione um número da seção Versículos para pedir a explicação.")
            return None
        number = item.data(Qt.UserRole + 1)
        instruction = (
            f"Explique {self.active_book_name} {self.active_chapter}:{number} em linguagem simples. "
            "Indique o sentido observável no texto e evite afirmar uma única interpretação doutrinária."
        )
        title = f"Explicação de {self.active_book_name} {self.active_chapter}:{number}"
        return instruction, f"{number}. {item.data(Qt.UserRole)}", title

    def _run_ai_request(self, api_key, model, instruction, bible_text, detail):
        """Executa a chamada de rede em uma thread de segundo plano."""
        try:
            result = create_bible_analysis(api_key, model, instruction, bible_text, detail)
        except GeminiServiceError as error:
            self.ai_signals.failed.emit(str(error))
        except Exception:
            self.ai_signals.failed.emit("Ocorreu uma falha inesperada ao gerar o conteúdo de IA.")
        else:
            self.ai_signals.finished.emit(result)

    def _ai_finished(self, result: str):
        """Apresenta o resultado em um diálogo navegável e acessível."""
        self._ai_busy = False
        self.statusBar().showMessage("Conteúdo de IA concluído.")
        AccessibleResultDialog(self, self._ai_result_title, result).exec()

    def _ai_failed(self, message: str):
        """Restaura os controles e anuncia uma falha segura da API."""
        self._ai_busy = False
        self._warn("Falha na inteligência artificial", message)

    # Voz e utilidades --------------------------------------------------
    def speak_current_verse(self):
        """Usa a voz SAPI ou repete o item para o leitor de tela quando desligada."""
        item = self.verse_list.currentItem()
        if not item or item.data(Qt.UserRole) is None:
            return
        spoken_text = (
            str(item.data(Qt.UserRole))
            if item.data(Qt.UserRole + 2) == "ending"
            else f"{item.data(Qt.UserRole + 1)}. {item.data(Qt.UserRole)}"
        )
        if self.voice_name == VOICE_OFF:
            self._announce_for(self.verse_list, spoken_text)
            return
        tts = self._ensure_tts()
        if not tts:
            self._announce_for(self.verse_list, spoken_text)
            return
        self._apply_selected_voice()
        tts.setRate(self.speech_rate)
        tts.stop()
        tts.say(spoken_text)

    def stop_speech(self):
        """Interrompe imediatamente a síntese de voz, quando ativa."""
        if self.tts:
            self.tts.stop()

    def change_font_size(self, amount: int):
        """Ajusta temporariamente o tamanho global dentro de limites utilizáveis."""
        self.font_size = max(8, min(28, int(self.font_size) + amount))
        self.pending_font_size = self.font_size
        self._apply_accessibility_settings()
        self._refresh_settings_options()
        self.statusBar().showMessage(f"Tamanho do texto: {self.font_size} pontos.")

    def _focus_reference(self):
        """Abre a tela de referência pelo Ctrl+L e seleciona seu conteúdo."""
        self.open_more_option("reference")
        self.reference_edit.setFocus()
        self.reference_edit.selectAll()

    def _focus_search(self):
        """Abre a tela de pesquisa pelo Ctrl+F."""
        self.open_more_option("search")
        self.search_edit.setFocus()
        self.search_edit.selectAll()

    def _focus_help(self):
        """F1 abre a ajuda como única tela visível."""
        self.open_more_option("help")
        self.help_text.setFocus()
        if self.help_text.count():
            self.help_text.setCurrentRow(0)

    def check_updates_now(self):
        """Solicita verificação imediata pela opção da Ajuda."""
        if hasattr(self, "update_controller"):
            self.update_controller.check_now(manual=True)

    def show_current_changelog(self):
        """Abre as novidades locais da versão instalada."""
        if hasattr(self, "update_controller"):
            self.update_controller.show_current_changelog()

    def _handle_escape(self):
        """Volta das telas internas ou, na Bíblia, interrompe a voz."""
        if self.page_stack.currentIndex() != self.main_page_index:
            self.show_main_page()
        else:
            self.stop_speech()

    def _warn(self, title: str, message: str):
        """Exibe mensagens operacionais em diálogo nativo acessível."""
        QMessageBox.warning(self, title, message)

    def _announce(self, message: str):
        """Envia uma mensagem imediata ao leitor de tela e à barra de estado."""
        self._announce_for(self.verse_list, message)

    def _announce_for(self, widget: QWidget, message: str):
        """Anuncia uma mensagem usando o controle relacionado como origem acessível."""
        self.statusBar().showMessage(message)
        QAccessible.updateAccessibility(
            QAccessibleAnnouncementEvent(widget, message)
        )

    def closeEvent(self, event):
        """Libera voz e bancos antes de confirmar o fechamento."""
        self.stop_speech()
        self.db.close()
        self.user_data.close()
        super().closeEvent(event)
