package com.bibliaacessivel.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Executa a chamada opcional ao Gemini sem incluir bibliotecas de rede externas. */
final class GeminiClient {
    private GeminiClient() {
        // O cliente expõe somente uma operação estática.
    }

    static String generate(String key, String model, String instruction, String bibleText)
            throws Exception {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalStateException("Informe a chave do Google Gemini nas Configurações.");
        }
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" +
                model + ":generateContent");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("x-goog-api-key", key.trim());

        JSONObject payload = new JSONObject();
        payload.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(
                new JSONObject().put("text", "Responda em português do Brasil, em texto simples, " +
                        "sem Markdown. Baseie-se no texto fornecido, respeite o limite pedido e " +
                        "reserve espaço para uma conclusão completa. Avise que a resposta é gerada por IA."))));
        payload.put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text",
                        "Tarefa: " + instruction + "\n\nTexto bíblico:\n" + bibleText)))));
        payload.put("generationConfig", new JSONObject()
                .put("maxOutputTokens", 3072)
                .put("temperature", 0.3));
        connection.getOutputStream().write(payload.toString().getBytes(StandardCharsets.UTF_8));

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String body = readAll(stream);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("O Google Gemini devolveu o erro HTTP " + status + ".");
        }
        JSONObject response = new JSONObject(body);
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IllegalStateException("O Gemini não devolveu texto.");
        }
        JSONObject candidate = candidates.getJSONObject(0);
        if ("MAX_TOKENS".equals(candidate.optString("finishReason"))) {
            throw new IllegalStateException("O resumo atingiu o limite. Tente novamente com um texto menor.");
        }
        JSONArray parts = candidate.getJSONObject("content").getJSONArray("parts");
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < parts.length(); index++) {
            String text = parts.getJSONObject(index).optString("text").trim();
            if (!text.isEmpty()) result.append(text).append("\n\n");
        }
        return result.toString().replace("*", "").replace("`", "").trim();
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            return result.toString();
        }
    }
}
