"""Diálogos próprios com rótulos, foco e nomes acessíveis explícitos."""

from __future__ import annotations

from PySide6.QtGui import QTextCursor
from PySide6.QtWidgets import (
    QDialog,
    QDialogButtonBox,
    QPlainTextEdit,
    QVBoxLayout,
    QWidget,
)


class AccessibleTextDialog(QDialog):
    """Exibe texto longo em um controle simples, navegável e somente leitura."""

    def __init__(self, parent: QWidget, title: str, accessible_name: str, text: str):
        """Mostra conteúdo somente leitura com foco inicial no próprio texto."""
        super().__init__(parent)
        self.setWindowTitle(title)
        self.setAccessibleName(accessible_name)
        self.resize(620, 360)
        layout = QVBoxLayout(self)
        self.text = QPlainTextEdit()
        self.text.setReadOnly(True)
        self.text.setAccessibleName(accessible_name)
        self.text.setPlainText(text)
        layout.addWidget(self.text, 1)
        buttons = QDialogButtonBox(QDialogButtonBox.Close)
        close_button = buttons.button(QDialogButtonBox.Close)
        close_button.setText("&Fechar")
        close_button.setAccessibleName("Fechar")
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)
        self.text.moveCursor(QTextCursor.Start)
        self.text.setFocus()
