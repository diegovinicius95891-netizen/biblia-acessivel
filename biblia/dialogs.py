"""Diálogos próprios com rótulos, foco e nomes acessíveis explícitos."""

from __future__ import annotations

from PySide6.QtGui import QTextCursor
from PySide6.QtWidgets import (
    QDialog,
    QDialogButtonBox,
    QLabel,
    QPlainTextEdit,
    QVBoxLayout,
    QWidget,
)


class NoteDialog(QDialog):
    """Editor de notas com rótulo, campo e botões explicitamente acessíveis."""

    def __init__(self, parent: QWidget, reference: str, note: str = ""):
        """Cria o editor e associa o rótulo ao campo multilinha."""
        super().__init__(parent)
        self.setWindowTitle(f"Nota — {reference}")
        self.setAccessibleName(f"Editar nota de {reference}")
        self.setAccessibleDescription(
            "Digite a nota e pressione Alt mais S para salvar ou Escape para cancelar. "
            "Salvar uma nota vazia remove a nota existente."
        )
        self.resize(620, 360)

        layout = QVBoxLayout(self)
        label = QLabel(f"&Nota para {reference}:")
        self.editor = QPlainTextEdit()
        self.editor.setAccessibleName(f"Texto da nota de {reference}")
        self.editor.setAccessibleDescription(
            "Campo de várias linhas. Uma nota vazia será removida ao salvar."
        )
        self.editor.setPlainText(note)
        label.setBuddy(self.editor)
        layout.addWidget(label)
        layout.addWidget(self.editor, 1)

        self.buttons = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        save_button = self.buttons.button(QDialogButtonBox.Save)
        cancel_button = self.buttons.button(QDialogButtonBox.Cancel)
        save_button.setText("&Salvar")
        save_button.setAccessibleName("Salvar nota")
        cancel_button.setText("&Cancelar")
        cancel_button.setAccessibleName("Cancelar edição da nota")
        self.buttons.accepted.connect(self.accept)
        self.buttons.rejected.connect(self.reject)
        layout.addWidget(self.buttons)

        QWidget.setTabOrder(self.editor, save_button)
        QWidget.setTabOrder(save_button, cancel_button)
        self.editor.selectAll()
        self.editor.setFocus()

    @classmethod
    def get_note(cls, parent: QWidget, reference: str, note: str = ""):
        """Executa o diálogo e devolve texto e confirmação do usuário."""
        dialog = cls(parent, reference, note)
        accepted = dialog.exec() == QDialog.Accepted
        return dialog.editor.toPlainText(), accepted


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
