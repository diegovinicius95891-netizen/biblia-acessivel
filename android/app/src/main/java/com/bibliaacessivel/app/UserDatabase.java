package com.bibliaacessivel.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.OffsetDateTime;
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
    UserDatabase(Context context) {
        super(context, "user_data.db", null, 3);
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
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createQuizTable(db);
        if (oldVersion < 3) makeQuizAnswersUnique(db);
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
        db.insertOrThrow("bookmarks", null, values);
        return true;
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
