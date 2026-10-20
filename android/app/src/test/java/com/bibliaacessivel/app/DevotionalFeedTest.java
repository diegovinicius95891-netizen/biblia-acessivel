package com.bibliaacessivel.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

/** Valida o contrato compartilhado com o painel do site. */
public final class DevotionalFeedTest {
    @Test public void filtersDraftsDuplicatesAndSortsNewestFirst() throws Exception {
        String json = "[" +
                "{\"id\":\"1\",\"titulo\":\"Antigo\",\"conteudo\":\"A\",\"data\":\"2026-10-19 10:00:00\"}," +
                "{\"id\":\"2\",\"titulo\":\"Novo\",\"conteudo\":\"B\",\"data\":\"2026-10-20 10:00:00\",\"status\":\"publicado\"}," +
                "{\"id\":\"2\",\"titulo\":\"Duplicado\",\"conteudo\":\"B\"}," +
                "{\"id\":\"3\",\"titulo\":\"Rascunho\",\"conteudo\":\"C\",\"status\":\"rascunho\"}]";
        List<DevotionalFeed.Item> posts = DevotionalFeed.parse(json);
        assertEquals(2, posts.size());
        assertEquals("2", posts.get(0).id);
        assertEquals("20/10/2026 às 10:00", posts.get(0).dateLabel());
    }

    @Test(expected = Exception.class)
    public void rejectsObjectInsteadOfList() throws Exception {
        DevotionalFeed.parse("{\"erro\":true}");
    }
}
