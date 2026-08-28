package com.bibliaacessivel.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Catálogo offline dos 640 hinos, sem áudio, cifra, partitura ou conexão de rede. */
final class HymnalCatalog {
    final List<Hymn> hymns;

    private HymnalCatalog(List<Hymn> hymns) {
        this.hymns = Collections.unmodifiableList(hymns);
    }

    /** Carrega e valida a sequência completa empacotada como asset. */
    static HymnalCatalog load(Context context) throws Exception {
        String json;
        try (InputStream input = context.getAssets().open("harpa_crista.json");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            json = output.toString(StandardCharsets.UTF_8.name());
        }
        JSONArray array = new JSONArray(json);
        if (array.length() != 640) throw new IllegalArgumentException("Catálogo da Harpa incompleto.");
        List<Hymn> hymns = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            int number = item.getInt("numero");
            String title = item.getString("titulo").trim();
            String lyrics = item.getString("letra").replaceAll("\\*+", "").trim();
            if (number != index + 1 || title.isEmpty() || lyrics.isEmpty()) {
                throw new IllegalArgumentException("Hino inválido na posição " + (index + 1));
            }
            hymns.add(new Hymn(number, title, lyrics));
        }
        return new HymnalCatalog(hymns);
    }

    /** Pesquisa por número, título ou trecho da letra ignorando acentos. */
    List<Hymn> search(String query) {
        String wanted = normalize(query);
        if (wanted.isEmpty()) return hymns;
        List<Hymn> result = new ArrayList<>();
        for (Hymn hymn : hymns) {
            boolean numberMatch = wanted.matches("\\d+")
                    && Integer.toString(hymn.number).startsWith(wanted);
            if (numberMatch || normalize(hymn.title).contains(wanted)
                    || normalize(hymn.lyrics).contains(wanted)) result.add(hymn);
        }
        return result;
    }

    /** Normaliza somente a chave de pesquisa, preservando a letra exibida. */
    private static String normalize(String text) {
        String decomposed = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").trim().replaceAll("\\s+", " ");
    }

    /** Hino completo com rótulo curto para a lista do TalkBack. */
    static final class Hymn {
        final int number;
        final String title;
        final String lyrics;

        Hymn(int number, String title, String lyrics) {
            this.number = number;
            this.title = title;
            this.lyrics = lyrics;
        }

        @Override public String toString() {
            return number + ". " + title;
        }
    }
}
