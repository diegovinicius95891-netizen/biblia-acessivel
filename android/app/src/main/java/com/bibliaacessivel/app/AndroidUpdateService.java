package com.bibliaacessivel.app;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Consulta somente releases Android e valida o APK antes de entregá-lo ao sistema. */
final class AndroidUpdateService {
    static final String REPOSITORY = "diegovinicius95891-netizen/biblia-acessivel";
    static final String APK_NAME = "BibliaAcessivel-Android.apk";
    static final String HASH_NAME = APK_NAME + ".sha256";
    private static final String TAG = "BibleAndroidUpdater";
    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "api.github.com", "github.com", "objects.githubusercontent.com",
            "github-releases.githubusercontent.com", "release-assets.githubusercontent.com"));

    /** Informa o progresso inteiro do download sem acoplar o serviço à interface. */
    interface ProgressListener {
        void onProgress(int value);
    }

    /** Metadados dos dois assets obrigatórios de uma release Android. */
    static final class ReleaseInfo {
        final String version;
        final String tag;
        final String notes;
        final String pageUrl;
        final Asset apk;
        final Asset hash;
        final boolean mandatory;

        ReleaseInfo(String version, String tag, String notes, String pageUrl,
                    Asset apk, Asset hash, boolean mandatory) {
            this.version = version;
            this.tag = tag;
            this.notes = notes;
            this.pageUrl = pageUrl;
            this.apk = apk;
            this.hash = hash;
            this.mandatory = mandatory;
        }
    }

    /** Nome, endereço e tamanho declarados pelo GitHub para um asset. */
    static final class Asset {
        final String name;
        final String url;
        final long size;

        Asset(String name, String url, long size) {
            this.name = name;
            this.url = url;
            this.size = size;
        }
    }

    /** Falha operacional que pode ser resumida sem mostrar detalhes técnicos. */
    static final class UpdateException extends Exception {
        UpdateException(String message) {
            super(message);
        }

        UpdateException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Procura a release estável Android mais nova e ignora releases Windows. */
    ReleaseInfo check(String currentVersion) throws UpdateException {
        String endpoint = "https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=30";
        Log.i(TAG, "Verificando versão Android " + currentVersion);
        try {
            JSONArray releases = new JSONArray(readText(endpoint, 2_000_000));
            SemanticVersion current = SemanticVersion.parse(currentVersion);
            SemanticVersion newest = null;
            ReleaseInfo result = null;
            for (int index = 0; index < releases.length(); index++) {
                JSONObject release = releases.getJSONObject(index);
                String tag = release.optString("tag_name");
                if (!shouldConsider(tag, release.optBoolean("draft"),
                        release.optBoolean("prerelease"))) continue;
                SemanticVersion candidate;
                try {
                    candidate = SemanticVersion.parse(tag);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (!candidate.prerelease.isEmpty()) continue;
                if (candidate.compareTo(current) <= 0 || (newest != null && candidate.compareTo(newest) <= 0)) {
                    continue;
                }
                Asset apk = null;
                Asset hash = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) continue;
                for (int assetIndex = 0; assetIndex < assets.length(); assetIndex++) {
                    JSONObject value = assets.getJSONObject(assetIndex);
                    Asset parsed = parseAsset(value);
                    if (APK_NAME.equals(parsed.name)) apk = parsed;
                    if (HASH_NAME.equals(parsed.name)) hash = parsed;
                }
                if (apk == null || hash == null) continue;
                String notes = release.optString("body", "Sem descrição publicada para esta versão.");
                newest = candidate;
                result = new ReleaseInfo(candidate.toString(), tag, notes,
                        release.optString("html_url"), apk, hash,
                        notes.toLowerCase(Locale.ROOT).contains("<!-- mandatory: true -->"));
            }
            Log.i(TAG, "Verificação concluída: " + (result == null ? "nenhuma" : result.version));
            return result;
        } catch (UpdateException error) {
            throw error;
        } catch (Exception error) {
            Log.w(TAG, "Falha na verificação", error);
            throw new UpdateException("Não foi possível verificar atualizações agora. Tente novamente mais tarde.", error);
        }
    }

    /** Baixa hash e APK, confere tamanho e remove qualquer arquivo inválido. */
    String downloadAndValidate(ReleaseInfo release, File destination, AtomicBoolean cancelled,
                               ProgressListener listener) throws UpdateException {
        destination.getParentFile().mkdirs();
        String manifest = readText(release.hash.url, 4096).trim();
        String expected = manifest.isEmpty() ? "" : manifest.split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}")) {
            throw new UpdateException("O arquivo de integridade publicado é inválido.");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new UpdateException("O aparelho não oferece o verificador SHA-256.", error);
        }
        long received = 0;
        try {
            HttpURLConnection connection = open(release.apk.url);
            long total = release.apk.size > 0 ? release.apk.size : connection.getContentLengthLong();
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[256 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (cancelled.get()) throw new UpdateException("Download cancelado.");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    received += count;
                    if (listener != null && total > 0) {
                        listener.onProgress(Math.min(100, (int) (received * 100 / total)));
                    }
                }
            } finally {
                connection.disconnect();
            }
            if (release.apk.size > 0 && received != release.apk.size) {
                throw new UpdateException("O download terminou com um tamanho diferente do publicado.");
            }
            String actual = hexadecimal(digest.digest());
            if (!actual.equals(expected)) {
                throw new UpdateException("A atualização não passou na verificação de integridade. Nenhum APK foi instalado.");
            }
            Log.i(TAG, "APK Android validado: versão=" + release.version + " sha256=" + actual);
            return actual;
        } catch (UpdateException error) {
            destination.delete();
            throw error;
        } catch (Exception error) {
            destination.delete();
            Log.w(TAG, "Falha no download", error);
            throw new UpdateException("Não foi possível baixar a atualização agora.", error);
        }
    }

    /** Lê uma resposta textual com limite para evitar conteúdo remoto excessivo. */
    private String readText(String address, int maximum) throws UpdateException {
        try {
            HttpURLConnection connection = open(address);
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (output.size() + count > maximum) throw new UpdateException("A resposta de atualização é grande demais.");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            } finally {
                connection.disconnect();
            }
        } catch (UpdateException error) {
            throw error;
        } catch (Exception error) {
            throw new UpdateException("Não foi possível consultar o serviço de atualização.", error);
        }
    }

    /** Abre HTTPS com timeout e valida também o destino final de redirecionamentos. */
    private HttpURLConnection open(String address) throws Exception {
        URL requested = new URL(address);
        for (int redirects = 0; redirects <= 5; redirects++) {
            validateUrl(requested);
            HttpURLConnection connection = (HttpURLConnection) requested.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "BibliaAcessivel-Android-Updater");
            connection.connect();
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) return connection;
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) break;
                requested = new URL(requested, location);
                continue;
            }
            connection.disconnect();
            break;
        }
        throw new UpdateException("O GitHub não entregou a atualização solicitada.");
    }

    /** Aceita somente HTTPS em domínios esperados do GitHub. */
    private static void validateUrl(URL url) throws UpdateException {
        if (!"https".equalsIgnoreCase(url.getProtocol()) ||
                !ALLOWED_HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT))) {
            throw new UpdateException("A atualização usa um endereço de download não autorizado.");
        }
    }

    /** Extrai somente os campos de asset necessários e valida a URL imediatamente. */
    private static Asset parseAsset(JSONObject value) throws Exception {
        String name = value.optString("name");
        String url = value.optString("browser_download_url");
        validateUrl(new URL(url));
        return new Asset(name, url, value.optLong("size"));
    }

    /** Converte os bytes do resumo criptográfico para 64 caracteres hexadecimais. */
    private static String hexadecimal(byte[] value) {
        StringBuilder builder = new StringBuilder();
        for (byte item : value) builder.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return builder.toString();
    }

    /** Separa o canal Android e descarta drafts e pré-lançamentos. */
    static boolean shouldConsider(String tag, boolean draft, boolean prerelease) {
        return !draft && !prerelease && tag != null &&
                tag.matches("^android-v\\d+\\.\\d+\\.\\d+$");
    }

    /** Expõe os únicos nomes que podem participar de uma atualização Android. */
    static boolean acceptsAssetName(String name) {
        return APK_NAME.equals(name) || HASH_NAME.equals(name);
    }
}
