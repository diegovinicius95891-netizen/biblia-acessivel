"""Testes do cliente da OpenAI sem efetuar chamadas externas."""

import json
import unittest
from unittest.mock import patch

from biblia.ai_client import AiServiceError, create_bible_analysis


class _FakeResponse:
    """Imita a parte usada de uma resposta HTTP válida."""

    def __enter__(self):
        """Permite o uso pelo gerenciador de contexto."""
        return self

    def __exit__(self, *_args):
        """Finaliza o contexto sem ocultar exceções."""
        return False

    def read(self):
        """Devolve uma estrutura realista da Responses API."""
        return json.dumps(
            {"output": [{"type": "message", "content": [{"type": "output_text", "text": "Resumo."}]}]}
        ).encode("utf-8")


class AiClientTests(unittest.TestCase):
    """Valida montagem, extração e exigência de chave no cliente."""

    def test_extracts_text_from_response(self):
        """Obtém texto do item de mensagem retornado pela API."""
        with patch("biblia.ai_client.urlopen", return_value=_FakeResponse()) as mocked:
            result = create_bible_analysis("sk-teste", "gpt-5.6-luna", "Resuma", "Texto", "curto")
        self.assertEqual("Resumo.", result)
        request = mocked.call_args.args[0]
        payload = json.loads(request.data.decode("utf-8"))
        self.assertFalse(payload["store"])
        self.assertEqual("gpt-5.6-luna", payload["model"])

    def test_requires_api_key(self):
        """Recusa uma solicitação local antes da rede quando a chave está vazia."""
        with self.assertRaises(AiServiceError):
            create_bible_analysis("", "gpt-5.6-luna", "Resuma", "Texto", "curto")


if __name__ == "__main__":
    unittest.main()
