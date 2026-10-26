package com.bibliaacessivel.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Catálogo HTTPS e cache privado dos louvores publicados no site. */
final class GospelRadioFeed {
    static final String FEED_URL = "https://audiogames.com.br/diego/louvores/louvores.json";
    private static final String AUDIO_BASE_URL = "https://audiogames.com.br/diego/louvores/uploads/";
    private static final String CACHE_KEY = "gospel_radio_cache_json";
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private GospelRadioFeed() {}

    static final class Track {
        final String id;
        final String title;
        final String audioUrl;
        final String artist;
        final String publishedAt;

        Track(String id, String title, String audioUrl, String artist, String publishedAt) {
            this.id = id;
            this.title = title;
            this.audioUrl = audioUrl;
            this.artist = artist;
            this.publishedAt = publishedAt;
        }

        @Override public String toString() {
            return artist.isEmpty() ? title : title + " — " + artist;
        }
    }

    static List<Track> parse(String json) throws Exception {
        JSONArray records = new JSONArray(json);
        List<Track> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.optJSONObject(index);
            if (record == null) continue;
            String status = record.optString("status", "publicado").trim().toLowerCase(Locale.ROOT);
            if (!status.isEmpty() && !status.equals("publicado") && !status.equals("published")) continue;
            String id = String.valueOf(record.opt("id")).trim();
            String title = record.optString("titulo", "").trim();
            String filename = record.optString("arquivo_salvo", "").trim();
            if (id.isEmpty() || id.equals("null") || !seen.add(id) || title.isEmpty() ||
                    filename.contains("/") || filename.contains("\\") ||
                    !filename.toLowerCase(Locale.ROOT).endsWith(".mp3")) continue;
            String artist = record.optString("artista_original",
                    record.optString("compositor", "")).trim();
            String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
            result.add(new Track(id, title, AUDIO_BASE_URL + encoded, artist,
                    record.optString("data", record.optString("atualizado_em", "")).trim()));
        }
        result.sort(Comparator.comparing((Track track) -> track.publishedAt)
                .thenComparing(track -> track.id).reversed());
        return result;
    }

    static List<Track> fetch(Context context) throws Exception {
        URL current = new URL(FEED_URL);
        for (int redirect = 0; redirect < 4; redirect++) {
            if (!"https".equals(current.getProtocol()) || !"audiogames.com.br".equals(current.getHost())) {
                throw new IllegalStateException("Origem não autorizada.");
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "BibliaAcessivel-Android/4.3.5");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IllegalStateException("Resposta inválida.");
                current = new URL(current, location);
                continue;
            }
            if (status != 200) {
                connection.disconnect();
                throw new IllegalStateException("Rádio indisponível.");
            }
            String json;
            try (InputStream input = connection.getInputStream()) {
                json = readLimited(input);
            } finally {
                connection.disconnect();
            }
            List<Track> tracks = parse(json);
            preferences(context).edit().putString(CACHE_KEY, json).apply();
            return tracks;
        }
        throw new IllegalStateException("Rádio indisponível.");
    }

    static List<Track> loadCache(Context context) {
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
            if (output.size() + read > MAX_BYTES) throw new IllegalStateException("Lista muito grande.");
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
}
