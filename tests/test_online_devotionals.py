"""Testes da fonte pública e do cache de devocionais."""

import json
import tempfile
import unittest
from pathlib import Path

from biblia.online_devotionals import (
    DevotionalFeedError,
    OnlineDevotionalService,
    parse_devotional_feed,
)


class OnlineDevotionalTests(unittest.TestCase):
    def test_filters_drafts_duplicates_and_invalid_records(self):
        payload = json.dumps([
            {"id": "2", "titulo": "Novo", "conteudo": "Texto", "data": "2026-10-20 12:00:00", "status": "publicado"},
            {"id": "1", "titulo": "Antigo", "conteudo": "Texto", "data": "2026-10-19 12:00:00"},
            {"id": "2", "titulo": "Duplicado", "conteudo": "Texto"},
            {"id": "3", "titulo": "Rascunho", "conteudo": "Texto", "status": "rascunho"},
            {"id": "4", "titulo": "Sem texto", "conteudo": ""},
        ])
        posts = parse_devotional_feed(payload)
        self.assertEqual(["2", "1"], [post.identifier for post in posts])
        self.assertEqual("20/10/2026 às 12:00", posts[0].date_label)

    def test_rejects_malformed_feed(self):
        with self.assertRaises(DevotionalFeedError):
            parse_devotional_feed('{"not": "a list"}')

    def test_cache_round_trip_and_corruption_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "devotionals.json"
            service = OnlineDevotionalService(path)
            posts = parse_devotional_feed('[{"id":"1","titulo":"Título","conteudo":"Corpo","data":"2026-10-20 10:00:00"}]')
            service.save_cache(posts)
            self.assertEqual(posts, service.load_cache())
            path.write_text("inválido", encoding="utf-8")
            self.assertEqual([], service.load_cache())


if __name__ == "__main__":
    unittest.main()
