"""Ponto de entrada da Bíblia Acessível."""

from __future__ import annotations

import sys
import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path


def main() -> int:
    """Inicializa o Qt, valida o banco local e executa a janela principal."""
    try:
        from PySide6.QtWidgets import QApplication, QMessageBox
    except ImportError as error:
        print(
            "PySide6 não pôde ser carregado. Execute: python -m pip install -r requirements.txt. "
            f"Detalhes técnicos: {error}",
            file=sys.stderr,
        )
        return 1

    from biblia.main_window import MainWindow
    from biblia.paths import migrate_legacy_personal_database, personal_data_directory

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
    personal_dir = personal_data_directory()
    personal_dir.mkdir(parents=True, exist_ok=True)
    user_database = personal_dir / "user_data.db"
    migrate_legacy_personal_database(application_dir / "data" / "user_data.db", user_database)
    log_path = personal_dir / "biblia-acessivel.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    handler = RotatingFileHandler(log_path, maxBytes=500_000, backupCount=2, encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    logging.getLogger().setLevel(logging.INFO)
    logging.getLogger().addHandler(handler)

    def report_unexpected(error_type, error, traceback):
        """Registra detalhes técnicos e mostra uma mensagem compreensível."""
        logging.getLogger(__name__).error("Falha inesperada", exc_info=(error_type, error, traceback))
        QMessageBox.critical(
            None, "Falha inesperada",
            "O aplicativo encontrou uma falha. Os detalhes técnicos foram registrados na pasta pessoal da Bíblia Acessível.",
        )

    sys.excepthook = report_unexpected
    if not database.exists():
        QMessageBox.critical(
            None,
            "Dados não encontrados",
            "O arquivo data/biblia.db não foi encontrado. Execute scripts/build_database.py.",
        )
        return 2

    window = MainWindow(database, user_database, personal_dir, application_dir)
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
