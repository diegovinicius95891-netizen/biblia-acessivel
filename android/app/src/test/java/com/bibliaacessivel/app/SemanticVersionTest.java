package com.bibliaacessivel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** Testa comparação real das versões exclusivas do canal Android. */
public final class SemanticVersionTest {
    @Test public void comparesNumericComponents() {
        assertTrue(SemanticVersion.parse("1.10.0").compareTo(SemanticVersion.parse("1.9.9")) > 0);
        assertTrue(SemanticVersion.parse("2.0.0").compareTo(SemanticVersion.parse("1.99.99")) > 0);
    }

    @Test public void understandsAndroidTagAndPrerelease() {
        assertEquals("2.0.0", SemanticVersion.parse("android-v2.0.0").toString());
        assertTrue(SemanticVersion.parse("android-v2.0.0-beta.1")
                .compareTo(SemanticVersion.parse("android-v2.0.0")) < 0);
    }

    @Test public void androidChannelRejectsWindowsDraftAndPrerelease() {
        assertTrue(AndroidUpdateService.shouldConsider("android-v2.0.0", false, false));
        assertFalse(AndroidUpdateService.shouldConsider("v99.0.0", false, false));
        assertFalse(AndroidUpdateService.shouldConsider("android-v2.1.0", true, false));
        assertFalse(AndroidUpdateService.shouldConsider("android-v2.1.0-beta.1", false, false));
        assertTrue(AndroidUpdateService.acceptsAssetName("BibliaAcessivel-Android.apk"));
        assertTrue(AndroidUpdateService.acceptsAssetName("BibliaAcessivel-Android.apk.sha256"));
        assertFalse(AndroidUpdateService.acceptsAssetName("BibliaAcessivel-Windows.zip"));
        assertFalse(AndroidUpdateService.acceptsAssetName("BibliaAcessivel.exe"));
    }
}
