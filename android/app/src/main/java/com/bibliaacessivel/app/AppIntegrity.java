package com.bibliaacessivel.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;

/** Confere se o APK foi assinado pelo mesmo certificado usado nas atualizações oficiais. */
final class AppIntegrity {
    private static final String EXPECTED_CERTIFICATE_SHA256 =
            "99d543111b3de0a4bf7acc1f16a3760351d1827515b89b86df5c0ce815e6822a";

    private AppIntegrity() { }

    static boolean hasExpectedSignature(Context context) {
        try {
            PackageManager manager = context.getPackageManager();
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = manager.getPackageInfo(context.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = info.signingInfo == null ? new Signature[0] :
                        info.signingInfo.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo info = manager.getPackageInfo(context.getPackageName(),
                        PackageManager.GET_SIGNATURES);
                @SuppressWarnings("deprecation")
                Signature[] legacy = info.signatures;
                signatures = legacy == null ? new Signature[0] : legacy;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                if (hex(digest.digest(signature.toByteArray())).equals(EXPECTED_CERTIFICATE_SHA256)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Falha fechada: uma instalação cuja assinatura não pôde ser
            // comprovada não recebe acesso ao conteúdo e às chaves privadas.
        }
        return false;
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format("%02x", value & 0xff));
        return output.toString();
    }
}
