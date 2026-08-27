"""Testes do fluxo seguro de atualização sem acessar a internet."""

import hashlib
import io
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event
import unittest
from unittest.mock import patch

from biblia.update_service import (
    DownloadCancelled,
    ReleaseAsset,
    ReleaseInfo,
    SemanticVersion,
    UpdateError,
    UpdateService,
    parse_release_payload,
)


def asset(name, content=b""):
    """Cria metadado de asset confiável para os cenários de parsing."""
    return {
        "name": name,
        "browser_download_url": f"https://github.com/owner/repo/releases/download/v2.0.0/{name}",
        "size": len(content),
    }


class FakeResponse(io.BytesIO):
    """Resposta de contexto mínima compatível com urllib."""

    def __init__(self, content):
        """Guarda conteúdo e informa seu tamanho como um servidor HTTP faria."""
        super().__init__(content)
        self.headers = {"Content-Length": str(len(content))}

    def __enter__(self):
        """Permite uso pelo protocolo de gerenciador de contexto."""
        return self

    def __exit__(self, *_args):
        """Fecha o fluxo simulado ao final da requisição."""
        self.close()


class UpdateServiceTests(unittest.TestCase):
    """Valida comparação, filtros, seleção, cancelamento e integridade."""

    def test_semantic_version_comparison_is_numeric(self):
        """Garante que 1.10.0 supere 1.9.9 e que prévia fique abaixo da estável."""
        self.assertGreater(SemanticVersion.parse("1.10.0"), SemanticVersion.parse("1.9.9"))
        self.assertGreater(SemanticVersion.parse("2.0.0"), SemanticVersion.parse("1.99.99"))
        self.assertLess(SemanticVersion.parse("1.6.0-beta.1"), SemanticVersion.parse("1.6.0"))

    def test_release_parser_ignores_draft_prerelease_and_source_files(self):
        """Seleciona somente uma release estável com ZIP e SHA esperados."""
        good_assets = [asset("BibliaAcessivel-Windows.zip"), asset("BibliaAcessivel-Windows.zip.sha256")]
        payload = [
            {"tag_name": "v9.0.0", "draft": True, "prerelease": False, "assets": good_assets},
            {"tag_name": "v8.0.0-beta.1", "draft": False, "prerelease": True, "assets": good_assets},
            {"tag_name": "v3.0.0", "draft": False, "prerelease": False,
             "assets": [asset("Source code.zip")], "body": "fonte"},
            {"tag_name": "v2.0.0", "draft": False, "prerelease": False,
             "assets": good_assets, "body": "Novidades", "html_url": "https://github.com/x/y"},
        ]
        release = parse_release_payload(payload, "1.9.0")
        self.assertEqual("2.0.0", release.version)
        self.assertEqual("BibliaAcessivel-Windows.zip", release.asset.name)
        self.assertIsNone(parse_release_payload(payload, "2.0.0"))

    def test_hash_success_and_failure(self):
        """Aceita o hash correto e apaga o pacote quando o hash diverge."""
        content = b"pacote testado"
        expected = hashlib.sha256(content).hexdigest().encode()
        release = ReleaseInfo(
            "2.0.0", "v2.0.0", "Notas", "https://github.com/x/y",
            ReleaseAsset("BibliaAcessivel-Windows.zip", "https://github.com/x/package", len(content)),
            ReleaseAsset("BibliaAcessivel-Windows.zip.sha256", "https://github.com/x/hash", len(expected)),
        )
        with TemporaryDirectory() as directory:
            destination = Path(directory) / release.asset.name
            service = UpdateService()
            service._request = lambda url: FakeResponse(expected if url.endswith("hash") else content)
            self.assertEqual(hashlib.sha256(content).hexdigest(), service.download_and_validate(release, destination, Event()))
            self.assertTrue(destination.exists())
            service._request = lambda url: FakeResponse(b"0" * 64 if url.endswith("hash") else content)
            with self.assertRaises(UpdateError):
                service.download_and_validate(release, destination, Event())
            self.assertFalse(destination.exists())

    def test_cancelled_download_removes_partial_file(self):
        """Interrompe antes de gravar e não deixa arquivo incompleto."""
        with TemporaryDirectory() as directory:
            target = Path(directory) / "download.zip"
            service = UpdateService()
            service._request = lambda _url: FakeResponse(b"dados")
            cancel = Event(); cancel.set()
            with self.assertRaises(DownloadCancelled):
                service._download_file(ReleaseAsset("download.zip", "https://github.com/x", 5), target, cancel)
            self.assertFalse(target.exists())

    def test_check_reports_no_update_and_wraps_network_failure(self):
        """Cobre versão atual e indisponibilidade sem expor erro técnico."""
        service = UpdateService()
        current = json.dumps([
            {"tag_name": "v2.0.0", "draft": False, "prerelease": False, "assets": []}
        ]).encode()
        service._request = lambda _url: FakeResponse(current)
        self.assertIsNone(service.check("2.0.0"))
        with patch.object(service, "_request", side_effect=OSError("rede indisponível")):
            with self.assertRaisesRegex(UpdateError, "Não foi possível verificar"):
                service.check("2.0.0")

    def test_rejects_asset_outside_github(self):
        """Não aceita URL arbitrária mesmo quando ela aparece num payload bem formado."""
        payload = [{
            "tag_name": "v2.1.0", "draft": False, "prerelease": False, "body": "Notas",
            "assets": [
                {"name": "BibliaAcessivel-Windows.zip", "browser_download_url": "https://example.com/app.zip", "size": 1},
                asset("BibliaAcessivel-Windows.zip.sha256"),
            ],
        }]
        with self.assertRaises(UpdateError):
            parse_release_payload(payload, "2.0.0")


if __name__ == "__main__":
    unittest.main()
