package com.bibliaacessivel.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/** Recebe o aviso enviado pelo publicador no mesmo momento em que o devocional sai no site. */
public final class DevotionalPushService extends FirebaseMessagingService {
    @Override public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        String id = data.get("id");
        String title = data.get("titulo");
        if (id == null || id.isEmpty() || title == null || title.isEmpty()) return;
        SharedPreferences preferences = getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!preferences.getBoolean("devotionals_notify", true)
                || DevotionalNotifications.scheduledMode(preferences)) return;
        // Marca como visto para a consulta periódica de reserva não repetir o aviso.
        if (id.equals(preferences.getString("devotionals_latest_id", ""))) return;
        preferences.edit().putString("devotionals_latest_id", id).apply();
        DevotionalNotifications.show(this, new DevotionalFeed.Item(id, title, "", ""), 1);
    }

    @Override public void onNewToken(@NonNull String token) {
        // O aviso é por tópico; ao trocar o token, basta reinscrever.
        PushMessaging.sync(this);
    }
}
