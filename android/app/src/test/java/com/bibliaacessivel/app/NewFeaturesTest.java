package com.bibliaacessivel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentValues;
import android.content.Context;

import org.robolectric.RuntimeEnvironment;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

/** Cobre IA, planos, lembretes, memorização e o backup compatível com o Windows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class NewFeaturesTest {
    private UserDatabase data;

    @Before public void open() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("user_data.db");
        data = new UserDatabase(context);
    }

    @After public void close() {
        data.close();
    }

    private static String resource(String name) {
        InputStream input = NewFeaturesTest.class.getClassLoader().getResourceAsStream(name);
        try (Scanner scanner = new Scanner(input, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return scanner.next();
        }
    }

    @Test public void importsABackupExportedByWindowsAndExportsTheSameFormat() throws Exception {
        data.importPayload(new JSONObject(resource("windows-backup.json")));
        assertEquals(1, data.notes().size());
        assertEquals("Amor de Deus", data.notes().get(0).title);
        assertTrue(data.isBookmarked("bpm", "PSA", 23, "1"));
        assertEquals(1, data.prayers("Orando").size());
        assertEquals(Arrays.asList(1), data.planCompletedDays("john"));
        assertEquals(2, (int) data.planState("john").getAsInteger("current_day"));

        JSONObject exported = data.exportPayload();
        assertEquals("biblia-acessivel-backup", exported.getString("format"));
        assertEquals(2, exported.getInt("version"));
        JSONObject tables = exported.getJSONObject("tables");
        assertEquals(UserDatabase.BACKUP_TABLES.length, tables.length());
        for (String table : UserDatabase.BACKUP_TABLES) assertTrue(table, tables.has(table));
        // Usado pela verificação cruzada com a versão Windows (importação em Python).
        java.nio.file.Files.write(java.nio.file.Paths.get("build", "android-backup-export.json"),
                exported.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    @Test public void upgradeFromThePublishedVersionKeepsNotesAndBookmarks() throws Exception {
        data.close();
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("user_data.db");
        android.database.sqlite.SQLiteDatabase old = context.openOrCreateDatabase("user_data.db", 0, null);
        old.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT,translation_id TEXT NOT NULL," +
                "book_code TEXT NOT NULL,book_name TEXT NOT NULL,chapter INTEGER NOT NULL,verse TEXT NOT NULL," +
                "verse_text TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,created_at TEXT NOT NULL)");
        old.execSQL("CREATE TABLE bookmarks (translation_id TEXT NOT NULL,book_code TEXT NOT NULL," +
                "chapter INTEGER NOT NULL,verse TEXT NOT NULL,PRIMARY KEY(translation_id,book_code,chapter,verse))");
        old.execSQL("CREATE TABLE quiz_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT,question_id TEXT NOT NULL," +
                "difficulty TEXT NOT NULL,category TEXT NOT NULL,is_correct INTEGER NOT NULL,answered_at TEXT NOT NULL)");
        old.execSQL("INSERT INTO notes(translation_id,book_code,book_name,chapter,verse,verse_text,title,body,created_at)" +
                " VALUES('bpm','JHN','João',3,'16','texto','Nota antiga','corpo','2026-10-01T10:00')");
        old.execSQL("INSERT INTO bookmarks VALUES('bpm','PSA',23,'1')");
        old.setVersion(3);
        old.close();

        data = new UserDatabase(context);
        assertEquals("Nota antiga", data.notes().get(0).title);
        assertTrue(data.isBookmarked("bpm", "PSA", 23, "1"));
        assertEquals(1, data.favorites().size());
        data.startPlan("john", false);
        data.importPayload(new JSONObject(resource("windows-backup.json")));
        assertEquals("Amor de Deus", data.notes().get(0).title);
    }

    @Test public void invalidBackupChangesNothing() throws Exception {
        data.addPrayer("Antes", "Pedido que deve continuar", "");
        try {
            data.importPayload(new JSONObject("{\"format\":\"outro\",\"version\":2,\"tables\":{}}"));
            fail("Um arquivo de outro formato não pode ser importado.");
        } catch (IllegalArgumentException expected) {
            // Esperado.
        }
        JSONObject broken = new JSONObject(resource("windows-backup.json"));
        broken.getJSONObject("tables").getJSONArray("notes").getJSONObject(0).put("coluna_inexistente", 1);
        try {
            data.importPayload(broken);
            fail("Uma coluna desconhecida precisa cancelar a importação.");
        } catch (IllegalArgumentException expected) {
            // Esperado.
        }
        assertEquals(1, data.prayers("Todas").size());
    }

    @Test public void planDaysAdvanceAndFinish() {
        data.startPlan("teste", false);
        assertTrue(data.togglePlanDay("teste", 1, 2));
        assertEquals(2, (int) data.planState("teste").getAsInteger("current_day"));
        assertTrue(data.togglePlanDay("teste", 2, 2));
        assertEquals("Concluído", data.planState("teste").getAsString("status"));
        assertFalse(data.togglePlanDay("teste", 2, 2));
        assertEquals("Ativo", data.planState("teste").getAsString("status"));
        data.abandonPlan("teste");
        assertNull(data.planState("teste"));
    }

    @Test public void distributionMatchesTheWindowsPlanFormat() {
        List<ReadingPlans.Chapter> chapters = new ArrayList<>();
        for (int chapter = 1; chapter <= 21; chapter++) chapters.add(new ReadingPlans.Chapter("João", chapter));
        List<List<String>> readings = ReadingPlans.distribute(chapters, 7);
        assertEquals(7, readings.size());
        assertEquals(Arrays.asList("João 1-3"), readings.get(0));
        assertEquals(Arrays.asList("João 19-21"), readings.get(6));
        assertEquals(Arrays.asList("Êxodo 13", "Êxodo 14", "Êxodo 15"),
                ReadingPlans.expand(Arrays.asList("Êxodo 13-15")));
    }

    @Test public void streakCountsConsecutiveDaysEndingTodayOrYesterday() {
        LocalDate today = LocalDate.now();
        assertEquals(3, Reminders.readingStreak(Arrays.asList(
                today.toString(), today.minusDays(1).toString(), today.minusDays(2).toString(),
                today.minusDays(5).toString())));
        assertEquals(1, Reminders.readingStreak(Arrays.asList(today.minusDays(1).toString())));
        assertEquals(0, Reminders.readingStreak(Arrays.asList(today.minusDays(3).toString())));
    }

    @Test public void memorizationHidesWordsByLevel() {
        String text = "Porque Deus amou o mundo de tal maneira";
        assertEquals("Porque lacuna amou o mundo de tal lacuna", MainActivity.hideWords(text, 2));
        assertTrue(MainActivity.hideWords(text, 3).contains("lacuna"));
        ContentValues ignored = new ContentValues();
        assertTrue(ignored.size() == 0);
    }

    @Test public void geminiResponsesNeverBecomeUnreadableErrors() throws Exception {
        assertEquals("Texto final.", GeminiClient.parseResponse(new JSONObject(
                "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[" +
                        "{\"text\":\"pensando\",\"thought\":true},{\"text\":\"**Texto final.**\"}]}}]}")));
        assertTrue(GeminiClient.parseResponse(new JSONObject(
                "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"Parte\"}]}}]}"))
                .contains("interrompida"));
        String[] failures = {
                "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",
                "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}",
                "{\"candidates\":[]}",
                "{}"
        };
        for (String failure : failures) {
            try {
                GeminiClient.parseResponse(new JSONObject(failure));
                fail("Deveria explicar a falha: " + failure);
            } catch (GeminiClient.AiException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
        assertTrue(GeminiClient.httpMessage(404, "").contains("modelo"));
        assertTrue(GeminiClient.httpMessage(429, "").contains("limite"));
        assertTrue(GeminiClient.httpMessage(400, "API key not valid").contains("chave"));
        assertEquals("gemini-2.5-flash-lite", MainActivity.bestAvailableModel(Arrays.asList(
                "gemini-2.5-pro", "gemini-3-flash-preview", "gemini-2.5-flash-lite", "gemini-2.5-flash")));
    }
}
