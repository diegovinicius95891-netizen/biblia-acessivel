package com.bibliaacessivel.app;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Guarda o motivo de um fechamento inesperado para mostrar na próxima abertura. */
final class CrashLog {
    private static final String FILE = "ultimo_erro.txt";
    private static boolean installed;

    private CrashLog() { }

    static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        File target = new File(context.getApplicationContext().getFilesDir(), FILE);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                String report = "Bíblia AGB " + BuildConfig.VERSION_NAME +
                        " — Android " + Build.VERSION.RELEASE + " — " + Build.MANUFACTURER + " " + Build.MODEL +
                        "\n" + new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT).format(new Date()) +
                        "\nLinha de execução: " + thread.getName() + "\n\n" + trace;
                Files.write(target.toPath(), report.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
                // Registrar o erro nunca pode impedir o encerramento normal.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Devolve e apaga o relatório pendente; vazio quando não houve fechamento. */
    static String consume(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.isFile()) return "";
        try {
            String report = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            file.delete();
            return report;
        } catch (Exception error) {
            file.delete();
            return "";
        }
    }
}
