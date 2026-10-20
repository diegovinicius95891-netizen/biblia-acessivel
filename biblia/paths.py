"""Caminhos de instalação e dados pessoais, separados para atualizações seguras."""

from __future__ import annotations

import os
import shutil
from pathlib import Path


def personal_data_directory() -> Path:
    """Retorna a pasta persistente do perfil Windows com fallback portável seguro."""
    # O override existe somente para testes de pacote e evita tocar nos dados
    # reais do usuário ao abrir um executável recém-compilado em automação.
    test_directory = os.environ.get("BIBLIA_TEST_DATA_DIR")
    if test_directory:
        return Path(test_directory)
    base = os.environ.get("APPDATA") or os.environ.get("LOCALAPPDATA")
    if base:
        return Path(base) / "BibliaAcessivel"
    return Path.home() / ".biblia-acessivel"


def migrate_legacy_personal_database(legacy: Path, destination: Path) -> bool:
    """Copia uma base antiga somente quando ainda não existe base no perfil."""
    if destination.exists() or not legacy.exists():
        return False
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".migrating")
    shutil.copy2(legacy, temporary)
    temporary.replace(destination)
    return True
