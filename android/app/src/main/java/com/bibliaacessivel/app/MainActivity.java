package com.bibliaacessivel.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.Html;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
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
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Atividade única com telas nativas, navegação previsível e sem guias. */
public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int CREATE_DEVOTIONAL = 7001;
    private static final int WORSHIP_VOICE_SEARCH = 7002;
    private static final int WORSHIP_AUDIO_PERMISSION = 7003;
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
    private List<DevotionalFeed.Item> onlineDevotionals = new ArrayList<>();
    private TextView onlineDevotionalStatus;
    private ListView onlineDevotionalList;
    private ArrayAdapter<String> onlineDevotionalAdapter;
    private List<GospelRadioFeed.Track> radioTracks = new ArrayList<>();
    private TextView radioStatus;
    private ListView radioList;
    private ArrayAdapter<String> radioAdapter;
    private MediaPlayer radioPlayer;
    private int radioIndex = -1;
    private boolean radioPreparing;
    private boolean chapterSwipeEnabled;
    private boolean chapterSwipeTracking;
    private float chapterSwipeStartX;
    private boolean touchExplorationSwipeTracking;
    private float touchExplorationStartX;
    private float touchExplorationStartY;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AppIntegrity.hasExpectedSignature(this)) {
            LinearLayout root = rootLayout();
            root.addView(heading("Não foi possível confirmar esta instalação"));
            root.addView(paragraph("A autenticidade do aplicativo não pôde ser confirmada. Baixe novamente pelo link oficial."));
            setContentView(root);
            return;
        }
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
                    if (getIntent().getBooleanExtra(DevotionalNotifications.OPEN_DEVOTIONALS, false)) {
                        showOnlineDevotionals();
                    } else {
                        showMainMenu();
                    }
                    updateManager.onAppReady();
                    DevotionalNotifications.schedule(this);
                    getWindow().getDecorView().postDelayed(this::ensureDevotionalNotificationPermission, 1200);
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
        root.addView(paragraph("Não consegui carregar os textos bíblicos nesta instalação. " +
                "Baixe novamente o aplicativo e tente outra vez."));
        setContentView(root);
    }

    /** Cria uma entrada curta: continuar o capítulo, trocar o livro ou abrir recursos. */
    private void showMainMenu() {
        chapterSwipeEnabled = false;
        backAction = null;
        LinearLayout root = rootLayout();
        root.addView(heading("Bíblia Acessível"));
        root.addView(paragraph("Seção Livros. Sua posição é " + currentReference() + "."));
        root.addView(actionButton("Abrir capítulo " + selectedChapter + " de " + selectedBook.name,
                "Continuar a leitura em " + currentReference(), this::showVerses));
        root.addView(actionButton("Trocar de livro", "Escolher outro livro, capítulo e versículo",
                this::confirmBookChoice));
        root.addView(actionButton("Mais opções", "Abrir traduções, pesquisa, notas e configurações",
                this::showMoreOptions));
        setContentView(root);
        focusForTalkBack(root.getChildAt(2), "Continuar leitura em " + currentReference());
    }

    /** Confirma se a pessoa deseja trocar o livro ou retomar exatamente a posição salva. */
    private void confirmBookChoice() {
        showChoiceDialog("Seção Livros",
                "Você quer trocar de livro ou continuar no capítulo em que parou?",
                new String[]{"Continuar em " + currentReference(), "Trocar de livro"},
                which -> {
                    if (which == 0) showVerses(); else showBooks();
                });
    }

    /** Lista livros do testamento escolhido e oferece o menu de aplicações. */
    private void showBooks() {
        boolean newTestament = selectedBook.number > 39;
        LinearLayout root = screen("Livros", this::showMainMenu);
        root.addView(actionButton("Modo culto", "Abrir pesquisa rápida do Modo culto", this::showWorshipModeFromReading));
        root.addView(paragraph("Selecione a seção: Antigo ou Novo Testamento."));
        Spinner testament = new Spinner(this);
        testament.setContentDescription("Seção da Bíblia: Antigo ou Novo Testamento");
        testament.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Antigo Testamento", "Novo Testamento"}));
        testament.setSelection(newTestament ? 1 : 0);
        root.addView(testament);
        TextView feedback = paragraph("");
        root.addView(feedback);
        testament.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int count = position == 1 ? 27 : 39;
                String name = position == 1 ? "Novo Testamento" : "Antigo Testamento";
                feedback.setText(name + " selecionado. " + count + " livros. Ative Escolher livro.");
                feedback.announceForAccessibility(feedback.getText());
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        root.addView(actionButton("Escolher livro", "Abrir os livros do testamento selecionado",
                () -> populateBookScreen(testament.getSelectedItemPosition() == 1)));
        setContentView(root);
        focusForTalkBack(testament, "Escolha Antigo ou Novo Testamento.");
    }

    /** Recria a lista de livros sem alterar o padrão de foco da tela. */
    private void populateBookScreen(boolean newTestament) {
        LinearLayout root = screen("Livros — " +
                (newTestament ? "Novo Testamento" : "Antigo Testamento"), this::showBooks);
        root.addView(actionButton("Modo culto", "Abrir pesquisa rápida do Modo culto", this::showWorshipModeFromReading));
        List<Models.Book> books = bible.books(translationId, newTestament);
        root.addView(paragraph((newTestament ? "Novo Testamento" : "Antigo Testamento") +
                " selecionado. " + books.size() + " livros. Escolha o livro com cima e baixo e ative para abrir."));
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
        root.addView(actionButton("Trocar testamento", "Escolher Antigo ou Novo Testamento", this::showBooks));
        root.addView(paragraph("Toque duas vezes para abrir capítulos. Toque e segure para Aplicações."));
        setContentView(root);
        int index = Math.max(0, books.indexOf(selectedBook));
        list.setSelection(index);
        focusForTalkBack(list, "Escolha o livro.");
    }

    /** Mostra números de capítulo do livro atual. */
    private void showChapters() {
        LinearLayout root = screen("Capítulos de " + selectedBook.name, this::showMainMenu);
        root.addView(actionButton("Modo culto", "Abrir pesquisa rápida do Modo culto", this::showWorshipModeFromReading));
        root.addView(actionButton("Trocar Antigo ou Novo Testamento",
                "Voltar à escolha entre Antigo e Novo Testamento", this::showBooks));
        LinearLayout navigation = horizontalRow();
        navigation.addView(actionButton("Capítulo anterior",
                previousChapterDescription(), () -> changeReadingChapter(-1)), weighted());
        navigation.addView(actionButton("Próximo capítulo",
                nextChapterDescription(), () -> changeReadingChapter(1)), weighted());
        root.addView(navigation);
        int total = bible.chapterCount(translationId, selectedBook.code);
        List<String> chapters = new ArrayList<>();
        for (int number = 1; number <= total; number++) chapters.add("Capítulo " + number);
        ListView list = listView(chapters);
        list.setContentDescription("Capítulos de " + selectedBook.name);
        list.setOnItemClickListener((parent, view, position, id) -> {
            selectedChapter = position + 1;
            selectVerse("1");
            savePosition();
            askHowToOpenChapter();
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
        focusForTalkBack(list, "Escolha o capítulo de " + selectedBook.name + ".");
    }

    /** Lista os textos do capítulo e abre suas ações com uma única ativação. */
    private void showVerses() {
        LinearLayout root = screen("Versículos de " + currentChapterReference(), this::showMainMenu);
        chapterSwipeEnabled = true;
        root.addView(actionButton("Modo culto", "Abrir pesquisa rápida do Modo culto", this::showWorshipModeFromReading));
        LinearLayout navigation = horizontalRow();
        navigation.addView(actionButton("Capítulo anterior",
                previousChapterDescription(), () -> changeReadingChapter(-1)), weighted());
        navigation.addView(actionButton("Próximo capítulo",
                nextChapterDescription(), () -> changeReadingChapter(1)), weighted());
        root.addView(navigation);
        List<Models.Verse> verses = bible.chapter(translationId, selectedBook.code, selectedChapter);
        ListView list = listView(verses);
        list.setContentDescription("Versículos de " + currentChapterReference() +
                ". Deslize com dois dedos para a esquerda para voltar o capítulo ou para a direita para avançar.");
        list.setOnItemClickListener((parent, view, position, id) -> {
            selectedVerse = verses.get(position);
            savePosition();
            showVerseActions();
        });
        root.addView(list, fill());
        root.addView(actionButton("Aplicações do versículo atual",
                "Abrir ações de " + currentReference(), this::showVerseActions));
        root.addView(paragraph("Ative um versículo para abrir suas ações. Também há botões e gesto de dois dedos para trocar de capítulo."));
        setContentView(root);
        list.setSelection(verseIndex(verses, selectedVerse == null ? "1" : selectedVerse.number));
        focusForTalkBack(list, "Versículos de " + currentChapterReference() + ".");
    }

    /** Reúne recursos secundários numa lista nativa navegável. */
    private void showMoreOptions() {
        LinearLayout root = screen("Mais opções", this::showMainMenu);
        String[] options = {
                "Traduções", "Comparar traduções", "Ir para uma referência", "Pesquisar na Bíblia", "Estudos — Teólogo de IA",
                "Fazer devocional", "Devocionais novos do site", "Rádio Gospel", "Anotações por dia",
                "Harpa Cristã", "Quiz bíblico", "Status do quiz", "Modo culto", "Atualizações",
                "Configurações", "Ideias, sugestões e suporte", "Licenças, leis e justificativa", "Ajuda"
        };
        ListView list = listView(Arrays.asList(options));
        list.setContentDescription("Mais opções");
        list.setOnItemClickListener((parent, view, position, id) -> {
            switch (position) {
                case 0: showTranslations(); break;
                case 1: showComparison(); break;
                case 2: showReference(); break;
                case 3: showSearch(); break;
                case 4: showStudies(); break;
                case 5: showDevotional(); break;
                case 6: showOnlineDevotionals(); break;
                case 7: showGospelRadio(); break;
                case 8: showNotes(); break;
                case 9: showHymnal(); break;
                case 10: showQuiz(); break;
                case 11: showQuizStatus(); break;
                case 12: showWorshipMode(); break;
                case 13: showUpdates(); break;
                case 14: showSettings(); break;
                case 15: openIdeasEmail(); break;
                case 16: showLegal(); break;
                default: showHelp();
            }
        });
        root.addView(list, fill());
        setContentView(root);
        focusForTalkBack(list, "Mais opções.");
    }

    /** Lista categorias prontas e a pergunta livre do Teólogo de IA. */
    private void showStudies() {
        LinearLayout root = screen("Estudos — Teólogo de IA", this::showMoreOptions);
        root.addView(paragraph("Escolha uma categoria. A pergunta sugerida poderá ser editada antes do envio. " +
                "A resposta usa a passagem atual e exige sua chave do Google Gemini."));
        root.addView(actionButton("Resumir o capítulo atual",
                "Gerar resumo de " + currentChapterReference(), () ->
                        runAi("Resumo de " + currentChapterReference(),
                                "Resuma o capítulo em no máximo 320 palavras, destaque sua mensagem " +
                                        "central e conclua completamente.",
                                () -> bible.chapterText(
                                        translationId, selectedBook.code, selectedChapter),
                                this::showStudies)));
        root.addView(actionButton("Resumir o livro atual",
                "Gerar resumo de " + selectedBook.name, () ->
                        runAi("Resumo de " + selectedBook.name,
                                "Resuma o livro em no máximo 550 palavras, apresente estrutura, temas " +
                                        "principais e conclusão completa.",
                                () -> bible.bookText(translationId, selectedBook.code),
                                this::showStudies)));
        List<AiStudyCatalog.Topic> topics = AiStudyCatalog.TOPICS;
        ListView list = listView(topics);
        list.setContentDescription("Categorias de estudo com inteligência artificial");
        list.setOnItemClickListener((parent, view, position, id) ->
                showAiStudyComposer(topics.get(position), this::showStudies));
        root.addView(list, fill());
        setContentView(root);
        focusForTalkBack(list, "Categorias de estudo.");
    }

    /** Abre um campo editável com a pergunta sugerida e o texto bíblico usado como contexto. */
    private void showAiStudyComposer(AiStudyCatalog.Topic topic, Runnable back) {
        LinearLayout root = screen("Teólogo de IA — " + topic.label, back);
        root.addView(paragraph("Passagem atual: " + currentReference() + ". " + selectedVerse.text));
        EditText question = editText("Pergunta para o Teólogo de IA", true);
        question.setMinLines(3);
        question.setText(topic.suggestedQuestion);
        root.addView(question, fill());
        root.addView(actionButton("Perguntar à IA", "Enviar pergunta sobre " + currentReference(), () -> {
            try {
                String instruction = AiStudyCatalog.instruction(
                        topic, question.getText().toString(), currentReference());
                runAi(topic.label + " — " + currentReference(), instruction,
                        () -> currentReference() + " — " + selectedVerse.text +
                                "\n\nContexto completo do capítulo:\n" +
                                bible.chapterText(translationId, selectedBook.code, selectedChapter),
                        () -> showAiStudyComposer(topic, back));
            } catch (IllegalArgumentException error) {
                question.setError(error.getMessage());
                question.requestFocus();
            }
        }));
        setContentView(root);
        focusForTalkBack(question, "Edite ou escreva sua pergunta.");
        if (!topic.suggestedQuestion.isEmpty()) question.selectAll();
    }

    /** Permite trocar a tradução e recarrega a mesma referência quando possível. */
    private void showTranslations() {
        LinearLayout root = screen("Traduções", this::showMoreOptions);
        List<Models.Translation> translations = bible.translations();
        root.addView(paragraph("Escolha a tradução na caixa de combinação e ative Aplicar tradução."));
        Spinner choices = new Spinner(this);
        choices.setContentDescription("Caixa de combinação de traduções bíblicas");
        choices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, translations));
        int selected = 0;
        for (int index = 0; index < translations.size(); index++) {
            if (translations.get(index).id.equals(translationId)) selected = index;
        }
        choices.setSelection(selected);
        root.addView(choices);
        TextView feedback = paragraph("Tradução atual: " + translations.get(selected).name + ".");
        root.addView(feedback);
        choices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                feedback.setText("Tradução escolhida: " + translations.get(position).name + ". Ative Aplicar tradução.");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        root.addView(actionButton("Aplicar tradução", "Usar a tradução escolhida como padrão", () -> {
            Models.Translation chosen = translations.get(choices.getSelectedItemPosition());
            String oldCode = selectedBook.code;
            translationId = chosen.id;
            selectedBook = bible.findBook(translationId, oldCode);
            if (selectedBook == null) selectedBook = bible.books(translationId, false).get(0);
            selectedChapter = Math.min(selectedChapter,
                    bible.chapterCount(translationId, selectedBook.code));
            selectVerse(selectedVerse == null ? "1" : selectedVerse.number);
            savePosition();
            Toast.makeText(this, "Tradução selecionada: " + chosen.name, Toast.LENGTH_LONG).show();
            feedback.setText("Tradução padrão selecionada: " + chosen.name + ".");
            feedback.announceForAccessibility(feedback.getText());
        }));
        setContentView(root);
        focusForTalkBack(choices, "Escolha a tradução padrão.");
    }

    /** Escolhe origem e destino e compara o versículo atual em seções separadas. */
    private void showComparison() {
        LinearLayout root = screen("Comparar traduções", this::showMoreOptions);
        List<Models.Translation> translations = bible.translations();
        root.addView(paragraph("Referência atual: " + currentReference() + ". Escolha a tradução de origem e a tradução para comparar."));
        Spinner source = new Spinner(this);
        source.setContentDescription("Tradução de origem");
        source.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, translations));
        Spinner target = new Spinner(this);
        target.setContentDescription("Tradução para comparar");
        target.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, translations));
        int sourceIndex = 0;
        for (int index = 0; index < translations.size(); index++) {
            if (translations.get(index).id.equals(translationId)) sourceIndex = index;
        }
        source.setSelection(sourceIndex);
        target.setSelection(translations.size() > 1 ? (sourceIndex + 1) % translations.size() : sourceIndex);
        root.addView(paragraph("Tradução de origem"));
        root.addView(source);
        root.addView(paragraph("Tradução para comparar"));
        root.addView(target);
        root.addView(actionButton("Comparar versículo", "Mostrar as duas traduções de " + currentReference(), () ->
                showComparisonResult(translations.get(source.getSelectedItemPosition()),
                        translations.get(target.getSelectedItemPosition()))));
        setContentView(root);
        focusForTalkBack(source, "Escolha a tradução de origem.");
    }

    /** Mostra primeiro a origem e depois o destino, permitindo inverter as duas. */
    private void showComparisonResult(Models.Translation source, Models.Translation target) {
        LinearLayout root = screen("Comparação de " + currentReference(), this::showComparison);
        String sourceText = verseText(source.id, selectedVerse.number);
        String targetText = verseText(target.id, selectedVerse.number);
        root.addView(heading("Tradução de origem — " + source.name));
        TextView sourceView = paragraph(currentReference() + ". " + sourceText);
        sourceView.setFocusable(true);
        sourceView.setContentDescription("Tradução de origem, " + source.name + ". " +
                currentReference() + ". " + sourceText);
        root.addView(sourceView);
        root.addView(heading("Tradução para comparar — " + target.name));
        TextView targetView = paragraph(currentReference() + ". " + targetText);
        targetView.setFocusable(true);
        targetView.setContentDescription("Tradução para comparar, " + target.name + ". " +
                currentReference() + ". " + targetText);
        root.addView(targetView);
        root.addView(actionButton("Trocar origem e destino", "Inverter as duas traduções",
                () -> showComparisonResult(target, source)));
        root.addView(actionButton("Escolher outras traduções", "Voltar às caixas de combinação", this::showComparison));
        setContentView(root);
        focusForTalkBack(sourceView, "Tradução de origem, " + source.name + ".");
    }

    private String verseText(String chosenTranslation, String verseNumber) {
        List<Models.Verse> verses = bible.passage(chosenTranslation, selectedBook.code,
                selectedChapter, verseNumber, verseNumber);
        return verses.isEmpty() ? "Texto indisponível nesta tradução." : verses.get(0).text;
    }

    /** Navega para uma referência digitada em português. */
    private void showReference() {
        LinearLayout root = screen("Ir para uma referência", this::showMoreOptions);
        EditText input = editText("Referência, exemplo João 3:16", false);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        root.addView(input);
        Button go = actionButton("Ir para referência", "Abrir a referência digitada", () -> {
            if (loadReference(input.getText().toString())) showVerses();
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            go.performClick();
            return true;
        });
        root.addView(go);
        setContentView(root);
        focusForTalkBack(input, "Digite a referência bíblica.");
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
        focusForTalkBack(input, "Digite o texto para pesquisar.");
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
        focusForTalkBack(list, results.size() + " resultados encontrados.");
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
        focusForTalkBack(reference, "Referência bíblica do devocional.");
    }

    /** Exibe todo o histórico do site e mantém uma cópia para leitura offline. */
    private void showOnlineDevotionals() {
        onlineDevotionals = DevotionalFeed.loadCache(this);
        LinearLayout root = screen("Devocionais do site", this::showMoreOptions);
        onlineDevotionalStatus = paragraph("Carregando a cópia dos devocionais.");
        root.addView(onlineDevotionalStatus);
        root.addView(actionButton("Procurar novos devocionais",
                "Atualizar agora os devocionais publicados no site", () -> refreshOnlineDevotionals(true)));
        onlineDevotionalList = listView(new ArrayList<String>());
        onlineDevotionalList.setContentDescription(
                "Devocionais publicados. Toque duas vezes para abrir o texto.");
        onlineDevotionalAdapter = (ArrayAdapter<String>) onlineDevotionalList.getAdapter();
        onlineDevotionalList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= onlineDevotionals.size()) return;
            DevotionalFeed.Item item = onlineDevotionals.get(position);
            showTextScreen(item.title, "Publicado em " + item.dateLabel() + "\n\n" + item.content,
                    this::showOnlineDevotionals);
        });
        root.addView(onlineDevotionalList, fill());
        setContentView(root);
        populateOnlineDevotionals();
        focusForTalkBack(onlineDevotionalList, "Devocionais publicados.");
        refreshOnlineDevotionals(false);
    }

    /** Atualiza rótulos sem recriar a tela nem perder a rota de foco. */
    private void populateOnlineDevotionals() {
        if (onlineDevotionalAdapter == null) return;
        onlineDevotionalAdapter.clear();
        for (DevotionalFeed.Item item : onlineDevotionals) onlineDevotionalAdapter.add(item.toString());
        if (onlineDevotionals.isEmpty()) {
            onlineDevotionalAdapter.add("Nenhum devocional carregado. Use Procurar novos devocionais.");
            onlineDevotionalStatus.setText(R.string.devotionals_empty_status);
        } else {
            onlineDevotionalStatus.setText(getResources().getQuantityString(
                    R.plurals.devotionals_available, onlineDevotionals.size(), onlineDevotionals.size()));
        }
        onlineDevotionalAdapter.notifyDataSetChanged();
    }

    /** Consulta em segundo plano; uma falha nunca remove o histórico já baixado. */
    private void refreshOnlineDevotionals(boolean manual) {
        if (onlineDevotionalStatus != null) onlineDevotionalStatus.setText(R.string.devotionals_loading);
        new Thread(() -> {
            try {
                List<DevotionalFeed.Item> posts = DevotionalFeed.fetch(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    onlineDevotionals = posts;
                    if (!posts.isEmpty()) {
                        preferences.edit().putString("devotionals_latest_id", posts.get(0).id)
                                .apply();
                    }
                    populateOnlineDevotionals();
                    if (manual) toast("Devocionais atualizados.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    populateOnlineDevotionals();
                    if (manual) toast("Não foi possível consultar o site. A cópia local foi preservada.");
                });
            }
        }, "devotional-screen-refresh").start();
    }

    /** Mostra o catálogo público e controles nativos de reprodução contínua. */
    private void showGospelRadio() {
        radioTracks = GospelRadioFeed.loadCache(this);
        LinearLayout root = screen("Rádio Gospel", this::showMoreOptions);
        radioStatus = paragraph("Escolha um louvor. A lista fica guardada, mas a reprodução precisa de internet.");
        radioStatus.setContentDescription("Estado da Rádio Gospel");
        root.addView(radioStatus);
        root.addView(actionButton("Atualizar lista de louvores",
                "Buscar os louvores publicados no site", () -> refreshGospelRadio(true)));
        radioList = listView(new ArrayList<String>());
        radioList.setContentDescription("Louvores da Rádio Gospel. Toque duas vezes para reproduzir.");
        radioAdapter = (ArrayAdapter<String>) radioList.getAdapter();
        radioList.setOnItemClickListener((parent, view, position, id) -> playRadio(position));
        root.addView(radioList, fill());
        LinearLayout controls = horizontalRow();
        controls.addView(actionButton("Pausar ou continuar", "Pausar ou continuar o louvor",
                this::toggleRadioPause), weighted());
        controls.addView(actionButton("Parar", "Parar a Rádio Gospel", this::stopRadio), weighted());
        root.addView(controls);
        LinearLayout navigation = horizontalRow();
        navigation.addView(actionButton("Anterior", "Tocar o louvor anterior",
                () -> changeRadioTrack(-1)), weighted());
        navigation.addView(actionButton("Próximo", "Tocar o próximo louvor",
                () -> changeRadioTrack(1)), weighted());
        root.addView(navigation);
        setContentView(root);
        populateGospelRadio();
        focusForTalkBack(radioList, "Louvores da Rádio Gospel.");
        refreshGospelRadio(false);
    }

    private void populateGospelRadio() {
        if (radioAdapter == null) return;
        radioAdapter.clear();
        for (GospelRadioFeed.Track track : radioTracks) radioAdapter.add(track.toString());
        if (radioTracks.isEmpty()) {
            radioAdapter.add("Nenhum louvor carregado. Use Atualizar lista de louvores.");
            radioStatus.setText("Ainda não há uma lista local da Rádio Gospel.");
        } else if (radioIndex < 0) {
            radioStatus.setText(radioTracks.size() + " louvores disponíveis. Escolha um para começar.");
        }
        radioAdapter.notifyDataSetChanged();
        if (radioIndex >= 0 && radioIndex < radioTracks.size()) radioList.setSelection(radioIndex);
    }

    private void refreshGospelRadio(boolean manual) {
        if (manual && radioStatus != null) radioStatus.setText("Atualizando a Rádio Gospel, aguarde.");
        new Thread(() -> {
            try {
                List<GospelRadioFeed.Track> tracks = GospelRadioFeed.fetch(this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    radioTracks = tracks;
                    populateGospelRadio();
                    if (manual) toast("Lista da Rádio Gospel atualizada.");
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    populateGospelRadio();
                    if (manual) toast("Não foi possível atualizar a Rádio Gospel. Tente novamente.");
                });
            }
        }, "gospel-radio-refresh").start();
    }

    private void playRadio(int index) {
        if (index < 0 || index >= radioTracks.size()) return;
        stopAndReleaseRadio();
        radioIndex = index;
        GospelRadioFeed.Track track = radioTracks.get(index);
        radioPreparing = true;
        if (radioStatus != null) radioStatus.setText("Carregando: " + track + ".");
        try {
            radioPlayer = new MediaPlayer();
            radioPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            radioPlayer.setDataSource(track.audioUrl);
            radioPlayer.setOnPreparedListener(player -> runOnUiThread(() -> {
                radioPreparing = false;
                player.start();
                if (radioStatus != null) radioStatus.setText("Tocando: " + track + ".");
            }));
            radioPlayer.setOnCompletionListener(player -> runOnUiThread(() -> changeRadioTrack(1)));
            radioPlayer.setOnErrorListener((player, what, extra) -> {
                runOnUiThread(() -> {
                    radioPreparing = false;
                    if (radioStatus != null) radioStatus.setText("Não foi possível tocar este louvor.");
                    toast("Não foi possível tocar o louvor. Confira a conexão e tente novamente.");
                });
                return true;
            });
            radioPlayer.prepareAsync();
            if (radioList != null) radioList.setSelection(index);
        } catch (Exception ignored) {
            radioPreparing = false;
            stopAndReleaseRadio();
            if (radioStatus != null) radioStatus.setText("Não foi possível tocar este louvor.");
            toast("Não foi possível tocar o louvor. Confira a conexão e tente novamente.");
        }
    }

    private void toggleRadioPause() {
        if (radioPlayer == null) {
            int selected = radioIndex >= 0 ? radioIndex : 0;
            if (selected >= radioTracks.size()) selected = 0;
            playRadio(selected);
            return;
        }
        if (radioPreparing) return;
        try {
            GospelRadioFeed.Track track = radioTracks.get(radioIndex);
            if (radioPlayer.isPlaying()) {
                radioPlayer.pause();
                if (radioStatus != null) radioStatus.setText("Pausado: " + track + ".");
            } else {
                radioPlayer.start();
                if (radioStatus != null) radioStatus.setText("Tocando: " + track + ".");
            }
        } catch (Exception ignored) {
            toast("Não foi possível controlar a reprodução agora.");
        }
    }

    private void stopRadio() {
        stopAndReleaseRadio();
        if (radioStatus != null && radioIndex >= 0 && radioIndex < radioTracks.size()) {
            radioStatus.setText("Parado: " + radioTracks.get(radioIndex) + ".");
        }
    }

    private void changeRadioTrack(int direction) {
        if (radioTracks.isEmpty()) {
            toast("Não há louvores disponíveis para tocar agora.");
            return;
        }
        int start = radioIndex >= 0 ? radioIndex : 0;
        playRadio((start + direction + radioTracks.size()) % radioTracks.size());
    }

    private void stopAndReleaseRadio() {
        if (radioPlayer == null) return;
        try { radioPlayer.stop(); } catch (Exception ignored) {}
        try { radioPlayer.reset(); } catch (Exception ignored) {}
        try { radioPlayer.release(); } catch (Exception ignored) {}
        radioPlayer = null;
        radioPreparing = false;
    }

    /** Abre o aplicativo de e-mail já endereçado para sugestões e problemas. */
    private void openIdeasEmail() {
        Uri uri = Uri.parse("mailto:diego@audiogames.com.br").buildUpon()
                .appendQueryParameter("subject", "Ideia ou problema na Bíblia Acessível")
                .appendQueryParameter("body", "Olá, Diego!\n\nQuero enviar a seguinte ideia ou relatar este problema:\n\n")
                .build();
        try {
            startActivity(new Intent(Intent.ACTION_SENDTO, uri));
        } catch (Exception ignored) {
            toast("Não consegui abrir o e-mail. Escreva para diego@audiogames.com.br.");
        }
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
        focusForTalkBack(list, "Anotações organizadas por dia e título.");
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
                "Alternar notificações de novos devocionais. " +
                        (preferences.getBoolean("devotionals_notify", true) ? "Ativadas" : "Desativadas"),
                "Abrir configurações de notificações do Android",
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
                case 7: toggleDevotionalNotifications(); break;
                case 8: openNotificationSettings(); break;
                default: startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(list, fill());
        setContentView(root);
        focusForTalkBack(list, "Configurações.");
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
        showTextScreen("Ajuda", "NAVEGAÇÃO\n\nNa tela principal, Continuar de onde parou abre " +
                "o capítulo completo já posicionado no último versículo. Use também Livros, Capítulos, Versículos " +
                "ou Mais opções. O texto bíblico é lido diretamente na lista de Versículos. Com o TalkBack ligado, deslize para a direita " +
                "para ouvir a próxima opção ou para a esquerda para ouvir a opção anterior. Toque duas vezes " +
                "para abrir. O gesto Voltar do Android retorna à tela anterior. Ao ativar um versículo, " +
                "o menu de ações abre sem precisar segurar.\n\nESTUDOS COM IA\n\nMais opções, Estudos " +
                "oferece exegese, hermenêutica, contexto histórico, teologia bíblica, comparação de " +
                "interpretações, aplicação e pergunta livre. A pergunta sempre pode ser editada antes do " +
                "envio. O Teólogo de IA é um assistente de estudo e não substitui orientação pastoral.\n\nVOZ\n\nAtivar a opção " +
                "Ouvir ou repetir no menu do versículo usa a " +
                "voz escolhida. Se a voz estiver desligada, o TalkBack anuncia novamente o texto.\n\n" +
                "HARPA, QUIZ E MODO CULTO\n\nA Harpa Cristã possui 640 hinos pesquisáveis e funciona " +
                "offline. O quiz possui 298 perguntas e cada uma conta somente uma vez. Recomeçar quiz do zero, " +
                "em Status do quiz, é a única forma de liberar tudo novamente. Traduções e testamentos usam caixas de combinação com feedback. " +
                "Comparar traduções separa origem e destino e permite inverter as duas. No Modo " +
                "culto, digite uma passagem e confirme: o texto aparece imediatamente, sem precisar tocar " +
                "em Ler versículo. A Rádio Gospel toca pela internet os louvores publicados no site. " +
                "Ideias, sugestões e suporte abre um e-mail para Diego.\n\nATUALIZAÇÕES\n\nA versão Android consulta somente releases android-v " +
                "e baixa somente BibliaAcessivel-Android.apk com seu SHA-256. O Windows usa outro pacote. " +
                "A consulta automática pode ser desligada nas Configurações.\n\nDEVOCIONAIS DO SITE\n\n" +
                "A seção Devocionais novos do site mostra todos os textos publicados por Diego e guarda a última " +
                "cópia para leitura sem internet. O Android verifica periodicamente mesmo com o aplicativo " +
                "fechado e avisa na barra de notificações quando encontra uma publicação realmente nova. " +
                "A notificação pode ser desligada nas Configurações.\n\n" +
                "PRIVACIDADE\n\nNotas e marcadores ficam somente no aparelho. A chave Gemini é protegida " +
                "pelo Android Keystore. A IA é opcional e exige internet.", this::showMoreOptions);
    }

    /** Alterna o trabalho diário e solicita permissão apenas quando necessário. */
    private void toggleDevotionalNotifications() {
        boolean enabled = !preferences.getBoolean("devotionals_notify", true);
        preferences.edit().putBoolean("devotionals_notify", enabled).apply();
        DevotionalNotifications.schedule(this);
        if (enabled) {
            preferences.edit().remove("devotionals_permission_prompt_version").apply();
            ensureDevotionalNotificationPermission();
        }
        toast("Notificações de devocionais " + (enabled ? "ativadas." : "desativadas."));
        showSettings();
    }

    /** Explica e pede a permissão do Android 13 ou superior uma única vez. */
    private void ensureDevotionalNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || !preferences.getBoolean("devotionals_notify", true) ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
                BuildConfig.VERSION_NAME.equals(preferences.getString(
                        "devotionals_permission_prompt_version", ""))) return;
        new AlertDialog.Builder(this).setTitle("Avisos de novos devocionais")
                .setMessage("Permita as notificações para receber o aviso na barra do Android mesmo quando a Bíblia estiver fechada. A Bíblia continuará funcionando se você não permitir.")
                .setPositiveButton("Continuar", (dialog, which) -> {
                    preferences.edit().putString("devotionals_permission_prompt_version",
                            BuildConfig.VERSION_NAME).apply();
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4002);
                })
                .setNegativeButton("Agora não", (dialog, which) ->
                        preferences.edit().putString("devotionals_permission_prompt_version",
                                BuildConfig.VERSION_NAME).apply())
                .show();
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            toast("Não foi possível abrir as configurações de notificações.");
        }
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
        if (query.isEmpty()) focusForTalkBack(input, "Pesquisar hino.");
        else if (!results.isEmpty()) focusForTalkBack(list, results.size() + " hinos encontrados.");
        else focusForTalkBack(input, "Nenhum hino encontrado. Digite outra pesquisa.");
    }

    /** Escolhe filtros do quiz em diálogos nativos e abre a lista resultante. */
    private void showQuiz() {
        showChoiceDialog("Dificuldade do quiz", "Escolha a dificuldade.",
                QuizCatalog.DIFFICULTIES, difficultyIndex ->
                        showChoiceDialog("Categoria do quiz", "Escolha a categoria.",
                                quizCatalog.categories, categoryIndex ->
                                        showQuizQuestions(QuizCatalog.DIFFICULTIES[difficultyIndex],
                                                quizCatalog.categories[categoryIndex])));
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
        focusForTalkBack(list, "Perguntas do quiz bíblico.");
    }

    /** Informa imediatamente acerto ou erro, explicação e referência bíblica. */
    private void answerQuiz(QuizCatalog.Question question, String difficulty, String category) {
        if (userData.hasAnsweredQuizQuestion(question.id)) {
            new AlertDialog.Builder(this).setTitle("Pergunta já respondida")
                    .setMessage("Esta pergunta já foi respondida e seu primeiro resultado permanece no status do quiz.")
                    .setPositiveButton("Voltar", (dialog, which) ->
                            showQuizQuestions(difficulty, category)).show();
            return;
        }
        showChoiceDialog(question.text, "Escolha uma resposta.", question.answers, chosen -> {
                    boolean correct = chosen == question.correct;
                    if (!userData.recordQuizAttempt(question, correct)) {
                        new AlertDialog.Builder(this).setTitle("Pergunta já respondida")
                                .setMessage("O resultado anterior foi mantido.")
                                .setPositiveButton("Voltar", (ignored, which) ->
                                        showQuizQuestions(difficulty, category)).show();
                        return;
                    }
                    String result = (correct ? "Resposta correta. " :
                            "Resposta incorreta. A resposta correta é " +
                                    question.answers[question.correct] + ". ") +
                            question.explanation + " Referência: " + question.reference + ".";
                    new AlertDialog.Builder(this).setTitle("Resultado do quiz")
                            .setMessage(result).setPositiveButton("Continuar",
                                    (resultDialog, which) -> showQuizQuestions(difficulty, category))
                            .setNegativeButton("Fechar", null).show();
                });
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
        root.addView(actionButton("Recomeçar quiz do zero",
                "Apagar todos os acertos e erros e liberar novamente as perguntas", () ->
                new AlertDialog.Builder(this).setTitle("Recomeçar quiz do zero")
                        .setMessage("Apagar todos os resultados do quiz? Depois disso, cada pergunta poderá ser respondida novamente uma vez.")
                        .setPositiveButton("Apagar e recomeçar", (dialog, which) -> {
                            userData.resetQuiz();
                            Toast.makeText(this, "Quiz restaurado. Todas as perguntas foram liberadas.", Toast.LENGTH_LONG).show();
                            showQuizStatus();
                        }).setNegativeButton("Cancelar", null).show()));
        setContentView(root);
        focusForTalkBack(list, "Status do quiz bíblico.");
    }

    /** Cria a busca rápida do culto com o campo focado e o resultado na mesma tela. */
    private void showWorshipMode() {
        showWorshipMode(this::showMoreOptions);
    }

    private void showWorshipModeFromReading() {
        showWorshipMode(this::showVerses);
    }

    private void showWorshipMode(Runnable returnAction) {
        LinearLayout root = screen("Modo culto", returnAction);
        root.addView(paragraph("Pesquise por texto ou por voz. Diga somente Pedro para escolher o livro, " +
                "ou uma passagem como Segunda Pedro 2 5 15 para abrir do versículo 5 ao 15."));
        worshipInput = editText("Pesquisar passagem por texto", false);
        worshipInput.setSingleLine(true);
        worshipInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        root.addView(worshipInput);
        Button open = actionButton("Pesquisar por texto", "Interpretar e mostrar a passagem digitada",
                this::openWorshipPassage);
        root.addView(open);
        root.addView(actionButton("Pesquisar por voz",
                "Dizer o livro, capítulo e versículos usando o reconhecimento de voz do Android",
                this::startWorshipVoiceSearch));
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
        focusForTalkBack(worshipInput, "Pesquisar passagem por texto ou voz.");
    }

    /** Resolve a referência e move o foco direto para o texto, sem menu intermediário. */
    private void openWorshipPassage() {
        try {
            BibleReference reference = BibleReference.parse(worshipInput.getText().toString());
            List<Models.Book> matches = bible.matchingBooks(translationId, reference.book);
            if (matches.isEmpty()) throw new IllegalArgumentException("Livro não encontrado.");
            if (matches.size() > 1) {
                showWorshipBookChoices(reference, matches);
                return;
            }
            openWorshipPassageForBook(reference, matches.get(0));
        } catch (IllegalArgumentException error) {
            worshipInput.setError(error.getMessage());
            worshipInput.requestFocus();
        }
    }

    /** Mostra cada livro possível em uma lista focada e anunciada pelo TalkBack. */
    private void showWorshipBookChoices(BibleReference reference, List<Models.Book> matches) {
        String[] choices = new String[matches.size()];
        for (int index = 0; index < matches.size(); index++) choices[index] = matches.get(index).name;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Escolha o livro. " + matches.size() + " opções")
                .setMessage("A busca por “" + reference.book + "” pode indicar mais de um livro.")
                .setItems(choices, (choiceDialog, which) -> {
                    Models.Book chosen = matches.get(which);
                    worshipInput.setText(formatWorshipReference(chosen, reference));
                    openWorshipPassageForBook(reference, chosen);
                })
                .setNegativeButton("Cancelar", null)
                .create();
        showDialogWithChoiceFocus(dialog, matches.size() + " livros encontrados para " +
                reference.book + ". Primeira opção: " + choices[0] +
                ". Deslize e toque duas vezes para escolher.");
    }

    /** Valida capítulo e intervalo depois que o livro exato foi escolhido. */
    private void openWorshipPassageForBook(BibleReference reference, Models.Book book) {
        try {
            if (reference.chapter > bible.chapterCount(translationId, book.code)) {
                throw new IllegalArgumentException(book.name + " não possui o capítulo " + reference.chapter + ".");
            }
            List<Models.Verse> wholeChapter = bible.chapter(translationId, book.code, reference.chapter);
            int lastVerse = wholeChapter.isEmpty() ? 0 :
                    BibleReference.number(wholeChapter.get(wholeChapter.size() - 1).number);
            if (reference.startVerse != null && BibleReference.number(reference.startVerse) > lastVerse) {
                throw new IllegalArgumentException(book.name + " " + reference.chapter +
                        " termina no versículo " + lastVerse + ".");
            }
            if (reference.endVerse != null && BibleReference.number(reference.endVerse) > lastVerse) {
                throw new IllegalArgumentException(book.name + " " + reference.chapter +
                        " termina no versículo " + lastVerse + ". Diminua o fim do intervalo.");
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
            String normalized = formatWorshipReference(book, reference);
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

    private String formatWorshipReference(Models.Book book, BibleReference reference) {
        return book.name + " " + reference.chapter +
                (reference.startVerse == null ? "" : ":" + reference.startVerse +
                        (reference.endVerse == null ? "" : "-" + reference.endVerse));
    }

    /** Abre o reconhecedor nativo; o texto reconhecido ainda passa pela mesma validação. */
    private void startWorshipVoiceSearch() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, WORSHIP_AUDIO_PERMISSION);
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga uma passagem bíblica");
        try {
            startActivityForResult(intent, WORSHIP_VOICE_SEARCH);
        } catch (Exception error) {
            toast("O reconhecimento de voz não está disponível neste aparelho.");
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
        focusForTalkBack(root.getChildAt(2), "Verificar atualizações.");
    }

    /** Mantém a IA como primeira ação do livro. */
    private void showBookActions() {
        showChoiceDialog("Menu do livro " + selectedBook.name, "Escolha uma ação.",
                new String[]{"Gerar resumo do livro com IA"}, which -> {
                    int words = 550;
                    runAi("Resumo de " + selectedBook.name,
                            "Resuma " + selectedBook.name + " em no máximo " + words +
                                    " palavras, em poucos parágrafos, e conclua completamente.",
                            () -> bible.bookText(translationId, selectedBook.code));
                });
    }

    /** Mantém a IA como primeira ação do capítulo. */
    private void showChapterActions() {
        showChoiceDialog("Menu do capítulo " + selectedChapter, "Escolha uma ação.",
                new String[]{"Gerar resumo do capítulo com IA"}, which ->
                        runAi("Resumo de " + currentChapterReference(),
                                "Resuma o capítulo em no máximo 320 palavras e conclua completamente.",
                                () -> bible.chapterText(translationId, selectedBook.code, selectedChapter)));
    }

    /** Oferece IA, estudos, anotação, cópia, marcador e voz para o texto atual. */
    private void showVerseActions() {
        if (selectedVerse == null) return;
        boolean marked = userData.isBookmarked(
                translationId, selectedBook.code, selectedChapter, selectedVerse.number);
        String[] actions = {
                "Gerar explicação do versículo com IA", "Teólogo de IA — exegese, hermenêutica e estudos",
                "Criar anotação", "Copiar texto",
                "Copiar referência e texto", marked ? "Remover marcador" : "Adicionar marcador",
                "Ouvir ou repetir pelo leitor de tela"
        };
        showChoiceDialog("Menu do versículo " + currentReference(), "Escolha uma ação.",
                actions, which -> {
                    switch (which) {
                        case 0:
                            runAi("Explicação de " + currentReference(),
                                    "Explique " + currentReference() + " em linguagem simples, em no máximo 280 palavras.",
                                    () -> selectedVerse.toString());
                            break;
                        case 1: showVerseAiStudies(); break;
                        case 2: createNote(); break;
                        case 3: copy(selectedVerse.text); break;
                        case 4: copy(currentReference() + " — " + selectedVerse.text); break;
                        case 5:
                            boolean nowMarked = userData.toggleBookmark(
                                    translationId, selectedBook.code, selectedChapter, selectedVerse.number);
                            toast(nowMarked ? "Marcador adicionado." : "Marcador removido.");
                            break;
                        default:
                            speakOrAnnounce(getWindow().getDecorView(), selectedVerse.toString());
                    }
                });
    }

    /** Mostra as categorias teológicas a partir do menu do versículo atual. */
    private void showVerseAiStudies() {
        List<AiStudyCatalog.Topic> topics = AiStudyCatalog.TOPICS;
        String[] labels = new String[topics.size()];
        for (int index = 0; index < topics.size(); index++) labels[index] = topics.get(index).label;
        showChoiceDialog("Teólogo de IA — " + currentReference(), "Escolha o tipo de estudo.",
                labels, which -> showAiStudyComposer(topics.get(which), this::showVerseActions));
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
        focusForTalkBack(title, "Título da anotação. Depois, preencha o texto da anotação.");
    }

    /** Executa a rede fora da thread visual e anuncia começo, sucesso ou falha. */
    private void runAi(String title, String instruction, Supplier<String> textSupplier) {
        runAi(title, instruction, textSupplier, this::showMainMenu);
    }

    /** Executa a IA e mantém uma rota de retorno adequada à tela que iniciou o estudo. */
    private void runAi(String title, String instruction, Supplier<String> textSupplier,
                       Runnable resultBack) {
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
                runOnUiThread(() -> showTextScreen(title, result, resultBack));
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
        AlertDialog keyDialog = new AlertDialog.Builder(this).setTitle("Chave do Google Gemini")
                .setView(input).setPositiveButton("Salvar", (dialog, which) -> {
                    try {
                        secureStore.saveApiKey(input.getText().toString());
                        toast("Chave salva com proteção do Android Keystore.");
                    } catch (Exception error) {
                        toast("Não foi possível proteger a chave.");
                    }
                }).setNegativeButton("Cancelar", null).create();
        keyDialog.setOnShowListener(ignored ->
                focusForTalkBack(input, "Cole ou digite a chave da API do Google Gemini."));
        keyDialog.show();
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
        AlertDialog modelDialog = new AlertDialog.Builder(this).setTitle("Modelo da inteligência artificial")
                .setSingleChoiceItems(AI_MODELS, checked, (choiceDialog, which) -> {
                    preferences.edit().putString("ai_model", AI_MODELS[which]).apply();
                    choiceDialog.dismiss();
                    toast("Modelo selecionado: " + AI_MODELS[which]);
                }).setNegativeButton("Cancelar", null).create();
        showDialogWithChoiceFocus(modelDialog, "Escolha o modelo da inteligência artificial.");
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
        showChoiceDialog("Voz do Android", "Escolha uma voz.", labels.toArray(new String[0]), which -> {
                    preferences.edit().putString("voice", values.get(which)).apply();
                    applyVoice();
                    toast("Voz selecionada: " + labels.get(which));
                });
    }

    /** Ajusta a velocidade em valores compreensíveis, sem controle deslizante inacessível. */
    private void chooseSpeechRate() {
        String[] labels = {"Lenta", "Normal", "Rápida"};
        float[] values = {0.75f, 1.0f, 1.35f};
        showChoiceDialog("Velocidade da voz", "Escolha a velocidade.", labels, which -> {
                    preferences.edit().putFloat("speech_rate", values[which]).apply();
                    if (ttsReady) tts.setSpeechRate(values[which]);
                    toast("Velocidade: " + labels[which]);
                });
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

    /** Oferece início imediato ou escolha de um versículo antes de abrir a leitura. */
    private void askHowToOpenChapter() {
        showChoiceDialog(currentChapterReference(),
                "Você quer começar no início ou escolher um versículo?",
                new String[]{"Começar no versículo 1", "Escolher versículo"}, which -> {
                            if (which == 0) {
                                selectVerse("1");
                                savePosition();
                                showVerses();
                            } else {
                                showVersePicker();
                            }
                        });
    }

    /** Lista os versículos do capítulo para que a leitura possa começar em qualquer ponto. */
    private void showVersePicker() {
        LinearLayout root = screen("Escolher versículo de " + currentChapterReference(), this::showChapters);
        root.addView(actionButton("Modo culto", "Abrir pesquisa rápida do Modo culto", this::showWorshipModeFromReading));
        List<Models.Verse> verses = bible.chapter(translationId, selectedBook.code, selectedChapter);
        ListView list = listView(verses);
        list.setContentDescription("Escolha o versículo inicial de " + currentChapterReference());
        list.setOnItemClickListener((parent, view, position, id) -> {
            selectedVerse = verses.get(position);
            savePosition();
            showVerses();
        });
        root.addView(list, fill());
        setContentView(root);
        list.setSelection(verseIndex(verses, selectedVerse == null ? "1" : selectedVerse.number));
        focusForTalkBack(list, "Escolha o versículo inicial.");
    }

    private String previousChapterDescription() {
        return selectedChapter > 1
                ? "Abrir capítulo anterior, " + selectedBook.name + " " + (selectedChapter - 1)
                : "Início do livro. Não há capítulo anterior";
    }

    private String nextChapterDescription() {
        int maximum = bible.chapterCount(translationId, selectedBook.code);
        return selectedChapter < maximum
                ? "Abrir próximo capítulo, " + selectedBook.name + " " + (selectedChapter + 1)
                : "Fim do livro. Não há capítulos seguintes";
    }

    /** Troca o capítulo, preserva a posição e anuncia claramente o destino ao TalkBack. */
    private void changeReadingChapter(int direction) {
        int maximum = bible.chapterCount(translationId, selectedBook.code);
        int target = selectedChapter + direction;
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
        showVerses();
        String message = (direction < 0 ? "Capítulo anterior. " : "Próximo capítulo. ") +
                selectedBook.name + " capítulo " + selectedChapter + ".";
        getWindow().getDecorView().announceForAccessibility(message);
    }

    /** Captura o gesto antes da lista, inclusive quando o TalkBack altera o fluxo de toque. */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (chapterSwipeEnabled && handleChapterSwipe(event)) return true;
        return super.dispatchTouchEvent(event);
    }

    /** Usa a posição dos dois dedos antes de um deles sair da tela. */
    private boolean handleChapterSwipe(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && touchExplorationIsEnabled()) {
            touchExplorationSwipeTracking = true;
            touchExplorationStartX = event.getX();
            touchExplorationStartY = event.getY();
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 2) {
            touchExplorationSwipeTracking = false;
            chapterSwipeStartX = averageFirstTwoPointers(event);
            chapterSwipeTracking = true;
            // Cancela o possível toque simples iniciado pela lista com o primeiro dedo.
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            return true;
        }
        if (!chapterSwipeTracking) {
            if (action == MotionEvent.ACTION_UP && touchExplorationSwipeTracking) {
                touchExplorationSwipeTracking = false;
                float horizontal = event.getX() - touchExplorationStartX;
                float vertical = event.getY() - touchExplorationStartY;
                if (Math.abs(horizontal) >= dp(64) && Math.abs(horizontal) > Math.abs(vertical) * 1.25f) {
                    int direction = horizontal < 0 ? -1 : 1;
                    getWindow().getDecorView().post(() -> changeReadingChapter(direction));
                    return true;
                }
            }
            if (action == MotionEvent.ACTION_CANCEL) touchExplorationSwipeTracking = false;
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) return true;
        if (action == MotionEvent.ACTION_POINTER_UP && event.getPointerCount() >= 2) {
            float distance = averageFirstTwoPointers(event) - chapterSwipeStartX;
            chapterSwipeTracking = false;
            if (Math.abs(distance) >= dp(64)) {
                int direction = distance < 0 ? -1 : 1;
                getWindow().getDecorView().post(() -> changeReadingChapter(direction));
            } else {
                toast("Gesto curto. Deslize os dois dedos um pouco mais para o lado.");
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            chapterSwipeTracking = false;
            return true;
        }
        return true;
    }

    private static float averageFirstTwoPointers(MotionEvent event) {
        return (event.getX(0) + event.getX(1)) / 2f;
    }

    private boolean touchExplorationIsEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        return manager != null && manager.isEnabled() && manager.isTouchExplorationEnabled();
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
        if (requestCode == WORSHIP_VOICE_SEARCH && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty() && worshipInput != null) {
                worshipInput.setText(results.get(0));
                worshipInput.announceForAccessibility("Reconhecido: " + results.get(0) + ". Pesquisando passagem.");
                openWorshipPassage();
            }
            return;
        }
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

    /** Continua a pesquisa por voz assim que a permissão solicitada for concedida. */
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WORSHIP_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startWorshipVoiceSearch();
            } else {
                toast("Sem permissão para o microfone. Você ainda pode pesquisar por texto.");
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
        focusForTalkBack(body, title + ". Texto disponível para leitura.");
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
        chapterSwipeEnabled = false;
        chapterSwipeTracking = false;
        touchExplorationSwipeTracking = false;
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

    /** Exibe uma escolha padronizada e entrega o foco à primeira opção após o diálogo abrir. */
    private void showChoiceDialog(String title, String message, String[] items, IntConsumer action) {
        if (items == null || items.length == 0) {
            toast("Não há opções disponíveis nesta parte do aplicativo.");
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title + ". " + message)
                .setItems(items, (choiceDialog, which) -> action.accept(which))
                .setNegativeButton("Cancelar", null)
                .create();
        showDialogWithChoiceFocus(dialog, title + ". " + message + " " + items.length +
                " opções. Primeira opção: " + items[0] + ".");
    }

    /** Aguarda a janela estar anexada antes de focar e anunciar sua lista ao TalkBack. */
    private void showDialogWithChoiceFocus(AlertDialog dialog, String announcement) {
        dialog.setOnShowListener(ignored -> {
            ListView choices = dialog.getListView();
            if (choices == null || choices.getAdapter() == null || choices.getAdapter().getCount() == 0) return;
            choices.setFocusable(true);
            choices.setSelection(0);
            choices.postDelayed(() -> {
                if (!dialog.isShowing()) return;
                choices.requestFocus();
                View firstChoice = choices.getChildAt(0);
                View accessibilityTarget = firstChoice == null ? choices : firstChoice;
                accessibilityTarget.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
                choices.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
                choices.announceForAccessibility(announcement);
            }, 80);
        });
        dialog.show();
    }

    /** Repete o foco depois do layout para evitar que o TalkBack permaneça na tela anterior. */
    private void focusForTalkBack(View view, String announcement) {
        if (view == null) return;
        view.setFocusable(true);
        view.requestFocus();
        view.postDelayed(() -> {
            if (!view.isShown()) return;
            view.requestFocus();
            View accessibilityTarget = view;
            if (view instanceof ListView && ((ListView) view).getChildCount() > 0) {
                accessibilityTarget = ((ListView) view).getChildAt(0);
            }
            accessibilityTarget.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
            view.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
            if (announcement != null && !announcement.isEmpty()) {
                view.announceForAccessibility(announcement);
            }
        }, 80);
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

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(DevotionalNotifications.OPEN_DEVOTIONALS, false) && bible != null) {
            showOnlineDevotionals();
        }
    }

    @Override protected void onDestroy() {
        stopAndReleaseRadio();
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
