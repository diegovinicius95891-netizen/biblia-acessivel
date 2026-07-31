"""Diálogos próprios com rótulos, foco e nomes acessíveis explícitos."""

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QTextCursor
from PySide6.QtWidgets import (
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


class ActivatableList(QListWidget):
    """Lista acessível que ativa o item atual tanto com Espaço quanto com Enter."""

    selectionRequested = Signal(QListWidgetItem)

    def __init__(self, parent=None):
        """Conecta também clique duplo à mesma rota de ativação."""
        super().__init__(parent)
        self.itemDoubleClicked.connect(self.selectionRequested.emit)

    def keyPressEvent(self, event):
        """Emite o item atual com Espaço/Enter e preserva as demais teclas."""
        if event.key() in (Qt.Key_Space, Qt.Key_Return, Qt.Key_Enter):
            item = self.currentItem()
            if item:
                self.selectionRequested.emit(item)
            event.accept()
            return
        super().keyPressEvent(event)


class AccessibleButton(QPushButton):
    """Botão que garante ativação por Enter/Return além do Espaço nativo."""

    def keyPressEvent(self, event):
        """Converte Enter em clique e delega Espaço e demais teclas ao Qt."""
        if event.key() in (Qt.Key_Return, Qt.Key_Enter):
            self.click()
            event.accept()
            return
        super().keyPressEvent(event)


class ChoiceDialog(QDialog):
    """Apresenta escolhas em lista, navegáveis e confirmáveis sem mouse."""

    def __init__(self, parent: QWidget, title: str, options, current_value=None):
        """Preenche opções como pares de rótulo e valor e destaca a atual."""
        super().__init__(parent)
        self.setWindowTitle(title)
        self.setAccessibleName(title)
        self.selected_value = current_value
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("Use cima e baixo. Pressione Espaço ou Enter para selecionar:"))
        self.options = ActivatableList()
        self.options.setAccessibleName(title)
        selected_row = 0
        for index, (label, value) in enumerate(options):
            item = QListWidgetItem(label)
            item.setData(Qt.UserRole, value)
            self.options.addItem(item)
            if value == current_value:
                selected_row = index
        self.options.setCurrentRow(selected_row)
        self.options.selectionRequested.connect(self._choose)
        layout.addWidget(self.options)
        buttons = QDialogButtonBox(QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Cancel).setText("&Cancelar")
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)
        self.options.setFocus()

    def _choose(self, item: QListWidgetItem):
        """Guarda o valor associado e fecha o diálogo como confirmado."""
        self.selected_value = item.data(Qt.UserRole)
        self.accept()

    @classmethod
    def get_choice(cls, parent: QWidget, title: str, options, current_value=None):
        """Executa o diálogo e devolve valor escolhido e confirmação."""
        dialog = cls(parent, title, options, current_value)
        accepted = dialog.exec() == QDialog.Accepted
        return dialog.selected_value, accepted


class ApplicationsDialog(ChoiceDialog):
    """Popup acessível que substitui menus contextuais difíceis para leitores de tela."""

    @classmethod
    def choose(cls, parent: QWidget, title: str, options):
        """Mostra ações como pares de rótulo e identificador e devolve a escolhida."""
        value, accepted = cls.get_choice(parent, title, options)
        return value if accepted else None


class ApiKeyDialog(QDialog):
    """Solicita a chave do Google Gemini em caixa mascarada confirmada com Enter."""

    def __init__(self, parent: QWidget, current_key: str = ""):
        """Cria o editor e preenche a chave atual sem expô-la visualmente."""
        super().__init__(parent)
        self.setWindowTitle("Chave da API do Google Gemini")
        self.setAccessibleName("Configurar chave da API do Google Gemini")
        layout = QFormLayout(self)
        self.editor = QLineEdit(current_key)
        self.editor.setEchoMode(QLineEdit.Password)
        self.editor.setAccessibleName("Chave da API do Google Gemini")
        self.editor.setAccessibleDescription("Cole a chave e pressione Enter para confirmar.")
        self.editor.returnPressed.connect(self.accept)
        layout.addRow("&Chave do Google Gemini:", self.editor)
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


class ModelDialog(ChoiceDialog):
    """Permite escolher um modelo numa lista ativada por Espaço ou Enter."""

    MODELS = (
        ("Econômico — Gemini 2.5 Flash-Lite", "gemini-2.5-flash-lite"),
        ("Equilibrado — Gemini 2.5 Flash", "gemini-2.5-flash"),
        ("Maior qualidade — Gemini 2.5 Pro", "gemini-2.5-pro"),
    )

    @classmethod
    def get_model(cls, parent: QWidget, current_model: str):
        """Devolve o identificador escolhido e a confirmação do diálogo."""
        return cls.get_choice(
            parent,
            "Modelo da inteligência artificial",
            cls.MODELS,
            current_model,
        )


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
        self.copy_button = AccessibleButton("&Copiar resultado")
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
