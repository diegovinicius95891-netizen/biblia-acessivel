"""Interface principal acessível, organizada em uma página única de seções.

As classes de lista especializam somente o comportamento do teclado. A classe
``MainWindow`` coordena apresentação, navegação, persistência e ações sobre o
texto selecionado. Regras de dados permanecem nos módulos ``database`` e
``user_data``.
"""

from __future__ import annotations

import re
import threading
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
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QScrollArea,
    QVBoxLayout,
    QWidget,
)

from .database import BibleDatabase
from .dialogs import (
    AccessibleResultDialog,
    AccessibleButton,
    ActivatableList,
    ApiKeyDialog,
    ApplicationsDialog,
    ChoiceDialog,
    ModelDialog,
)
from .gemini_client import GeminiServiceError, create_bible_analysis
from .legal import LEGAL_TEXT
from .secure_store import SecureStoreError, protect_text, unprotect_text
from .user_data import UserDataDatabase

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

    def __init__(self, database_path: Path):
        """Abre bancos, constrói controles e restaura a última posição."""
        super().__init__()
        self.db = BibleDatabase(database_path)
        self.user_data = UserDataDatabase(database_path.with_name("user_data.db"))
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
        self.high_contrast = self.settings.value("accessibility/high_contrast", False, type=bool)
        self.pending_high_contrast = self.high_contrast
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

        self.setWindowTitle("Bíblia Acessível")
        self.resize(1100, 760)
        self.setAccessibleName("Bíblia Acessível")
        self._build_ui()
        self._build_menu()
        self._build_shortcuts()
        self._apply_accessibility_settings()
        self._load_translations_and_restore()
        self.statusBar().showMessage(
            "Pronto. Tab muda de seção; setas navegam dentro da seção. F1 abre a ajuda."
        )

    # Interface ---------------------------------------------------------
    def _build_ui(self):
        """Cria uma única página rolável; não há abas nem páginas ocultas."""
        self.scroll_area = QScrollArea()
        self.scroll_area.setWidgetResizable(True)
        self.scroll_area.setAccessibleName("Seções do aplicativo")
        self.sections_widget = QWidget()
        self.sections_layout = QVBoxLayout(self.sections_widget)
        self.sections_layout.addWidget(self._build_reader_section())
        self.sections_layout.addWidget(self._build_search_section())
        self.sections_layout.addWidget(self._build_settings_section())
        self.sections_layout.addWidget(self._build_legal_section())
        self.sections_layout.addWidget(self._build_help_section())
        self.sections_layout.addStretch(1)
        self.scroll_area.setWidget(self.sections_widget)
        self.setCentralWidget(self.scroll_area)

    def _build_reader_section(self):
        """Monta as seções de seleção e leitura na ordem natural de Tab."""
        section = QWidget()
        layout = QVBoxLayout(section)
        selection_row = QHBoxLayout()

        translation_group = QGroupBox("Seção &Traduções")
        translation_layout = QVBoxLayout(translation_group)
        translation_layout.addWidget(QLabel("Cima/baixo navegam; Espaço ou Enter marca a tradução."))
        self.translation_list = TranslationList()
        self.translation_list.setAccessibleName("Seção Traduções, caixas de seleção")
        self.translation_list.setAccessibleDescription(
            "Use cima e baixo para navegar. Pressione Espaço ou Enter para marcar uma tradução."
        )
        self.translation_list.chooseRequested.connect(self.select_current_translation)
        self.translation_list.itemClicked.connect(lambda _item: self.select_current_translation())
        translation_layout.addWidget(self.translation_list)
        translation_group.setFocusProxy(self.translation_list)
        selection_row.addWidget(translation_group, 1)

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
        layout.addLayout(selection_row, 1)

        reading_group = QGroupBox("Seção &Leitura")
        reading_layout = QVBoxLayout(reading_group)
        self.chapter_heading = QLabel("Escolha um livro e um capítulo")
        self.chapter_heading.setAccessibleName("Título da leitura atual")
        heading_font = self.chapter_heading.font()
        heading_font.setBold(True)
        heading_font.setPointSize(heading_font.pointSize() + 2)
        self.chapter_heading.setFont(heading_font)
        reading_layout.addWidget(self.chapter_heading)
        self.verse_list = VerseList()
        self.verse_list.setAccessibleName("Seção Leitura, versículos")
        self.verse_list.setAccessibleDescription(
            "Cima e baixo navegam. Esquerda e direita mudam o capítulo. "
            "Enter lê em voz alta. Aplicações ou Shift F10 abre as ações do texto."
        )
        self.verse_list.activateRequested.connect(self.speak_current_verse)
        self.verse_list.itemDoubleClicked.connect(lambda _item: self.speak_current_verse())
        self.verse_list.currentItemChanged.connect(self._verse_changed)
        self.verse_list.chapterRequested.connect(self.change_chapter_from_reading)
        self.verse_list.applicationsRequested.connect(self.show_verse_menu)
        self.verse_list.setContextMenuPolicy(Qt.CustomContextMenu)
        self.verse_list.customContextMenuRequested.connect(self.show_verse_menu_at)
        reading_layout.addWidget(self.verse_list, 1)
        reading_group.setFocusProxy(self.verse_list)
        layout.addWidget(reading_group, 2)

        reference_group = QGroupBox("Referência direta — atalho Ctrl+L")
        reference_layout = QHBoxLayout(reference_group)
        self.reference_edit = QLineEdit()
        self.reference_edit.setAccessibleName("Ir para referência")
        self.reference_edit.setPlaceholderText("Exemplo: João 3:16")
        self.reference_edit.returnPressed.connect(self.go_to_reference)
        go_button = AccessibleButton("&Ir")
        go_button.setFocusPolicy(Qt.NoFocus)
        go_button.clicked.connect(self.go_to_reference)
        reference_layout.addWidget(self.reference_edit)
        reference_layout.addWidget(go_button)
        layout.addWidget(reference_group)

        QWidget.setTabOrder(self.translation_list, self.book_list)
        QWidget.setTabOrder(self.book_list, self.chapter_list)
        QWidget.setTabOrder(self.chapter_list, self.verse_list)
        QWidget.setTabOrder(self.verse_list, self.reference_edit)
        return section

    def _build_search_section(self):
        """Cria a pesquisa como seção da página única."""
        section = QGroupBox("Seção &Pesquisa")
        layout = QVBoxLayout(section)
        form = QFormLayout()
        self.search_translation = QComboBox()
        self.search_translation.setAccessibleName("Tradução para pesquisa")
        form.addRow("&Tradução:", self.search_translation)
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
        """Mantém a ajuda acessível como a última seção da mesma página."""
        section = QGroupBox("Seção A&juda")
        layout = QVBoxLayout(section)
        self.help_text = ReadingTextList("Ajuda e atalhos")
        self.help_text.set_text(
            "BÍBLIA ACESSÍVEL\n\n"
            "NAVEGAÇÃO POR SEÇÕES\n"
            "Tab e Shift+Tab percorrem as seções. Cima e baixo navegam dentro da seção atual.\n"
            "Em Traduções, Espaço ou Enter marca uma edição. Em Livros, esquerda mostra o Antigo "
            "Testamento e direita mostra o Novo. Enter em Livros leva aos capítulos; Enter em "
            "Capítulos abre a leitura.\n\n"
            "INTELIGÊNCIA ARTIFICIAL\n"
            "Aplicações ou Shift+F10 abre uma lista acessível. Cima e baixo navegam; Espaço ou "
            "Enter executa. Em Livros há resumo do livro; em Capítulos, resumo do capítulo; e "
            "na Leitura, explicação do número selecionado.\n\n"
            "ATALHOS\n"
            "Ctrl+L: referência. Ctrl+F: pesquisa. Ctrl+Seta esquerda/direita: capítulo anterior ou "
            "seguinte. F5: ouvir. Ctrl+Alt+C: copiar texto. Ctrl+Alt+R: "
            "copiar referência e texto. Ctrl+Alt+M: marcador. Escape: parar voz. F1: ajuda.\n\n"
            "CONTINUIDADE E PRIVACIDADE\n"
            "A posição e os marcadores permanecem locais. Em Configurações, cima e baixo navegam "
            "por chave, modelo, detalhamento, fonte, voz e contraste. Espaço ou Enter abre a opção; "
            "Tab leva aos botões e Espaço ou Enter os aciona. "
            "A chave do Google é gravada criptografada para a conta atual do Windows."
        )
        self.help_text.setMinimumHeight(180)
        layout.addWidget(self.help_text)
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
        self.action_copy_text.setShortcut("Ctrl+Alt+C")
        self.action_copy_text.triggered.connect(self.copy_verse_text)
        item_menu.addAction(self.action_copy_text)
        self.action_copy_reference = QAction("Copiar &referência e texto", self)
        self.action_copy_reference.setShortcut("Ctrl+Alt+R")
        self.action_copy_reference.triggered.connect(self.copy_verse_with_reference)
        item_menu.addAction(self.action_copy_reference)
        item_menu.addSeparator()
        self.action_bookmark = QAction("Adicionar ou remover &marcador", self)
        self.action_bookmark.setShortcut("Ctrl+Alt+M")
        self.action_bookmark.triggered.connect(self.toggle_bookmark)
        item_menu.addAction(self.action_bookmark)
        self.action_speak = QAction("&Ouvir", self)
        self.action_speak.triggered.connect(self.speak_current_verse)
        item_menu.addAction(self.action_speak)

        help_menu = self.menuBar().addMenu("A&juda")
        help_action = QAction("Ajuda e atalhos", self)
        help_action.setShortcut(QKeySequence.HelpContents)
        help_action.triggered.connect(self._focus_help)
        help_menu.addAction(help_action)

    def _build_shortcuts(self):
        """Registra atalhos globais sem substituir comandos de edição comuns."""
        QShortcut(QKeySequence("Ctrl+L"), self, activated=self._focus_reference)
        QShortcut(QKeySequence("Ctrl+F"), self, activated=self._focus_search)
        QShortcut(QKeySequence("F5"), self, activated=self.speak_current_verse)
        QShortcut(QKeySequence("Escape"), self, activated=self.stop_speech)
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
        self._update_tab_order()

    def _update_tab_order(self):
        """Garante uma rota linear entre todas as seções da página."""
        chain: list[QWidget] = [
            self.translation_list,
            self.book_list,
            self.chapter_list,
            self.verse_list,
            self.reference_edit,
        ]
        chain.extend(
            (
                self.search_translation,
                self.search_edit,
                self.search_button,
                self.search_results,
                self.settings_options,
                self.get_api_key_button,
                self.save_settings_button,
                self.legal_text,
                self.help_text,
            )
        )
        for current, following in zip(chain, chain[1:]):
            QWidget.setTabOrder(current, following)

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
        else:
            self.statusBar().showMessage(
                f"Capítulo indisponível na fonte: {self.active_book_name}, capítulo {self.active_chapter}."
            )

    def _verse_changed(self, current, _previous=None):
        """Salva o número atual e anuncia a presença de marcador."""
        if self._loading or not current:
            return
        if current.data(Qt.UserRole + 2) == "ending":
            self.statusBar().showMessage(current.data(Qt.UserRole))
            return
        if current.data(Qt.UserRole + 1) is None:
            return
        verse = str(current.data(Qt.UserRole + 1))
        self.settings.setValue("position/verse", verse)
        marked = self.user_data.is_bookmarked(
            self.current_translation_id(), self.active_book_code, self.active_chapter, verse
        )
        suffix = "; marcado" if marked else ""
        self.statusBar().showMessage(
            f"{self.active_book_name} {self.active_chapter}:{verse}{suffix}."
        )

    def go_to_reference(self):
        """Interpreta referências como João 3:16 e navega até elas."""
        text = self.reference_edit.text().strip()
        match = re.match(r"^(.+?)\s+(\d+)(?::(\d+))?$", text)
        if not match:
            self._warn("Referência inválida", "Use o formato João 3:16 ou Gênesis 1.")
            return
        book_query, chapter_text, verse = match.groups()
        book = self.db.resolve_book(self.current_translation_id(), book_query)
        if not book:
            self._warn("Livro não encontrado", f"Não foi possível localizar o livro “{book_query}”.")
            return
        maximum = self.db.chapter_count(self.current_translation_id(), book["book_code"])
        chapter = int(chapter_text)
        if chapter > maximum:
            self._warn("Capítulo não encontrado", f"{book['book_name']} possui {maximum} capítulos.")
            return
        self._show_location(book["book_code"], chapter, verse)
        self.reference_edit.clear()

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
            (("Gerar resumo do livro com inteligência artificial", "ai_book"),),
        )
        if selected == "ai_book":
            self.generate_ai_for_scope("book")

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
            (("Gerar resumo do capítulo com inteligência artificial", "ai_chapter"),),
        )
        if selected == "ai_chapter":
            self.generate_ai_for_scope("chapter")

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
                "Abra um capítulo e selecione um item na seção Leitura.",
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
                ("Ouvir com a voz interna", "speak"),
            ),
        )
        if selected == "copy_text":
            self.copy_verse_text()
        elif selected == "copy_reference":
            self.copy_verse_with_reference()
        elif selected == "bookmark":
            self.toggle_bookmark()
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
            self.statusBar().showMessage("Marcador adicionado." if marked else "Marcador removido.")
            self._verse_changed(self.verse_list.currentItem())

    # Pesquisa e demais seções -----------------------------------------
    def perform_search(self):
        """Executa pesquisa local e apresenta até quinhentos resultados."""
        query = self.search_edit.text().strip()
        if not query:
            self._warn("Pesquisa vazia", "Digite uma ou mais palavras para pesquisar.")
            return
        rows = self.db.search(self.search_translation.currentData(), query)
        self.search_results.clear()
        for row in rows:
            item = QListWidgetItem(f"{row['book_name']} {row['chapter']}:{row['verse']}. {row['text']}")
            item.setData(Qt.UserRole, dict(row))
            self.search_results.addItem(item)
        suffix = " O limite de 500 resultados foi atingido." if len(rows) == 500 else ""
        self.search_status.setText(f"{len(rows)} resultados encontrados.{suffix}")
        self.statusBar().showMessage(self.search_status.text())
        if rows:
            self.search_results.setCurrentRow(0)
            self.search_results.setFocus()

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
            (f"Velocidade da voz interna — {self._choice_label(SPEECH_RATE_OPTIONS, self.pending_speech_rate)}", "speech_rate"),
            (f"Alto contraste — {self._choice_label(CONTRAST_OPTIONS, self.pending_high_contrast)}", "high_contrast"),
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
        elif option_id == "speech_rate":
            value, accepted = ChoiceDialog.get_choice(
                self, "Velocidade da voz interna", SPEECH_RATE_OPTIONS, self.pending_speech_rate
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
            elif option_id == "speech_rate":
                self.pending_speech_rate = float(value)
            else:
                self.pending_high_contrast = bool(value)
            self._refresh_settings_options()

    @staticmethod
    def _choice_label(options, current_value):
        """Encontra o rótulo legível associado a um valor de configuração."""
        return next((label for label, value in options if value == current_value), str(current_value))

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
        self.settings.setValue("accessibility/speech_rate", self.pending_speech_rate)
        self.settings.setValue("accessibility/high_contrast", self.pending_high_contrast)
        self.settings.sync()
        self.api_key = self.pending_api_key
        self.ai_model = self.pending_ai_model
        self.ai_detail = self.pending_ai_detail
        self.font_size = self.pending_font_size
        self.speech_rate = self.pending_speech_rate
        self.high_contrast = self.pending_high_contrast
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

    def generate_ai_for_scope(self, task: str):
        """Prepara o escopo do menu atual e inicia a requisição sem bloquear a interface."""
        if self._ai_busy:
            self.statusBar().showMessage("Aguarde a solicitação de IA atual terminar.")
            return
        if not self.api_key:
            self._warn(
                "Chave necessária",
                "Configure e salve sua chave da API do Google Gemini na seção Configurações.",
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
            rows = self.db.book(self.current_translation_id(), book_code)
            text = "\n".join(
                f"Capítulo {row['chapter']}, {row['verse']}. {row['text']}" for row in rows
            )
            instruction = (
                f"Resuma o livro de {book_name}, destacando sua progressão e temas "
                "principais sem substituir a leitura do texto completo."
            )
            return instruction, text, f"Resumo de {book_name}"
        if task == "chapter":
            if not chapter_item:
                self._warn("Capítulo necessário", "Selecione um capítulo antes de usar a IA.")
                return None
            chapter = int(chapter_item.data(Qt.UserRole))
            rows = self.db.chapter(
                self.current_translation_id(), book_code, chapter
            )
            text = "\n".join(f"{row['verse']}. {row['text']}" for row in rows)
            instruction = (
                f"Resuma {book_name}, capítulo {chapter}, apresentando "
                "a sequência do texto e suas ideias centrais."
            )
            return instruction, text, f"Resumo de {book_name}, capítulo {chapter}"
        item = self.verse_list.currentItem()
        if not item or item.data(Qt.UserRole + 1) is None:
            self._warn("Texto necessário", "Selecione um número da seção Leitura para pedir a explicação.")
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
        """Usa a voz do sistema sem pronunciar a palavra 'versículo'."""
        item = self.verse_list.currentItem()
        if not item or item.data(Qt.UserRole) is None:
            return
        if not self._tts_checked:
            self._tts_checked = True
            if QTextToSpeech:
                self.tts = QTextToSpeech(self)
        if not self.tts:
            self.statusBar().showMessage("A voz interna não está disponível; use o leitor de tela.")
            return
        self.tts.setRate(self.speech_rate)
        self.tts.stop()
        if item.data(Qt.UserRole + 2) == "ending":
            self.tts.say(item.data(Qt.UserRole))
        else:
            # Somente o número, como aparece na seção Leitura.
            self.tts.say(f"{item.data(Qt.UserRole + 1)}. {item.data(Qt.UserRole)}")

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
        """Leva Ctrl+L ao campo de referência e seleciona seu conteúdo."""
        self.reference_edit.setFocus()
        self.reference_edit.selectAll()

    def _focus_search(self):
        """Leva Ctrl+F ao campo de pesquisa na página única."""
        self.search_edit.setFocus()
        self.search_edit.selectAll()

    def _focus_help(self):
        """F1 leva diretamente ao texto da ajuda na página única."""
        self.help_text.setFocus()
        if self.help_text.count():
            self.help_text.setCurrentRow(0)

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
