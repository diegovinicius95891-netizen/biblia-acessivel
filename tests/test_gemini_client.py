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
            {"candidates": [{"content": {"parts": [{"text": "**Resumo**\n\n* Item do Gemini."}]}}]}
        ).encode("utf-8")


class _TruncatedResponse(_FakeResponse):
    """Imita uma resposta parcial interrompida pelo limite de saída."""

    def read(self):
        """Marca explicitamente o término como MAX_TOKENS."""
        return json.dumps(
            {
                "candidates": [
                    {
                        "finishReason": "MAX_TOKENS",
                        "content": {"parts": [{"text": "Resposta cortada"}]},
                    }
                ]
            }
        ).encode("utf-8")


class GeminiClientTests(unittest.TestCase):
    """Valida montagem da requisição, extração de texto e chave obrigatória."""

    def test_extracts_text_from_generate_content(self):
        """Obtém texto e usa o cabeçalho oficial de autenticação do Google."""
        with patch("biblia.gemini_client.urlopen", return_value=_FakeResponse()) as mocked:
            result = create_bible_analysis(
                "chave-teste", "gemini-2.5-flash-lite", "Resuma", "Texto", "curto"
            )
        self.assertEqual("Resumo\n\n- Item do Gemini.", result)
        self.assertNotIn("*", result)
        request = mocked.call_args.args[0]
        payload = json.loads(request.data.decode("utf-8"))
        self.assertIn("contents", payload)
        self.assertEqual(1536, payload["generationConfig"]["maxOutputTokens"])
        self.assertEqual("chave-teste", request.get_header("X-goog-api-key"))
        self.assertIn("gemini-2.5-flash-lite:generateContent", request.full_url)

    def test_requires_api_key(self):
        """Recusa localmente uma solicitação cuja chave está vazia."""
        with self.assertRaises(GeminiServiceError):
            create_bible_analysis("", "gemini-2.5-flash-lite", "Resuma", "Texto", "curto")

    def test_truncated_output_is_not_presented_as_complete(self):
        """Explica MAX_TOKENS em vez de mostrar silenciosamente texto incompleto."""
        with patch("biblia.gemini_client.urlopen", return_value=_TruncatedResponse()):
            with self.assertRaisesRegex(GeminiServiceError, "detalhamento Curto"):
                create_bible_analysis(
                    "chave-teste", "gemini-2.5-flash-lite", "Resuma", "Texto", "detalhado"
                )


if __name__ == "__main__":
    unittest.main()
