"""Integração acessível entre o serviço de atualização e a interface Qt."""

from __future__ import annotations

import base64
import ctypes
import logging
import os
import subprocess
import sys
import threading
from datetime import date, datetime, timedelta
from pathlib import Path

from PySide6.QtCore import QObject, QSettings, QTimer, Signal
from PySide6.QtWidgets import (
    QDialog,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QMessageBox,
    QProgressBar,
    QVBoxLayout,
)

from .changelog import CURRENT_CHANGELOG
from .dialogs import AccessibleButton, AccessibleResultDialog
from .update_service import ReleaseInfo, UpdateError, UpdateService
from .version import APP_VERSION, WINDOWS_ASSET_NAME


LOGGER = logging.getLogger(__name__)


def independent_process_environment(source: dict[str, str] | None = None) -> dict[str, str]:
    """Cria ambiente limpo para um processo que deve sobreviver ao EXE atual.

    O bootloader do PyInstaller usa variáveis privadas para fazer processos-filhos
    reutilizarem a pasta temporária ``_MEI``. Isso é correto para trabalhadores,
    mas não para o reinício depois de uma atualização, pois a pasta da instância
    antiga é removida assim que ela termina.
    """
    environment = dict(os.environ if source is None else source)
    for name in tuple(environment):
        if name.startswith("_PYI_") or name == "_MEIPASS2":
            environment.pop(name, None)

    bundle_root = getattr(sys, "_MEIPASS", "")
    if bundle_root and environment.get("PATH"):
        normalized_root = os.path.normcase(os.path.abspath(bundle_root))
        environment["PATH"] = os.pathsep.join(
            entry for entry in environment["PATH"].split(os.pathsep)
            if (
                os.path.normcase(os.path.abspath(entry or ".")) != normalized_root
                and not os.path.normcase(os.path.abspath(entry or ".")).startswith(normalized_root + os.sep)
            )
        )

    environment["PYINSTALLER_RESET_ENVIRONMENT"] = "1"
    return environment


def launch_independent_process(arguments: list[str], **options) -> subprocess.Popen:
    """Inicia helper externo sem herdar a busca de DLLs do pacote congelado."""
    options["env"] = independent_process_environment(options.get("env"))
    bundle_root = getattr(sys, "_MEIPASS", "")
    dll_search_was_reset = False
    if sys.platform == "win32" and getattr(sys, "frozen", False):
        # SetDllDirectoryW também afeta processos-filhos no Windows. O PowerShell
        # precisa usar as DLLs do sistema, não as cópias internas do aplicativo.
        dll_search_was_reset = ctypes.windll.kernel32.SetDllDirectoryW(None) != 0
    try:
        return subprocess.Popen(arguments, **options)
    finally:
        if dll_search_was_reset and bundle_root:
            ctypes.windll.kernel32.SetDllDirectoryW(str(bundle_root))


class UpdateSignals(QObject):
    """Entrega resultados das tarefas de rede à thread principal do Qt."""

    checked = Signal(object, bool)
    check_failed = Signal(str, bool)
    progress = Signal(int)
    downloaded = Signal(object, object, str)
    download_failed = Signal(str)


class UpdateAvailableDialog(QDialog):
    """Apresenta versão, tamanho, novidades e decisões numa ordem previsível."""

    def __init__(self, parent, release: ReleaseInfo):
        """Cria controles nomeados e inicia o foco em Atualizar agora."""
        super().__init__(parent)
        self.action = "close"
        self.setWindowTitle("Nova versão disponível")
        self.setAccessibleName("Atualização disponível, janela")
        self.resize(660, 480)
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel(f"Versão instalada: {APP_VERSION}"))
        layout.addWidget(QLabel(f"Nova versão: {release.version}"))
        size_mb = release.asset.size / 1024 / 1024 if release.asset.size else 0
        layout.addWidget(QLabel(f"Tamanho: {size_mb:.1f} MB" if size_mb else "Tamanho: não informado"))
        layout.addWidget(QLabel("Novidades:"))
        self.notes = QListWidget()
        self.notes.setAccessibleName("Novidades da atualização")
        self.notes.setWordWrap(True)
        for paragraph in release.notes.replace("#", "").splitlines():
            text = paragraph.strip().lstrip("*- ").strip()
            if text and not text.startswith("<!--"):
                self.notes.addItem(QListWidgetItem(text))
        layout.addWidget(self.notes, 1)
        self.update_button = AccessibleButton("&Atualizar agora")
        self.update_button.setAccessibleName("Atualizar agora")
        self.update_button.clicked.connect(lambda: self._finish("update"))
        layout.addWidget(self.update_button)
        if release.mandatory:
            warning = QLabel("Esta é uma atualização obrigatória por segurança ou proteção dos dados.")
            warning.setAccessibleName("Atualização obrigatória")
            layout.addWidget(warning)
        else:
            self.later_button = AccessibleButton("&Lembrar depois")
            self.later_button.clicked.connect(lambda: self._finish("later"))
            layout.addWidget(self.later_button)
            self.ignore_button = AccessibleButton("&Ignorar esta versão")
            self.ignore_button.clicked.connect(lambda: self._finish("ignore"))
            layout.addWidget(self.ignore_button)
        close_button = AccessibleButton("&Sair sem atualizar" if release.mandatory else "&Fechar")
        close_button.clicked.connect(self.reject)
        layout.addWidget(close_button)
        self.update_button.setFocus()

    def _finish(self, action: str):
        """Guarda a decisão sem depender do texto visual do botão."""
        self.action = action
        self.accept()


