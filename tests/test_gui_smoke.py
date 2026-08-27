"""Testes rápidos de teclado, acessibilidade e integração da janela."""

import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
os.environ.setdefault("BIBLIA_DISABLE_AUTO_UPDATE", "1")

try:
    from PySide6.QtCore import Qt
    from PySide6.QtTest import QTest
    from PySide6.QtWidgets import QApplication, QDialog, QLineEdit
    from biblia.dialogs import ApiKeyDialog, ApplicationsDialog, ModelDialog, NoteEditorDialog
    from biblia.main_window import BookList, ChapterList, MainWindow, VerseList, VOICE_OFF
    from biblia.user_data import UserDataDatabase
except ImportError:  # Permite validar o banco antes da instalação da interface.
    QApplication = None
    MainWindow = None


DB_PATH = Path(__file__).resolve().parents[1] / "data" / "biblia.db"


@unittest.skipIf(QApplication is None, "PySide6 não instalado")
class GuiSmokeTests(unittest.TestCase):
    """Exercita a interface com a plataforma Qt fora da tela."""

    @classmethod
    def setUpClass(cls):
        """Compartilha uma única aplicação Qt entre os testes."""
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        """Cria um banco pessoal isolado para cada teste de interface."""
        self._temporary = TemporaryDirectory()
        self._windows = []

    def tearDown(self):
        """Descarta os dados produzidos pelo cenário."""
        for window in self._windows:
            window.close()
        self.app.processEvents()
        self._temporary.cleanup()

    def make_window(self):
        """Abre a janela sem tocar no banco pessoal real do projeto."""
        window = MainWindow(DB_PATH, Path(self._temporary.name) / "user_data.db")
        self._windows.append(window)
        return window

    def test_clean_main_screen_navigation_and_secondary_pages(self):
        """Valida as cinco seções principais e telas internas acionadas por Mais opções."""
        window = self.make_window()
        self.assertFalse(hasattr(window, "tabs"))
        self.assertIs(window.page_stack, window.centralWidget())
        self.assertEqual(window.main_page_index, window.page_stack.currentIndex())
        self.assertEqual(22, window.more_options.count())
        self.assertEqual(4, window.translation_list.count())
        self.assertEqual(39, window.book_list.count())

        window.book_list.setFocus()
        QTest.keyClick(window.book_list, Qt.Key_Right)
        self.assertEqual(27, window.book_list.count())
        self.assertIn("Novo Testamento", window.testament_label.text())
        QTest.keyClick(window.book_list, Qt.Key_Left)
        self.assertEqual(39, window.book_list.count())

        window.reference_edit.setText("João 3:16")
        window.go_to_reference()
        self.assertEqual("JHN", window.book_list.currentItem().data(Qt.UserRole))
        self.assertEqual(3, window.active_chapter)
        self.assertTrue(window.verse_list.currentItem().text().startswith("16. "))
        self.assertNotIn("Versículo", window.verse_list.currentItem().text())
        self.assertIn("João 3:16", window.reading_area.item(0).text())
        self.assertEqual("Fim do capítulo.", window.verse_list.item(window.verse_list.count() - 1).text())

        window.verse_list.setFocus()
        QTest.keyClick(window.verse_list, Qt.Key_Right)
        self.assertEqual(4, window.active_chapter)
        QTest.keyClick(window.verse_list, Qt.Key_Left)
        self.assertEqual(3, window.active_chapter)

        window.search_edit.setText("bom pastor")
        window.perform_search()
        self.assertGreater(window.search_results.count(), 0)

        legal_content = " ".join(
            window.legal_text.item(index).text()
            for index in range(window.legal_text.count())
        )
        self.assertIn("LEGISLAÇÃO BRASILEIRA", legal_content)
        self.assertIn("CC BY-SA 4.0", legal_content)
        self.assertGreater(window.help_text.count(), 1)
        window.more_options.setCurrentRow(7)
        QTest.keyClick(window.more_options, Qt.Key_Space)
        self.assertNotEqual(window.main_page_index, window.page_stack.currentIndex())
        window._handle_escape()
        self.assertEqual(window.main_page_index, window.page_stack.currentIndex())
        window.close()

    def test_end_of_book_is_announced_and_does_not_cross_books(self):
        """Mantém direita no último capítulo e adiciona a mensagem terminal."""
        window = self.make_window()
        window._show_location("JHN", 21, focus_reading=False)
        ending = window.verse_list.item(window.verse_list.count() - 1)
        self.assertEqual("Fim do livro. Não há capítulos seguintes.", ending.text())
        QTest.keyClick(window.verse_list, Qt.Key_Right)
        self.assertEqual("JHN", window.active_book_code)
        self.assertEqual(21, window.active_chapter)
        window.close()

    def test_extended_pages_are_registered_and_keyboard_accessible(self):
        """Abre cada novo recurso e valida nomes, foco e busca temática local."""
        window = self.make_window()
        page_ids = (
            "dashboard", "plans", "prayers", "daily_devotional", "favorites",
            "history", "topics", "books_info", "characters", "memorization",
            "quiz", "statistics", "worship", "backup",
        )
        for page_id in page_ids:
            with self.subTest(page=page_id):
                self.assertIn(page_id, window.secondary_pages)
                window.open_more_option(page_id)
                self.app.processEvents()
                self.assertNotEqual(window.main_page_index, window.page_stack.currentIndex())
                self.assertTrue(window.focusWidget().accessibleName())
        window.open_more_option("search")
        window.search_edit.setText("ansiedade")
        window.search_mode.setCurrentIndex(window.search_mode.findData("topic"))
        window.perform_search()
        self.assertGreater(window.search_results.count(), 0)
        self.assertEqual("ansiedade", window.user_data.searches()[0]["query"])
        window.close()

    def test_applications_key_is_detected(self):
        """Garante que Aplicações funciona em livros, capítulos e leitura."""
        for list_type in (BookList, ChapterList, VerseList):
            widget = list_type()
            requested = []
            widget.applicationsRequested.connect(lambda: requested.append(True))
            QTest.keyClick(widget, Qt.Key_Menu)
            self.assertEqual([True], requested)

        book_list = BookList()
        chapters = []
        book_list.chaptersRequested.connect(lambda: chapters.append(True))
        QTest.keyClick(book_list, Qt.Key_Space)
        self.assertEqual([True], chapters)

        verse_list = VerseList()
        activations = []
        verse_list.activateRequested.connect(lambda: activations.append(True))
        QTest.keyClick(verse_list, Qt.Key_Space)
        QTest.keyClick(verse_list, Qt.Key_Return)
        self.assertEqual([True, True], activations)

    def test_settings_ai_and_notes_are_accessible(self):
        """Confere configurações, tarefas de IA e a nova rota para anotações."""
        window = self.make_window()
        self.assertTrue(hasattr(window, "action_create_note"))
        self.assertEqual("Anotações organizadas por dia", window.notes_list.accessibleName())
        self.assertEqual("Opções de configurações", window.settings_options.accessibleName())
        self.assertEqual(9, window.settings_options.count())
        self.assertIn("chave da API", window.settings_options.item(0).text())
        self.assertIn("modelo", window.settings_options.item(1).text())
        self.assertIn("Voz SAPI", window.settings_options.item(4).text())
        self.assertTrue(all(model_id.startswith("gemini-") for _label, model_id in ModelDialog.MODELS))
        window.pending_api_key = ""
        window._refresh_settings_options()
        self.assertFalse(window.get_api_key_button.isHidden())
        with patch("biblia.main_window.QDesktopServices.openUrl", return_value=True) as open_url:
            window.open_google_api_keys_page()
        self.assertEqual("https://aistudio.google.com/apikey", open_url.call_args.args[0].toString())
        window.settings_options.setCurrentRow(0)
        with patch("biblia.main_window.ApiKeyDialog.get_key", return_value=("sk-teste", True)):
            QTest.keyClick(window.settings_options, Qt.Key_Space)
        self.assertEqual("sk-teste", window.pending_api_key)
        self.assertTrue(window.get_api_key_button.isHidden())
        window.settings_options.setCurrentRow(4)
        with patch(
            "biblia.main_window.ChoiceDialog.get_choice", return_value=(VOICE_OFF, True)
        ):
            QTest.keyClick(window.settings_options, Qt.Key_Space)
        self.assertEqual(VOICE_OFF, window.pending_voice_name)
        window._show_location("JHN", 3, "16", focus_reading=False)
        instruction, text, title = window._prepare_ai_task("verse")
        self.assertIn("João 3:16", instruction)
        self.assertTrue(text.startswith("16."))
        self.assertIn("João 3:16", title)
        window.voice_name = VOICE_OFF
        with patch.object(window, "_announce_for") as announce:
            window.speak_current_verse()
        self.assertTrue(announce.called)
        self.assertIn("16.", announce.call_args.args[1])
        window.close()

    def test_applications_dialog_and_buttons_accept_space_or_enter(self):
        """Valida o popup acessível e a ativação explícita dos botões."""
        dialog = ApplicationsDialog(None, "Aplicações", (("Copiar texto", "copy"),))
        dialog.show()
        self.app.processEvents()
        self.assertEqual(0, dialog.options.currentRow())
        self.assertTrue(dialog.options.hasFocus())
        QTest.keyClick(dialog.options, Qt.Key_Space)
        self.assertEqual(QDialog.Accepted, dialog.result())
        self.assertEqual("copy", dialog.selected_value)

        window = self.make_window()
        activated = []
        window.save_settings_button.clicked.disconnect()
        window.save_settings_button.clicked.connect(lambda: activated.append(True))
        window.save_settings_button.setFocus()
        QTest.keyClick(window.save_settings_button, Qt.Key_Space)
        QTest.keyClick(window.save_settings_button, Qt.Key_Return)
        self.assertEqual([True, True], activated)
        window.close()

    def test_application_menus_focus_ai_and_preserve_verse_actions(self):
        """Confere títulos, foco inicial na IA e ações existentes do versículo."""
        window = self.make_window()

        with patch("biblia.main_window.ApplicationsDialog.choose", return_value=None) as choose:
            window.show_book_menu()
            _parent, title, options = choose.call_args.args
            self.assertTrue(title.startswith("Menu do livro "))
            self.assertEqual("ai_book", options[0][1])

            window.show_chapter_menu()
            _parent, title, options = choose.call_args.args
            self.assertTrue(title.startswith("Menu do capítulo "))
            self.assertEqual("ai_chapter", options[0][1])

            window._show_location("JHN", 3, "16", focus_reading=False)
            window.show_verse_menu()
            _parent, title, options = choose.call_args.args
            self.assertTrue(title.startswith("Menu do versículo "))
            action_ids = [action_id for _label, action_id in options]
            self.assertEqual("ai_verse", action_ids[0])
            self.assertEqual(
                {"copy_text", "copy_reference", "bookmark", "note", "memorize", "speak"},
                set(action_ids[1:]),
            )

        window.close()

    def test_note_grouping_and_devotional_file_export(self):
        """Salva anotação por dia e exporta um devocional com referência substituível."""
        with TemporaryDirectory() as directory:
            window = self.make_window()
            window.user_data.close()
            window.user_data = UserDataDatabase(Path(directory) / "user_data.db")
            window._show_location("JHN", 3, "16", focus_reading=False)

            with patch(
                "biblia.main_window.NoteEditorDialog.get_note",
                return_value=("Amor de Deus", "Minha reflexão.", True),
            ):
                window.create_note()
            self.assertEqual("Amor de Deus", window.user_data.notes()[0]["title"])
            window.refresh_notes()
            self.assertEqual(2, window.notes_list.count())
            self.assertIn("1 anotação", window.notes_list.item(0).text())
            self.assertIn("Amor de Deus", window.notes_list.item(1).text())

            window.prepare_devotional()
            self.assertEqual("João 3:16", window.devotional_reference.text())
            window.devotional_reference.setText("Gênesis 1:1")
            window.load_devotional_reference()
            self.assertEqual("Gênesis 1:1", window.devotional_reference.text())
            self.assertEqual("Devocional sobre Gênesis 1:1", window.devotional_title.text())
            window.devotional_title.setText("Começos")
            window.devotional_editor.setPlainText("Reflexão pessoal.")
            target = Path(directory) / "devocional.txt"
            with patch(
                "biblia.main_window.QFileDialog.getSaveFileName",
                return_value=(str(target), "Arquivo de texto (*.txt)"),
            ), patch("biblia.main_window.QMessageBox.information"):
                window.save_devotional()
            saved = target.read_text(encoding="utf-8")
            self.assertIn("Começos", saved)
            self.assertIn("Referência: Gênesis 1:1", saved)
            self.assertIn("Reflexão pessoal.", saved)
            window.close()

    def test_api_key_dialog_is_masked_and_enter_confirms(self):
        """Confere a caixa protegida e a confirmação direta pelo Enter."""
        dialog = ApiKeyDialog(None, "sk-exemplo")
        self.assertEqual("Chave da API do Google Gemini", dialog.editor.accessibleName())
        self.assertEqual(QLineEdit.Password, dialog.editor.echoMode())
        QTest.keyClick(dialog.editor, Qt.Key_Return)
        self.assertEqual(QDialog.Accepted, dialog.result())

    def test_note_editor_lets_tab_reach_save_controls(self):
        """Evita que a caixa multilinha aprisione o foco do teclado."""
        dialog = NoteEditorDialog(None, "João 3:16")
        dialog.show()
        self.app.processEvents()
        self.assertTrue(dialog.body_editor.tabChangesFocus())
        dialog.title_editor.setFocus()
        QTest.keyClick(dialog.title_editor, Qt.Key_Return)
        self.assertTrue(dialog.body_editor.hasFocus())

    def test_worship_mode_shows_passage_immediately_and_returns_to_search(self):
        """Enter preenche o texto sem menu intermediário e permite nova referência."""
        window = self.make_window()
        window.show()
        self.app.processEvents()
        window.open_more_option("worship")
        window.extended.worship_edit.setText("João 3:16")
        QTest.keyClick(window.extended.worship_edit, Qt.Key_Return)
        self.assertEqual(1, window.extended.worship_result.count())
        self.assertIn("Deus", window.extended.worship_result.item(0).text())
        self.assertIn("João 3:16", window.extended.worship_heading.text())
        self.assertTrue(window.extended.worship_result.hasFocus())
        window.extended.focus_worship_search()
        self.assertTrue(window.extended.worship_edit.hasFocus())
        window.close()


if __name__ == "__main__":
    unittest.main()
