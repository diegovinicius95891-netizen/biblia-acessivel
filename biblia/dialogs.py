"""Diálogos próprios com rótulos, foco e nomes acessíveis explícitos."""

from __future__ import annotations

from PySide6.QtGui import QTextCursor
from PySide6.QtWidgets import (
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QFormLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QPlainTextEdit,
    QPushButton,
    QVBoxLayout,
    QWidget,
)


class ApiKeyDialog(QDialog):
    """Solicita a chave pessoal em uma caixa mascarada confirmada com Enter."""

    def __init__(self, parent: QWidget, current_key: str = ""):
        """Cria o editor e preenche a chave atual sem expô-la visualmente."""
        super().__init__(parent)
        self.setWindowTitle("Chave da API OpenAI")
        self.setAccessibleName("Configurar chave da API OpenAI")
        layout = QFormLayout(self)
        self.editor = QLineEdit(current_key)
        self.editor.setEchoMode(QLineEdit.Password)
        self.editor.setAccessibleName("Chave da API OpenAI")
        self.editor.setAccessibleDescription("Cole a chave e pressione Enter para confirmar.")
        self.editor.returnPressed.connect(self.accept)
        layout.addRow("&Chave da API:", self.editor)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("&Confirmar")
        buttons.button(QDialogButtonBox.Cancel).setText("&Cancelar")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addRow(buttons)
        self.editor.selectAll()
        self.editor.setFocus()

    @classmethod
    def get_key(cls, parent: QWidget, current_key: str = ""):
        """Devolve a chave digitada e informa se o diálogo foi confirmado."""
        dialog = cls(parent, current_key)
        accepted = dialog.exec() == QDialog.Accepted
        return dialog.editor.text().strip(), accepted


class ModelDialog(QDialog):
    """Permite escolher um modelo em uma caixa de combinação acessível."""

    MODELS = (
        ("Econômico — GPT-5.6 Luna", "gpt-5.6-luna"),
        ("Equilibrado — GPT-5.6 Terra", "gpt-5.6-terra"),
        ("Maior qualidade — GPT-5.6 Sol", "gpt-5.6-sol"),
    )

    def __init__(self, parent: QWidget, current_model: str):
        """Cria o seletor e destaca o modelo atualmente configurado."""
        super().__init__(parent)
        self.setWindowTitle("Modelo da inteligência artificial")
        layout = QFormLayout(self)
        self.combo = QComboBox()
        self.combo.setAccessibleName("Modelo da inteligência artificial")
        for label, model_id in self.MODELS:
            self.combo.addItem(label, model_id)
        self.combo.setCurrentIndex(max(0, self.combo.findData(current_model)))
        layout.addRow("&Modelo:", self.combo)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("&Confirmar")
        buttons.button(QDialogButtonBox.Cancel).setText("&Cancelar")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addRow(buttons)
        self.combo.setFocus()

    @classmethod
    def get_model(cls, parent: QWidget, current_model: str):
        """Devolve o identificador escolhido e a confirmação do diálogo."""
        dialog = cls(parent, current_model)
        accepted = dialog.exec() == QDialog.Accepted
        return dialog.combo.currentData(), accepted


class AccessibleResultDialog(QDialog):
    """Mostra o resultado da IA como parágrafos navegáveis por cima e baixo."""

    def __init__(self, parent: QWidget, title: str, text: str):
        """Divide o resultado e oferece cópia e fechamento em controles nativos."""
        super().__init__(parent)
        self.setWindowTitle(title)
        self.setAccessibleName(title)
        self.resize(720, 500)
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("Use cima e baixo para ler o resultado:"))
        self.paragraphs = QListWidget()
        self.paragraphs.setAccessibleName(f"Resultado: {title}")
        self.paragraphs.setWordWrap(True)
        for paragraph in text.split("\n\n"):
            normalized = " ".join(paragraph.split())
            if normalized:
                self.paragraphs.addItem(QListWidgetItem(normalized))
        layout.addWidget(self.paragraphs, 1)
        self.copy_button = QPushButton("&Copiar resultado")
        self.copy_button.clicked.connect(self._copy)
        layout.addWidget(self.copy_button)
        buttons = QDialogButtonBox(QDialogButtonBox.Close)
        buttons.button(QDialogButtonBox.Close).setText("&Fechar")
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)
        if self.paragraphs.count():
            self.paragraphs.setCurrentRow(0)
        self.paragraphs.setFocus()

    def _copy(self):
        """Copia o resultado completo preservando a separação dos parágrafos."""
        from PySide6.QtWidgets import QApplication

        text = "\n\n".join(self.paragraphs.item(i).text() for i in range(self.paragraphs.count()))
        QApplication.clipboard().setText(text)


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