class DownloadProgressDialog(QDialog):
    """Mostra progresso moderado e oferece cancelamento pelo teclado."""

    cancel_requested = Signal()

    def __init__(self, parent):
        """Cria barra nomeada e botão Cancelar como únicos controles."""
        super().__init__(parent)
        self.setWindowTitle("Baixando atualização")
        self.setAccessibleName("Baixando atualização, janela")
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel("Baixando atualização. O aplicativo continua respondendo."))
        self.progress = QProgressBar()
        self.progress.setRange(0, 100)
        self.progress.setAccessibleName("Progresso do download")
        layout.addWidget(self.progress)
        cancel = AccessibleButton("&Cancelar download")
        cancel.clicked.connect(self.cancel_requested.emit)
        layout.addWidget(cancel)
        cancel.setFocus()

    def set_progress(self, value: int):
        """Atualiza visualmente; o controlador anuncia somente marcos relevantes."""
        self.progress.setValue(value)


class UpdateController(QObject):
    """Coordena verificação diária, decisões, download, backup e reinício."""

    ANNOUNCED_PROGRESS = (10, 25, 50, 75, 100)

    def __init__(self, host, app_data_dir: Path, install_dir: Path):
        """Conecta sinais e agenda verificação sem bloquear a abertura da Bíblia."""
        super().__init__(host)
        self.host = host
        self.app_data_dir = app_data_dir
        self.install_dir = install_dir
        self.settings = QSettings()
        self.service = UpdateService()
        self.signals = UpdateSignals(self)
        self.signals.checked.connect(self._checked)
        self.signals.check_failed.connect(self._check_failed)
        self.signals.progress.connect(self._download_progress)
        self.signals.downloaded.connect(self._downloaded)
        self.signals.download_failed.connect(self._download_failed)
        self.cancel_event = threading.Event()
        self.progress_dialog = None
        self._last_announced = 0
        QTimer.singleShot(1800, self._show_post_update_message)
        if os.environ.get("BIBLIA_DISABLE_AUTO_UPDATE") != "1":
            QTimer.singleShot(2500, self.check_automatically)

    def automatic_enabled(self) -> bool:
        """Lê a preferência persistente cujo padrão é ativado."""
        return self.settings.value("updates/automatic", True, type=bool)

    def notifications_enabled(self) -> bool:
        """Lê se atualizações encontradas devem abrir uma janela."""
        return self.settings.value("updates/notify", True, type=bool)

    def check_automatically(self):
        """Executa no máximo uma consulta diária e ignora falhas silenciosamente."""
        if not self.automatic_enabled():
            return
        last = str(self.settings.value("updates/last_check", ""))
        if last == date.today().isoformat():
            return
        self.check_now(manual=False)

    def check_now(self, manual: bool = True):
        """Inicia consulta em segundo plano e informa atividade quando solicitada."""
        if manual:
            self.host._announce_for(self.host.help_text, "Verificando atualizações.")

        def worker():
            """Consulta a API fora da thread principal e devolve apenas sinais."""
            try:
                release = self.service.check(APP_VERSION)
                self.signals.checked.emit(release, manual)
            except UpdateError as error:
                self.signals.check_failed.emit(str(error), manual)

        threading.Thread(target=worker, daemon=True, name="update-check").start()

    def _checked(self, release: ReleaseInfo | None, manual: bool):
        """Registra a consulta e abre o diálogo quando a versão deve ser oferecida."""
        self.settings.setValue("updates/last_check", date.today().isoformat())
        if not release:
            if manual:
                QMessageBox.information(self.host, "Atualizações", "Você já está usando a versão mais recente.")
            return
        ignored = str(self.settings.value("updates/ignored_version", ""))
        remind_at = str(self.settings.value("updates/remind_after", ""))
        if not manual and not release.mandatory:
            if ignored == release.version or not self.notifications_enabled():
                return
            if remind_at:
                try:
                    if datetime.now().astimezone() < datetime.fromisoformat(remind_at):
                        return
                except ValueError:
                    pass
        dialog = UpdateAvailableDialog(self.host, release)
        dialog.exec()
        if release.mandatory and dialog.action == "close":
            self.host.close()
            return
        if dialog.action == "ignore":
            self.settings.setValue("updates/ignored_version", release.version)
        elif dialog.action == "later":
            later = datetime.now().astimezone() + timedelta(hours=24)
            self.settings.setValue("updates/remind_after", later.isoformat(timespec="seconds"))
        elif dialog.action == "update":
            self._start_download(release)

    def _check_failed(self, message: str, manual: bool):
        """Mantém falhas automáticas no log e mostra falhas manuais de forma simples."""
        if not manual:
            self.settings.setValue("updates/last_check", date.today().isoformat())
        if manual:
            QMessageBox.warning(self.host, "Atualizações", message)

    def _start_download(self, release: ReleaseInfo):
        """Prepara destino privado e inicia transferência cancelável."""
        updates = self.app_data_dir / "updates"
        destination = updates / f"{release.tag}-{WINDOWS_ASSET_NAME}"
        self.cancel_event = threading.Event()
        self._last_announced = 0
        self.progress_dialog = DownloadProgressDialog(self.host)
        self.progress_dialog.cancel_requested.connect(self.cancel_event.set)
        self.progress_dialog.show()

        def worker():
            """Transfere e valida o pacote sem bloquear teclado ou leitor de tela."""
            try:
                digest = self.service.download_and_validate(
                    release, destination, self.cancel_event, self.signals.progress.emit
                )
                self.signals.downloaded.emit(release, destination, digest)
            except (UpdateError, OSError) as error:
                self.signals.download_failed.emit(str(error))

        threading.Thread(target=worker, daemon=True, name="update-download").start()

    def _download_progress(self, value: int):
        """Atualiza a barra e anuncia somente 10, 25, 50, 75 e 100 por cento."""
        if self.progress_dialog:
            self.progress_dialog.set_progress(value)
            milestone = max((item for item in self.ANNOUNCED_PROGRESS if value >= item), default=0)
            if milestone > self._last_announced:
                self._last_announced = milestone
                self.host._announce_for(self.progress_dialog.progress, f"Download {milestone}%.")

    def _download_failed(self, message: str):
        """Fecha o progresso e diferencia cancelamento de erro de integridade."""
        if self.progress_dialog:
            self.progress_dialog.close()
            self.progress_dialog = None
        title = "Download cancelado" if "cancelado" in message.lower() else "Falha na atualização"
        QMessageBox.warning(self.host, title, message)

    def _downloaded(self, release: ReleaseInfo, archive: Path, digest: str):
        """Confirma instalação somente depois de tamanho e SHA-256 validados."""
        if self.progress_dialog:
            self.progress_dialog.close()
            self.progress_dialog = None
        answer = QMessageBox.question(
            self.host,
            "Atualização pronta para instalação",
            "A atualização foi baixada e validada. O aplicativo precisa ser fechado. Instalar agora?",
            QMessageBox.Yes | QMessageBox.No,
            QMessageBox.Yes,
        )
        if answer != QMessageBox.Yes:
            return
        try:
            backup = self._backup_personal_data(release.version)
            self._launch_installer(archive)
            LOGGER.info("Instalação iniciada: versão=%s sha256=%s backup=%s", release.version, digest, backup)
        except (OSError, UpdateError) as error:
            LOGGER.exception("Não foi possível iniciar a instalação")
            QMessageBox.warning(
                self.host, "Não foi possível instalar",
                "A atualização permanece baixada e a versão atual continua utilizável. " + str(error),
            )

    def _backup_personal_data(self, version: str) -> Path:
        """Faz backup consistente do SQLite e mantém somente os cinco mais recentes."""
        backup_dir = self.app_data_dir / "backups"
        backup_dir.mkdir(parents=True, exist_ok=True)
        target = backup_dir / f"backup_pre_update_{version}_{datetime.now():%Y%m%d_%H%M%S}.db"
        self.host.user_data.backup_to(target)
        backups = sorted(backup_dir.glob("backup_pre_update_*.db"), key=lambda item: item.stat().st_mtime)
        for old in backups[:-5]:
            old.unlink(missing_ok=True)
        return target

    @staticmethod
    def _ps_quote(value: Path | str) -> str:
        """Escapa caminho literal para uma string PowerShell entre aspas simples."""
        return str(value).replace("'", "''")

    def _launch_installer(self, archive: Path):
        """Inicia helper PowerShell oculto que espera, substitui e reabre o EXE."""
        if not getattr(sys, "frozen", False):
            raise UpdateError("Em modo de desenvolvimento, teste a instalação usando o EXE compilado.")
        executable = Path(sys.executable).resolve()
        if executable.parent != self.install_dir.resolve() or not archive.is_file():
            raise UpdateError("Os caminhos da atualização não puderam ser validados.")
        staging = archive.parent / f"staging-{os.getpid()}"
        rollback = archive.parent / f"rollback-{os.getpid()}"
        command = (
            "$ErrorActionPreference='Stop'; "
            f"$pidToWait={os.getpid()}; $archive='{self._ps_quote(archive)}'; "
            f"$staging='{self._ps_quote(staging)}'; $destination='{self._ps_quote(self.install_dir)}'; "
            f"$rollback='{self._ps_quote(rollback)}'; "
            f"$exe='{self._ps_quote(executable)}'; "
            "Wait-Process -Id $pidToWait -Timeout 120 -ErrorAction SilentlyContinue; "
            "if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }; "
            "if (Test-Path -LiteralPath $rollback) { Remove-Item -LiteralPath $rollback -Recurse -Force }; "
            "Expand-Archive -LiteralPath $archive -DestinationPath $staging -Force; "
            "$source=Join-Path $staging 'BibliaAcessivel'; "
            "if (-not (Test-Path -LiteralPath (Join-Path $source 'BibliaAcessivel.exe'))) { throw 'Pacote inválido' }; "
            "New-Item -ItemType Directory -Path (Join-Path $rollback 'data') -Force | Out-Null; "
            "$programFiles=@('BibliaAcessivel.exe','LEIA-ME.txt','THIRD_PARTY_NOTICES.md'); "
            "foreach($name in $programFiles){$old=Join-Path $destination $name; if(Test-Path -LiteralPath $old){Copy-Item -LiteralPath $old -Destination $rollback -Force}}; "
            "$oldBible=Join-Path $destination 'data\\biblia.db'; if(Test-Path -LiteralPath $oldBible){Copy-Item -LiteralPath $oldBible -Destination (Join-Path $rollback 'data') -Force}; "
            "$failed=$false; try { Copy-Item -Path (Join-Path $source '*') -Destination $destination -Recurse -Force } "
            "catch { Copy-Item -Path (Join-Path $rollback '*') -Destination $destination -Recurse -Force; $failed=$true }; "
            "Remove-Item -LiteralPath $staging -Recurse -Force; "
            "Remove-Item -LiteralPath $rollback -Recurse -Force; "
            "$env:PYINSTALLER_RESET_ENVIRONMENT='1'; Start-Process -FilePath $exe; if($failed){exit 1}"
        )
        encoded = base64.b64encode(command.encode("utf-16le")).decode("ascii")
        launch_independent_process(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded],
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            close_fds=True,
        )
        self.settings.setValue("updates/installing_version", archive.name.split("-", 1)[0].lstrip("v"))
        self.settings.sync()
        self.host.close()

    def _show_post_update_message(self):
        """Mostra uma única confirmação quando a versão instalada mudou."""
        previous = str(self.settings.value("updates/last_running_version", ""))
        self.settings.setValue("updates/last_running_version", APP_VERSION)
        installing = str(self.settings.value("updates/installing_version", ""))
        if installing == APP_VERSION and previous != APP_VERSION:
            self.settings.remove("updates/installing_version")
            QMessageBox.information(
                self.host, "Atualização concluída",
                f"Aplicativo atualizado para a versão {APP_VERSION}. As novidades estão no menu Ajuda.",
            )

    def show_current_changelog(self):
        """Exibe as novidades locais sem depender da disponibilidade do GitHub."""
        AccessibleResultDialog(self.host, "Novidades da versão instalada", CURRENT_CHANGELOG).exec()
