"""Consulta e armazena os devocionais públicos do site oficial.

O módulo não conhece a interface gráfica. Isso mantém validação, cache e rede
testáveis e permite que a janela continue responsiva durante a consulta.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen


DEVOTIONALS_FEED_URL = "https://audiogames.com.br/diego/devocionais/posts.json"
_MAX_FEED_BYTES = 5 * 1024 * 1024


class DevotionalFeedError(RuntimeError):
    """Erro legível ao consultar ou validar a fonte pública."""


@dataclass(frozen=True)
class OnlineDevotional:
    """Um texto público com identidade estável e data de publicação."""

    identifier: str
    title: str
    content: str
    published_at: str

    @property
    def date_label(self) -> str:
        """Apresenta a data brasileira sem falhar com registros antigos."""
        try:
            return datetime.fromisoformat(self.published_at.replace("Z", "+00:00")).strftime(
                "%d/%m/%Y às %H:%M"
            )
        except ValueError:
            return self.published_at or "Data não informada"


def parse_devotional_feed(payload: str | bytes) -> list[OnlineDevotional]:
    """Valida o JSON, remove rascunhos e devolve itens novos primeiro."""
    try:
        decoded = payload.decode("utf-8-sig") if isinstance(payload, bytes) else payload
        records = json.loads(decoded)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise DevotionalFeedError("A lista de devocionais recebida está inválida.") from error
    if not isinstance(records, list):
        raise DevotionalFeedError("A lista de devocionais recebida está inválida.")

    result: list[OnlineDevotional] = []
    seen: set[str] = set()
    for record in records:
        if not isinstance(record, dict):
            continue
        status = str(record.get("status", "publicado")).strip().casefold()
        if status not in ("", "publicado", "published"):
            continue
        identifier = str(record.get("id", "")).strip()
        title = str(record.get("titulo", "")).strip()
        content = str(record.get("conteudo", "")).strip()
        published_at = str(record.get("data", record.get("atualizado_em", ""))).strip()
        if not identifier or identifier in seen or not title or not content:
            continue
        seen.add(identifier)
        result.append(OnlineDevotional(identifier, title, content, published_at))

    result.sort(key=lambda item: (_date_sort_key(item.published_at), item.identifier), reverse=True)
    return result


def _date_sort_key(value: str) -> datetime:
    """Transforma datas ISO ou do painel em chave comparável."""
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.replace(tzinfo=None) if parsed.tzinfo else parsed
    except ValueError:
        return datetime.min


class OnlineDevotionalService:
    """Baixa a fonte HTTPS e mantém a última cópia válida para uso offline."""

    def __init__(self, cache_path: Path, feed_url: str = DEVOTIONALS_FEED_URL):
        self.cache_path = Path(cache_path)
        self.feed_url = feed_url

    def fetch(self, timeout: float = 15.0) -> list[OnlineDevotional]:
        """Consulta o site, limita o tamanho e atualiza o cache atomicamente."""
        request = Request(self.feed_url, headers={"User-Agent": "BibliaAcessivel/4.0"})
        try:
            with urlopen(request, timeout=timeout) as response:
                final = urlparse(response.geturl())
                if final.scheme != "https" or final.hostname != "audiogames.com.br":
                    raise DevotionalFeedError("O site redirecionou para um endereço não autorizado.")
                data = response.read(_MAX_FEED_BYTES + 1)
        except DevotionalFeedError:
            raise
        except OSError as error:
            raise DevotionalFeedError(
                "Não foi possível consultar os devocionais. Verifique a internet e tente novamente."
            ) from error
        if len(data) > _MAX_FEED_BYTES:
            raise DevotionalFeedError("A lista de devocionais excedeu o tamanho permitido.")
        posts = parse_devotional_feed(data)
        self.save_cache(posts)
        return posts

    def load_cache(self) -> list[OnlineDevotional]:
        """Recupera somente uma cópia local válida; corrupção não derruba o app."""
        try:
            if not self.cache_path.is_file():
                return []
            return parse_devotional_feed(self.cache_path.read_text(encoding="utf-8"))
        except (OSError, DevotionalFeedError):
            return []

    def save_cache(self, posts: list[OnlineDevotional]) -> None:
        """Substitui o cache somente depois de terminar toda a gravação."""
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.cache_path.with_suffix(self.cache_path.suffix + ".tmp")
        records = [
            {
                "id": post.identifier,
                "titulo": post.title,
                "conteudo": post.content,
                "data": post.published_at,
                "status": "publicado",
            }
            for post in posts
        ]
        try:
            temporary.write_text(
                json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8"
            )
            temporary.replace(self.cache_path)
        except OSError as error:
            temporary.unlink(missing_ok=True)
            raise DevotionalFeedError("Não foi possível guardar os devocionais para leitura offline.") from error
