package com.bibliaacessivel.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordena atualização Android sem misturar APKs ou regras da versão Windows. */
final class UpdateManager {
    static final String CURRENT_CHANGELOG =
            "Novidades da versão Android 2.1.5\n\n" +
            "Ativar um versículo agora abre diretamente o menu de ações; não é mais necessário tocar e segurar. Com o TalkBack, basta o toque duplo normal.\n\n" +
            "Novo botão Continuar de onde parou na tela principal, com a última referência salva.\n\n" +
            "Nova seção Estudos com resumos de livro e capítulo e Teólogo de IA: exegese, hermenêutica, contexto histórico, teologia bíblica, comparação de interpretações, palavras importantes, aplicação, esboço e pergunta livre.\n\n" +
            "O Teólogo de IA também pode ser aberto pelo menu de qualquer versículo, e todas as perguntas sugeridas podem ser editadas antes do envio.\n\n" +
            "Banco bíblico reconstruído e auditado. Foram corrigidas referências duplicadas no fim de capítulos da Bíblia Portuguesa Mundial e da World English Bible.\n\n" +
            "Permanecem três traduções completas. A Open Translation Bible foi retirada porque a fonte oficial continua sem 1 Reis 19.\n\n" +
            "Cada pergunta do quiz agora registra somente a primeira resposta. Duplicações antigas são corrigidas automaticamente.\n\n" +
            "Nova seção Harpa Cristã com 640 hinos e pesquisa offline.\n\n" +
            "Quiz ampliado para 298 perguntas de estudo.\n\n" +
            "Nova seção Status do quiz com acertos, erros e aproveitamento.\n\n" +
            "O atualizador continua aceitando somente releases android-v e o APK Android.\n\n" +
            "O botão Atualizar agora fica sempre visível; depois do download, Instalar agora abre o instalador oficial do Android.";

    private final Activity activity;
    private final UserDatabase userData;
    private final SharedPreferences preferences;
    private final AndroidUpdateService service = new AndroidUpdateService();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private int announcedProgress;

    UpdateManager(Activity activity, UserDatabase userData, SharedPreferences preferences) {
        this.activity = activity;
        this.userData = userData;
        this.preferences = preferences;
    }

    /** Mostra confirmação pós-atualização uma vez e agenda a consulta diária. */
    void onAppReady() {
        String previous = preferences.getString("updates_last_running_version", "");
        String installing = preferences.getString("updates_installing_version", "");
        preferences.edit().putString("updates_last_running_version", BuildConfig.VERSION_NAME).apply();
        if (BuildConfig.VERSION_NAME.equals(installing) && !BuildConfig.VERSION_NAME.equals(previous)) {
            String installedPath = preferences.getString("updates_pending_apk", "");
            if (!installedPath.isEmpty()) new File(installedPath).delete();
            preferences.edit().remove("updates_installing_version")
                    .remove("updates_pending_apk").remove("updates_pending_version")
                    .remove("updates_pending_sha256").apply();
            new AlertDialog.Builder(activity).setTitle("Atualização concluída")
                    .setMessage("Aplicativo atualizado para a versão " + BuildConfig.VERSION_NAME +
                            ". Seus dados pessoais foram preservados.")
                    .setPositiveButton("Continuar", null)
                    .setNeutralButton("Ver novidades", (dialog, which) -> showChangelog())
                    .show();
        }
        activity.getWindow().getDecorView().postDelayed(this::checkAutomatically, 2500);
    }

    /** Consulta uma vez por dia quando a preferência automática está ativada. */
    void checkAutomatically() {
        if (!preferences.getBoolean("updates_automatic", true)) return;
        String today = LocalDate.now().toString();
        if (today.equals(preferences.getString("updates_last_check", ""))) return;
        checkNow(false);
    }

    /** Inicia uma consulta fora da thread da interface. */
    void checkNow(boolean manual) {
        if (manual) announce("Verificando atualizações.");
        new Thread(() -> {
            try {
                AndroidUpdateService.ReleaseInfo release = service.check(BuildConfig.VERSION_NAME);
                activity.runOnUiThread(() -> handleCheck(release, manual));
            } catch (AndroidUpdateService.UpdateException error) {
                activity.runOnUiThread(() -> handleCheckFailure(error.getMessage(), manual));
            }
        }, "android-update-check").start();
    }

