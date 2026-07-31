"""Ponto de entrada da Bíblia Acessível."""

from __future__ import annotations

import sys
from pathlib import Path


def main() -> int:
    """Inicializa o Qt, valida o banco local e executa a janela principal."""
    try:
        from PySide6.QtWidgets import QApplication, QMessageBox
    except ImportError:
        print(
            "PySide6 não está instalado. Execute: python -m pip install -r requirements.txt",
            file=sys.stderr,
        )
        return 1

    from biblia.main_window import MainWindow

    app = QApplication(sys.argv)
    app.setApplicationName("Bíblia Acessível")
    app.setOrganizationName("Projeto Bíblia Acessível")

    # No executável de arquivo único, ``__file__`` aponta para a pasta
    # temporária interna. Os dados editáveis continuam ao lado do EXE.
    application_dir = (
        Path(sys.executable).resolve().parent
        if getattr(sys, "frozen", False)
        else Path(__file__).resolve().parent
    )
    database = application_dir / "data" / "biblia.db"
    if not database.exists():
        QMessageBox.critical(
            None,
            "Dados não encontrados",
            "O arquivo data/biblia.db não foi encontrado. Execute scripts/build_database.py.",
        )
        return 2

    window = MainWindow(database)
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
