package com.bibliaacessivel.app;

import android.graphics.Bitmap;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Desenha a abertura e telas principais em PNG (build/telas) para conferência visual. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 27, qualifiers = "w360dp-h640dp-xxhdpi")
public final class VisualPreviewTest {
    private static void save(MainActivity activity, String name) throws Exception {
        ShadowLooper.idleMainLooper();
        View root = activity.getWindow().getDecorView();
        int width = 1080, height = 1920;
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        Files.createDirectories(Paths.get("build", "telas"));
        try (FileOutputStream output = new FileOutputStream("build/telas/" + name + ".png")) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
    }

    @Test public void drawsSplashAndMainScreens() throws Exception {
        android.app.Application application = org.robolectric.RuntimeEnvironment.getApplication();
        byte[] certificate;
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream("official-cert.der")) {
            certificate = input.readAllBytes();
        }
        org.robolectric.Shadows.shadowOf(application.getPackageManager())
                .getInternalMutablePackageInfo(application.getPackageName()).signatures =
                new android.content.pm.Signature[]{new android.content.pm.Signature(certificate)};
        androidx.work.WorkManager.initialize(application, new androidx.work.Configuration.Builder()
                .setExecutor(Runnable::run).build());
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        save(activity, "1-abertura");
        Field bible = MainActivity.class.getDeclaredField("bible");
        bible.setAccessible(true);
        long deadline = System.currentTimeMillis() + 60_000;
        while (bible.get(activity) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            ShadowLooper.idleMainLooper();
        }
        Thread.sleep(300);
        for (String[] screen : new String[][]{{"showMainMenu", "2-menu"}, {"showVerses", "3-versiculos"},
                {"showMoreOptions", "4-mais-opcoes"}, {"showPlans", "5-planos"}, {"showStudies", "6-estudos-ia"}}) {
            Method method = MainActivity.class.getDeclaredMethod(screen[0]);
            method.setAccessible(true);
            method.invoke(activity);
            save(activity, screen[1]);
        }
    }
}
