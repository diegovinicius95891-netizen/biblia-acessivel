package com.bibliaacessivel.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Copia e consulta o banco bíblico offline distribuído dentro do APK. */
final class BibleRepository implements AutoCloseable {
    private final SQLiteDatabase database;

    BibleRepository(Context context) throws IOException {
        File target = new File(context.getNoBackupFilesDir(), "biblia.db");
        if (!target.exists()) {
            try (InputStream input = context.getAssets().open("biblia.db");
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, count);
                }
            }
        }
        database = SQLiteDatabase.openDatabase(target.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY);
    }

    List<Models.Translation> translations() {
        List<Models.Translation> result = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                "SELECT id,name FROM translations ORDER BY display_order", null)) {
            while (cursor.moveToNext()) {
                result.add(new Models.Translation(cursor.getString(0), cursor.getString(1)));
            }
        }
        return result;
    }

    List<Models.Book> books(String translationId, boolean newTestament) {
        List<Models.Book> result = new ArrayList<>();
        String comparison = newTestament ? "> 39" : "<= 39";
        try (Cursor cursor = database.rawQuery(
                "SELECT book_number,book_code,book_name FROM verses " +
                        "WHERE translation_id=? AND book_number " + comparison + " " +
                        "GROUP BY book_number,book_code,book_name ORDER BY book_number",
                new String[]{translationId})) {
            while (cursor.moveToNext()) {
                result.add(new Models.Book(cursor.getInt(0), cursor.getString(1), cursor.getString(2)));
            }
        }
        return result;
    }

    Models.Book findBook(String translationId, String query) {
        String wanted = normalize(query);
        for (boolean nt : new boolean[]{false, true}) {
            for (Models.Book book : books(translationId, nt)) {
                if (normalize(book.name).equals(wanted) || normalize(book.code).equals(wanted)) {
                    return book;
                }
            }
        }
        return null;
    }

    int chapterCount(String translationId, String bookCode) {
        try (Cursor cursor = database.rawQuery(
                "SELECT COALESCE(MAX(chapter),0) FROM verses WHERE translation_id=? AND book_code=?",
                new String[]{translationId, bookCode})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    List<Models.Verse> chapter(String translationId, String bookCode, int chapter) {
        List<Models.Verse> result = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                "SELECT verse,text FROM verses WHERE translation_id=? AND book_code=? AND chapter=? " +
                        "ORDER BY verse_sort",
                new String[]{translationId, bookCode, Integer.toString(chapter)})) {
            while (cursor.moveToNext()) {
                result.add(new Models.Verse(cursor.getString(0), cursor.getString(1)));
            }
        }
        return result;
    }

    /** Recorta um capítulo por número inicial e final, ou devolve o capítulo inteiro. */
    List<Models.Verse> passage(String translationId, String bookCode, int chapter,
                               String startVerse, String endVerse) {
        List<Models.Verse> all = chapter(translationId, bookCode, chapter);
        if (startVerse == null) return all;
        int start = BibleReference.number(startVerse);
        int end = endVerse == null ? start : BibleReference.number(endVerse);
        List<Models.Verse> result = new ArrayList<>();
        for (Models.Verse verse : all) {
            int number = BibleReference.number(verse.number);
            if (number >= start && number <= end) result.add(verse);
        }
        return result;
    }

    String chapterText(String translationId, String bookCode, int chapter) {
        StringBuilder builder = new StringBuilder();
        for (Models.Verse verse : chapter(translationId, bookCode, chapter)) {
            builder.append(verse).append('\n');
        }
        return builder.toString();
    }

    String bookText(String translationId, String bookCode) {
        StringBuilder builder = new StringBuilder();
        int chapters = chapterCount(translationId, bookCode);
        for (int chapter = 1; chapter <= chapters; chapter++) {
            builder.append("Capítulo ").append(chapter).append('\n');
            builder.append(chapterText(translationId, bookCode, chapter));
        }
        return builder.toString();
    }

    List<String> search(String translationId, String query) {
        List<String> result = new ArrayList<>();
        try (Cursor cursor = database.rawQuery(
                "SELECT book_name,chapter,verse,text FROM verses " +
                        "WHERE translation_id=? AND text LIKE ? ORDER BY book_number,chapter,verse_sort LIMIT 300",
                new String[]{translationId, "%" + query + "%"})) {
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0) + " " + cursor.getInt(1) + ":" +
                        cursor.getString(2) + ". " + cursor.getString(3));
            }
        }
        return result;
    }

    String legalText(String translationId) {
        try (Cursor cursor = database.rawQuery(
                "SELECT legal_html FROM translations WHERE id=?", new String[]{translationId})) {
            return cursor.moveToFirst() ? cursor.getString(0) : "Licenças indisponíveis.";
        }
    }

    static String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    @Override public void close() {
        database.close();
    }
}
