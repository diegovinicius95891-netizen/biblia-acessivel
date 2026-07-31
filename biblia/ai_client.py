"""Cliente mínimo e assíncrono para os recursos opcionais de IA.

O módulo usa somente a biblioteca padrão para não aumentar as dependências do
aplicativo. A chave recebida permanece em memória durante a requisição e nunca
é registrada em arquivo, log ou mensagem de erro.
"""

from __future__ import annotations

import json
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


RESPONSES_URL = "https://api.openai.com/v1/responses"


class AiServiceError(RuntimeError):
    """Representa uma falha segura e compreensível ao chamar a API."""


def create_bible_analysis(
    api_key: str,
    model: str,
    task_instruction: str,
    bible_text: str,
    detail: str,
) -> str:
    """Envia o texto à Responses API e devolve somente a resposta textual."""
    if not api_key.strip():
        raise AiServiceError("Informe sua chave da API antes de gerar o conteúdo.")

    output_limits = {"curto": 500, "médio": 900, "detalhado": 1500}
    verbosity = {"curto": "low", "médio": "medium", "detalhado": "high"}
    payload = {
        "model": model,
        "store": False,
        "reasoning": {"effort": "low"},
        "text": {"verbosity": verbosity.get(detail, "medium")},
        "max_output_tokens": output_limits.get(detail, 900),
        "instructions": (
            "Responda em português do Brasil, com linguagem respeitosa, clara e acessível. "
            "Baseie-se apenas no texto bíblico fornecido. Diferencie resumo textual de "
            "interpretação e avise brevemente que a resposta é gerada por IA e pode conter "
            "erros. Não invente citações, contexto histórico ou doutrina."
        ),
        "input": f"Tarefa: {task_instruction}\n\nTexto bíblico:\n{bible_text}",
    }
    request = Request(
        RESPONSES_URL,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key.strip()}",
            "Content-Type": "application/json",
        },
        method="POST",
    )

    try:
        with urlopen(request, timeout=120) as response:
            result = json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        message = _api_error_message(error)
        raise AiServiceError(message) from None
    except URLError:
        raise AiServiceError(
            "Não foi possível acessar a OpenAI. Verifique sua internet e tente novamente."
        ) from None
    except (TimeoutError, json.JSONDecodeError):
        raise AiServiceError("A resposta da IA demorou demais ou chegou em formato inválido.") from None

    text_parts = []
    for output in result.get("output", []):
        if output.get("type") != "message":
            continue
        for content in output.get("content", []):
            if content.get("type") == "output_text" and content.get("text"):
                text_parts.append(content["text"].strip())
    if not text_parts:
        raise AiServiceError("A API não devolveu conteúdo textual para esta solicitação.")
    return "\n\n".join(text_parts)


def _api_error_message(error: HTTPError) -> str:
    """Converte respostas de erro da API sem incluir credenciais ou dados bíblicos."""
    try:
        body = json.loads(error.read().decode("utf-8"))
        api_message = body.get("error", {}).get("message", "")
    except (json.JSONDecodeError, UnicodeDecodeError):
        api_message = ""
    if error.code == 401:
        return "A chave da API foi recusada. Confira a chave informada."
    if error.code == 429:
        return "O limite ou o saldo da conta da API foi atingido. Confira sua conta OpenAI."
    if api_message:
        return f"A OpenAI recusou a solicitação: {api_message}"
    return f"A OpenAI devolveu o erro HTTP {error.code}."
