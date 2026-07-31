"""Testes do cliente Google Gemini sem efetuar chamadas externas."""

import json
import unittest
from unittest.mock import patch

from biblia.gemini_client import GeminiServiceError, create_bible_analysis


class _FakeResponse:
    """Imita a parte usada de uma resposta HTTP válida do Gemini."""

    def __enter__(self):
        """Permite o uso pelo gerenciador de contexto."""
        return self

    def __exit__(self, *_args):
        """Finaliza o contexto sem ocultar exceções."""
        return False

    def read(self):
        """Devolve uma estrutura realista de ``generateContent``."""
        return json.dumps(
            {"candidates": [{"content": {"parts": [{"text": "Resumo do Gemini."}]}}]}
        ).encode("utf-8")


class GeminiClientTests(unittest.TestCase):
    """Valida montagem da requisição, extração de texto e chave obrigatória."""

    def test_extracts_text_from_generate_content(self):
        """Obtém texto e usa o cabeçalho oficial de autenticação do Google."""
        with patch("biblia.gemini_client.urlopen", return_value=_FakeResponse()) as mocked:
            result = create_bible_analysis(
                "chave-teste", "gemini-2.5-flash-lite", "Resuma", "Texto", "curto"
            )
        self.assertEqual("Resumo do Gemini.", result)
        request = mocked.call_args.args[0]
        payload = json.loads(request.data.decode("utf-8"))
        self.assertIn("contents", payload)
        self.assertEqual("chave-teste", request.get_header("X-goog-api-key"))
        self.assertIn("gemini-2.5-flash-lite:generateContent", request.full_url)

    def test_requires_api_key(self):
        """Recusa localmente uma solicitação cuja chave está vazia."""
        with self.assertRaises(GeminiServiceError):
            create_bible_analysis("", "gemini-2.5-flash-lite", "Resuma", "Texto", "curto")


if __name__ == "__main__":
    unittest.main()
