package com.bibliaacessivel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Abre o aplicativo com a Bíblia real e percorre todas as telas novas sem erro. */
@RunWith(RobolectricTestRunner.class)
// SDK 27 usa a conferência de assinatura legada, que o Robolectric permite simular.
@Config(sdk = 27)
public final class ScreensSmokeTest {
    private static Object field(MainActivity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private static void call(MainActivity activity, String name) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(activity);
        ShadowLooper.idleMainLooper();
    }

    private static String heading(MainActivity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        ViewGroup root = (ViewGroup) content.getChildAt(0);
        return ((TextView) root.getChildAt(0)).getText().toString();
    }

    @Test public void everyNewScreenOpensWithTheRealBible() throws Exception {
        // Certificado público oficial: a conferência de autenticidade passa como num APK verdadeiro.
        android.app.Application application = org.robolectric.RuntimeEnvironment.getApplication();
        byte[] certificate;
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream("official-cert.der")) {
            certificate = input.readAllBytes();
        }
        org.robolectric.Shadows.shadowOf(application.getPackageManager())
                .getInternalMutablePackageInfo(application.getPackageName()).signatures =
                new android.content.pm.Signature[]{new android.content.pm.Signature(certificate)};
        // No aparelho o WorkManager inicia sozinho; no teste ele é iniciado com uma execução direta.
        androidx.work.WorkManager.initialize(application, new androidx.work.Configuration.Builder()
                .setExecutor(Runnable::run).build());
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        long deadline = System.currentTimeMillis() + 60_000;
        while (field(activity, "bible") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            ShadowLooper.idleMainLooper();
        }
        assertNotNull("A Bíblia precisa carregar", field(activity, "bible"));
        // Espera a abertura (logo AGB) terminar, como no aparelho, antes de abrir outras telas.
        Thread.sleep(MainActivity.SPLASH_MIN_MILLIS + 300);
        ShadowLooper.idleMainLooper();
        ShadowLooper.idleMainLooper();

        String[][] screens = {
                {"showPlans", "Planos de leitura"}, {"showPrayers", "Diário de oração"},
                {"showMoment", "Meu momento com Deus"}, {"showFavorites", "Favoritos"},
                {"showReadingHistory", "Histórico de leitura"}, {"showMemorization", "Memorização de versículos"},
                {"showReminderSettings", "Lembrete diário"}, {"showBackup", "Backup e restauração"},
                {"showSettings", "Configurações"}, {"showMoreOptions", "Mais opções"},
                {"showStudies", "Estudos — Teólogo de IA"}, {"showVerses", null}, {"showHelp", "Ajuda"},
        };
        for (String[] screen : screens) {
            call(activity, screen[0]);
            if (screen[1] != null) assertEquals(screen[0], screen[1], heading(activity));
        }
        assertTrue(heading(activity).length() > 0);

        // O plano de João pode ser iniciado, lido e marcado, e o lembrete monta a mensagem do dia.
        UserDatabase data = (UserDatabase) field(activity, "userData");
        data.startPlan("john", false);
        call(activity, "openActivePlan");
        assertTrue(heading(activity) + " | " + org.robolectric.shadows.ShadowToast.getTextOfLatestToast(), heading(activity).startsWith("Versículos de João"));
        String message = Reminders.buildMessage(activity);
        assertTrue(message, message.startsWith("Versículo do dia"));
        assertTrue(message, message.contains("Evangelho de João"));
        controller.pause().stop().destroy();
    }
}
