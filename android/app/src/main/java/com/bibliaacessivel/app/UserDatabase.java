package com.bibliaacessivel.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;

/** Guarda notas e marcadores somente no armazenamento privado do aplicativo. */
final class UserDatabase extends SQLiteOpenHelper {
    /** Tabelas do backup, na mesma ordem e formato da versão Windows (backup versão 2). */
    static final String[] BACKUP_TABLES = {
            "favorite_categories", "bookmarks", "notes", "search_history", "reading_history",
            "reading_plans", "reading_plan_days", "prayers", "devotionals", "memorized_verses",
            "quiz_attempts"
    };

    UserDatabase(Context context) {
        super(context, "user_data.db", null, 4);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "translation_id TEXT NOT NULL,book_code TEXT NOT NULL,book_name TEXT NOT NULL," +
                "chapter INTEGER NOT NULL,verse TEXT NOT NULL,verse_text TEXT NOT NULL," +
                "title TEXT NOT NULL,body TEXT NOT NULL,created_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE bookmarks (translation_id TEXT NOT NULL,book_code TEXT NOT NULL," +
                "chapter INTEGER NOT NULL,verse TEXT NOT NULL," +
                "PRIMARY KEY(translation_id,book_code,chapter,verse))");
        createQuizTable(db);
        createSharedTables(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createQuizTable(db);
        if (oldVersion < 3) makeQuizAnswersUnique(db);
        if (oldVersion < 4) createSharedTables(db);
    }

    /** Cria as tabelas compartilhadas com o Windows e completa os marcadores antigos. */
    private static void createSharedTables(SQLiteDatabase db) {
        String[] bookmarkColumns = {
                // ALTER TABLE só aceita valor padrão constante; CURRENT_TIMESTAMP faria a coluna não ser criada.
                "created_at TEXT NOT NULL DEFAULT ''", "book_name TEXT NOT NULL DEFAULT ''",
                "end_chapter INTEGER", "end_verse TEXT", "kind TEXT NOT NULL DEFAULT 'verse'", "category_id INTEGER"
        };
        for (String column : bookmarkColumns) {
            try {
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN " + column);
            } catch (Exception alreadyExists) {
                // Coluna presente: a migração pode rodar com segurança mais de uma vez.
            }
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS favorite_categories (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE,created_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS search_history (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "query TEXT NOT NULL,search_mode TEXT NOT NULL DEFAULT 'auto',book_code TEXT,created_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_history (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "translation_id TEXT NOT NULL,book_code TEXT NOT NULL,book_name TEXT NOT NULL," +
                "chapter INTEGER NOT NULL,verse TEXT,accessed_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_plans (plan_id TEXT PRIMARY KEY,status TEXT NOT NULL," +
                "started_at TEXT NOT NULL,current_day INTEGER NOT NULL DEFAULT 1,completed_at TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_plan_days (plan_id TEXT NOT NULL,day_number INTEGER NOT NULL," +
                "completed_at TEXT NOT NULL,PRIMARY KEY(plan_id,day_number))");
        db.execSQL("CREATE TABLE IF NOT EXISTS prayers (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL," +
                "request TEXT NOT NULL,created_at TEXT NOT NULL,notes TEXT NOT NULL DEFAULT ''," +
                "category TEXT NOT NULL DEFAULT '',status TEXT NOT NULL DEFAULT 'Orando',answered_at TEXT," +
                "answer_text TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE IF NOT EXISTS devotionals (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "devotional_date TEXT NOT NULL,verse_reference TEXT NOT NULL DEFAULT ''," +
                "verse_text TEXT NOT NULL DEFAULT '',learned TEXT NOT NULL DEFAULT ''," +
                "practice TEXT NOT NULL DEFAULT '',prayer TEXT NOT NULL DEFAULT ''," +
                "personal_note TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL,updated_at TEXT NOT NULL," +
                "UNIQUE(devotional_date))");
        db.execSQL("CREATE TABLE IF NOT EXISTS memorized_verses (translation_id TEXT NOT NULL," +
                "book_code TEXT NOT NULL,book_name TEXT NOT NULL,chapter INTEGER NOT NULL,verse TEXT NOT NULL," +
                "verse_text TEXT NOT NULL,created_at TEXT NOT NULL," +
                "PRIMARY KEY(translation_id,book_code,chapter,verse))");
        db.execSQL("INSERT OR IGNORE INTO favorite_categories(name,created_at) VALUES('Favoritos gerais',?)",
                new Object[]{now()});
    }

    static String now() {
        return LocalDateTime.now().withNano(0).toString();
    }

    // Histórico ---------------------------------------------------------------
    void recordReading(String translationId, Models.Book book, int chapter, String verse) {
        SQLiteDatabase db = getWritableDatabase();
        // Não repete a mesma posição seguida: só registra quando o capítulo muda.
        try (Cursor last = db.rawQuery("SELECT book_code,chapter FROM reading_history ORDER BY id DESC LIMIT 1", null)) {
            if (last.moveToFirst() && book.code.equals(last.getString(0)) && last.getInt(1) == chapter) return;
        }
        ContentValues values = new ContentValues();
        values.put("translation_id", translationId);
        values.put("book_code", book.code);
        values.put("book_name", book.name);
        values.put("chapter", chapter);
        values.put("verse", verse);
        values.put("accessed_at", now());
        db.insert("reading_history", null, values);
        db.execSQL("DELETE FROM reading_history WHERE id NOT IN (SELECT id FROM reading_history ORDER BY id DESC LIMIT 500)");
    }

    List<ContentValues> readingHistory() {
        return rows("SELECT * FROM reading_history ORDER BY accessed_at DESC,id DESC LIMIT 200");
    }

    void clearReadingHistory() {
        getWritableDatabase().delete("reading_history", null, null);
    }

    /** Dias diferentes com leitura registrada, para o contador de sequência. */
    List<String> readingDays() {
        List<String> days = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT DISTINCT substr(accessed_at,1,10) AS day FROM reading_history ORDER BY day DESC", null)) {
            while (cursor.moveToNext()) days.add(cursor.getString(0));
        }
        return days;
    }

    // Favoritos ---------------------------------------------------------------
    List<ContentValues> favorites() {
        return rows("SELECT b.*,c.name AS category_name FROM bookmarks b LEFT JOIN favorite_categories c " +
                "ON c.id=b.category_id ORDER BY b.created_at DESC");
    }

    void removeFavorite(String translationId, String bookCode, int chapter, String verse) {
        getWritableDatabase().delete("bookmarks", "translation_id=? AND book_code=? AND chapter=? AND verse=?",
                new String[]{translationId, bookCode, Integer.toString(chapter), verse});
    }

    // Planos de leitura ------------------------------------------------------
    ContentValues planState(String planId) {
        List<ContentValues> found = rows("SELECT * FROM reading_plans WHERE plan_id=?", planId);
        return found.isEmpty() ? null : found.get(0);
    }

    List<Integer> planCompletedDays(String planId) {
        List<Integer> days = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT day_number FROM reading_plan_days WHERE plan_id=? ORDER BY day_number", new String[]{planId})) {
            while (cursor.moveToNext()) days.add(cursor.getInt(0));
        }
        return days;
    }

