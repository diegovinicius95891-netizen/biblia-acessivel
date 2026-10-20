package com.bibliaacessivel.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.time.LocalDate;
import java.util.List;

/** Trabalho tolerante a falhas que compara a fonte pública uma vez por dia. */
public final class DevotionalWorker extends Worker {
    public DevotionalWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!preferences.getBoolean("devotionals_notify", true)) return Result.success();
        String today = LocalDate.now().toString();
        if (today.equals(preferences.getString("devotionals_last_background_check", ""))) return Result.success();
        try {
            List<DevotionalFeed.Item> posts = DevotionalFeed.fetch(context);
            if (posts.isEmpty()) return Result.success();
            String previous = preferences.getString("devotionals_latest_id", "");
            String latest = posts.get(0).id;
            int newCount = 0;
            if (previous != null && !previous.isEmpty() && !latest.equals(previous)) {
                for (DevotionalFeed.Item post : posts) {
                    if (post.id.equals(previous)) break;
                    newCount++;
                }
            }
            preferences.edit().putString("devotionals_latest_id", latest)
                    .putString("devotionals_last_background_check", today).apply();
            if (newCount > 0) DevotionalNotifications.show(context, posts.get(0), newCount);
            return Result.success();
        } catch (Exception error) {
            return getRunAttemptCount() < 3 ? Result.retry() : Result.success();
        }
    }
}
