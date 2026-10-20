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
        logging.getLogger(__name__).error("Interface indisponível", exc_info=error)
        print("Não foi possível abrir a interface do aplicativo.", file=sys.stderr)
        return 1

    from biblia.main_window import MainWindow
    from biblia.paths import migrate_legacy_personal_database, personal_data_directory

    app = QApplication(sys.argv)
    app.setApplicationName("Bíblia Acessível")
    app.setOrganizationName("Projeto Bíblia Acessível")

    # O pacote oficial mantém uma cópia externa para atualização e também uma
    # cópia interna para que o EXE continue funcional quando for baixado sozinho.
    application_dir = (
        Path(sys.executable).resolve().parent
        if getattr(sys, "frozen", False)
        else Path(__file__).resolve().parent
    )
    bundled_dir = Path(getattr(sys, "_MEIPASS", application_dir)).resolve()
    external_database = application_dir / "data" / "biblia.db"
    bundled_database = bundled_dir / "data" / "biblia.db"
    database = external_database if external_database.exists() else bundled_database
    resource_dir = application_dir if external_database.exists() else bundled_dir
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
            "O aplicativo encontrou uma falha e não conseguiu concluir esta ação. Tente novamente.",
        )

    sys.excepthook = report_unexpected
    if not database.exists():
        QMessageBox.critical(
            None,
            "Não foi possível abrir a Bíblia",
            "Os textos bíblicos não puderam ser carregados nesta instalação. Baixe novamente o aplicativo e tente outra vez.",
        )
        return 2

    window = MainWindow(database, user_database, personal_dir, application_dir, resource_dir)
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
