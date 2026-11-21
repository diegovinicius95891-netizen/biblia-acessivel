package com.bibliaacessivel.app;

import android.app.Application;

/** Prepara o Firebase antes de qualquer tela ou aviso recebido com o app fechado. */
public final class BibliaApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        PushMessaging.init(this);
    }
}