    List<ContentValues> activePlans() {
        return rows("SELECT * FROM reading_plans WHERE status='Ativo' ORDER BY started_at");
    }

    void startPlan(String planId, boolean restart) {
        SQLiteDatabase db = getWritableDatabase();
        if (restart) {
            db.delete("reading_plan_days", "plan_id=?", new String[]{planId});
            db.delete("reading_plans", "plan_id=?", new String[]{planId});
        }
        // INSERT OR IGNORE + UPDATE em vez de "ON CONFLICT DO UPDATE": o SQLite do Android 8 a 10 não o aceita.
        db.execSQL("INSERT OR IGNORE INTO reading_plans(plan_id,status,started_at,current_day) VALUES(?, 'Ativo', ?, 1)",
                new Object[]{planId, now()});
        db.execSQL("UPDATE reading_plans SET status='Ativo' WHERE plan_id=?", new Object[]{planId});
    }

    void setPlanStatus(String planId, String status) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        getWritableDatabase().update("reading_plans", values, "plan_id=?", new String[]{planId});
    }

    void abandonPlan(String planId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("reading_plan_days", "plan_id=?", new String[]{planId});
        db.delete("reading_plans", "plan_id=?", new String[]{planId});
    }

    /** Marca ou desmarca o dia e recalcula o dia atual, como no Windows. */
    boolean togglePlanDay(String planId, int day, int totalDays) {
        SQLiteDatabase db = getWritableDatabase();
        boolean marked = !planCompletedDays(planId).contains(day);
        if (marked) {
            ContentValues values = new ContentValues();
            values.put("plan_id", planId);
            values.put("day_number", day);
            values.put("completed_at", now());
            db.insert("reading_plan_days", null, values);
        } else {
            db.delete("reading_plan_days", "plan_id=? AND day_number=?", new String[]{planId, Integer.toString(day)});
        }
        List<Integer> completed = planCompletedDays(planId);
        int next = totalDays;
        for (int candidate = 1; candidate <= totalDays; candidate++) {
            if (!completed.contains(candidate)) {
                next = candidate;
                break;
            }
        }
        boolean finished = completed.size() >= totalDays;
        ContentValues values = new ContentValues();
        values.put("current_day", next);
        values.put("status", finished ? "Concluído" : "Ativo");
        values.put("completed_at", finished ? now() : null);
        db.update("reading_plans", values, "plan_id=?", new String[]{planId});
        return marked;
    }

    // Diário de oração -------------------------------------------------------
    void addPrayer(String title, String request, String category) {
        ContentValues values = new ContentValues();
        values.put("title", title.trim());
        values.put("request", request.trim());
        values.put("category", category.trim());
        values.put("created_at", now());
        values.put("status", "Orando");
        getWritableDatabase().insertOrThrow("prayers", null, values);
    }

    List<ContentValues> prayers(String status) {
        return "Todas".equals(status)
                ? rows("SELECT * FROM prayers ORDER BY created_at DESC,id DESC")
                : rows("SELECT * FROM prayers WHERE status=? ORDER BY created_at DESC,id DESC", status);
    }

    void answerPrayer(long id, String answer) {
        ContentValues values = new ContentValues();
        values.put("status", "Respondida");
        values.put("answered_at", LocalDate.now().toString());
        values.put("answer_text", answer.trim());
        getWritableDatabase().update("prayers", values, "id=?", new String[]{Long.toString(id)});
    }

    void setPrayerStatus(long id, String status) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        getWritableDatabase().update("prayers", values, "id=?", new String[]{Long.toString(id)});
    }

    void deletePrayer(long id) {
        getWritableDatabase().delete("prayers", "id=?", new String[]{Long.toString(id)});
    }

    // Memorização ------------------------------------------------------------
    boolean toggleMemorized(String translationId, Models.Book book, int chapter, Models.Verse verse) {
        SQLiteDatabase db = getWritableDatabase();
        String[] key = {translationId, book.code, Integer.toString(chapter), verse.number};
        if (!rows("SELECT 1 AS x FROM memorized_verses WHERE translation_id=? AND book_code=? AND chapter=? AND verse=?",
                key).isEmpty()) {
            db.delete("memorized_verses", "translation_id=? AND book_code=? AND chapter=? AND verse=?", key);
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("translation_id", translationId);
        values.put("book_code", book.code);
        values.put("book_name", book.name);
        values.put("chapter", chapter);
        values.put("verse", verse.number);
        values.put("verse_text", verse.text);
        values.put("created_at", now());
        db.insertOrThrow("memorized_verses", null, values);
        return true;
    }

    List<ContentValues> memorizedVerses() {
        return rows("SELECT * FROM memorized_verses ORDER BY created_at DESC");
    }

    // Meu momento com Deus ---------------------------------------------------
    ContentValues devotionalFor(String day) {
        List<ContentValues> found = rows("SELECT * FROM devotionals WHERE devotional_date=?", day);
        return found.isEmpty() ? null : found.get(0);
    }

    List<ContentValues> devotionalEntries() {
        return rows("SELECT * FROM devotionals ORDER BY devotional_date DESC");
    }

    void saveDevotional(String day, String reference, String verseText, String learned, String practice,
                        String prayer, String note) {
        ContentValues values = new ContentValues();
        values.put("devotional_date", day);
        values.put("verse_reference", reference);
        values.put("verse_text", verseText);
        values.put("learned", learned);
        values.put("practice", practice);
        values.put("prayer", prayer);
        values.put("personal_note", note);
        values.put("updated_at", now());
        SQLiteDatabase db = getWritableDatabase();
        if (db.update("devotionals", values, "devotional_date=?", new String[]{day}) == 0) {
            values.put("created_at", now());
            db.insertOrThrow("devotionals", null, values);
        }
    }

    // Backup compatível com o Windows ----------------------------------------
    JSONObject exportPayload() throws Exception {
        JSONObject tables = new JSONObject();
        for (String table : BACKUP_TABLES) {
            JSONArray array = new JSONArray();
            for (ContentValues row : rows("SELECT * FROM " + table)) {
                JSONObject item = new JSONObject();
                for (String column : row.keySet()) {
                    Object value = row.get(column);
                    item.put(column, value == null ? JSONObject.NULL : value);
                }
                array.put(item);
            }
            tables.put(table, array);
        }
        return new JSONObject().put("format", "biblia-acessivel-backup").put("version", 2)
                .put("created_at", now()).put("tables", tables);
    }

    /** Valida e importa numa transação: em qualquer erro, nada é alterado. */
    void importPayload(JSONObject payload) throws Exception {
        if (!"biblia-acessivel-backup".equals(payload.optString("format")) ||
                (payload.optInt("version") != 1 && payload.optInt("version") != 2)) {
            throw new IllegalArgumentException("Este arquivo não é um backup compatível da Bíblia AGB.");
        }
        JSONObject tables = payload.optJSONObject("tables");
        if (tables == null) throw new IllegalArgumentException("O backup possui uma estrutura inválida.");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String table : BACKUP_TABLES) db.delete(table, null, null);
            for (String table : BACKUP_TABLES) {
                JSONArray array = tables.optJSONArray(table);
                if (array == null) continue;
                List<String> columns = columns(db, table);
                for (int index = 0; index < array.length(); index++) {
                    JSONObject row = array.getJSONObject(index);
                    ContentValues values = new ContentValues();
                    Iterator<String> keys = row.keys();
                    while (keys.hasNext()) {
                        String column = keys.next();
                        if (!columns.contains(column)) {
                            throw new IllegalArgumentException("Um registro de " + table + " é inválido.");
                        }
                        Object value = row.get(column);
                        if (value == JSONObject.NULL) values.putNull(column);
                        else if (value instanceof Integer || value instanceof Long) values.put(column, ((Number) value).longValue());
                        else if (value instanceof Number) values.put(column, ((Number) value).doubleValue());
                        else values.put(column, String.valueOf(value));
                    }
                    db.insertWithOnConflict(table, null, values,
                            "quiz_attempts".equals(table) ? SQLiteDatabase.CONFLICT_IGNORE : SQLiteDatabase.CONFLICT_ABORT);
                }
            }
            db.execSQL("INSERT OR IGNORE INTO favorite_categories(name,created_at) VALUES('Favoritos gerais',?)",
                    new Object[]{now()});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static List<String> columns(SQLiteDatabase db, String table) {
        List<String> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) result.add(cursor.getString(1));
        }
        return result;
    }

    private List<ContentValues> rows(String sql, String... args) {
        List<ContentValues> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                ContentValues values = new ContentValues();
                for (int column = 0; column < cursor.getColumnCount(); column++) {
                    String name = cursor.getColumnName(column);
                    switch (cursor.getType(column)) {
                        case Cursor.FIELD_TYPE_NULL: values.putNull(name); break;
                        case Cursor.FIELD_TYPE_INTEGER: values.put(name, cursor.getLong(column)); break;
                        case Cursor.FIELD_TYPE_FLOAT: values.put(name, cursor.getDouble(column)); break;
                        default: values.put(name, cursor.getString(column));
                    }
                }
                result.add(values);
            }
        }
        return result;
    }

    /** Cria a tabela separada de resultados sem guardar a alternativa escolhida. */
    private static void createQuizTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS quiz_attempts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT,question_id TEXT NOT NULL," +
                "difficulty TEXT NOT NULL,category TEXT NOT NULL,is_correct INTEGER NOT NULL," +
                "answered_at TEXT NOT NULL)");
        makeQuizAnswersUnique(db);
    }

    /** Mantém a primeira resposta antiga e impede que a pergunta conte novamente. */
    private static void makeQuizAnswersUnique(SQLiteDatabase db) {
        db.execSQL("DELETE FROM quiz_attempts WHERE id NOT IN (" +
                "SELECT MIN(id) FROM quiz_attempts GROUP BY question_id)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS quiz_attempts_question_id_unique " +
                "ON quiz_attempts(question_id)");
    }

    long addNote(String translationId, Models.Book book, int chapter, Models.Verse verse,
                 String title, String body) {
        ContentValues values = new ContentValues();
        values.put("translation_id", translationId);
        values.put("book_code", book.code);
        values.put("book_name", book.name);
        values.put("chapter", chapter);
        values.put("verse", verse.number);
        values.put("verse_text", verse.text);
        values.put("title", title);
        values.put("body", body);
        values.put("created_at", OffsetDateTime.now().toString());
        return getWritableDatabase().insertOrThrow("notes", null, values);
    }

    List<Models.Note> notes() {
        List<Models.Note> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,book_code,book_name,chapter,verse,verse_text,title,body,created_at " +
                        "FROM notes ORDER BY created_at DESC,id DESC", null)) {
            while (cursor.moveToNext()) {
                result.add(new Models.Note(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getInt(3), cursor.getString(4),
                        cursor.getString(5), cursor.getString(6), cursor.getString(7),
                        cursor.getString(8)));
            }
        }
        return result;
    }

    boolean isBookmarked(String translationId, String bookCode, int chapter, String verse) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM bookmarks WHERE translation_id=? AND book_code=? AND chapter=? AND verse=?",
                new String[]{translationId, bookCode, Integer.toString(chapter), verse})) {
            return cursor.moveToFirst();
        }
    }

    boolean toggleBookmark(String translationId, String bookCode, int chapter, String verse) {
        SQLiteDatabase db = getWritableDatabase();
        if (isBookmarked(translationId, bookCode, chapter, verse)) {
            db.delete("bookmarks", "translation_id=? AND book_code=? AND chapter=? AND verse=?",
                    new String[]{translationId, bookCode, Integer.toString(chapter), verse});
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("translation_id", translationId);
        values.put("book_code", bookCode);
        values.put("chapter", chapter);
        values.put("verse", verse);
        values.put("created_at", now());
        values.put("kind", "*".equals(verse) ? "chapter" : "verse");
        db.insertOrThrow("bookmarks", null, values);
        return true;
    }

    /** Guarda o nome do livro para a lista de favoritos ficar legível sem consultar a Bíblia. */
    void setBookmarkBookName(String translationId, String bookCode, int chapter, String verse, String bookName) {
        ContentValues values = new ContentValues();
        values.put("book_name", bookName);
        getWritableDatabase().update("bookmarks", values,
                "translation_id=? AND book_code=? AND chapter=? AND verse=?",
                new String[]{translationId, bookCode, Integer.toString(chapter), verse});
    }

    /** Registra somente a primeira resposta e informa se a gravação aconteceu. */
    boolean recordQuizAttempt(QuizCatalog.Question question, boolean correct) {
        ContentValues values = new ContentValues();
        values.put("question_id", question.id);
        values.put("difficulty", question.difficulty);
        values.put("category", question.category);
        values.put("is_correct", correct ? 1 : 0);
        values.put("answered_at", OffsetDateTime.now().toString());
        return getWritableDatabase().insertWithOnConflict(
                "quiz_attempts", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    /** Informa se a pergunta já possui um resultado definitivo no aparelho. */
    boolean hasAnsweredQuizQuestion(String questionId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM quiz_attempts WHERE question_id=?", new String[]{questionId})) {
            return cursor.moveToFirst();
        }
    }

    /** Retorna total, acertos e erros, nesta ordem. */
    int[] quizStatistics() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*),COALESCE(SUM(is_correct),0) FROM quiz_attempts", null)) {
            cursor.moveToFirst();
            int total = cursor.getInt(0);
            int correct = cursor.getInt(1);
            return new int[]{total, correct, total - correct};
        }
    }

    /** Apaga somente os resultados do quiz para permitir um novo começo consciente. */
    void resetQuiz() {
        getWritableDatabase().delete("quiz_attempts", null, null);
    }

    /** Cria uma cópia consistente antes da instalação e mantém as cinco mais recentes. */
    void backupBeforeUpdate(String version) throws Exception {
        SQLiteDatabase database = getReadableDatabase();
        database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).close();
        File source = getDatabaseFile();
        File directory = new File(source.getParentFile(), "backups");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar a pasta de backup.");
        }
        File target = new File(directory, "backup_pre_update_" + version + "_" +
                System.currentTimeMillis() + ".db");
        try (FileChannel input = new FileInputStream(source).getChannel();
             FileChannel output = new FileOutputStream(target).getChannel()) {
            input.transferTo(0, input.size(), output);
            output.force(true);
        }
        File[] backups = directory.listFiles((folder, name) -> name.startsWith("backup_pre_update_"));
        if (backups == null || backups.length <= 5) return;
        Arrays.sort(backups, Comparator.comparingLong(File::lastModified));
        for (int index = 0; index < backups.length - 5; index++) backups[index].delete();
    }

    /** Resolve o caminho privado do SQLite criado pelo Android. */
    private File getDatabaseFile() {
        String path = getReadableDatabase().getPath();
        if (path == null || path.isEmpty()) {
            throw new IllegalStateException("O banco pessoal não possui um caminho válido.");
        }
        return new File(path);
    }
}
