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
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Agenda a verificação diária e publica avisos nativos sem expor dados pessoais. */
final class DevotionalNotifications {
    static final String OPEN_DEVOTIONALS = "open_devotionals";
    static final String CHANNEL_ID = "new_devotionals";
    private static final String PERIODIC_NAME = "daily-devotional-check";

    private DevotionalNotifications() {}

    static void schedule(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        WorkManager manager = WorkManager.getInstance(context);
        if (!preferences.getBoolean("devotionals_notify", true)) {
            manager.cancelUniqueWork(PERIODIC_NAME);
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                DevotionalWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints).build();
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic);
        manager.enqueue(new OneTimeWorkRequest.Builder(DevotionalWorker.class)
                .setConstraints(constraints).build());
    }

    static void show(Context context, DevotionalFeed.Item item, int count) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Novos devocionais", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Avisa quando Diego publica um novo devocional no site.");
        manager.createNotificationChannel(channel);
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
                .setContentIntent(pending).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT);
        manager.notify(4001, notification.build());
    }
}
