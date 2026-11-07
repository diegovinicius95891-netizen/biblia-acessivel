package com.bibliaacessivel.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Lembrete diário com versículo do dia, leitura do plano e sequência de leitura. */
final class Reminders {
    static final String CHANNEL_ID = "daily_reminder";
    static final String OPEN_PLANS = "open_plans";
    private static final String WORK_NAME = "daily-reminder";
    static final String[] DAILY_REFERENCES = {
            "Salmos 23:1", "João 3:16", "Filipenses 4:6", "Isaías 41:10", "Romanos 8:28", "Provérbios 3:5",
            "Mateus 11:28", "1 Pedro 5:7", "Salmos 46:1", "Romanos 15:13", "Josué 1:9", "Lamentações 3:22",
    };

    private Reminders() { }

    static boolean enabled(SharedPreferences preferences) {
        return preferences.getBoolean("reminder_enabled", true);
    }

    static int minutesOfDay(SharedPreferences preferences) {
        return preferences.getInt("reminder_minutes", 7 * 60);
    }

    static String timeLabel(SharedPreferences preferences) {
        int minutes = minutesOfDay(preferences);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** Agenda o próximo lembrete exatamente no horário escolhido (ou cancela, se desligado). */
    static void schedule(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        WorkManager manager = WorkManager.getInstance(context);
        if (!enabled(preferences)) {
            manager.cancelUniqueWork(WORK_NAME);
            return;
        }
        createChannel(context);
        int minutes = minutesOfDay(preferences);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = LocalDate.now().atTime(LocalTime.of(minutes / 60, minutes % 60));
        if (!next.isAfter(now)) next = next.plusDays(1);
        long delay = Duration.between(now, next).toMillis();
        manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(ReminderWorker.class)
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS).build());
    }

    static void createChannel(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Lembrete diário de leitura",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Versículo do dia e leitura do plano no horário escolhido.");
        manager.createNotificationChannel(channel);
    }

    static String dailyReference() {
        return DAILY_REFERENCES[(int) (LocalDate.now().toEpochDay() % DAILY_REFERENCES.length)];
    }

    /** Conta dias seguidos com leitura, terminando hoje ou ontem. */
    static int readingStreak(List<String> daysDescending) {
        LocalDate expected = LocalDate.now();
        if (!daysDescending.isEmpty() && !daysDescending.get(0).equals(expected.toString())) {
            expected = expected.minusDays(1);
        }
        int streak = 0;
        for (String day : daysDescending) {
            if (!day.equals(expected.toString())) break;
            streak++;
            expected = expected.minusDays(1);
        }
        return streak;
    }

    /** Monta o texto do lembrete; também usado na tela inicial. */
    static String buildMessage(Context context) {
        StringBuilder text = new StringBuilder();
        String reference = dailyReference();
        try (BibleRepository bible = new BibleRepository(context)) {
            SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
            String translation = preferences.getString("translation", "bpm");
            String verse = verseText(bible, translation, reference);
            text.append("Versículo do dia, ").append(reference).append(": ").append(verse);
            UserDatabase data = new UserDatabase(context);
            try {
                List<ContentValues> active = data.activePlans();
                if (!active.isEmpty()) {
                    Map<String, ReadingPlans.Plan> plans = ReadingPlans.build(bible, translation);
                    ReadingPlans.Plan plan = plans.get(active.get(0).getAsString("plan_id"));
                    if (plan != null) {
                        int day = active.get(0).getAsInteger("current_day");
                        text.append("\n\nLeitura de hoje do plano ").append(plan.name).append(", dia ")
                                .append(day).append(": ").append(String.join(", ", plan.day(day))).append('.');
                    }
                }
                int streak = readingStreak(data.readingDays());
                if (streak > 1) text.append("\n\nVocê leu a Bíblia em ").append(streak).append(" dias seguidos.");
            } finally {
                data.close();
            }
        } catch (Exception error) {
            text.setLength(0);
            text.append("Versículo do dia: ").append(reference).append(". Abra a Bíblia para ler.");
        }
        return text.toString();
    }

    static String verseText(BibleRepository bible, String translation, String reference) {
        try {
            BibleReference parsed = BibleReference.parse(reference);
            Models.Book book = bible.findExactBook(translation, parsed.book);
            if (book == null) return "";
            StringBuilder text = new StringBuilder();
            for (Models.Verse verse : bible.passage(translation, book.code, parsed.chapter,
                    parsed.startVerse, parsed.endVerse)) {
                if (text.length() > 0) text.append(' ');
                text.append(verse.text);
            }
            return text.toString();
        } catch (Exception error) {
            return "";
        }
    }

    static void show(Context context, String message) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        createChannel(context);
        Intent intent = new Intent(context, MainActivity.class).putExtra(OPEN_PLANS, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 402, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(4002, new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Hora da leitura bíblica")
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pending).setAutoCancel(true).build());
    }

    /** Mostra o lembrete e agenda o do dia seguinte. */
    public static final class ReminderWorker extends Worker {
        public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
            super(context, parameters);
        }

        @NonNull @Override public Result doWork() {
            Context context = getApplicationContext();
            try {
                show(context, buildMessage(context));
            } catch (Exception ignored) {
                // Um lembrete perdido nunca pode impedir o agendamento do próximo.
            }
            schedule(context);
            return Result.success();
        }
    }
}
