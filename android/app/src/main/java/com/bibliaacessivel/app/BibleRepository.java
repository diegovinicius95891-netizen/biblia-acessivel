package com.bibliaacessivel.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Copia e consulta o banco bíblico offline distribuído dentro do APK. */
final class BibleRepository implements AutoCloseable {
    private static final String ASSET_VERSION_KEY = "installed_bible_asset_version";
    private final SQLiteDatabase database;

    BibleRepository(Context context) throws IOException {
        File target = new File(context.getNoBackupFilesDir(), "biblia.db");
        SharedPreferences state = context.getSharedPreferences("bible_assets", Context.MODE_PRIVATE);
        boolean needsRefresh = !target.exists() ||
                state.getInt(ASSET_VERSION_KEY, 0) != BuildConfig.VERSION_CODE;
        if (needsRefresh) {
            installBundledDatabase(context, target);
            state.edit().putInt(ASSET_VERSION_KEY, BuildConfig.VERSION_CODE).apply();
        }
        database = SQLiteDatabase.openDatabase(target.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY);
    }

    /** Atualiza a cópia bíblica de forma atômica sem tocar no banco de dados pessoais. */
    private static void installBundledDatabase(Context context, File target) throws IOException {
        File pending = new File(target.getParentFile(), "biblia.db.new");
        if (pending.exists() && !pending.delete()) {
            throw new IOException("Não foi possível preparar a atualização dos textos bíblicos.");
        }
        try {
            try (InputStream input = context.getAssets().open("biblia.db");
                 FileOutputStream output = new FileOutputStream(pending)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            validateBundledDatabase(pending);
            try {
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (pending.exists()) pending.delete();
        }
    }

    /** Recusa um asset antigo ou incompleto antes que ele substitua a Bíblia funcional. */
    private static void validateBundledDatabase(File file) throws IOException {
        SQLiteDatabase candidate = null;
        try {
            candidate = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            int translations;
            int completeTranslations;
            int chapters;
            try (Cursor cursor = candidate.rawQuery("SELECT COUNT(*) FROM translations", null)) {
                cursor.moveToFirst();
                translations = cursor.getInt(0);
            }
            try (Cursor cursor = candidate.rawQuery(
                    "SELECT COUNT(*) FROM (SELECT translation_id FROM verses " +
                            "GROUP BY translation_id HAVING COUNT(DISTINCT book_code)=66)", null)) {
                cursor.moveToFirst();
                completeTranslations = cursor.getInt(0);
            }
            try (Cursor cursor = candidate.rawQuery(
                    "SELECT COUNT(*) FROM (SELECT DISTINCT translation_id,book_code,chapter FROM verses)",
                    null)) {
                cursor.moveToFirst();
                chapters = cursor.getInt(0);
            }
            if (translations < 5 || completeTranslations != translations ||
                    chapters != translations * 1189) {
                throw new IOException("A atualização dos textos bíblicos não passou na verificação.");
            }
        } catch (RuntimeException error) {
            throw new IOException("A atualização dos textos bíblicos não pôde ser validada.", error);
        } finally {
            if (candidate != null) candidate.close();
        }
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
        List<Models.Book> matches = matchingBooks(translationId, query);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /** Localiza nomes, códigos, ordinais falados e famílias ambíguas como "Pedro". */
    List<Models.Book> matchingBooks(String translationId, String query) {
        List<Models.Book> available = new ArrayList<>();
        for (boolean nt : new boolean[]{false, true}) available.addAll(books(translationId, nt));
        return matchBookCandidates(available, query);
    }

    /** Regra pura compartilhada pelos testes para escolher entre todas as famílias numeradas. */
    static List<Models.Book> matchBookCandidates(List<Models.Book> available, String query) {
        String wanted = normalizeBookQuery(query);
        List<Models.Book> exact = new ArrayList<>();
        List<Models.Book> partial = new ArrayList<>();
        List<Models.Book> family = new ArrayList<>();
        String wantedFamily = numberedBookFamily(wanted);
        boolean numberedQuery = wanted.matches("^[123].+");
        boolean gospelQuery = isGospelQuery(query);
        for (Models.Book book : available) {
            String name = normalizeBookQuery(book.name);
            if (name.equals(wanted) || normalize(book.code).equals(wanted)) exact.add(book);
            else if (!wanted.isEmpty() && (name.endsWith(wanted) || name.contains(wanted))) partial.add(book);
            if (!wantedFamily.isEmpty() && numberedBookFamily(name).equals(wantedFamily)) family.add(book);
        }
        // "João" pode ser o Evangelho ou uma das três cartas. A palavra
        // "Evangelho" preserva a escolha exata; sem ela, todas são oferecidas.
        if (!exact.isEmpty()) {
            if (!numberedQuery && !gospelQuery && family.size() > 1) return family;
            return exact;
        }
        if (family.size() > 1) return family;
        if (!partial.isEmpty()) return partial;
        // Se a pessoa disser um ordinal inexistente, como "terceira Pedro",
        // oferece os livros reais da mesma família em vez de encerrar a busca.
        return family;
    }

    /** Converte ordinais e expressões de voz para o formato dos livros numerados. */
    static String normalizeBookQuery(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
        text = text.replaceFirst("^(?:o\\s+)?evangelho\\s+(?:de|do|da)\\s+", "")
                .replaceFirst("^i{3}(?:\\s+|[.])", "3 ")
                .replaceFirst("^i{2}(?:\\s+|[.])", "2 ")
                .replaceFirst("^i(?:\\s+|[.])", "1 ")
                .replaceFirst("^(primeira|primeiro|uma|um)\\s+", "1 ")
                .replaceFirst("^(segunda|segundo|duas|dois)\\s+", "2 ")
                .replaceFirst("^(terceira|terceiro|tres)\\s+", "3 ")
                .replaceFirst("^([123])\\s*[ªº°.]\\s*", "$1 ")
                .replaceFirst("^([123]\\s+)?(?:a\\s+)?(?:carta|epistola|livro)\\s+(?:(?:aos?|as|de|do|da|dos|das)\\s+)?", "$1")
                .replaceFirst("^([123])\\s+(?:aos?|as|de|do|da|dos|das)\\s+", "$1 ");
        return normalize(text);
    }

    /** Distingue a escolha explícita do Evangelho da busca genérica por João. */
    static boolean isGospelQuery(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
        return text.matches("^(?:o\\s+)?evangelho\\s+(?:de|do|da)\\s+.+");
    }

    /** Remove somente o número inicial para comparar 1, 2 e 3 do mesmo livro. */
    static String numberedBookFamily(String normalizedName) {
        return normalizedName == null ? "" : normalizedName.replaceFirst("^[123]", "");
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
