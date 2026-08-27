"""Serviços puros e testáveis de atualização por GitHub Releases.

O módulo não conhece a interface Qt. Ele consulta somente o repositório oficial,
seleciona o pacote Windows conhecido, baixa com limite de tempo e exige o hash
SHA-256 publicado junto da versão antes de liberar a instalação.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from functools import total_ordering
from pathlib import Path
from threading import Event
from typing import Callable

from .version import RELEASE_REPOSITORY, WINDOWS_ASSET_NAME


LOGGER = logging.getLogger(__name__)
ALLOWED_DOWNLOAD_HOSTS = {
    "github.com",
    "api.github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com",
}


class UpdateError(RuntimeError):
    """Falha operacional apresentada ao usuário com uma mensagem simples."""


class DownloadCancelled(UpdateError):
    """Indica cancelamento voluntário sem tratar a situação como corrupção."""


@total_ordering
@dataclass(frozen=True)
class SemanticVersion:
    """Versão semântica comparável sem usar ordenação textual ingênua."""

    major: int
    minor: int
    patch: int
    prerelease: tuple = ()

    @classmethod
    def parse(cls, value: str) -> "SemanticVersion":
        """Converte ``vMAJOR.MINOR.PATCH`` e sufixos de pré-lançamento."""
        match = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?", value.strip())
        if not match:
            raise ValueError(f"Versão inválida: {value}")
        prerelease = tuple(match.group(4).split(".")) if match.group(4) else ()
        return cls(int(match.group(1)), int(match.group(2)), int(match.group(3)), prerelease)

    def __lt__(self, other):
        """Compara números e considera uma prévia menor que a versão estável."""
        if not isinstance(other, SemanticVersion):
            return NotImplemented
        ours = (self.major, self.minor, self.patch)
        theirs = (other.major, other.minor, other.patch)
        if ours != theirs:
            return ours < theirs
        if not self.prerelease:
            return False
        if not other.prerelease:
            return True
        for left, right in zip(self.prerelease, other.prerelease):
            if left == right:
                continue
            if left.isdigit() and right.isdigit():
                return int(left) < int(right)
            if left.isdigit() != right.isdigit():
                return left.isdigit()
            return left < right
        return len(self.prerelease) < len(other.prerelease)


@dataclass(frozen=True)
class ReleaseAsset:
    """Arquivo publicado e aprovado para download."""

    name: str
    url: str
    size: int


@dataclass(frozen=True)
class ReleaseInfo:
    """Metadados suficientes para informar, baixar e validar uma versão."""

    version: str
    tag: str
    notes: str
    html_url: str
    asset: ReleaseAsset
    hash_asset: ReleaseAsset
    mandatory: bool = False


def _safe_asset(asset: dict) -> ReleaseAsset:
    """Valida nome, URL HTTPS e origem antes de aceitar um asset remoto."""
    name = str(asset.get("name", ""))
    url = str(asset.get("browser_download_url", ""))
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.hostname not in ALLOWED_DOWNLOAD_HOSTS:
        raise UpdateError("A versão encontrada usa um endereço de download não autorizado.")
    return ReleaseAsset(name, url, int(asset.get("size") or 0))


def parse_release_payload(payload: list[dict], current_version: str) -> ReleaseInfo | None:
    """Escolhe a versão estável posterior que contém ZIP e SHA-256 esperados."""
    current = SemanticVersion.parse(current_version)
    candidates = []
    for release in payload:
        if release.get("draft") or release.get("prerelease"):
            continue
        try:
            version = SemanticVersion.parse(str(release.get("tag_name", "")))
        except ValueError:
            continue
        if version <= current:
            continue
        assets = {str(asset.get("name", "")): asset for asset in release.get("assets", [])}
        package = assets.get(WINDOWS_ASSET_NAME)
        checksum = assets.get(f"{WINDOWS_ASSET_NAME}.sha256")
        if not package or not checksum:
            continue
        candidates.append((version, release, _safe_asset(package), _safe_asset(checksum)))
    if not candidates:
        return None
    version, release, package, checksum = max(candidates, key=lambda entry: entry[0])
    body = str(release.get("body") or "Sem descrição publicada para esta versão.").strip()
    mandatory = "<!-- mandatory: true -->" in body.lower()
    return ReleaseInfo(
        version=f"{version.major}.{version.minor}.{version.patch}",
        tag=str(release["tag_name"]),
        notes=body,
        html_url=str(release.get("html_url", "")),
        asset=package,
        hash_asset=checksum,
        mandatory=mandatory,
    )


class UpdateService:
    """Consulta e transfere versões do repositório público sem exigir token."""

    def __init__(self, repository: str = RELEASE_REPOSITORY, timeout: int = 15):
        """Fixa o repositório oficial e um tempo limite curto de rede."""
        self.repository = repository
        self.timeout = timeout

    def _request(self, url: str):
        """Cria requisição identificada, aceita pela API pública do GitHub."""
        request = urllib.request.Request(
            url,
            headers={"Accept": "application/vnd.github+json", "User-Agent": "BibliaAcessivel-Updater"},
        )
        return urllib.request.urlopen(request, timeout=self.timeout)

    def check(self, current_version: str) -> ReleaseInfo | None:
        """Consulta releases publicadas e devolve a atualização estável mais nova."""
        url = f"https://api.github.com/repos/{self.repository}/releases?per_page=20"
        LOGGER.info("Verificando atualizações: versão=%s url=%s", current_version, url)
        try:
            with self._request(url) as response:
                payload = json.loads(response.read().decode("utf-8"))
            if not isinstance(payload, list):
                raise UpdateError("A resposta do serviço de atualização é inválida.")
            result = parse_release_payload(payload, current_version)
            LOGGER.info("Verificação concluída: encontrada=%s", result.version if result else "nenhuma")
            return result
        except (OSError, urllib.error.URLError, json.JSONDecodeError, UpdateError) as error:
            LOGGER.warning("Falha na verificação de atualização: %s", error)
            if isinstance(error, UpdateError):
                raise
            raise UpdateError("Não foi possível verificar atualizações agora. Tente novamente mais tarde.") from error

    def _download_file(
        self,
        asset: ReleaseAsset,
        destination: Path,
        cancel: Event,
        progress: Callable[[int], None] | None = None,
    ) -> None:
        """Baixa um asset em blocos e permite cancelamento entre eles."""
        destination.parent.mkdir(parents=True, exist_ok=True)
        received = 0
        try:
            with self._request(asset.url) as response, destination.open("wb") as target:
                effective_url = response.geturl() if hasattr(response, "geturl") else asset.url
                effective_host = urllib.parse.urlparse(effective_url).hostname
                if effective_host not in ALLOWED_DOWNLOAD_HOSTS:
                    raise UpdateError("O download foi redirecionado para uma origem não autorizada.")
                total = asset.size or int(response.headers.get("Content-Length") or 0)
                while True:
                    if cancel.is_set():
                        raise DownloadCancelled("Download cancelado.")
                    block = response.read(256 * 1024)
                    if not block:
                        break
                    target.write(block)
                    received += len(block)
                    if progress and total:
                        progress(min(100, int(received * 100 / total)))
            if asset.size and received != asset.size:
                raise UpdateError("O download terminou com um tamanho diferente do publicado.")
        except Exception:
            destination.unlink(missing_ok=True)
            raise

    def download_and_validate(
        self,
        release: ReleaseInfo,
        destination: Path,
        cancel: Event,
        progress: Callable[[int], None] | None = None,
    ) -> str:
        """Baixa pacote e manifesto de hash e remove o pacote se houver divergência."""
        hash_path = destination.with_suffix(destination.suffix + ".sha256.download")
        self._download_file(release.hash_asset, hash_path, cancel)
        try:
            parts = hash_path.read_text(encoding="utf-8").strip().split()
            if not parts:
                raise UpdateError("O arquivo de integridade publicado é inválido.")
            expected = parts[0].lower()
            if not re.fullmatch(r"[0-9a-f]{64}", expected):
                raise UpdateError("O arquivo de integridade publicado é inválido.")
            self._download_file(release.asset, destination, cancel, progress)
            actual = hashlib.sha256(destination.read_bytes()).hexdigest()
            if actual != expected:
                destination.unlink(missing_ok=True)
                raise UpdateError(
                    "A atualização foi baixada, mas não passou na verificação de integridade. "
                    "Nenhum arquivo foi instalado."
                )
            LOGGER.info("Download e SHA-256 validados: versão=%s arquivo=%s", release.version, destination)
            return actual
        finally:
            hash_path.unlink(missing_ok=True)
