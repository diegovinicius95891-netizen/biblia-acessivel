"""Testes rápidos de teclado, acessibilidade e integração da janela."""

import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

try:
    from PySide6.QtCore import Qt
    from PySide6.QtTest import QTest
    from PySide6.QtWidgets import QApplication, QDialogButtonBox
    from biblia.dialogs import NoteDialog
    from biblia.main_window import MainWindow, VerseList
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

    def test_single_page_navigation_search_and_legal_section(self):
        """Valida página única, testamentos, leitura, busca e leis."""
        window = MainWindow(DB_PATH)
        self.assertFalse(hasattr(window, "tabs"))
        self.assertIs(window.scroll_area, window.centralWidget())
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
        window.close()

    def test_end_of_book_is_announced_and_does_not_cross_books(self):
        """Mantém direita no último capítulo e adiciona a mensagem terminal."""
        window = MainWindow(DB_PATH)
        window._show_location("JHN", 21, focus_reading=False)
        ending = window.verse_list.item(window.verse_list.count() - 1)
        self.assertEqual("Fim do livro. Não há capítulos seguintes.", ending.text())
        QTest.keyClick(window.verse_list, Qt.Key_Right)
        self.assertEqual("JHN", window.active_book_code)
        self.assertEqual(21, window.active_chapter)
        window.close()

    def test_applications_key_is_detected(self):
        """Garante que a tecla Aplicações emite o pedido de menu."""
        verse_list = VerseList()
        requested = []
        verse_list.applicationsRequested.connect(lambda: requested.append(True))
        QTest.keyClick(verse_list, Qt.Key_Menu)
        self.assertEqual([True], requested)

    def test_note_dialog_has_accessible_editor_and_buttons(self):
        """Confere nomes acessíveis nos controles do editor de notas."""
        dialog = NoteDialog(None, "João 3:16", "Texto inicial")
        self.assertEqual("Texto da nota de João 3:16", dialog.editor.accessibleName())
        self.assertEqual("Texto inicial", dialog.editor.toPlainText())
        self.assertTrue(dialog.buttons.button(QDialogButtonBox.Save).accessibleName())
        self.assertTrue(dialog.buttons.button(QDialogButtonBox.Cancel).accessibleName())
        dialog.close()

    def test_note_action_saves_through_accessible_dialog(self):
        """Simula confirmação e verifica persistência e agrupamento."""
        with TemporaryDirectory() as directory:
            window = MainWindow(DB_PATH)
            window.user_data.close()
            window.user_data = UserDataDatabase(Path(directory) / "user.db")
            window.reference_edit.setText("João 3:16")
            window.go_to_reference()
            with patch("biblia.main_window.NoteDialog.get_note", return_value=("Nota acessível", True)):
                window.edit_note()
            self.assertEqual(
                "Nota acessível", window.user_data.note(window.current_translation_id(), "JHN", 3, "16")
            )
            self.assertEqual(1, len(window.note_book_widgets))
            notes_book = window.note_book_widgets[0]
            self.assertIn("1 nota", notes_book.button.text())
            self.assertFalse(notes_book.button.isChecked())
            self.assertTrue(notes_book.list.isHidden())
            window.close()


if __name__ == "__main__":
    unittest.main()
