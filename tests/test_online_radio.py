import tempfile
import unittest
from pathlib import Path

from biblia.online_radio import OnlineRadioService, RadioFeedError, parse_radio_feed


class OnlineRadioTests(unittest.TestCase):
    def test_parses_published_mp3_and_rejects_drafts_or_unsafe_paths(self):
        payload = """[
          {"id":"2","titulo":"Louvor novo","arquivo_salvo":"novo som.mp3","status":"publicado","data":"2026-10-20 10:00:00","compositor":"Autor"},
          {"id":"1","titulo":"Rascunho","arquivo_salvo":"rascunho.mp3","status":"rascunho"},
          {"id":"3","titulo":"Inválido","arquivo_salvo":"../segredo.mp3"}
        ]"""
        tracks = parse_radio_feed(payload)
        self.assertEqual(1, len(tracks))
        self.assertEqual("Louvor novo — Autor", tracks[0].label)
        self.assertTrue(tracks[0].audio_url.endswith("novo%20som.mp3"))

    def test_rejects_non_list(self):
        with self.assertRaises(RadioFeedError):
            parse_radio_feed('{"erro":true}')

    def test_load_cache_is_tolerant_and_reads_valid_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "radio.json"
            service = OnlineRadioService(path)
            self.assertEqual([], service.load_cache())
            path.write_text('[{"id":"1","titulo":"Faixa","arquivo_salvo":"faixa.mp3"}]', encoding="utf-8")
            self.assertEqual("Faixa", service.load_cache()[0].title)


if __name__ == "__main__":
    unittest.main()
