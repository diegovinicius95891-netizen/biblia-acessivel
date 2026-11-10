package com.bibliaacessivel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
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
        android.app.Application application = org.robolectric.RuntimeEnvironment.getApplication();
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

        // Seleção múltipla: segurar marca o 1, um toque marca o 3, e o botão de ações mostra os dois.
        call(activity, "showVerses");
        ViewGroup page = (ViewGroup) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        android.widget.ListView verses = null;
        for (int i = 0; i < page.getChildCount(); i++) {
            if (page.getChildAt(i) instanceof android.widget.ListView) verses = (android.widget.ListView) page.getChildAt(i);
        }
        assertNotNull(verses);
        verses.getOnItemLongClickListener().onItemLongClick(verses, null, 0, 0);
        verses.getOnItemClickListener().onItemClick(verses, null, 2, 2);
        boolean found = false;
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child instanceof android.widget.Button && child.getVisibility() == View.VISIBLE &&
                    ((android.widget.Button) child).getText().toString().startsWith("Ações para 2 versículos")) {
                assertTrue(((android.widget.Button) child).getText().toString(),
                        ((android.widget.Button) child).getText().toString().endsWith(":1, 3"));
                found = true;
            }
        }
        assertTrue("Botão de ações em massa", found);

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
