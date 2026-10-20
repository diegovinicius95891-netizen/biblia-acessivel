package com.bibliaacessivel.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fonte HTTPS dos devocionais, com validação e cache privado no aparelho. */
final class DevotionalFeed {
    static final String FEED_URL = "https://audiogames.com.br/diego/devocionais/posts.json";
    private static final String CACHE_KEY = "devotionals_cache_json";
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private DevotionalFeed() {}

    /** Registro imutável exibido na lista e na notificação. */
    static final class Item {
        final String id;
        final String title;
        final String content;
        final String publishedAt;

        Item(String id, String title, String content, String publishedAt) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.publishedAt = publishedAt;
        }

        String dateLabel() {
            try {
                LocalDateTime parsed = LocalDateTime.parse(publishedAt,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT));
                return parsed.format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", new Locale("pt", "BR")));
            } catch (DateTimeParseException ignored) {
                return publishedAt.isEmpty() ? "Data não informada" : publishedAt;
            }
        }

        @Override public String toString() { return dateLabel() + " — " + title; }
    }

    /** Analisa o mesmo formato usado pelo painel do site e ignora rascunhos. */
    static List<Item> parse(String json) throws Exception {
        JSONArray records = new JSONArray(json);
        List<Item> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.optJSONObject(index);
            if (record == null) continue;
            String status = record.optString("status", "publicado").trim().toLowerCase(Locale.ROOT);
            if (!status.isEmpty() && !status.equals("publicado") && !status.equals("published")) continue;
            String id = String.valueOf(record.opt("id")).trim();
            String title = record.optString("titulo", "").trim();
            String content = record.optString("conteudo", "").trim();
            String publishedAt = record.optString("data", record.optString("atualizado_em", "")).trim();
            if (id.isEmpty() || id.equals("null") || !seen.add(id) || title.isEmpty() || content.isEmpty()) continue;
            result.add(new Item(id, title, content, publishedAt));
        }
        result.sort(Comparator.comparing((Item item) -> item.publishedAt)
                .thenComparing(item -> item.id).reversed());
        return result;
    }

    /** Baixa com limite de tamanho, aceita somente HTTPS e guarda a resposta validada. */
    static List<Item> fetch(Context context) throws Exception {
        URL current = new URL(FEED_URL);
        for (int redirect = 0; redirect < 4; redirect++) {
            if (!"https".equals(current.getProtocol()) || !"audiogames.com.br".equals(current.getHost())) {
                throw new IllegalStateException("O site redirecionou para um endereço não autorizado.");
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "BibliaAcessivel-Android/4.0");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IllegalStateException("Redirecionamento inválido.");
                current = new URL(current, location);
                continue;
            }
            if (status != 200) {
                connection.disconnect();
                throw new IllegalStateException("O site respondeu com o código " + status + ".");
            }
            String json;
            try (InputStream input = connection.getInputStream()) {
                json = readLimited(input);
            } finally {
                connection.disconnect();
            }
            List<Item> parsed = parse(json);
            preferences(context).edit().putString(CACHE_KEY, json).apply();
            return parsed;
        }
        throw new IllegalStateException("O site redirecionou muitas vezes.");
    }

    /** Recupera a última resposta válida; cache corrompido equivale a lista vazia. */
    static List<Item> loadCache(Context context) {
        try {
            String json = preferences(context).getString(CACHE_KEY, "[]");
            return parse(json == null ? "[]" : json);
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static String readLimited(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_BYTES) throw new IllegalStateException("Lista de devocionais muito grande.");
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
}