    /** Aplica cache, versão ignorada e lembrete antes de oferecer uma release. */
    private void handleCheck(AndroidUpdateService.ReleaseInfo release, boolean manual) {
        preferences.edit().putString("updates_last_check", LocalDate.now().toString()).apply();
        if (release == null) {
            if (manual) message("Atualizações", "Você já está usando a versão Android mais recente.");
            return;
        }
        if (!manual && !release.mandatory) {
            if (!preferences.getBoolean("updates_notify", true)) return;
            if (release.version.equals(preferences.getString("updates_ignored_version", ""))) return;
            String remind = preferences.getString("updates_remind_after", "");
            try {
                if (!remind.isEmpty() && OffsetDateTime.now().isBefore(OffsetDateTime.parse(remind))) return;
            } catch (Exception ignored) {
                // Preferência antiga inválida não impede uma atualização real.
            }
        }
        showAvailable(release);
    }

    /** Mantém falha automática silenciosa e apresenta somente a mensagem manual. */
    private void handleCheckFailure(String text, boolean manual) {
        if (!manual) {
            preferences.edit().putString("updates_last_check", LocalDate.now().toString()).apply();
        } else {
            message("Atualizações", text);
        }
    }

    /** Mostra novidades e ações numa lista nativa anunciada pelo TalkBack. */
    private void showAvailable(AndroidUpdateService.ReleaseInfo release) {
        long megabytes = release.apk.size <= 0 ? 0 : Math.max(1, release.apk.size / 1024 / 1024);
        String details = "Versão instalada: " + BuildConfig.VERSION_NAME + "\n" +
                "Nova versão Android: " + release.version + "\n" +
                (megabytes > 0 ? "Tamanho: aproximadamente " + megabytes + " MB\n" : "") +
                "\nNovidades:\n" + plainNotes(release.notes);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(release.mandatory ? "Atualização Android obrigatória" : "Nova versão Android disponível")
                .setMessage(details)
                .setPositiveButton("Atualizar agora", null)
                .setNegativeButton(release.mandatory ? "Sair sem atualizar" : "Depois", null);
        if (!release.mandatory) builder.setNeutralButton("Ignorar esta versão", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.dismiss();
                startDownload(release);
            });
            if (release.mandatory) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                    dialog.dismiss();
                    activity.finish();
                });
            }
            if (!release.mandatory) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                    preferences.edit().putString("updates_ignored_version", release.version).apply();
                    dialog.dismiss();
                });
            }
        });
        dialog.show();
    }

    /** Cria progresso acessível, botão de cancelamento e inicia o download verificado. */
    private void startDownload(AndroidUpdateService.ReleaseInfo release) {
        cancelled.set(false);
        announcedProgress = 0;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView status = new TextView(activity);
        status.setText(R.string.update_downloading_status);
        status.setTextSize(18);
        content.addView(status);
        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setContentDescription(activity.getString(R.string.update_progress_description));
        content.addView(progressBar);
        Button cancel = new Button(activity);
        cancel.setText(R.string.update_cancel_download);
        cancel.setContentDescription(activity.getString(R.string.update_cancel_description));
        cancel.setOnClickListener(view -> cancelled.set(true));
        content.addView(cancel);
        progressDialog = new AlertDialog.Builder(activity).setTitle("Baixando atualização Android")
                .setView(content).create();
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();
        cancel.requestFocus();

        File target = new File(updatesDirectory(), release.tag + "-" + AndroidUpdateService.APK_NAME);
        new Thread(() -> {
            try {
                String digest = service.downloadAndValidate(
                        release, target, cancelled, value -> activity.runOnUiThread(() -> updateProgress(value)));
                activity.runOnUiThread(() -> downloadFinished(release, target, digest));
            } catch (AndroidUpdateService.UpdateException error) {
                activity.runOnUiThread(() -> downloadFailed(error.getMessage()));
            }
        }, "android-update-download").start();
    }

    /** Atualiza a barra e anuncia somente cinco marcos para não cansar o TalkBack. */
    private void updateProgress(int value) {
        if (progressBar == null) return;
        progressBar.setProgress(value);
        int milestone = value >= 100 ? 100 : value >= 75 ? 75 : value >= 50 ? 50 : value >= 25 ? 25 : value >= 10 ? 10 : 0;
        if (milestone > announcedProgress) {
            announcedProgress = milestone;
            progressBar.announceForAccessibility("Download " + milestone + "%.");
        }
    }

    /** Guarda o APK validado, cria backup e pede autorização para instalar. */
    private void downloadFinished(AndroidUpdateService.ReleaseInfo release, File apk, String digest) {
        closeProgress();
        preferences.edit()
                .putString("updates_pending_apk", apk.getAbsolutePath())
                .putString("updates_pending_version", release.version)
                .putString("updates_pending_sha256", digest)
                .apply();
        try {
            userData.backupBeforeUpdate(release.version);
        } catch (Exception error) {
            message("Não foi possível criar o backup",
                    "O APK foi validado, mas a instalação não continuará sem proteger os dados pessoais.");
            return;
        }
        new AlertDialog.Builder(activity).setTitle("Atualização Android pronta")
                .setMessage("O APK foi baixado, conferido por SHA-256 e os dados receberam backup. Instalar agora?")
                .setPositiveButton("Instalar agora", (dialog, which) -> installPending())
                .setNegativeButton("Depois", null).show();
    }

    /** Fecha progresso e diferencia cancelamento de falha operacional. */
    private void downloadFailed(String text) {
        closeProgress();
        message(text.toLowerCase(Locale.ROOT).contains("cancelado") ?
                "Download cancelado" : "Falha na atualização", text);
    }

    /** Fecha e limpa referências visuais do diálogo de progresso. */
    private void closeProgress() {
        if (progressDialog != null) progressDialog.dismiss();
        progressDialog = null;
        progressBar = null;
    }

    /** Entrega somente o APK validado ao instalador oficial do Android. */
    void installPending() {
        String path = preferences.getString("updates_pending_apk", "");
        String version = preferences.getString("updates_pending_version", "");
        File apk = path.isEmpty() ? null : new File(path);
        if (apk == null || !apk.isFile() || !path.startsWith(updatesDirectory().getAbsolutePath())) {
            message("Nenhuma atualização pronta", "Use Verificar atualizações para baixar o APK Android.");
            return;
        }
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity).setTitle("Autorizar instalação")
                    .setMessage("O Android precisa permitir que a Bíblia Acessível abra o APK validado. " +
                            "Ative a permissão nesta tela e depois volte a Atualizações para instalar.")
                    .setPositiveButton("Abrir permissão", (dialog, which) -> {
                        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(settings);
                    }).setNegativeButton("Cancelar", null).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    activity, activity.getPackageName() + ".files", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            preferences.edit().putString("updates_installing_version", version).apply();
            activity.startActivity(install);
        } catch (Exception error) {
            message("Não foi possível abrir o instalador",
                    "O APK continua salvo e a versão atual permanece funcionando.");
        }
    }

    /** Alterna a consulta automática e confirma o novo estado. */
    void toggleAutomatic() {
        boolean enabled = !preferences.getBoolean("updates_automatic", true);
        preferences.edit().putBoolean("updates_automatic", enabled).apply();
        message("Atualizações automáticas", enabled ? "Ativadas." : "Desativadas.");
    }

    /** Alterna somente a janela de notificação, preservando consulta manual. */
    void toggleNotifications() {
        boolean enabled = !preferences.getBoolean("updates_notify", true);
        preferences.edit().putBoolean("updates_notify", enabled).apply();
        message("Notificações de atualização", enabled ? "Ativadas." : "Desativadas.");
    }

    /** Texto legível do estado das duas preferências. */
    String settingsSummary() {
        return "Atualizações automáticas: " +
                (preferences.getBoolean("updates_automatic", true) ? "ativadas" : "desativadas") +
                ". Notificações: " +
                (preferences.getBoolean("updates_notify", true) ? "ativadas" : "desativadas") + ".";
    }

    /** Exibe as novidades incorporadas no APK e disponíveis offline. */
    void showChangelog() {
        new AlertDialog.Builder(activity).setTitle("Novidades da versão Android")
                .setMessage(CURRENT_CHANGELOG).setPositiveButton("Fechar", null).show();
    }

    /** Pasta privada externa compartilhável somente pelo FileProvider declarado. */
    private File updatesDirectory() {
        File base = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = new File(activity.getFilesDir(), "downloads");
        File result = new File(base, "updates");
        result.mkdirs();
        return result;
    }

    /** Remove marcas simples de Markdown antes de apresentar o changelog. */
    private static String plainNotes(String value) {
        return value.replace("#", "").replace("*", "").replace("<!-- mandatory: true -->", "").trim();
    }

    /** Abre uma mensagem nativa com título e texto acessíveis. */
    private void message(String title, String text) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(text)
                .setPositiveButton("OK", null).show();
    }

    /** Envia mensagem breve ao TalkBack a partir da janela principal. */
    private void announce(String text) {
        View source = activity.getWindow().getDecorView();
        source.announceForAccessibility(text);
    }
}
