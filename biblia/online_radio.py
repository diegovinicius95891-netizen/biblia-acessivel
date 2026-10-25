"""Catálogo remoto e cache local dos louvores publicados no site oficial."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from urllib.parse import quote, urljoin, urlparse
from urllib.request import Request, urlopen


RADIO_FEED_URL = "https://audiogames.com.br/diego/louvores/louvores.json"
RADIO_AUDIO_BASE_URL = "https://audiogames.com.br/diego/louvores/uploads/"
MAX_FEED_BYTES = 5 * 1024 * 1024


class RadioFeedError(RuntimeError):
    """Falha operacional apresentada sem detalhes técnicos ao usuário."""


@dataclass(frozen=True)
class GospelTrack:
    """Louvor validado, pronto para listagem e reprodução."""

    identifier: str
    title: str
    audio_url: str
    artist: str = ""
    description: str = ""
    published_at: str = ""

    @property
    def label(self) -> str:
        detail = self.artist.strip()
        return f"{self.title} — {detail}" if detail else self.title


def _safe_audio_url(filename: str) -> str:
    """Aceita somente um nome MP3 simples dentro da pasta pública de louvores."""
    clean = filename.strip()
    path = PurePosixPath(clean)
    if not clean or path.name != clean or path.suffix.lower() != ".mp3":
        return ""
    return urljoin(RADIO_AUDIO_BASE_URL, quote(clean))


def parse_radio_feed(payload: str | bytes) -> list[GospelTrack]:
    """Valida o JSON público, ignora rascunhos e remove itens repetidos."""
    try:
        if isinstance(payload, bytes):
            payload = payload.decode("utf-8")
        records = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RadioFeedError("A lista da Rádio Gospel chegou inválida.") from error
    if not isinstance(records, list):
        raise RadioFeedError("A lista da Rádio Gospel chegou inválida.")

    tracks: list[GospelTrack] = []
    seen: set[str] = set()
    for record in records:
        if not isinstance(record, dict):
            continue
        status = str(record.get("status", "publicado")).strip().lower()
        if status not in ("", "publicado", "published"):
            continue
        identifier = str(record.get("id", "")).strip()
        title = str(record.get("titulo", "")).strip()
        audio_url = _safe_audio_url(str(record.get("arquivo_salvo", "")))
        if not identifier or identifier in seen or not title or not audio_url:
            continue
        seen.add(identifier)
        artist = str(record.get("artista_original") or record.get("compositor") or "").strip()
        tracks.append(GospelTrack(
            identifier=identifier,
            title=title,
            audio_url=audio_url,
            artist=artist,
            description=str(record.get("descricao", "")).strip(),
            published_at=str(record.get("data", record.get("atualizado_em", ""))).strip(),
        ))
    tracks.sort(key=lambda track: (track.published_at, track.identifier), reverse=True)
    return tracks


class OnlineRadioService:
    """Baixa o catálogo HTTPS e mantém a última lista válida para uso posterior."""

    def __init__(self, cache_path: Path):
        self.cache_path = cache_path

    def fetch(self) -> list[GospelTrack]:
        request = Request(RADIO_FEED_URL, headers={"User-Agent": "BibliaAcessivel-Windows/4.3.1"})
        try:
            with urlopen(request, timeout=20) as response:
                final = urlparse(response.geturl())
                if final.scheme != "https" or final.hostname != "audiogames.com.br":
                    raise RadioFeedError("A Rádio Gospel respondeu por um endereço não autorizado.")
                data = response.read(MAX_FEED_BYTES + 1)
        except RadioFeedError:
            raise
        except OSError as error:
            raise RadioFeedError(
                "Não foi possível atualizar a Rádio Gospel. Verifique a internet e tente novamente."
            ) from error
        if len(data) > MAX_FEED_BYTES:
            raise RadioFeedError("A lista da Rádio Gospel excedeu o tamanho permitido.")
        tracks = parse_radio_feed(data)
        self._save_cache(data)
        return tracks

    def load_cache(self) -> list[GospelTrack]:
        try:
            return parse_radio_feed(self.cache_path.read_text(encoding="utf-8"))
        except (OSError, RadioFeedError):
            return []

    def _save_cache(self, payload: bytes) -> None:
        try:
            self.cache_path.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.cache_path.with_suffix(self.cache_path.suffix + ".tmp")
            temporary.write_bytes(payload)
            temporary.replace(self.cache_path)
        except OSError as error:
            raise RadioFeedError("Não foi possível guardar a lista da Rádio Gospel.") from error
