package com.bibliaacessivel.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.Html;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Atividade única com telas nativas, navegação previsível e sem guias. */
public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int CREATE_DEVOTIONAL = 7001;
    private static final String VOICE_OFF = "__off__";
    private static final String[] AI_MODELS = {
            "gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-2.5-pro"
    };

    private BibleRepository bible;
    private HymnalCatalog hymnal;
    private QuizCatalog quizCatalog;
    private UserDatabase userData;
    private SecureStore secureStore;
    private UpdateManager updateManager;
    private SharedPreferences preferences;
    private TextToSpeech tts;
    private boolean ttsReady;
    private Runnable backAction;
    private String translationId;
    private Models.Book selectedBook;
    private int selectedChapter;
    private Models.Verse selectedVerse;
    private String pendingDevotional;
    private EditText worshipInput;
    private TextView worshipStatus;
    private ListView worshipList;
    private ArrayAdapter<String> worshipAdapter;
    private Models.Book worshipBook;
    private int worshipChapter;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        userData = new UserDatabase(this);
        secureStore = new SecureStore(this);
        updateManager = new UpdateManager(this, userData, preferences);
        tts = new TextToSpeech(this, this);
        showLoading();
        new Thread(() -> {
            try {
                bible = new BibleRepository(this);
                hymnal = HymnalCatalog.load(this);
                quizCatalog = QuizCatalog.load(this);
                restorePosition();
                runOnUiThread(() -> {
                    showMainMenu();
                    updateManager.onAppReady();
                });
            } catch (Exception error) {
                runOnUiThread(() -> showFatalError(error));
            }
        }).start();
    }

    /** Restaura tradução, livro, capítulo e número selecionados. */
    private void restorePosition() {
        List<Models.Translation> translations = bible.translations();
        translationId = preferences.getString("translation", "bpm");
        boolean validTranslation = false;
        for (Models.Translation translation : translations) {
            if (translation.id.equals(translationId)) validTranslation = true;
        }
        if (!validTranslation && !translations.isEmpty()) translationId = translations.get(0).id;
        String bookCode = preferences.getString("book", "GEN");
        selectedBook = bible.findBook(translationId, bookCode);
        if (selectedBook == null) selectedBook = bible.books(translationId, false).get(0);
        selectedChapter = Math.max(1, Math.min(
                preferences.getInt("chapter", 1), bible.chapterCount(translationId, selectedBook.code)));
        selectVerse(preferences.getString("verse", "1"));
    }

    /** Mostra um estado simples enquanto o banco grande é copiado na primeira abertura. */
    private void showLoading() {
        LinearLayout root = rootLayout();
        TextView text = heading("Bíblia Acessível. Preparando leitura offline, aguarde.");
        root.addView(text);
        setContentView(root);
    }

    /** Informa falha de inicialização de forma legível pelo TalkBack. */
    private void showFatalError(Exception error) {
        LinearLayout root = rootLayout();
        root.addView(heading("Não foi possível abrir a Bíblia"));
        root.addView(paragraph(error.getMessage() == null ? error.toString() : error.getMessage()));
        setContentView(root);
    }

    /** Cria a tela principal com exatamente cinco destinos. */
    private void showMainMenu() {
        backAction = null;
        LinearLayout root = rootLayout();
        root.addView(heading("Bíblia Acessível"));
        root.addView(paragraph(currentReference() +
                ". Com o TalkBack, deslize para a direita ou para a esquerda para conhecer as opções e toque duas vezes para abrir."));
        root.addView(actionButton("Livros", "Abrir seção Livros", this::showBooks));
        root.addView(actionButton("Capítulos", "Abrir capítulos de " + selectedBook.name,
                this::showChapters));
        root.addView(actionButton("Versículos", "Abrir versículos de " + currentChapterReference(),
                this::showVerses));
        root.addView(actionButton("Área de leitura", "Ler o texto selecionado", this::showReading));
        root.addView(actionButton("Mais opções", "Abrir traduções, pesquisa, notas e configurações",
                this::showMoreOptions));
        setContentView(root);
        root.getChildAt(2).requestFocus();
    }

    /** Lista livros do testamento escolhido e oferece o menu de aplicações. */
    private void showBooks() {
        boolean newTestament = selectedBook.number > 39;
        LinearLayout root = screen("Livros", this::showMainMenu);
        LinearLayout toggles = horizontalRow();
        toggles.addView(actionButton("Antigo Testamento", "Mostrar 39 livros do Antigo Testamento",
                () -> populateBookScreen(false)), weighted());
        toggles.addView(actionButton("Novo Testamento", "Mostrar 27 livros do Novo Testamento",
                () -> populateBookScreen(true)), weighted());
        root.addView(toggles);
        setContentView(root);
        populateBookScreen(newTestament);
    }

    /** Recria a lista de livros sem alterar o padrão de foco da tela. */
    private void populateBookScreen(boolean newTestament) {
        LinearLayout root = screen("Livros — " +
                (newTestament ? "Novo Testamento" : "Antigo Testamento"), this::showMainMenu);
        LinearLayout toggles = horizontalRow();
        toggles.addView(actionButton("Antigo", "Mostrar Antigo Testamento",
                () -> populateBookScreen(false)), weighted());
        toggles.addView(actionButton("Novo", "Mostrar Novo Testamento",
                () -> populateBookScreen(true)), weighted());
        root.addView(toggles);
        List<Models.Book> books = bible.books(translationId, newTestament);
        ListView list = listView(books);
        list.setContentDescription("Livros do " + (newTestament ? "Novo" : "Antigo") + " Testamento");
        list.setOnItemClickListener((parent, view, position, id) -> {
            selectedBook = books.get(position);
            selectedChapter = 1;
            selectVerse("1");
            savePosition();
            showChapters();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            selectedBook = books.get(position);
            showBookActions();
            return true;
        });
        root.addView(list, fill());
        root.addView(paragraph("Toque duas vezes para abrir capítulos. Toque e segure para Aplicações."));
        setContentView(root);
        int index = Math.max(0, books.indexOf(selectedBook));
        list.setSelection(index);
        list.requestFocus();
    }

    /** Mostra números de capítulo do livro atual. */
    private void showChapters() {
        LinearLayout root = screen("Capítulos de " + selectedBook.name, this::showMainMenu);
        int total = bible.chapterCount(translationId, selectedBook.code);
        List<String> chapters = new ArrayList<>();
        for (int number = 1; number <= total; number++) chapters.add("Capítulo " + number);
        ListView list = listView(chapters);
        list.setContentDescription("Capítulos de " + selectedBook.name);
        list.setOnItemClickListener((parent, view, position, id) -> {
            selectedChapter = position + 1;
            selectVerse("1");
            savePosition();
            showVerses();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            selectedChapter = position + 1;
            showChapterActions();
            return true;
        });
        root.addView(list, fill());
        root.addView(paragraph("Ative para abrir. Toque e segure para Aplicações do capítulo."));
        setContentView(root);
        list.setSelection(Math.max(0, selectedChapter - 1));
        list.requestFocus();
    }

    /** Lista os textos do capítulo e repete o atual por voz ou TalkBack. */
    private void showVerses() {
        LinearLayout root = screen("Versículos de " + currentChapterReference(), this::showMainMenu);
        List<Models.Verse> verses = bible.chapter(translationId, selectedBook.code, selectedChapter);
        ListView list = listView(verses);
        list.setContentDescription("Versículos de " + currentChapterReference());
        list.setOnItemClickListener((parent, view, position, id) -> {
            selectedVerse = verses.get(position);
            savePosition();
            speakOrAnnounce(view, selectedVerse.toString());
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            selectedVerse = verses.get(position);
            savePosition();
            showVerseActions();
            return true;
        });
        root.addView(list, fill());
        int maximum = bible.chapterCount(translationId, selectedBook.code);
        if (selectedChapter < maximum) {
            root.addView(actionButton("Próximo capítulo",
                    "Abrir o capítulo " + (selectedChapter + 1) + " de " + selectedBook.name,
                    this::nextChapterFromVerses));
        } else {
            root.addView(actionButton("Fim do livro",
                    "Fim do livro. Não há capítulos seguintes",
                    () -> toast("Fim do livro. Não há capítulos seguintes.")));
        }
        root.addView(actionButton("Aplicações do versículo atual",
                "Abrir ações de " + currentReference(), this::showVerseActions));
        root.addView(paragraph("Ative para ouvir ou repetir. Toque e segure para Aplicações."));
        setContentView(root);
        list.setSelection(verseIndex(verses, selectedVerse == null ? "1" : selectedVerse.number));
        list.requestFocus();
    }

    /** Exibe somente a referência e o texto selecionados, com troca de capítulo. */
    private void showReading() {
        LinearLayout root = screen("Área de leitura", this::showMainMenu);
        TextView reference = heading(currentReference());
        root.addView(reference);
        TextView text = paragraph(selectedVerse == null ? "Nenhum texto disponível." : selectedVerse.text);
        text.setTextIsSelectable(true);
        text.setContentDescription(currentReference() + ". " + text.getText());
        root.addView(text, fill());
        root.addView(actionButton("Repetir versículo", "Repetir " + currentReference(),
                () -> speakOrAnnounce(text, selectedVerse.toString())));
        LinearLayout navigation = horizontalRow();
        navigation.addView(actionButton("Capítulo anterior", "Voltar um capítulo",
                () -> changeChapter(-1)), weighted());
        navigation.addView(actionButton("Próximo capítulo", "Avançar um capítulo",
                () -> changeChapter(1)), weighted());
        root.addView(navigation);
        root.addView(actionButton("Aplicações", "Abrir ações do versículo", this::showVerseActions));
        setContentView(root);
        text.requestFocus();
    }

    /** Reúne recursos secundários numa lista nativa navegável. */
    private void showMoreOptions() {
        LinearLayout root = screen("Mais opções", this::showMainMenu);
        String[] options = {
                "Traduções", "Ir para uma referência", "Pesquisar na Bíblia", "Fazer devocional",
                "Anotações por dia", "Harpa Cristã", "Quiz bíblico", "Status do quiz",
                "Modo culto", "Atualizações", "Configurações", "Licenças, leis e justificativa", "Ajuda"
        };
        ListView list = listView(Arrays.asList(options));
        list.setContentDescription("Mais opções");
        list.setOnItemClickListener((parent, view, position, id) -> {
            switch (position) {
                case 0: showTranslations(); break;
                case 1: showReference(); break;
                case 2: showSearch(); break;
                case 3: showDevotional(); break;
                case 4: showNotes(); break;
                case 5: showHymnal(); break;
                case 6: showQuiz(); break;
                case 7: showQuizStatus(); break;
                case 8: showWorshipMode(); break;
                case 9: showUpdates(); break;
                case 10: showSettings(); break;
                case 11: showLegal(); break;
                default: showHelp();
            }
        });
        root.addView(list, fill());
        setContentView(root);
        list.requestFocus();
    }

    /** Permite trocar a tradução e recarrega a mesma referência quando possível. */
    private void showTranslations() {
        LinearLayout root = screen("Traduções", this::showMoreOptions);
        List<Models.Translation> translations = bible.translations();
        ListView list = listView(translations);
        list.setContentDescription("Traduções bíblicas");
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Models.Translation chosen = translations.get(position);
            String oldCode = selectedBook.code;
            translationId = chosen.id;
            selectedBook = bible.findBook(translationId, oldCode);
            if (selectedBook == null) selectedBook = bible.books(translationId, false).get(0);
            selectedChapter = Math.min(selectedChapter,
                    bible.chapterCount(translationId, selectedBook.code));
            selectVerse(selectedVerse == null ? "1" : selectedVerse.number);
            savePosition();
            Toast.makeText(this, "Tradução selecionada: " + chosen.name, Toast.LENGTH_LONG).show();
        });
        root.addView(list, fill());
        setContentView(root);
        list.requestFocus();
    }

    /** Navega para uma referência digitada em português. */
    private void showReference() {
        LinearLayout root = screen("Ir para uma referência", this::showMoreOptions);
        EditText input = editText("Referência, exemplo João 3:16", false);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        root.addView(input);
        Button go = actionButton("Ir para referência", "Abrir a referência digitada", () -> {
            if (loadReference(input.getText().toString())) showReading();
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            go.performClick();
            return true;
        });
        root.addView(go);
        setContentView(root);
        input.requestFocus();
    }

    /** Pesquisa localmente sem depender de internet. */
    private void showSearch() {
        LinearLayout root = screen("Pesquisar na Bíblia", this::showMoreOptions);
        EditText input = editText("Texto a pesquisar", false);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        root.addView(input);
        Button search = actionButton("Pesquisar", "Executar pesquisa offline", () -> {
            String query = input.getText().toString().trim();
            if (query.isEmpty()) {
                input.setError("Digite uma palavra.");
                return;
            }
            showSearchResults(query);
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            search.performClick();
            return true;
        });
        root.addView(search);
        setContentView(root);
        input.requestFocus();
    }

    /** Mostra resultados textuais e permite copiá-los pelo menu padrão. */
    private void showSearchResults(String query) {
        LinearLayout root = screen("Resultados para " + query, this::showSearch);
        List<String> results = bible.search(translationId, query);
        root.addView(paragraph(results.size() + " resultados encontrados."));
        ListView list = listView(results);
        list.setContentDescription("Resultados da pesquisa");
        list.setOnItemClickListener((parent, view, position, id) ->
                showTextScreen("Resultado", results.get(position), () -> showSearchResults(query)));
        root.addView(list, fill());
        setContentView(root);
        list.requestFocus();
    }

    /** Cria editor de devocional com referência substituível e exportação SAF. */
    private void showDevotional() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        EditText reference = editText("Referência bíblica", false);
        reference.setText(currentReference());
        EditText title = editText("Título do devocional", false);
        title.setText(getString(R.string.devotional_title, currentReference()));
        TextView source = paragraph(selectedVerse == null ? "" : selectedVerse.text);
        EditText body = editText("Texto do devocional", true);
        body.setText("Reflexão:\n\nAplicação para hoje:\n\nOração:\n");
        content.addView(reference);
        content.addView(actionButton("Carregar referência", "Trocar o texto bíblico", () -> {
            if (loadReference(reference.getText().toString())) {
                reference.setText(currentReference());
                source.setText(selectedVerse.text);
                title.setText(getString(R.string.devotional_title, currentReference()));
            }
        }));
        content.addView(title);
        content.addView(source);
        content.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        content.addView(actionButton("Salvar devocional", "Escolher pasta e nome do arquivo", () -> {
            if (body.getText().toString().trim().isEmpty()) {
                body.setError("Escreva o devocional.");
                return;
            }
            pendingDevotional = title.getText() + "\n\nReferência: " + reference.getText() +
                    "\n\nTexto bíblico: " + source.getText() + "\n\n" + body.getText() + "\n";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, safeFilename(title.getText().toString()) + ".txt");
            startActivityForResult(intent, CREATE_DEVOTIONAL);
        }));
        showCustomScreen("Fazer devocional", content, this::showMoreOptions);
        reference.requestFocus();
    }

    /** Agrupa notas por data e abre dia completo ou título isolado. */
    private void showNotes() {
        LinearLayout root = screen("Anotações por dia", this::showMoreOptions);
        List<Models.Note> notes = userData.notes();
        List<NoteRow> rows = groupNotes(notes);
        List<String> labels = new ArrayList<>();
        for (NoteRow row : rows) labels.add(row.label);
        if (labels.isEmpty()) labels.add("Nenhuma anotação salva.");
        ListView list = listView(labels);
        list.setContentDescription("Anotações organizadas por dia e título");
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (rows.isEmpty()) return;
            NoteRow row = rows.get(position);
            showTextScreen(row.label, row.text, this::showNotes);
        });
        root.addView(list, fill());
        setContentView(root);
        list.requestFocus();
    }

    /** Exibe opções persistentes de IA, voz, velocidade e contraste do sistema. */
    private void showSettings() {
        LinearLayout root = screen("Configurações", this::showMoreOptions);
        String[] options = {
                "Colar ou alterar chave do Google Gemini",
                "Obter chave da API do Google",
                "Escolher modelo de inteligência artificial",
                "Escolher voz do Android ou desativar",
                "Velocidade da voz",
                "Alternar atualizações automáticas. " +
                        (preferences.getBoolean("updates_automatic", true) ? "Ativadas" : "Desativadas"),
                "Alternar notificações de atualização. " +
                        (preferences.getBoolean("updates_notify", true) ? "Ativadas" : "Desativadas"),
                "Abrir configurações de acessibilidade do Android"
        };
        ListView list = listView(Arrays.asList(options));
        list.setContentDescription("Opções de configurações");
        list.setOnItemClickListener((parent, view, position, id) -> {
            switch (position) {
                case 0: editApiKey(); break;
                case 1: openGoogleApiKeyPage(); break;
                case 2: chooseModel(); break;
                case 3: chooseVoice(); break;
                case 4: chooseSpeechRate(); break;
                case 5: updateManager.toggleAutomatic(); showSettings(); break;
                case 6: updateManager.toggleNotifications(); showSettings(); break;
                default: startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(list, fill());
        setContentView(root);
        list.requestFocus();
    }

    /** Converte o HTML jurídico do banco em texto selecionável e acessível. */
    private void showLegal() {
        String plain = Html.fromHtml(bible.legalText(translationId), Html.FROM_HTML_MODE_LEGACY).toString();
        String hymnalNotice = "\n\nHARPA CRISTÃ\n\nO aplicativo inclui somente letras em texto, " +
                "sem gravações, cifras, partituras ou arranjos, para leitura acessível e estudo " +
                "num aplicativo gratuito. Catálogo estruturado: github.com/eduardo7321/harpa-crista-app, " +
                "licença MIT. A decisão considera o artigo 46, inciso I, alínea d, da Lei 9.610 de 1998 " +
                "e a declaração pública consultada em aharpacristaonline.com.br de que as letras " +
                "tradicionais são de domínio público. Autoria e integridade permanecem respeitadas. " +
                "O nome identifica o hinário e não representa vínculo com CPAD ou CGADB.";
        String thirdParty = readTextAsset("THIRD_PARTY_NOTICES.md");
        showTextScreen("Licenças, leis e justificativa",
                plain + hymnalNotice + "\n\nAVISOS E LICENÇAS COMPLETOS\n\n" + thirdParty,
                this::showMoreOptions);
    }

    /** Lê um aviso textual empacotado sem depender de internet ou armazenamento externo. */
    private String readTextAsset(String name) {
        try (InputStream input = getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception error) {
            return "O aviso completo não pôde ser carregado nesta instalação.";
        }
    }

    /** Explica comandos essenciais, gestos e privacidade. */
    private void showHelp() {
        showTextScreen("Ajuda", "NAVEGAÇÃO\n\nNa tela principal, use Livros, Capítulos, " +
                "Versículos, Área de leitura ou Mais opções. Com o TalkBack ligado, deslize para a direita " +
                "para ouvir a próxima opção ou para a esquerda para ouvir a opção anterior. Toque duas vezes " +
                "para abrir. O gesto Voltar do Android retorna à tela anterior. Toque e segure " +
                "livro, capítulo ou versículo para abrir Aplicações.\n\nVOZ\n\nAtivar um versículo usa a " +
                "voz escolhida. Se a voz estiver desligada, o TalkBack anuncia novamente o texto.\n\n" +
                "HARPA, QUIZ E MODO CULTO\n\nA Harpa Cristã possui 640 hinos pesquisáveis e funciona " +
                "offline. O quiz possui 298 perguntas e registra acertos e erros em Status do quiz. No Modo " +
                "culto, digite uma passagem e confirme: o texto aparece imediatamente, sem precisar tocar " +
                "em Ler versículo.\n\nATUALIZAÇÕES\n\nA versão Android consulta somente releases android-v " +
                "e baixa somente BibliaAcessivel-Android.apk com seu SHA-256. O Windows usa outro pacote. " +
                "A consulta automática pode ser desligada nas Configurações.\n\n" +
                "PRIVACIDADE\n\nNotas e marcadores ficam somente no aparelho. A chave Gemini é protegida " +
                "pelo Android Keystore. A IA é opcional e exige internet.", this::showMoreOptions);
    }

    /** Abre pesquisa offline e lista acessível dos 640 hinos. */
    private void showHymnal() {
        showHymnal("");
    }

    /** Recria a tela com o filtro confirmado e mantém uma rota de foco previsível. */
    private void showHymnal(String query) {
        LinearLayout root = screen("Harpa Cristã", this::showMoreOptions);
        root.addView(paragraph("Digite número, título ou trecho da letra e confirme. " +
                "Sem pesquisa, todos os 640 hinos são mostrados."));
        EditText input = editText("Pesquisar na Harpa Cristã", false);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setText(query);
        root.addView(input);
        Button search = actionButton("Pesquisar hinos", "Pesquisar número, título ou letra",
                () -> showHymnal(input.getText().toString().trim()));
        input.setOnEditorActionListener((view, actionId, event) -> {
            search.performClick();
            return true;
        });
        root.addView(search);
        List<HymnalCatalog.Hymn> results = hymnal.search(query);
        root.addView(paragraph(results.size() + " hinos encontrados."));
        ListView list = listView(results);
        list.setContentDescription("Hinos da Harpa Cristã");
        list.setOnItemClickListener((parent, view, position, id) -> {
            HymnalCatalog.Hymn hymn = results.get(position);
            showTextScreen(hymn.toString(), hymn.lyrics, () -> showHymnal(query));
        });
        root.addView(list, fill());
        setContentView(root);
        if (query.isEmpty()) input.requestFocus();
        else if (!results.isEmpty()) list.requestFocus();
        else input.requestFocus();
    }

    /** Escolhe filtros do quiz em diálogos nativos e abre a lista resultante. */
    private void showQuiz() {
        new AlertDialog.Builder(this).setTitle("Dificuldade do quiz")
                .setItems(QuizCatalog.DIFFICULTIES, (firstDialog, difficultyIndex) ->
                        new AlertDialog.Builder(this).setTitle("Categoria do quiz")
                                .setItems(quizCatalog.categories, (secondDialog, categoryIndex) ->
                                        showQuizQuestions(
                                                QuizCatalog.DIFFICULTIES[difficultyIndex],
                                                quizCatalog.categories[categoryIndex]))
                                .setNegativeButton("Cancelar", null).show())
                .setNegativeButton("Cancelar", null).show();
    }

    /** Lista as perguntas filtradas e mantém o foco na primeira linha. */
    private void showQuizQuestions(String difficulty, String category) {
        LinearLayout root = screen("Quiz bíblico", this::showMoreOptions);
        List<QuizCatalog.Question> questions = quizCatalog.filtered(difficulty, category);
        root.addView(paragraph(questions.size() + " perguntas. Dificuldade: " + difficulty +
                ". Categoria: " + category + ". Ative uma pergunta para responder."));
        ListView list = listView(questions);
        list.setContentDescription("Perguntas do quiz bíblico");
        list.setOnItemClickListener((parent, view, position, id) ->
                answerQuiz(questions.get(position), difficulty, category));
        root.addView(list, fill());
        root.addView(actionButton("Trocar filtros", "Escolher outra dificuldade ou categoria", this::showQuiz));
        setContentView(root);
        list.requestFocus();
    }

    /** Informa imediatamente acerto ou erro, explicação e referência bíblica. */
    private void answerQuiz(QuizCatalog.Question question, String difficulty, String category) {
        new AlertDialog.Builder(this).setTitle(question.text)
                .setItems(question.answers, (dialog, chosen) -> {
                    boolean correct = chosen == question.correct;
                    userData.recordQuizAttempt(question, correct);
                    String result = (correct ? "Resposta correta. " :
                            "Resposta incorreta. A resposta correta é " +
                                    question.answers[question.correct] + ". ") +
                            question.explanation + " Referência: " + question.reference + ".";
                    new AlertDialog.Builder(this).setTitle("Resultado do quiz")
                            .setMessage(result).setPositiveButton("Continuar",
                                    (resultDialog, which) -> showQuizQuestions(difficulty, category))
                            .setNegativeButton("Fechar", null).show();
                }).setNegativeButton("Cancelar", null).show();
    }

    /** Mostra quantas respostas foram certas e erradas neste aparelho. */
    private void showQuizStatus() {
        int[] stats = userData.quizStatistics();
        int accuracy = stats[0] == 0 ? 0 : Math.round(stats[1] * 100f / stats[0]);
        List<String> lines = Arrays.asList(
                "Perguntas respondidas: " + stats[0],
                "Acertos: " + stats[1],
                "Erros: " + stats[2],
                "Aproveitamento: " + accuracy + " por cento"
        );
        LinearLayout root = screen("Status do quiz", this::showMoreOptions);
        root.addView(paragraph("Resultados acumulados somente neste aparelho."));
        ListView list = listView(lines);
        list.setContentDescription("Acertos e erros do quiz bíblico");
        root.addView(list, fill());
        setContentView(root);
        list.requestFocus();
    }

    /** Cria a busca rápida do culto com o campo focado e o resultado na mesma tela. */
    private void showWorshipMode() {
        LinearLayout root = screen("Modo culto", this::showMoreOptions);
        root.addView(paragraph("Digite uma passagem e confirme. O texto aparecerá imediatamente."));
        worshipInput = editText("Digite uma passagem, exemplo Habacuque 2:4", false);
        worshipInput.setSingleLine(true);
        worshipInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        root.addView(worshipInput);
        Button open = actionButton("Mostrar passagem", "Mostrar imediatamente a passagem digitada",
                this::openWorshipPassage);
        root.addView(open);
        worshipStatus = paragraph("Nenhuma passagem carregada.");
        worshipStatus.setContentDescription("Estado da passagem no Modo culto");
        root.addView(worshipStatus);
        worshipAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        worshipList = new ListView(this);
        worshipList.setAdapter(worshipAdapter);
        worshipList.setContentDescription("Texto da passagem exibida no Modo culto");
        worshipList.setOnItemClickListener((parent, view, position, id) ->
                speakOrAnnounce(view, worshipAdapter.getItem(position)));
        root.addView(worshipList, fill());
        LinearLayout navigation = horizontalRow();
        navigation.addView(actionButton("Capítulo anterior", "Mostrar capítulo anterior no Modo culto",
                () -> changeWorshipChapter(-1)), weighted());
        navigation.addView(actionButton("Próximo capítulo", "Mostrar próximo capítulo no Modo culto",
                () -> changeWorshipChapter(1)), weighted());
        root.addView(navigation);
        root.addView(actionButton("Copiar passagem", "Copiar toda a passagem exibida",
                this::copyWorshipPassage));
        root.addView(actionButton("Digitar outra passagem", "Voltar ao campo de referência", () -> {
            worshipInput.requestFocus();
            worshipInput.selectAll();
            worshipInput.announceForAccessibility("Digite outra passagem e confirme.");
        }));
        worshipInput.setOnEditorActionListener((view, actionId, event) -> {
            openWorshipPassage();
            return true;
        });
        setContentView(root);
        worshipInput.requestFocus();
    }

    /** Resolve a referência e move o foco direto para o texto, sem menu intermediário. */
    private void openWorshipPassage() {
        try {
            BibleReference reference = BibleReference.parse(worshipInput.getText().toString());
            Models.Book book = bible.findBook(translationId, reference.book);
            if (book == null || reference.chapter > bible.chapterCount(translationId, book.code)) {
                throw new IllegalArgumentException("Passagem não encontrada.");
            }
            List<Models.Verse> verses = bible.passage(
                    translationId, book.code, reference.chapter, reference.startVerse, reference.endVerse);
            if (verses.isEmpty()) throw new IllegalArgumentException("Passagem não encontrada.");
            worshipBook = book;
            worshipChapter = reference.chapter;
            selectedBook = book;
            selectedChapter = reference.chapter;
            selectedVerse = verses.get(0);
            savePosition();
            worshipAdapter.clear();
            for (Models.Verse verse : verses) worshipAdapter.add(verse.toString());
            worshipAdapter.notifyDataSetChanged();
            String normalized = book.name + " " + reference.chapter +
                    (reference.startVerse == null ? "" : ":" + reference.startVerse +
                            (reference.endVerse == null ? "" : "-" + reference.endVerse));
            worshipStatus.setText(getResources().getQuantityString(
                    R.plurals.worship_loaded, verses.size(), normalized, verses.size()));
            worshipList.setContentDescription(normalized + ", texto da passagem já exibido");
            worshipList.setSelection(0);
            worshipList.requestFocus();
            worshipList.announceForAccessibility(
                    "Passagem carregada: " + normalized + ". " + verses.get(0));
        } catch (IllegalArgumentException error) {
            worshipInput.setError(error.getMessage());
            worshipInput.requestFocus();
        }
    }

    /** Mostra o capítulo vizinho sem sair do Modo culto. */
    private void changeWorshipChapter(int direction) {
        if (worshipBook == null) {
            toast("Digite uma passagem primeiro.");
            return;
        }
        int target = worshipChapter + direction;
        int maximum = bible.chapterCount(translationId, worshipBook.code);
        if (target < 1 || target > maximum) {
            toast("Não há outro capítulo nessa direção.");
            return;
        }
        worshipInput.setText(getString(R.string.worship_chapter, worshipBook.name, target));
        openWorshipPassage();
    }

    /** Copia referência e todos os versículos mostrados no Modo culto. */
    private void copyWorshipPassage() {
        if (worshipAdapter == null || worshipAdapter.getCount() == 0) {
            toast("Digite uma passagem primeiro.");
            return;
        }
        StringBuilder text = new StringBuilder(worshipStatus.getText()).append('\n');
        for (int index = 0; index < worshipAdapter.getCount(); index++) {
            text.append(worshipAdapter.getItem(index)).append('\n');
        }
        copy(text.toString());
    }

    /** Reúne verificação, novidades, APK pendente e preferências Android. */
    private void showUpdates() {
        LinearLayout root = screen("Atualizações Android", this::showMoreOptions);
        root.addView(paragraph("Versão instalada: " + BuildConfig.VERSION_NAME + ". " +
                updateManager.settingsSummary()));
        root.addView(actionButton("Verificar atualizações", "Consultar agora releases Android",
                () -> updateManager.checkNow(true)));
        root.addView(actionButton("Instalar atualização baixada",
                "Abrir no instalador do Android o APK já validado", updateManager::installPending));
        root.addView(actionButton("Novidades da versão", "Ler novidades disponíveis offline",
                updateManager::showChangelog));
        root.addView(actionButton("Alternar atualizações automáticas",
                "Ativar ou desativar a verificação diária", updateManager::toggleAutomatic));
        root.addView(actionButton("Alternar notificações",
                "Ativar ou desativar avisos de nova versão", updateManager::toggleNotifications));
        setContentView(root);
        root.getChildAt(2).requestFocus();
    }

    /** Mantém a IA como primeira ação do livro. */
    private void showBookActions() {
        new AlertDialog.Builder(this)
                .setTitle("Menu do livro " + selectedBook.name)
                .setItems(new String[]{"Gerar resumo do livro com IA"}, (dialog, which) -> {
                    int words = 550;
                    runAi("Resumo de " + selectedBook.name,
                            "Resuma " + selectedBook.name + " em no máximo " + words +
                                    " palavras, em poucos parágrafos, e conclua completamente.",
                            () -> bible.bookText(translationId, selectedBook.code));
                }).setNegativeButton("Cancelar", null).show();
    }

    /** Mantém a IA como primeira ação do capítulo. */
    private void showChapterActions() {
        new AlertDialog.Builder(this)
                .setTitle("Menu do capítulo " + selectedChapter)
                .setItems(new String[]{"Gerar resumo do capítulo com IA"}, (dialog, which) ->
                        runAi("Resumo de " + currentChapterReference(),
                                "Resuma o capítulo em no máximo 320 palavras e conclua completamente.",
                                () -> bible.chapterText(translationId, selectedBook.code, selectedChapter)))
                .setNegativeButton("Cancelar", null).show();
    }

    /** Oferece IA, anotação, cópia, marcador e voz para o texto atual. */
    private void showVerseActions() {
        if (selectedVerse == null) return;
        boolean marked = userData.isBookmarked(
                translationId, selectedBook.code, selectedChapter, selectedVerse.number);
        String[] actions = {
                "Gerar explicação do versículo com IA", "Criar anotação", "Copiar texto",
                "Copiar referência e texto", marked ? "Remover marcador" : "Adicionar marcador",
                "Ouvir ou repetir pelo leitor de tela"
        };
        new AlertDialog.Builder(this).setTitle("Menu do versículo " + currentReference())
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            runAi("Explicação de " + currentReference(),
                                    "Explique " + currentReference() + " em linguagem simples, em no máximo 280 palavras.",
                                    () -> selectedVerse.toString());
                            break;
                        case 1: createNote(); break;
                        case 2: copy(selectedVerse.text); break;
                        case 3: copy(currentReference() + " — " + selectedVerse.text); break;
                        case 4:
                            boolean nowMarked = userData.toggleBookmark(
                                    translationId, selectedBook.code, selectedChapter, selectedVerse.number);
                            toast(nowMarked ? "Marcador adicionado." : "Marcador removido.");
                            break;
                        default:
                            speakOrAnnounce(getWindow().getDecorView(), selectedVerse.toString());
                    }
                }).setNegativeButton("Cancelar", null).show();
    }

    /** Solicita título e corpo e grava a nota somente quando houver conteúdo. */
    private void createNote() {
        LinearLayout layout = rootLayout();
        EditText title = editText("Título da anotação", false);
        title.setText(currentReference());
        EditText body = editText("Texto da anotação", true);
        layout.addView(title);
        layout.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Criar anotação para " + currentReference())
                .setView(layout)
                .setPositiveButton("Salvar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (body.getText().toString().trim().isEmpty()) {
                        body.setError("Digite a anotação.");
                        body.requestFocus();
                        return;
                    }
                    userData.addNote(translationId, selectedBook, selectedChapter, selectedVerse,
                            title.getText().toString().trim().isEmpty() ? currentReference() : title.getText().toString(),
                            body.getText().toString().trim());
                    toast("Anotação salva em Mais opções, Anotações por dia.");
                    dialog.dismiss();
                }));
        dialog.show();
        title.selectAll();
    }

    /** Executa a rede fora da thread visual e anuncia começo, sucesso ou falha. */
    private void runAi(String title, String instruction, Supplier<String> textSupplier) {
        String apiKey = secureStore.loadApiKey();
        if (apiKey.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Chave necessária")
                    .setMessage("Abra Mais opções, Configurações e informe sua chave do Google Gemini.")
                    .setPositiveButton("Colar chave", (dialog, which) -> editApiKey())
                    .setNeutralButton("Obter chave", (dialog, which) -> openGoogleApiKeyPage())
                    .setNegativeButton("Cancelar", null).show();
            return;
        }
        View source = getWindow().getDecorView();
        source.announceForAccessibility("Gerando conteúdo com inteligência artificial. Aguarde.");
        toast("Gerando conteúdo com IA. Aguarde.");
        String model = preferences.getString("ai_model", AI_MODELS[0]);
        new Thread(() -> {
            try {
                String result = GeminiClient.generate(apiKey, model, instruction, textSupplier.get());
                runOnUiThread(() -> showTextScreen(title, result, this::showMainMenu));
            } catch (Exception error) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Falha na inteligência artificial")
                        .setMessage(error.getMessage()).setPositiveButton("OK", null).show());
            }
        }).start();
    }

    /** Edita a chave em campo protegido e salva pelo Android Keystore. */
    private void editApiKey() {
        EditText input = editText("Chave da API do Google Gemini", false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(secureStore.loadApiKey());
        new AlertDialog.Builder(this).setTitle("Chave do Google Gemini")
                .setView(input).setPositiveButton("Salvar", (dialog, which) -> {
                    try {
                        secureStore.saveApiKey(input.getText().toString());
                        toast("Chave salva com proteção do Android Keystore.");
                    } catch (Exception error) {
                        toast("Não foi possível proteger a chave.");
                    }
                }).setNegativeButton("Cancelar", null).show();
    }

    /** Abre a página oficial do Google AI Studio para criar ou copiar uma chave Gemini. */
    private void openGoogleApiKeyPage() {
        Intent browser = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://aistudio.google.com/app/apikey"));
        try {
            startActivity(browser);
        } catch (Exception error) {
            toast("Não foi possível abrir o navegador neste aparelho.");
        }
    }

    /** Escolhe um dos modelos já suportados pelo cliente. */
    private void chooseModel() {
        String current = preferences.getString("ai_model", AI_MODELS[0]);
        int checked = 0;
        for (int i = 0; i < AI_MODELS.length; i++) if (AI_MODELS[i].equals(current)) checked = i;
        new AlertDialog.Builder(this).setTitle("Modelo da inteligência artificial")
                .setSingleChoiceItems(AI_MODELS, checked, (dialog, which) -> {
                    preferences.edit().putString("ai_model", AI_MODELS[which]).apply();
                    dialog.dismiss();
                    toast("Modelo selecionado: " + AI_MODELS[which]);
                }).setNegativeButton("Cancelar", null).show();
    }

    /** Lista vozes instaladas pelo Android e inclui a desativação. */
    private void chooseVoice() {
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        labels.add("Desativada — TalkBack repete ao ativar");
        values.add(VOICE_OFF);
        labels.add("Voz padrão do Android");
        values.add("");
        if (ttsReady && tts.getVoices() != null) {
            List<Voice> voices = new ArrayList<>(tts.getVoices());
            voices.sort(Comparator.comparing(Voice::getName));
            for (Voice voice : voices) {
                if (voice.getLocale().getLanguage().equals(new Locale("pt").getLanguage())) {
                    labels.add(voice.getName() + " — " + voice.getLocale().getDisplayName());
                    values.add(voice.getName());
                }
            }
        }
        new AlertDialog.Builder(this).setTitle("Voz do Android")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    preferences.edit().putString("voice", values.get(which)).apply();
                    applyVoice();
                    toast("Voz selecionada: " + labels.get(which));
                }).setNegativeButton("Cancelar", null).show();
    }

    /** Ajusta a velocidade em valores compreensíveis, sem controle deslizante inacessível. */
    private void chooseSpeechRate() {
        String[] labels = {"Lenta", "Normal", "Rápida"};
        float[] values = {0.75f, 1.0f, 1.35f};
        new AlertDialog.Builder(this).setTitle("Velocidade da voz")
                .setItems(labels, (dialog, which) -> {
                    preferences.edit().putFloat("speech_rate", values[which]).apply();
                    if (ttsReady) tts.setSpeechRate(values[which]);
                    toast("Velocidade: " + labels[which]);
                }).setNegativeButton("Cancelar", null).show();
    }

    /** Usa voz interna ou força novo anúncio quando ela estiver desligada. */
    private void speakOrAnnounce(View source, String text) {
        String voice = preferences.getString("voice", "");
        if (VOICE_OFF.equals(voice) || !ttsReady) {
            source.announceForAccessibility(text);
            return;
        }
        applyVoice();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "verse");
    }

    /** Aplica nome e velocidade salvos depois que o serviço TTS estiver pronto. */
    private void applyVoice() {
        if (!ttsReady) return;
        tts.setLanguage(new Locale("pt", "BR"));
        tts.setSpeechRate(preferences.getFloat("speech_rate", 1.0f));
        String wanted = preferences.getString("voice", "");
        if (!wanted.isEmpty() && !VOICE_OFF.equals(wanted)) {
            Set<Voice> voices = tts.getVoices();
            if (voices != null) for (Voice voice : voices) {
                if (voice.getName().equals(wanted)) {
                    tts.setVoice(voice);
                    break;
                }
            }
        }
    }

    @Override public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
        if (ttsReady) applyVoice();
    }

    /** Interpreta Livro capítulo:número e mantém mensagens de erro acessíveis. */
    private boolean loadReference(String value) {
        String text = value.trim();
        int colon = text.lastIndexOf(':');
        int space = colon > 0 ? text.lastIndexOf(' ', colon) : -1;
        if (space < 1 || colon < space) {
            toast("Use o formato João 3:16.");
            return false;
        }
        try {
            Models.Book book = bible.findBook(translationId, text.substring(0, space));
            int chapter = Integer.parseInt(text.substring(space + 1, colon));
            String verse = text.substring(colon + 1);
            if (book == null || chapter < 1 || chapter > bible.chapterCount(translationId, book.code)) {
                toast("Referência não encontrada.");
                return false;
            }
            selectedBook = book;
            selectedChapter = chapter;
            selectVerse(verse);
            savePosition();
            return selectedVerse != null;
        } catch (NumberFormatException error) {
            toast("Referência inválida.");
            return false;
        }
    }

    /** Troca capítulos somente dentro do livro atual. */
    private void changeChapter(int direction) {
        int target = selectedChapter + direction;
        int maximum = bible.chapterCount(translationId, selectedBook.code);
        if (target < 1) {
            toast("Início do livro. Não há capítulos anteriores.");
            return;
        }
        if (target > maximum) {
            toast("Fim do livro. Não há capítulos seguintes.");
            return;
        }
        selectedChapter = target;
        selectVerse("1");
        savePosition();
        showReading();
    }

    /** Avança a partir do fim da lista e abre os versículos do novo capítulo. */
    private void nextChapterFromVerses() {
        int maximum = bible.chapterCount(translationId, selectedBook.code);
        if (selectedChapter >= maximum) {
            toast("Fim do livro. Não há capítulos seguintes.");
            return;
        }
        selectedChapter++;
        selectVerse("1");
        savePosition();
        showVerses();
    }

    /** Seleciona pelo início do número para também aceitar intervalos. */
    private void selectVerse(String number) {
        List<Models.Verse> verses = bible.chapter(translationId, selectedBook.code, selectedChapter);
        selectedVerse = verses.isEmpty() ? null : verses.get(0);
        for (Models.Verse verse : verses) {
            if (verse.number.split("-")[0].equals(number)) {
                selectedVerse = verse;
                break;
            }
        }
    }

    /** Persiste a posição sem bloquear a tela. */
    private void savePosition() {
        SharedPreferences.Editor editor = preferences.edit()
                .putString("translation", translationId)
                .putString("book", selectedBook.code)
                .putInt("chapter", selectedChapter);
        if (selectedVerse != null) editor.putString("verse", selectedVerse.number);
        editor.apply();
    }

    /** Constrói linhas de dia e linhas de título para a tela de notas. */
    private List<NoteRow> groupNotes(List<Models.Note> notes) {
        Map<String, List<Models.Note>> grouped = new LinkedHashMap<>();
        for (Models.Note note : notes) {
            String day = note.createdAt.length() >= 10 ? note.createdAt.substring(0, 10) : note.createdAt;
            grouped.computeIfAbsent(day, ignored -> new ArrayList<>()).add(note);
        }
        List<NoteRow> result = new ArrayList<>();
        for (Map.Entry<String, List<Models.Note>> group : grouped.entrySet()) {
            String dayLabel = formatDay(group.getKey());
            StringBuilder all = new StringBuilder();
            for (Models.Note note : group.getValue()) all.append(noteText(note)).append("\n\n");
            result.add(new NoteRow(dayLabel + " — " + group.getValue().size() + " anotações", all.toString()));
            for (Models.Note note : group.getValue()) {
                result.add(new NoteRow("Título: " + note.title + ". Referência: " + note.reference(),
                        noteText(note)));
            }
        }
        return result;
    }

    /** Formata uma nota completa para leitura ou cópia. */
    private String noteText(Models.Note note) {
        return note.title + "\n\nReferência: " + note.reference() + "\n\nTexto bíblico: " +
                note.verseText + "\n\nAnotação: " + note.body;
    }

    /** Formata ISO local usando nomes do locale português. */
    private String formatDay(String iso) {
        try {
            LocalDate day = LocalDate.parse(iso);
            return day.format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy",
                    new Locale("pt", "BR")));
        } catch (Exception ignored) {
            return iso;
        }
    }

    /** Recebe o documento escolhido pelo usuário e grava o devocional. */
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_DEVOTIONAL && resultCode == RESULT_OK && data != null
                && data.getData() != null && pendingDevotional != null) {
            Uri uri = data.getData();
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Destino indisponível.");
                output.write(pendingDevotional.getBytes(StandardCharsets.UTF_8));
                toast("Devocional salvo.");
            } catch (Exception error) {
                toast("Não foi possível salvar o devocional.");
            }
        }
    }

    /** Cria tela textual rolável com foco inicial no conteúdo. */
    private void showTextScreen(String title, String text, Runnable back) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView body = paragraph(text);
        body.setTextIsSelectable(true);
        body.setFocusable(true);
        content.addView(body, fill());
        content.addView(actionButton("Copiar texto", "Copiar todo o conteúdo", () -> copy(text)));
        showCustomScreen(title, content, back);
        body.requestFocus();
    }

    /** Envolve conteúdo customizado em título e botão Voltar. */
    private void showCustomScreen(String title, View content, Runnable back) {
        LinearLayout root = screen(title, back);
        root.addView(content, fill());
        setContentView(root);
    }

    /** Cria raiz vertical com espaçamento confortável para toque. */
    private LinearLayout rootLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        layout.setPadding(padding, padding, padding, padding);
        return layout;
    }

    /** Cria tela secundária uniforme e registra a ação do botão Voltar. */
    private LinearLayout screen(String title, Runnable back) {
        backAction = back;
        LinearLayout root = rootLayout();
        root.addView(heading(title));
        root.addView(actionButton("Voltar", "Voltar à tela anterior", back));
        return root;
    }

    /** Produz título com semântica de cabeçalho para TalkBack. */
    private TextView heading(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(24);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setAccessibilityHeading(true);
        }
        view.setPadding(0, dp(8), 0, dp(12));
        return view;
    }

    /** Produz parágrafo com tamanho legível e seleção de texto opcional. */
    private TextView paragraph(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    /** Cria botão nativo que responde a toque e à ativação do TalkBack. */
    private Button actionButton(String text, String description, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setContentDescription(description);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    /** Cria lista nativa com linhas grandes e quebra de texto. */
    private <T> ListView listView(List<T> values) {
        ListView list = new ListView(this);
        ArrayAdapter<T> adapter = new ArrayAdapter<T>(this,
                android.R.layout.simple_list_item_1, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextSize(18);
                view.setMinHeight(dp(52));
                view.setGravity(Gravity.CENTER_VERTICAL);
                return view;
            }
        };
        list.setAdapter(adapter);
        list.setFocusable(true);
        return list;
    }

    /** Cria campo de uma ou várias linhas com rótulo acessível próprio. */
    private EditText editText(String hint, boolean multiline) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setContentDescription(hint);
        edit.setTextSize(18);
        edit.setSingleLine(!multiline);
        if (multiline) {
            edit.setMinLines(5);
            edit.setGravity(Gravity.TOP);
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        return edit;
    }

    /** Cria uma linha horizontal para ações pareadas. */
    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    /** Parâmetros para ocupar o espaço vertical restante. */
    private LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
    }

    /** Parâmetros para dividir uma linha em partes iguais. */
    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    /** Converte dp para pixels do dispositivo. */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Copia texto e confirma a ação sem expor dados para fora do clipboard escolhido. */
    private void copy(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Bíblia Acessível", text));
        toast("Texto copiado.");
    }

    /** Exibe mensagem curta também anunciada pelos serviços de acessibilidade. */
    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    /** Referência completa do item selecionado. */
    private String currentReference() {
        return selectedBook.name + " " + selectedChapter + ":" +
                (selectedVerse == null ? "1" : selectedVerse.number);
    }

    /** Referência somente até o capítulo. */
    private String currentChapterReference() {
        return selectedBook.name + ", capítulo " + selectedChapter;
    }

    /** Localiza o índice visual do número atual. */
    private int verseIndex(List<Models.Verse> verses, String number) {
        for (int index = 0; index < verses.size(); index++) {
            if (verses.get(index).number.equals(number)) return index;
        }
        return 0;
    }

    /** Remove caracteres inadequados de nomes sugeridos ao seletor de documentos. */
    private String safeFilename(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("[^A-Za-z0-9 _-]", "").trim();
        return normalized.isEmpty() ? "devocional" : normalized;
    }

    @Override public void onBackPressed() {
        if (backAction != null) backAction.run();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (bible != null) bible.close();
        if (userData != null) userData.close();
        super.onDestroy();
    }

    /** Linha de apresentação que aponta para um texto agregado ou individual. */
    private static final class NoteRow {
        final String label;
        final String text;

        NoteRow(String label, String text) {
            this.label = label;
            this.text = text;
        }
    }
}
