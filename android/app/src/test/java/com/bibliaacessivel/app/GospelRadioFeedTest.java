package com.bibliaacessivel.app;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class GospelRadioFeedTest {
    @Test public void parsesPublishedMp3AndRejectsUnsafeEntries() throws Exception {
        String json = "[" +
                "{\"id\":\"2\",\"titulo\":\"Louvor novo\",\"arquivo_salvo\":\"novo som.mp3\",\"status\":\"publicado\",\"data\":\"2026-10-20 10:00:00\",\"compositor\":\"Autor\"}," +
                "{\"id\":\"1\",\"titulo\":\"Rascunho\",\"arquivo_salvo\":\"rascunho.mp3\",\"status\":\"rascunho\"}," +
                "{\"id\":\"3\",\"titulo\":\"Inválido\",\"arquivo_salvo\":\"../segredo.mp3\"}" +
                "]";
        List<GospelRadioFeed.Track> tracks = GospelRadioFeed.parse(json);
        assertEquals(1, tracks.size());
        assertEquals("Louvor novo — Autor", tracks.get(0).toString());
        assertTrue(tracks.get(0).audioUrl.endsWith("novo%20som.mp3"));
    }
}
