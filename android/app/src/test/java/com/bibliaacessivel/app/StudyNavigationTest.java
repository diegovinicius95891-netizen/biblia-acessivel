package com.bibliaacessivel.app;

import android.app.AlertDialog;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StudyNavigationTest {
    private MainActivity activity() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        Field book = MainActivity.class.getDeclaredField("selectedBook");
        book.setAccessible(true);
        book.set(activity, new Models.Book(1, "GEN", "Gênesis"));
        return activity;
    }

    private void open(MainActivity activity, String name) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(activity);
    }

    private String heading(MainActivity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        ViewGroup root = (ViewGroup) content.getChildAt(0);
        return ((TextView) root.getChildAt(0)).getText().toString();
    }

    @Test public void bothScopesReturnToStudiesWithoutReopeningTheDialog() throws Exception {
        for (int scope = 0; scope < 2; scope++) {
            MainActivity activity = activity();
            open(activity, "showStudies");
            open(activity, "showStudyThemeScope");
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            dialog.getListView().performItemClick(null, scope, scope);
            activity.onBackPressed();
            assertEquals("Estudos — Teólogo de IA", heading(activity));
            assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing());
            // Reopening and cancelling must leave Studies as the underlying screen.
            open(activity, "showStudyThemeScope");
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            assertEquals("Estudos — Teólogo de IA", heading(activity));
            activity.onBackPressed();
            assertEquals("Mais opções", heading(activity));
        }
    }
}
