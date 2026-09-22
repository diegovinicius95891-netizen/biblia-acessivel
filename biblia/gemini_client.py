"""Cliente mínimo da API Google Gemini para resumos e explicações bíblicas."""

from __future__ import annotations

import json
import re
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


GENERATE_CONTENT_URL = (
    "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
)


class GeminiServiceError(RuntimeError):
    """Representa uma falha da API em linguagem compreensível e sem expor a chave."""


def create_bible_analysis(
    api_key: str,
    model: str,
    task_instruction: str,
    bible_text: str,
    detail: str,
) -> str:
    """Chama ``generateContent`` e extrai todos os trechos textuais retornados."""
    if not api_key.strip():
        raise GeminiServiceError("Informe e salve sua chave da API do Google antes de continuar.")

    # O limite anterior era menor que alguns resumos solicitados e podia cortar
    # a frase final. Estes tetos deixam folga para o modelo concluir, enquanto a
    # instrução da tarefa controla o tamanho legível em palavras.
    output_limits = {"curto": 1536, "médio": 3072, "detalhado": 5120}
    system_instruction = (
        "Responda em português do Brasil, com linguagem respeitosa, clara e acessível. "
        "Baseie-se apenas no texto bíblico fornecido. Diferencie resumo textual de "
        "interpretação e avise brevemente que a resposta é gerada por IA e pode conter "
        "erros. Não invente citações, contexto histórico ou doutrina."
        " Responda somente em texto simples: não use Markdown, asteriscos, cerquilhas, "
        "sublinhados, crases ou tabelas. Use frases e parágrafos comuns."
        " Respeite rigorosamente o limite de palavras informado na tarefa e "
        "reserve espaço para concluir a última frase."
    )
    payload = {
        "system_instruction": {"parts": [{"text": system_instruction}]},
        "contents": [
            {
                "role": "user",
                "parts": [
                    {"text": f"Tarefa: {task_instruction}\n\nTexto bíblico:\n{bible_text}"}
                ],
            }
        ],
        "generationConfig": {
            "maxOutputTokens": output_limits.get(detail, 3072),
            "temperature": 0.3,
        },
    }
    request = Request(
        GENERATE_CONTENT_URL.format(model=quote(model, safe="")),
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"x-goog-api-key": api_key.strip(), "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=120) as response:
            result = json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        raise GeminiServiceError(_api_error_message(error)) from None
    except URLError:
        raise GeminiServiceError(
            "Não foi possível acessar o Google Gemini. Verifique sua internet e tente novamente."
        ) from None
    except (TimeoutError, json.JSONDecodeError):
        raise GeminiServiceError("A resposta do Google demorou demais ou chegou inválida.") from None

    text_parts = []
    for candidate in result.get("candidates", []):
        for part in candidate.get("content", {}).get("parts", []):
            if part.get("text"):
                text_parts.append(part["text"].strip())
    finish_reasons = [
        candidate.get("finishReason") for candidate in result.get("candidates", [])
        if candidate.get("finishReason")
    ]
    if text_parts and "MAX_TOKENS" not in finish_reasons:
        return _plain_text("\n\n".join(text_parts))
    if text_parts and "MAX_TOKENS" in finish_reasons:
        raise GeminiServiceError(
            "O Gemini atingiu o limite antes de concluir. Escolha o detalhamento Curto nas "
            "Configurações e tente novamente."
        )

    block_reason = result.get("promptFeedback", {}).get("blockReason")
    if block_reason:
        raise GeminiServiceError(f"O Google bloqueou esta solicitação: {block_reason}.")
    if finish_reasons:
        raise GeminiServiceError(
            "O Gemini terminou sem produzir texto. Motivo: " + ", ".join(finish_reasons) + "."
        )
    raise GeminiServiceError("O Gemini respondeu, mas não devolveu nenhum texto.")


def _plain_text(text: str) -> str:
    """Remove marcas comuns de Markdown que atrapalham a leitura por voz."""
    text = re.sub(r"(?m)^\s{0,3}#{1,6}\s*", "", text)
    text = re.sub(r"\[([^]]+)]\([^)]+\)", r"\1", text)
    text = re.sub(r"(?m)^[ \t]*[*+][ \t]+", "- ", text)
    text = text.replace("*", "").replace("_", "").replace("`", "")
    text = re.sub(r"[ \t]+\n", "\n", text)
    return text.strip()


def _api_error_message(error: HTTPError) -> str:
    """Traduz erros HTTP do Google sem registrar chave ou conteúdo enviado."""
    try:
        body = json.loads(error.read().decode("utf-8"))
        api_message = body.get("error", {}).get("message", "")
    except (json.JSONDecodeError, UnicodeDecodeError):
        api_message = ""
    if error.code in (401, 403):
        suffix = f" Detalhe do Google: {api_message}" if api_message else ""
        return "A chave da API do Google foi recusada ou não tem permissão para o Gemini." + suffix
    if error.code == 429:
        return "A cota da API do Google foi atingida. Confira limites e faturamento no AI Studio."
    if error.code == 404:
        return "O modelo escolhido não está disponível para esta chave. Escolha outro modelo."
    if api_message:
        return f"O Google recusou a solicitação: {api_message}"
    return f"A API do Google devolveu o erro HTTP {error.code}."
