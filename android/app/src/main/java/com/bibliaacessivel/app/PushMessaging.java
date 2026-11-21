package com.bibliaacessivel.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

/** Avisos instantâneos pelo Firebase: o publicador manda um aviso ao tópico e todos recebem na hora. */
final class PushMessaging {
    static final String TOPIC = "devocionais";

    private PushMessaging() {}

    static boolean available() {
        return !BuildConfig.FIREBASE_APP_ID.isEmpty();
    }

    /** Inicia o Firebase com os identificadores do BuildConfig; sem eles, o app segue só com a consulta periódica. */
    static void init(Context context) {
        if (!available() || !FirebaseApp.getApps(context).isEmpty()) return;
        try {
            FirebaseApp.initializeApp(context, new FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build());
        } catch (RuntimeException error) {
            android.util.Log.w("PushMessaging", "Firebase indisponível", error);
        }
    }

    /** Inscreve no tópico apenas quando o aviso está ligado no modo "na hora". */
    static void sync(Context context) {
        if (!available()) return;
        init(context);
        SharedPreferences preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        boolean wanted = preferences.getBoolean("devotionals_notify", true)
                && !DevotionalNotifications.scheduledMode(preferences);
        try {
            FirebaseMessaging messaging = FirebaseMessaging.getInstance();
            if (wanted) messaging.subscribeToTopic(TOPIC);
            else messaging.unsubscribeFromTopic(TOPIC);
            // Builds de depuração também ouvem um tópico de teste, que não chega a ninguém de verdade.
            if (BuildConfig.DEBUG) {
                if (wanted) messaging.subscribeToTopic(TOPIC + "-teste");
                else messaging.unsubscribeFromTopic(TOPIC + "-teste");
            }
        } catch (RuntimeException error) {
            android.util.Log.w("PushMessaging", "Firebase indisponível", error);
        }
    }
}
