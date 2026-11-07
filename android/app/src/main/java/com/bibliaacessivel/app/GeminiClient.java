package com.bibliaacessivel.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Executa a chamada opcional ao Gemini sem incluir bibliotecas de rede externas. */
final class GeminiClient {
    /** Limite do texto bíblico enviado, para livros longos como Salmos não estourarem memória ou cota. */
    static final int MAX_CONTEXT_CHARS = 120_000;
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/";

    private GeminiClient() {
        // O cliente expõe somente operações estáticas.
    }

    /** Falha já traduzida para uma mensagem que pode ser lida em voz alta. */
    static final class AiException extends Exception {
        AiException(String message) {
            super(message);
        }
    }

    static String generate(String key, String model, String instruction, String bibleText)
            throws AiException {
        if (key == null || key.trim().isEmpty()) {
            throw new AiException("Informe a chave do Google Gemini em Mais opções, Configurações.");
        }
        String context = bibleText == null ? "" : bibleText;
        if (context.length() > MAX_CONTEXT_CHARS) {
            context = context.substring(0, MAX_CONTEXT_CHARS) +
                    "\n\n[Texto encurtado pelo aplicativo por ser muito longo.]";
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(
                    new JSONObject().put("text", "Responda em português do Brasil, em texto simples, " +
                            "sem Markdown. Baseie-se no texto fornecido, respeite o limite pedido e " +
                            "reserve espaço para uma conclusão completa. Não invente citações, palavras em " +
                            "idiomas bíblicos ou consensos acadêmicos. Diferencie o texto bíblico, o contexto " +
                            "e as interpretações. Avise que a resposta é gerada por IA e pode conter erros."))));
            payload.put("contents", new JSONArray().put(new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text",
                            "Tarefa: " + instruction + "\n\nTexto bíblico:\n" + context)))));
            JSONObject generation = new JSONObject()
                    .put("maxOutputTokens", 4096)
                    .put("temperature", 0.3);
            // Modelos que "pensam" gastam o limite de saída raciocinando; um orçamento pequeno
            // evita respostas vazias ou cortadas.
            if (model.contains("2.5")) {
                generation.put("thinkingConfig", new JSONObject()
                        .put("thinkingBudget", model.contains("pro") ? 512 : 0));
            }
            payload.put("generationConfig", generation);
            JSONObject response = request(key, "models/" + model.trim() + ":generateContent", payload);
            return parseResponse(response);
        } catch (AiException error) {
            throw error;
        } catch (UnknownHostException error) {
            throw new AiException("Sem conexão com a internet. Confira o Wi-Fi ou os dados móveis e tente de novo.");
        } catch (SocketTimeoutException error) {
            throw new AiException("O Google Gemini demorou demais para responder. Tente de novo em instantes.");
        } catch (OutOfMemoryError error) {
            throw new AiException("O texto ficou grande demais para este aparelho. Tente um capítulo em vez do livro inteiro.");
        } catch (Exception error) {
            throw new AiException("Não foi possível falar com o Google Gemini agora. Tente de novo em instantes.");
        }
    }

    /** Lista os modelos disponíveis para a chave, para o usuário não escolher um modelo desativado. */
    static List<String> listModels(String key) throws AiException {
        try {
            JSONObject response = request(key, "models?pageSize=200", null);
            JSONArray models = response.optJSONArray("models");
            List<String> result = new ArrayList<>();
            if (models == null) return result;
            for (int index = 0; index < models.length(); index++) {
                JSONObject model = models.getJSONObject(index);
                String name = model.optString("name").replace("models/", "");
                JSONArray methods = model.optJSONArray("supportedGenerationMethods");
                boolean generates = false;
                for (int method = 0; methods != null && method < methods.length(); method++) {
                    if ("generateContent".equals(methods.optString(method))) generates = true;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (generates && lower.startsWith("gemini-") && !lower.contains("embedding") &&
                        !lower.contains("tts") && !lower.contains("image") && !lower.contains("audio") &&
                        !lower.contains("live")) {
                    result.add(name);
                }
            }
            return result;
        } catch (AiException error) {
            throw error;
        } catch (Exception error) {
            throw new AiException("Não foi possível consultar os modelos agora.");
        }
    }

    /** Interpreta a resposta, inclusive bloqueios de segurança e respostas cortadas. */
    static String parseResponse(JSONObject response) throws AiException {
        JSONObject feedback = response.optJSONObject("promptFeedback");
        if (feedback != null && !feedback.optString("blockReason").isEmpty()) {
            throw new AiException("O Google Gemini recusou este pedido. Reformule a pergunta e tente novamente.");
        }
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new AiException("O Google Gemini não devolveu texto. Tente novamente.");
        }
        JSONObject candidate = candidates.optJSONObject(0);
        String finish = candidate == null ? "" : candidate.optString("finishReason");
        JSONObject content = candidate == null ? null : candidate.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder result = new StringBuilder();
        for (int index = 0; parts != null && index < parts.length(); index++) {
            JSONObject part = parts.optJSONObject(index);
            if (part == null || part.optBoolean("thought")) continue;
            String text = part.optString("text").trim();
            if (!text.isEmpty()) result.append(text).append("\n\n");
        }
        String text = result.toString().replace("*", "").replace("`", "").replace("#", "").trim();
        if (text.isEmpty()) {
            if ("SAFETY".equals(finish) || "PROHIBITED_CONTENT".equals(finish) || "BLOCKLIST".equals(finish)) {
                throw new AiException("O Google Gemini recusou este pedido. Reformule a pergunta e tente novamente.");
            }
            if ("MAX_TOKENS".equals(finish)) {
                throw new AiException("A resposta ficou longa demais e veio vazia. Tente um texto menor ou outro modelo.");
            }
            throw new AiException("O Google Gemini não devolveu texto. Tente novamente.");
        }
        if ("MAX_TOKENS".equals(finish)) {
            text += "\n\n[A resposta foi interrompida por atingir o limite de tamanho.]";
        }
        return text;
    }

    /** Converte códigos HTTP do Google em orientações claras. */
    static String httpMessage(int status, String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (status == 400 && lower.contains("api key")) {
            return "A chave do Google Gemini é inválida. Confira a chave em Mais opções, Configurações.";
        }
        if (status == 400 && lower.contains("location")) {
            return "O Google Gemini não está disponível para esta conta ou região.";
        }
        if (status == 401 || status == 403) {
            return "A chave do Google Gemini não tem permissão. Gere uma nova chave e salve em Configurações.";
        }
        if (status == 404) {
            return "O modelo escolhido não existe mais. Em Configurações, escolha outro modelo de inteligência artificial.";
        }
        if (status == 429) {
            return "O limite de uso gratuito do Google Gemini foi atingido. Espere alguns minutos e tente de novo.";
        }
        if (status >= 500) {
            return "Os servidores do Google Gemini estão instáveis. Tente de novo em instantes.";
        }
        return "O Google Gemini devolveu o erro " + status + ". Tente novamente.";
    }

    private static JSONObject request(String key, String path, JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        try {
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(150_000);
            connection.setRequestProperty("x-goog-api-key", key.trim());
            if (payload != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (status < 200 || status >= 300) {
                throw new AiException(httpMessage(status, body));
            }
            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
            return result.toString();
        }
    }
}
