package com.bibliaacessivel.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Agenda a verificação a cada 15 minutos e publica avisos nativos sem expor dados pessoais. */
final class DevotionalNotifications {
    static final String OPEN_DEVOTIONALS = "open_devotionals";
    // Canal novo com prioridade alta: o antigo não pode ter a importância alterada depois de criado.
    static final String CHANNEL_ID = "new_devotionals_alert";
    private static final String OLD_CHANNEL_ID = "new_devotionals";
    private static final String PERIODIC_NAME = "daily-devotional-check";
    /** O menor intervalo que o Android permite para verificações em segundo plano. */
    static final long CHECK_INTERVAL_MINUTES = 15;

    private DevotionalNotifications() {}

    static void schedule(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        WorkManager manager = WorkManager.getInstance(context);
        createChannel(context);
        if (!preferences.getBoolean("devotionals_notify", true)) {
            manager.cancelUniqueWork(PERIODIC_NAME);
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        // O aviso de devocional novo deve chegar rápido: a consulta leva poucos bytes.
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                DevotionalWorker.class, CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints).build();
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic);
        // Remove a consulta imediata que versões anteriores disparavam a cada abertura.
        manager.cancelUniqueWork(PERIODIC_NAME + "-now");
    }

    static void createChannel(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        manager.deleteNotificationChannel(OLD_CHANNEL_ID);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Novos devocionais", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Avisa quando Diego publica um novo devocional no site.");
        manager.createNotificationChannel(channel);
    }

    static void show(Context context, DevotionalFeed.Item item, int count) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        createChannel(context);
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(OPEN_DEVOTIONALS, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 401, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = count == 1 ? item.title : count + " novos textos. Mais recente: " + item.title;
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(count == 1 ? "Novo devocional" : "Novos devocionais")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH);
        manager.notify(4001, notification.build());
    }
}
