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
    private static final String VOICE_OFF = "__off__";
    private static final String[] AI_MODELS = {
            "gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-2.5-pro"
    };

    private BibleRepository bible;
    private HymnalCatalog hymnal;
    private QuizCatalog quizCatalog;
    private UserDatabase userData;
    private SecureStore secureStore;
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
        CrashLog.install(this);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                        if (backAction != null) backAction.run();
                        else finish();
                    });
        }
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        userData = new UserDatabase(this);
        secureStore = new SecureStore(this);
        tts = createTts();
        showLoading();
        new Thread(() -> {
            try {
                BibleRepository repository = new BibleRepository(this);
                // Se a tela foi fechada durante a cópia do banco, o carregador fecha o próprio banco.
                synchronized (this) {
                    if (isDestroyed()) {
                        repository.close();
                        return;
                    }
                    bible = repository;
                }
                hymnal = HymnalCatalog.load(this);
                quizCatalog = QuizCatalog.load(this);
                restorePosition();
                runOnUiThread(() -> afterSplash(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (getIntent().getBooleanExtra(DevotionalNotifications.OPEN_DEVOTIONALS, false)) {
                        showOnlineDevotionals();
                    } else if (getIntent().getBooleanExtra(Reminders.OPEN_PLANS, false)) {
                        openActivePlan();
                    } else {
                        showMainMenu();
                    }
                    DevotionalNotifications.schedule(this);
                    Reminders.schedule(this);
                    getWindow().getDecorView().postDelayed(this::ensureDevotionalNotificationPermission, 1200);
                    showLastCrashReport();
                    deliverPendingAi();
                }));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showFatalError(error);
                });
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
    /** Momento em que a abertura apareceu; o menu espera um tempo mínimo para ela ser vista e ouvida. */
    private long splashShownAt;
    static final long SPLASH_MIN_MILLIS = 1800;

    /** Abertura: logo AGB e a frase ÁUDIO GAMES BRASIL, enquanto a Bíblia é preparada. */
    private void showLoading() {
        splashShownAt = System.currentTimeMillis();
        LinearLayout root = rootLayout();
        root.setBackground(new WallpaperDrawable(getResources(), 60));
        root.setGravity(Gravity.CENTER);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.logo_agb);
        logo.setAdjustViewBounds(true);
        logo.setContentDescription("Logo AGB: Bíblia aberta, cruz e bengala, símbolo de acessibilidade");
        int size = Math.min(dp(280), getResources().getDisplayMetrics().widthPixels - dp(48));
        root.addView(logo, new LinearLayout.LayoutParams(size, size));
        TextView brand = heading("ÁUDIO GAMES BRASIL");
        brand.setGravity(Gravity.CENTER);
        brand.setTextColor(getColor(R.color.agb_dourado));
        brand.setLetterSpacing(0.08f);
        root.addView(brand);
        TextView status = paragraph("Bíblia AGB. Preparando a leitura, aguarde.");
        status.setGravity(Gravity.CENTER);
        root.addView(status);
        setContentView(root);
        root.announceForAccessibility("Áudio Games Brasil. Bíblia AGB.");
    }

    /** Executa a entrada no aplicativo respeitando o tempo mínimo da abertura. */
    private void afterSplash(Runnable action) {
        long remaining = SPLASH_MIN_MILLIS - (System.currentTimeMillis() - splashShownAt);
        if (remaining <= 0) action.run();
        else getWindow().getDecorView().postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) action.run();
        }, remaining);
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
        root.addView(heading("Bíblia AGB (Audio Games Brasil)"));
        root.addView(paragraph("Seção Livros. Sua posição é " + currentReference() + "."));
        root.addView(actionButton("Abrir capítulo " + selectedChapter + " de " + selectedBook.name,
                "Continuar a leitura em " + currentReference(), this::showVerses));
        root.addView(actionButton("Trocar de livro", "Escolher outro livro, capítulo e versículo",
                this::confirmBookChoice));
        root.addView(actionButton("Mais opções", "Abrir traduções, pesquisa, notas e configurações",
                this::showMoreOptions));
        root.addView(actionButton("Apoie a Bíblia AGB com uma doação",
                "Copiar a chave Pix e doar o valor que quiser", this::showDonation));
        setContentView(root);
        focusForTalkBack(root.getChildAt(2), "Continuar leitura em " + currentReference());
    }

    static final String PIX_KEY = "diego@audiogames.com.br";

    /** Mostra a chave Pix para doações de qualquer valor. */
    private void showDonation() {
        LinearLayout root = screen("Apoie a Bíblia AGB", this::showMainMenu);
        root.addView(paragraph("A Bíblia AGB é gratuita, sem anúncios e feita para ser acessível. " +
                "Se ela abençoa a sua vida, você pode ajudar a manter o projeto com uma doação " +
                "pelo Pix, do valor que quiser."));
        root.addView(paragraph("Chave Pix (e-mail): " + PIX_KEY + ". Recebedor: Diego, no Nubank."));
        root.addView(actionButton("Copiar chave Pix", "Copiar " + PIX_KEY + " para colar no app do banco", () -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("Chave Pix", PIX_KEY));
            toast("Chave Pix copiada. Abra o app do seu banco, escolha Pix, Pagar com chave, e cole.");
        }));
        root.addView(paragraph("Como doar: copie a chave, abra o aplicativo do seu banco, escolha " +
                "Pix, depois Transferir ou Pagar com chave, cole a chave e digite o valor. " +
                "Confira se o nome do recebedor é Diego antes de confirmar. Que Deus abençoe!"));
        setContentView(root);
        focusForTalkBack(root.getChildAt(2), "Apoie a Bíblia AGB");
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
        root.addView(paragraph("Selecione a seção: Antigo Testamento, Novo Testamento ou Todos os livros."));
        Spinner testament = new Spinner(this);
        testament.setContentDescription("Seção da Bíblia: Antigo Testamento, Novo Testamento ou Todos os livros");
        testament.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Antigo Testamento", "Novo Testamento", "Todos os livros"}));
        testament.setSelection(newTestament ? 1 : 0);
        root.addView(testament);
        TextView feedback = paragraph("");
        root.addView(feedback);
        testament.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int count = position == 2 ? 66 : position == 1 ? 27 : 39;
                String name = position == 2 ? "Todos os livros" :
                        position == 1 ? "Novo Testamento" : "Antigo Testamento";
                feedback.setText(name + " selecionado. " + count + " livros. Ative Escolher livro.");
                feedback.announceForAccessibility(feedback.getText());
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        root.addView(actionButton("Escolher livro", "Abrir os livros do testamento selecionado",
                () -> populateBookScreen(testament.getSelectedItemPosition())));
        setContentView(root);
        focusForTalkBack(testament, "Escolha Antigo Testamento, Novo Testamento ou Todos os livros.");
    }

    /** Recria a lista de livros sem alterar o padrão de foco da tela. */
    private void populateBookScreen(int scope) {
        String sectionName = scope == 2 ? "Todos os livros" :
                scope == 1 ? "Novo Testamento" : "Antigo Testamento";
        LinearLayout root = screen("Livros — " + sectionName, this::showBooks);
        root.addView(actionButton("Modo culto", "Abrir pesquisa rápida do Modo culto", this::showWorshipModeFromReading));
        List<Models.Book> books = scope == 2 ? bible.books(translationId) : bible.books(translationId, scope == 1);
        root.addView(paragraph(sectionName + " selecionado. " + books.size() +
                " livros. Pesquise pelo nome ou escolha com cima e baixo."));
        EditText search = editText("Pesquisar livro", false);
        root.addView(search);
        TextView status = paragraph(books.size() + " livros exibidos.");
        root.addView(status);
        List<Models.Book> visible = new ArrayList<>(books);
        ListView list = listView(visible);
        list.setContentDescription("Livros de " + sectionName);
        @SuppressWarnings("unchecked")
        ArrayAdapter<Models.Book> adapter = (ArrayAdapter<Models.Book>) list.getAdapter();
        Runnable filter = () -> {
            String wanted = normalizeForSearch(search.getText().toString());
            visible.clear();
            for (Models.Book book : books) {
                if (wanted.isEmpty() || normalizeForSearch(book.name).contains(wanted)) visible.add(book);
            }
            adapter.notifyDataSetChanged();
            status.setText(visible.size() + " livro ou livros encontrados.");
            status.announceForAccessibility(status.getText());
            if (!visible.isEmpty()) {
                list.setSelection(0);
                focusForTalkBack(list, visible.size() + " resultados. Primeiro: " + visible.get(0).name + ".");
            } else {
                search.setError("Nenhum livro encontrado.");
                search.requestFocus();
            }
        };
        search.setOnEditorActionListener((view, actionId, event) -> {
            filter.run();
            return true;
        });
        root.addView(actionButton("Pesquisar livro", "Filtrar livros pelo nome", filter));
        list.setOnItemClickListener((parent, view, position, id) -> {
            selectedBook = (Models.Book) parent.getItemAtPosition(position);
            selectedChapter = 1;
            selectVerse("1");
            savePosition();
            showChapters();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            selectedBook = (Models.Book) parent.getItemAtPosition(position);
            showBookActions();
            return true;
        });
        root.addView(list, fill());
        root.addView(actionButton("Trocar seção de livros",
                "Escolher Antigo Testamento, Novo Testamento ou Todos os livros", this::showBooks));
        root.addView(paragraph("Toque duas vezes para abrir capítulos. Toque e segure para Aplicações."));
        setContentView(root);
        int index = Math.max(0, visible.indexOf(selectedBook));
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
        root.addView(continuousActive
                ? actionButton("Parar leitura em voz alta", "Interromper a leitura contínua",
                        () -> stopContinuousReading("Leitura em voz alta encerrada."))
                : actionButton("Ler em voz alta", "Ler o capítulo a partir do versículo atual, com temporizador opcional",
                        this::chooseContinuousReading));
        List<Models.Verse> verses = bible.chapter(translationId, selectedBook.code, selectedChapter);
        userData.recordReading(translationId, selectedBook, selectedChapter,
                selectedVerse == null ? "1" : selectedVerse.number);
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
        // Durante a leitura contínua, a voz já anuncia o capítulo; o TalkBack não fala por cima.
        focusForTalkBack(list, continuousActive ? "" : "Versículos de " + currentChapterReference() + ".");
    }

    /** Reúne recursos secundários numa lista nativa navegável. */
    private void showMoreOptions() {
        // Agrupado por assunto, como no Windows: leitura, estudo, devoção, quiz e sistema.
        Map<String, Runnable> options = new LinkedHashMap<>();
        options.put("Ir para uma referência", this::showReference);
        options.put("Pesquisar na Bíblia", this::showSearch);
        options.put("Traduções", this::showTranslations);
        options.put("Comparar traduções", this::showComparison);
        options.put("Planos de leitura", this::showPlans);
        options.put("Histórico de leitura", this::showReadingHistory);
        options.put("Favoritos", this::showFavorites);
        options.put("Modo culto", this::showWorshipMode);
        options.put("Estudos — Teólogo de IA", this::showStudies);
        options.put("Memorização de versículos", this::showMemorization);
        options.put("Meu momento com Deus", this::showMoment);
        options.put("Fazer devocional", this::showDevotional);
        options.put("Anotações por dia", this::showNotes);
        options.put("Diário de oração", this::showPrayers);
        options.put("Devocionais novos do site", this::showOnlineDevotionals);
        options.put("Rádio Gospel", this::showGospelRadio);
        options.put("Harpa Cristã", this::showHymnal);
        options.put("Quiz bíblico", this::showQuiz);
        options.put("Status do quiz", this::showQuizStatus);
        options.put("Lembrete diário", this::showReminderSettings);
        options.put("Configurações", this::showSettings);
        options.put("Backup e restauração", this::showBackup);
        options.put("Novidades da versão", () -> showTextScreen("Novidades da versão " +
                BuildConfig.VERSION_NAME, Changelog.CURRENT, this::showMoreOptions));
        options.put("Ideias, sugestões e suporte", this::openIdeasEmail);
        options.put("Ajuda", this::showHelp);
        options.put("Licenças, leis e justificativa", this::showLegal);
        showMenuScreen("Mais opções", this::showMainMenu, options);
    }

    /** Tela de lista em que cada item tem uma ação própria; evita índices frágeis. */
    private void showMenuScreen(String title, Runnable back, Map<String, Runnable> options) {
        LinearLayout root = screen(title, back);
        List<String> labels = new ArrayList<>(options.keySet());
        ListView list = listView(labels);
        list.setContentDescription(title);
        list.setOnItemClickListener((parent, view, position, id) -> options.get(labels.get(position)).run());
        root.addView(list, fill());
        setContentView(root);
        focusForTalkBack(list, title + ".");
    }

    /** Lista categorias prontas e a pergunta livre do Teólogo de IA. */
    private void showStudies() {
        LinearLayout root = screen("Estudos — Teólogo de IA", this::showMoreOptions);
        root.addView(paragraph("Escolha uma categoria. A pergunta sugerida poderá ser editada antes do envio. " +
                "A resposta usa a passagem atual e exige sua chave do Google Gemini."));
        root.addView(actionButton("Estudar e pesquisar temas com IA",
                "Estudar e pesquisar temas com inteligência artificial", this::showStudyThemeScope));
        final String translation = translationId;
        final String bookCode = selectedBook.code;
        final int chapter = selectedChapter;
        root.addView(actionButton("Resumir o capítulo atual",
                "Gerar resumo de " + currentChapterReference(), () ->
                        runAi("Resumo de " + currentChapterReference(),
                                "Resuma o capítulo em no máximo 320 palavras, destaque sua mensagem " +
                                        "central e conclua completamente.",
                                () -> bible.chapterText(translation, bookCode, chapter),
                                this::showStudies)));
        root.addView(actionButton("Resumir o livro atual",
                "Gerar resumo de " + selectedBook.name, () ->
                        runAi("Resumo de " + selectedBook.name,
                                "Resuma o livro em no máximo 550 palavras, apresente estrutura, temas " +
                                        "principais e conclusão completa.",
                                () -> bible.bookText(translation, bookCode),
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

    /** Pergunta primeiro se o tema partirá do texto atual ou da Bíblia toda. */
    private void showStudyThemeScope() {
        String[] choices = {
                "Com base no texto bíblico que estou lendo agora",
                "Focado na Bíblia toda"
        };
        showChoiceDialog("Temas para estudo", "Escolha como a IA deve abordar o tema.",
                choices, which -> showStudyThemes(which == 0));
    }

    /** Oferece pesquisa e seleção acessíveis entre temas cotidianos do Teólogo de IA. */
    private void showStudyThemes(boolean useCurrentText) {
        String scopeLabel = useCurrentText ? "texto bíblico atual" : "Bíblia toda";
        LinearLayout root = screen("Teólogo de IA — Temas para estudo", this::showStudies);
        root.addView(paragraph("Escopo escolhido: " + scopeLabel + ". Pesquise um assunto, como " +
                "oração, relacionamento cristão, fé, perdão, álcool ou adoração. " +
                "Deixe vazio para mostrar todos os temas."));
        EditText search = editText("Pesquisar temas para estudo", false);
        root.addView(search);
        List<AiStudyCatalog.Topic> visible = AiStudyCatalog.searchThemes(this, "");
        TextView status = paragraph(visible.size() + " temas disponíveis.");
        root.addView(status);
        ListView list = listView(visible);
        list.setContentDescription("Temas para estudo do Teólogo de IA");
        @SuppressWarnings("unchecked")
        ArrayAdapter<AiStudyCatalog.Topic> adapter = (ArrayAdapter<AiStudyCatalog.Topic>) list.getAdapter();
        Runnable filter = () -> {
            List<AiStudyCatalog.Topic> matches = AiStudyCatalog.searchThemes(this, search.getText().toString());
            visible.clear();
            visible.addAll(matches);
            adapter.notifyDataSetChanged();
            status.setText(matches.size() + " temas encontrados.");
            status.announceForAccessibility(status.getText());
            if (!matches.isEmpty()) {
                list.setSelection(0);
                focusForTalkBack(list, matches.size() + " temas encontrados. Primeiro: " +
                        matches.get(0).label + ".");
            } else {
                search.setError("Nenhum tema encontrado. Tente outras palavras.");
                search.requestFocus();
            }
        };
        search.setOnEditorActionListener((view, actionId, event) -> {
            filter.run();
            return true;
        });
        root.addView(actionButton("Pesquisar temas", "Filtrar os temas para estudo", filter));
        list.setOnItemClickListener((parent, view, position, id) ->
                showAiStudyComposer((AiStudyCatalog.Topic) parent.getItemAtPosition(position),
                        () -> showStudyThemes(useCurrentText), useCurrentText));
        root.addView(list, fill());
        setContentView(root);
        focusForTalkBack(search, "Pesquisar temas para estudo. Escopo: " + scopeLabel + ".");
    }

    /** Abre um campo editável com a pergunta sugerida e o texto bíblico usado como contexto. */
    private void showAiStudyComposer(AiStudyCatalog.Topic topic, Runnable back) {
        showAiStudyComposer(topic, back, true);
    }

    /** Abre a pergunta usando o alcance explicitamente escolhido pelo usuário. */
    private void showAiStudyComposer(AiStudyCatalog.Topic topic, Runnable back, boolean useCurrentText) {
        if (useCurrentText && selectedVerse == null) {
            toast("Abra um capítulo antes de estudar o texto atual. Usando a Bíblia toda.");
            useCurrentText = false;
        }
        final boolean currentText = useCurrentText;
        final Models.Verse verse = selectedVerse;
        LinearLayout root = screen("Teólogo de IA — " + topic.label, back);
        String scope = currentText ? currentReference() : "toda a Bíblia";
        // Fotografa a passagem agora: o pedido roda em segundo plano e a leitura pode mudar.
        final String reference = currentReference();
        final String translation = translationId;
        final String bookCode = selectedBook.code;
        final int chapter = selectedChapter;
        root.addView(paragraph(currentText
                ? "Passagem atual: " + reference + ". " + verse.text
                : "Escopo selecionado: Bíblia toda."));
        EditText question = editText("Pergunta para o Teólogo de IA", true);
        question.setMinLines(3);
        question.setText(topic.suggestedQuestion);
        root.addView(question, fill());
        root.addView(actionButton("Perguntar à IA", "Enviar pergunta sobre o tema " + topic.label, () -> {
            try {
                String instruction = AiStudyCatalog.instruction(
                        topic, question.getText().toString(), scope) +
                        (currentText
                                ? " Parta do texto fornecido e diga claramente se a relação com o tema não for direta."
                                : " Desenvolva o tema considerando a Bíblia toda, com passagens representativas " +
                                "e contextualizadas do Antigo e do Novo Testamento quando pertinentes.");
                runAi(topic.label + " — " + (currentText ? "texto atual" : "Bíblia toda"), instruction,
                        currentText
                                ? () -> reference + " — " + verse.text +
                                "\n\nContexto completo do capítulo:\n" +
                                bible.chapterText(translation, bookCode, chapter)
                                : () -> "Escopo solicitado: toda a Bíblia. Tema: " + topic.label + ".",
                        () -> showAiStudyComposer(topic, back, currentText));
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
        String number = selectedVerse == null ? "1" : selectedVerse.number;
        String sourceText = verseText(source.id, number);
        String targetText = verseText(target.id, number);
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
                    radioIndex = indexOfPlayingTrack(tracks);
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

    /** Reencontra o louvor atual na lista nova para Anterior e Próximo não pularem de faixa. */
    private int indexOfPlayingTrack(List<GospelRadioFeed.Track> tracks) {
        if (radioIndex < 0 || radioIndex >= radioTracks.size()) return -1;
        String playing = radioTracks.get(radioIndex).audioUrl;
        for (int index = 0; index < tracks.size(); index++) {
            if (tracks.get(index).audioUrl.equals(playing)) return index;
        }
        stopAndReleaseRadio();
        return -1;
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
                .appendQueryParameter("subject", "Ideia ou problema na Bíblia AGB")
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
        Map<String, Runnable> options = new LinkedHashMap<>();
        String savedKey = secureStore.loadApiKey();
        options.put(savedKey == null || savedKey.trim().isEmpty()
                ? "Chave do Google Gemini: não configurada. Toque para colar a chave"
                : "Chave do Google Gemini: configurada. Toque para alterar", () -> editApiKey(this::showSettings));
        options.put("Obter chave da API do Google", this::openGoogleApiKeyPage);
        options.put("Escolher modelo de inteligência artificial. Atual: " +
                preferences.getString("ai_model", AI_MODELS[0]), this::chooseModel);
        options.put("Voz e velocidade da leitura. " + voiceSummary(), this::showVoiceSettings);
        options.put("Alternar avisos de novos devocionais. " +
                (preferences.getBoolean("devotionals_notify", true) ? "Ativados" : "Desativados"),
                this::toggleDevotionalNotifications);
        options.put("Lembrete diário de leitura. " + (Reminders.enabled(preferences)
                ? "Ativado às " + Reminders.timeLabel(preferences) : "Desativado"), this::showReminderSettings);
        options.put("Permitir notificações da Bíblia" + (notificationsAllowed() ? ". Já permitidas" : ""),
                () -> ensureNotificationPermission(true));
        options.put("Abrir configurações de notificações do Android", this::openNotificationSettings);
        options.put("Abrir configurações de acessibilidade do Android",
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        showMenuScreen("Configurações", this::showMoreOptions, options);
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
        showTextScreen("Ajuda", "NAVEGAÇÃO\n\nNa tela principal, o primeiro botão, Abrir capítulo, abre " +
                "o capítulo completo já posicionado no último versículo lido. Use também Trocar de livro " +
                "ou Mais opções. Na leitura, os botões Capítulo anterior e Próximo capítulo ficam no topo. " +
                "O texto bíblico é lido diretamente na lista de Versículos. Com o TalkBack ligado, deslize para a direita " +
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
                "em Status do quiz, é a única forma de liberar tudo novamente. Traduções e testamentos usam caixas de combinação com feedback; Livros também oferece Todos os livros e pesquisa por nome. " +
                "No Teólogo de IA, Temas para estudo permite escolher entre o texto atual e a Bíblia toda, pesquisar temas cotidianos e editar a pergunta antes de enviar. " +
                "Comparar traduções separa origem e destino e permite inverter as duas. No Modo " +
                "culto, digite uma passagem e confirme: o texto aparece imediatamente, sem precisar tocar " +
                "em Ler versículo. A Rádio Gospel toca pela internet os louvores publicados no site. " +
                "Ideias, sugestões e suporte abre um e-mail para Diego.\n\n" +
                "ATUALIZAÇÕES\n\nAs novas versões chegam pela Google Play.\n\n" +
                "DEVOCIONAIS DO SITE\n\n" +
                "A seção Devocionais novos do site mostra todos os textos publicados por Diego e guarda a última " +
                "cópia para leitura sem internet. O Android verifica a cada 15 minutos, mesmo com o aplicativo " +
                "fechado, e avisa na barra de notificações assim que encontra uma publicação nova. " +
                "O aviso pode ser desligado nas Configurações.\n\n" +
                "PLANOS, LEMBRETE E LEITURA EM VOZ ALTA\n\nPlanos de leitura tem os mesmos 17 planos do Windows. " +
                "O Lembrete diário avisa, no horário escolhido, o versículo do dia, a leitura do plano ativo e quantos " +
                "dias seguidos você leu. Em Versículos, Ler em voz alta lê o capítulo e continua nos seguintes, " +
                "com temporizador para parar no fim do capítulo ou depois de 15, 30 ou 60 minutos.\n\n" +
                "ORAÇÃO, FAVORITOS E MEMORIZAÇÃO\n\nO Diário de oração guarda pedidos e respostas. Meu momento com Deus " +
                "reúne versículo do dia, reflexão, prática e oração. Favoritos e Histórico de leitura abrem a passagem " +
                "com um toque. Na Memorização, pratique completando o versículo com palavras escondidas.\n\n" +
                "BACKUP\n\nBackup e restauração usa o mesmo arquivo da versão Windows: exporte no celular e importe " +
                "no computador, ou o contrário.\n\n" +
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
        ensureNotificationPermission(false);
    }

    /** Verdadeiro quando o Android vai mostrar as notificações da Bíblia. */
    private boolean notificationsAllowed() {
        boolean permission = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        return permission && androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    /**
     * Pede a permissão de notificações para devocionais e lembretes. Na abertura, pergunta uma vez
     * por versão; pelo botão das telas (manual), pergunta sempre e, se o Android já bloqueou o
     * pedido, leva direto às configurações de notificação da Bíblia.
     */
    private void ensureNotificationPermission(boolean manual) {
        boolean wanted = preferences.getBoolean("devotionals_notify", true) || Reminders.enabled(preferences);
        if (!wanted || notificationsAllowed()) {
            if (manual) toast("As notificações da Bíblia já estão permitidas.");
            return;
        }
        if (!manual && BuildConfig.VERSION_NAME.equals(preferences.getString(
                "devotionals_permission_prompt_version", ""))) return;
        preferences.edit().putString("devotionals_permission_prompt_version", BuildConfig.VERSION_NAME).apply();
        boolean canAsk = Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                (!preferences.getBoolean("notification_permission_requested", false) ||
                        shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS));
        new AlertDialog.Builder(this).setTitle("Permitir notificações")
                .setMessage("Permita as notificações para saber na hora quando sair um devocional novo e para " +
                        "receber o lembrete diário de leitura, mesmo com a Bíblia fechada.")
                .setPositiveButton(canAsk ? "Permitir" : "Abrir configurações", (dialog, which) -> {
                    if (canAsk) {
                        preferences.edit().putBoolean("notification_permission_requested", true).apply();
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4002);
                    } else {
                        openNotificationSettings();
                    }
                })
                .setNegativeButton("Agora não", null)
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
        showChoiceDialog("Modo culto — livros encontrados",
                "A busca por “" + reference.book + "” encontrou " + matches.size() +
                        " livros. Escolha um deles.", choices, which -> {
                    Models.Book chosen = matches.get(which);
                    worshipInput.setText(formatWorshipReference(chosen, reference));
                    openWorshipPassageForBook(reference, chosen);
                });
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

    /**
     * Abre o reconhecedor nativo; o texto reconhecido ainda passa pela mesma validação.
     * O microfone pertence ao aplicativo de voz do sistema, então a Bíblia não pede essa permissão.
     */
    private void startWorshipVoiceSearch() {
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

    /** Mantém a IA como primeira ação do livro. */
    private void showBookActions() {
        showChoiceDialog("Menu do livro " + selectedBook.name, "Escolha uma ação.",
                new String[]{"Gerar resumo do livro com IA"}, which -> {
                    int words = 550;
                    final String translation = translationId;
                    final Models.Book book = selectedBook;
                    runAi("Resumo de " + book.name,
                            "Resuma " + book.name + " em no máximo " + words +
                                    " palavras, em poucos parágrafos, e conclua completamente.",
                            () -> bible.bookText(translation, book.code));
                });
    }

    /** Mantém a IA como primeira ação do capítulo. */
    private void showChapterActions() {
        showChoiceDialog("Menu do capítulo " + selectedChapter, "Escolha uma ação.",
                new String[]{"Gerar resumo do capítulo com IA"}, which -> {
                    final String translation = translationId;
                    final String bookCode = selectedBook.code;
                    final int chapter = selectedChapter;
                    runAi("Resumo de " + currentChapterReference(),
                            "Resuma o capítulo em no máximo 320 palavras e conclua completamente.",
                            () -> bible.chapterText(translation, bookCode, chapter));
                });
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
                "Ouvir ou repetir pelo leitor de tela", "Ler em voz alta a partir daqui",
                "Adicionar ou remover da memorização"
        };
        showChoiceDialog("Menu do versículo " + currentReference(), "Escolha uma ação.",
                actions, which -> {
                    switch (which) {
                        case 0: {
                            final String verseText = selectedVerse.toString();
                            runAi("Explicação de " + currentReference(),
                                    "Explique " + currentReference() + " em linguagem simples, em no máximo 280 palavras.",
                                    () -> verseText);
                            break;
                        }
                        case 1: showVerseAiStudies(); break;
                        case 2: createNote(); break;
                        case 3: copy(selectedVerse.text); break;
                        case 4: copy(currentReference() + " — " + selectedVerse.text); break;
                        case 5:
                            boolean nowMarked = userData.toggleBookmark(
                                    translationId, selectedBook.code, selectedChapter, selectedVerse.number);
                            if (nowMarked) {
                                userData.setBookmarkBookName(translationId, selectedBook.code, selectedChapter,
                                        selectedVerse.number, selectedBook.name);
                            }
                            toast(nowMarked ? "Adicionado aos favoritos." : "Removido dos favoritos.");
                            break;
                        case 7: chooseContinuousReading(); break;
                        case 8: {
                            boolean added = userData.toggleMemorized(translationId, selectedBook, selectedChapter, selectedVerse);
                            toast(added ? "Adicionado à memorização." : "Removido da memorização.");
                            break;
                        }
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

    /**
     * Resultado da IA guardado fora da tela: se o Android fechar ou recriar a atividade enquanto
     * o Gemini responde (tela bloqueada, troca de app, tema escuro), a resposta é mostrada na
     * próxima vez que a Bíblia estiver visível, em vez de derrubar o aplicativo.
     */
    private static final class AiOutcome {
        final String title;
        final String text;
        final boolean failed;

        AiOutcome(String title, String text, boolean failed) {
            this.title = title;
            this.text = text;
            this.failed = failed;
        }
    }

    private static volatile AiOutcome pendingAiOutcome;
    private static volatile int aiRequestId;
    private static volatile boolean aiBusy;
    private AlertDialog aiProgressDialog;

    /** Executa a IA e mantém uma rota de retorno adequada à tela que iniciou o estudo. */
    private void runAi(String title, String instruction, Supplier<String> textSupplier,
                       Runnable resultBack) {
        if (aiBusy) {
            toast("A inteligência artificial ainda está respondendo o pedido anterior. Aguarde.");
            return;
        }
        String apiKey = secureStore.loadApiKey();
        if (apiKey.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Chave necessária")
                    .setMessage("Abra Mais opções, Configurações e informe sua chave do Google Gemini.")
                    .setPositiveButton("Colar chave", (dialog, which) -> editApiKey(null))
                    .setNeutralButton("Obter chave", (dialog, which) -> openGoogleApiKeyPage())
                    .setNegativeButton("Cancelar", null).show();
            return;
        }
        String model = preferences.getString("ai_model", AI_MODELS[0]);
        int requestId = ++aiRequestId;
        aiBusy = true;
        aiResultBack = resultBack;
        aiProgressDialog = new AlertDialog.Builder(this)
                .setTitle("Gerando com inteligência artificial")
                .setMessage(title + ". Aguarde; isso pode levar alguns segundos.")
                .setNegativeButton("Cancelar", (dialog, which) -> {
                    aiRequestId++;
                    aiBusy = false;
                    toast("Pedido à inteligência artificial cancelado.");
                })
                .setCancelable(false)
                .create();
        aiProgressDialog.show();
        getWindow().getDecorView().announceForAccessibility(
                "Gerando conteúdo com inteligência artificial. Aguarde.");
        new Thread(() -> {
            AiOutcome outcome;
            try {
                String context = textSupplier.get();
                String text;
                try {
                    text = GeminiClient.generate(apiKey, model, instruction, context);
                } catch (GeminiClient.AiException modelError) {
                    // O Google aposenta modelos de tempos em tempos: escolhe outro disponível e tenta uma vez.
                    String replacement = modelError.getMessage() != null &&
                            modelError.getMessage().startsWith("O modelo escolhido")
                            ? bestAvailableModel(GeminiClient.listModels(apiKey)) : null;
                    if (replacement == null) throw modelError;
                    preferences.edit().putString("ai_model", replacement).apply();
                    text = GeminiClient.generate(apiKey, replacement, instruction, context);
                }
                outcome = new AiOutcome(title, text, false);
            } catch (GeminiClient.AiException error) {
                outcome = new AiOutcome(title, error.getMessage(), true);
            } catch (Throwable error) {
                outcome = new AiOutcome(title,
                        "Algo deu errado ao preparar o pedido. Tente novamente; se continuar, " +
                                "escolha um capítulo em vez do livro inteiro.", true);
            }
            if (requestId != aiRequestId) return;
            pendingAiOutcome = outcome;
            aiBusy = false;
            runOnUiThread(this::deliverPendingAi);
        }, "gemini-request").start();
    }

    private Runnable aiResultBack;

    /** Prefere modelos estáveis e rápidos: flash-lite, depois flash, depois qualquer outro. */
    static String bestAvailableModel(List<String> models) {
        String[] order = {"flash-lite", "flash", "pro", ""};
        for (String wanted : order) {
            for (String model : models) {
                if (model.contains(wanted) && !model.contains("preview") && !model.contains("exp")) return model;
            }
        }
        return models.isEmpty() ? null : models.get(0);
    }

    /** Mostra a resposta guardada somente quando esta tela ainda existe e está pronta. */
    private void deliverPendingAi() {
        if (isFinishing() || isDestroyed() || bible == null) return;
        AiOutcome outcome = pendingAiOutcome;
        if (outcome == null) return;
        pendingAiOutcome = null;
        if (aiProgressDialog != null && aiProgressDialog.isShowing()) {
            try { aiProgressDialog.dismiss(); } catch (Exception ignored) { }
        }
        aiProgressDialog = null;
        if (outcome.failed) {
            new AlertDialog.Builder(this).setTitle("Falha na inteligência artificial")
                    .setMessage(outcome.text).setPositiveButton("OK", null).show();
        } else {
            showTextScreen(outcome.title, outcome.text, aiResultBack != null ? aiResultBack : this::showMainMenu);
        }
    }

    /** Edita a chave em campo protegido e salva pelo Android Keystore. */
    private void editApiKey(Runnable afterSave) {
        EditText input = editText("Chave da API do Google Gemini", false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(secureStore.loadApiKey());
        AlertDialog keyDialog = new AlertDialog.Builder(this).setTitle("Chave do Google Gemini")
                .setView(input).setPositiveButton("Salvar", (dialog, which) -> {
                    try {
                        secureStore.saveApiKey(input.getText().toString());
                        toast(input.getText().toString().trim().isEmpty() ? "Chave removida."
                                : "Chave configurada e salva com proteção do Android Keystore.");
                        if (afterSave != null) afterSave.run();
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

    /** Consulta os modelos ativos da chave; sem internet ou sem chave, usa a lista padrão. */
    private void chooseModel() {
        String apiKey = secureStore.loadApiKey();
        if (apiKey.isEmpty()) {
            showModelChoices(new ArrayList<>(Arrays.asList(AI_MODELS)));
            return;
        }
        toast("Consultando os modelos disponíveis para sua chave.");
        new Thread(() -> {
            List<String> models;
            try {
                models = GeminiClient.listModels(apiKey);
            } catch (Exception error) {
                models = new ArrayList<>();
            }
            if (models.isEmpty()) models = new ArrayList<>(Arrays.asList(AI_MODELS));
            final List<String> choices = models;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) showModelChoices(choices);
            });
        }, "gemini-models").start();
    }

    private void showModelChoices(List<String> models) {
        String current = preferences.getString("ai_model", AI_MODELS[0]);
        String[] items = models.toArray(new String[0]);
        int checked = Math.max(0, models.indexOf(current));
        AlertDialog modelDialog = new AlertDialog.Builder(this).setTitle("Modelo da inteligência artificial")
                .setSingleChoiceItems(items, checked, (choiceDialog, which) -> {
                    preferences.edit().putString("ai_model", items[which]).apply();
                    choiceDialog.dismiss();
                    toast("Modelo selecionado: " + items[which]);
                }).setNegativeButton("Cancelar", null).create();
        showDialogWithChoiceFocus(modelDialog, "Escolha o modelo da inteligência artificial. " +
                items.length + " modelos disponíveis.");
    }

    static final float MIN_RATE = 0.5f;
    static final float MAX_RATE = 3.0f;
    static final float RATE_STEP = 0.05f;
    private static final float[] RATE_OPTIONS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f};

    /** Cria o TTS com o mecanismo escolhido; vazio usa o mecanismo padrão do Android. */
    private TextToSpeech createTts() {
        String engine = preferences.getString("tts_engine", "");
        return engine.isEmpty() ? new TextToSpeech(this, this) : new TextToSpeech(this, this, engine);
    }

    /** Velocidade no formato falado pelo leitor de tela, por exemplo "1,25 X". */
    static String rateLabel(float rate) {
        String number = String.format(new Locale("pt", "BR"), "%.2f", rate);
        if (number.endsWith("0")) number = number.substring(0, number.length() - 1);
        if (number.endsWith(",0")) number = number.substring(0, number.length() - 2);
        return number + " X";
    }

    private float speechRate() {
        float rate = preferences.getFloat("speech_rate", 1.0f);
        return Math.max(MIN_RATE, Math.min(MAX_RATE, rate));
    }

    private String engineLabel() {
        String engine = preferences.getString("tts_engine", "");
        if (engine.isEmpty()) return "padrão do Android";
        if (tts != null) for (TextToSpeech.EngineInfo info : tts.getEngines()) {
            if (info.name.equals(engine)) return info.label;
        }
        return engine;
    }

    private String voiceLabel() {
        String voice = preferences.getString("voice", "");
        if (VOICE_OFF.equals(voice)) return "desativada, o TalkBack lê";
        return voice.isEmpty() ? "padrão do mecanismo" : voice;
    }

    private String voiceSummary() {
        return "Velocidade " + rateLabel(speechRate()) + ", mecanismo " + engineLabel();
    }

    /** Mecanismo, voz, velocidade (controle deslizante ou opções) e teste da voz. */
    private void showVoiceSettings() {
        LinearLayout root = screen("Voz e velocidade", this::showSettings);
        root.addView(paragraph("Esta voz lê em voz alta os capítulos e os versículos. " +
                "Ela é separada da voz do seu leitor de tela."));
        root.addView(actionButton("Mecanismo de voz: " + engineLabel(),
                "Escolher outro programa de voz instalado no celular", this::chooseEngine));
        root.addView(actionButton("Voz: " + voiceLabel(),
                "Escolher uma voz do mecanismo atual", this::chooseVoice));
        boolean slider = preferences.getBoolean("rate_slider", true);
        root.addView(actionButton("Ajuste da velocidade: " + (slider ? "controle deslizante" : "lista de opções"),
                "Trocar entre controle deslizante e lista de opções", () -> {
                    preferences.edit().putBoolean("rate_slider", !slider).apply();
                    showVoiceSettings();
                    toast(slider ? "Velocidade por lista de opções." : "Velocidade por controle deslizante.");
                }));
        if (slider) {
            TextView label = paragraph("Velocidade: " + rateLabel(speechRate()));
            root.addView(label);
            android.widget.SeekBar bar = new android.widget.SeekBar(this);
            bar.setMax(Math.round((MAX_RATE - MIN_RATE) / RATE_STEP));
            bar.setProgress(Math.round((speechRate() - MIN_RATE) / RATE_STEP));
            bar.setContentDescription("Velocidade da voz");
            bar.setMinimumHeight(dp(56));
            Runnable describe = () -> {
                String text = rateLabel(MIN_RATE + bar.getProgress() * RATE_STEP);
                label.setText("Velocidade: " + text);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bar.setStateDescription(text);
                else bar.setContentDescription("Velocidade da voz, " + text);
            };
            describe.run();
            bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                    float rate = MIN_RATE + progress * RATE_STEP;
                    preferences.edit().putFloat("speech_rate", rate).apply();
                    if (ttsReady) tts.setSpeechRate(rate);
                    describe.run();
                }

                @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) { }

                @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) { }
            });
            root.addView(bar, fill());
        } else {
            root.addView(actionButton("Velocidade: " + rateLabel(speechRate()),
                    "Escolher a velocidade numa lista", this::chooseSpeechRate));
        }
        root.addView(actionButton("Testar a voz", "Ouvir a voz e a velocidade escolhidas", this::testVoice));
        setContentView(root);
        focusForTalkBack(root.getChildAt(2), "Voz e velocidade");
    }

    /** Lê uma frase de exemplo com a voz e a velocidade atuais. */
    private void testVoice() {
        if (VOICE_OFF.equals(preferences.getString("voice", ""))) {
            toast("A voz está desativada. Escolha uma voz para testar.");
            return;
        }
        if (!ttsReady) {
            toast("A voz ainda não está pronta. Tente novamente em instantes.");
            return;
        }
        applyVoice();
        tts.speak("Esta é a voz da Bíblia AGB, na velocidade " + rateLabel(speechRate()) +
                ". Porque Deus amou o mundo de tal maneira que deu o seu Filho unigênito.",
                TextToSpeech.QUEUE_FLUSH, null, "teste");
    }

    /** Lista os mecanismos de voz instalados (Google, Samsung, eSpeak, Vocalizer e outros). */
    private void chooseEngine() {
        List<TextToSpeech.EngineInfo> engines = tts == null ? new ArrayList<>() : tts.getEngines();
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        labels.add("Padrão do Android");
        values.add("");
        for (TextToSpeech.EngineInfo info : engines) {
            labels.add(info.label);
            values.add(info.name);
        }
        showChoiceDialog("Mecanismo de voz", "Escolha o programa de voz. " + engines.size() +
                        " instalados.", labels.toArray(new String[0]), which -> {
                    preferences.edit().putString("tts_engine", values.get(which)).putString("voice", "").apply();
                    if (continuousActive) stopContinuousReading("Leitura em voz alta encerrada.");
                    try { tts.shutdown(); } catch (Exception ignored) { }
                    ttsReady = false;
                    tts = createTts();
                    toast("Mecanismo de voz: " + labels.get(which));
                    showVoiceSettings();
                });
    }

    /** Lista as vozes do mecanismo atual, em português primeiro, e inclui a desativação. */
    private void chooseVoice() {
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        labels.add("Desativada — TalkBack repete ao ativar");
        values.add(VOICE_OFF);
        labels.add("Voz padrão do mecanismo");
        values.add("");
        if (ttsReady && tts.getVoices() != null) {
            List<Voice> voices = new ArrayList<>(tts.getVoices());
            String portuguese = new Locale("pt").getLanguage();
            voices.sort(Comparator.comparing((Voice voice) -> !voice.getLocale().getLanguage().equals(portuguese))
                    .thenComparing(voice -> voice.getLocale().getDisplayName())
                    .thenComparing(Voice::getName));
            for (Voice voice : voices) {
                if (voice.getFeatures() != null && voice.getFeatures().contains(
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                labels.add(voice.getName() + " — " + voice.getLocale().getDisplayName() +
                        (voice.isNetworkConnectionRequired() ? ", precisa de internet" : ""));
                values.add(voice.getName());
            }
        } else {
            toast("A voz ainda está carregando. A lista completa aparece em instantes.");
        }
        showChoiceDialog("Voz", "Escolha uma voz. " + (labels.size() - 2) + " vozes disponíveis.",
                labels.toArray(new String[0]), which -> {
                    preferences.edit().putString("voice", values.get(which)).apply();
                    applyVoice();
                    toast("Voz selecionada: " + labels.get(which));
                    showVoiceSettings();
                });
    }

    /** Lista de velocidades fixas, para quem prefere não usar o controle deslizante. */
    private void chooseSpeechRate() {
        String[] labels = new String[RATE_OPTIONS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = rateLabel(RATE_OPTIONS[i]) +
                (RATE_OPTIONS[i] == 1.0f ? ", normal" : "");
        showChoiceDialog("Velocidade da voz", "Escolha a velocidade. Atual: " + rateLabel(speechRate()),
                labels, which -> {
                    preferences.edit().putFloat("speech_rate", RATE_OPTIONS[which]).apply();
                    if (ttsReady) tts.setSpeechRate(RATE_OPTIONS[which]);
                    toast("Velocidade: " + rateLabel(RATE_OPTIONS[which]));
                    showVoiceSettings();
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
        tts.setSpeechRate(speechRate());
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
        if (ttsReady) {
            applyVoice();
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }

                @Override public void onDone(String utteranceId) {
                    if (utteranceId != null && utteranceId.startsWith("continuo-")) {
                        runOnUiThread(() -> continueReading(utteranceId));
                    }
                }

                @Override public void onError(String utteranceId) {
                    runOnUiThread(() -> stopContinuousReading("A leitura em voz alta foi interrompida."));
                }
            });
        }
    }

    // Abrir referências --------------------------------------------------------
    /** Abre "Livro capítulo" ou "Livro capítulo:versículo" na tela de Versículos. */
    private boolean openReading(String reference) {
        try {
            BibleReference parsed = BibleReference.parse(reference);
            Models.Book book = bible.findExactBook(translationId, parsed.book);
            if (book == null || parsed.chapter < 1 || parsed.chapter > bible.chapterCount(translationId, book.code)) {
                toast("Não encontrei " + reference + " nesta tradução.");
                return false;
            }
            selectedBook = book;
            selectedChapter = parsed.chapter;
            selectVerse(parsed.startVerse == null ? "1" : parsed.startVerse);
            savePosition();
            showVerses();
            return true;
        } catch (Exception error) {
            toast("Não consegui abrir " + reference + ".");
            return false;
        }
    }

    // Planos de leitura --------------------------------------------------------
    private Map<String, ReadingPlans.Plan> plans() {
        return ReadingPlans.build(bible, translationId);
    }

    private void showPlans() {
        Map<String, ReadingPlans.Plan> catalog = plans();
        LinearLayout root = screen("Planos de leitura", this::showMoreOptions);
        root.addView(paragraph("Escolha um plano para iniciar, ler o dia ou marcar como concluído. " +
                "O lembrete diário avisa a leitura do plano ativo."));
        List<String> labels = new ArrayList<>();
        List<ReadingPlans.Plan> order = new ArrayList<>(catalog.values());
        for (ReadingPlans.Plan plan : order) {
            android.content.ContentValues state = userData.planState(plan.id);
            if (state == null) {
                labels.add(plan.name + ". Não iniciado. " + plan.days() + " dias.");
            } else {
                int completed = userData.planCompletedDays(plan.id).size();
                labels.add(plan.name + ". " + state.getAsString("status") + ". " + completed + " de " + plan.days() +
                        " dias concluídos, " + Math.round(100.0 * completed / plan.days()) + " por cento. Dia atual: " +
                        state.getAsInteger("current_day") + ".");
            }
        }
        ListView list = listView(labels);
        list.setContentDescription("Planos de leitura");
        list.setOnItemClickListener((parent, view, position, id) -> showPlanActions(order.get(position)));
        root.addView(list, fill());
        setContentView(root);
        focusForTalkBack(list, "Planos de leitura.");
    }

    private void showPlanActions(ReadingPlans.Plan plan) {
        android.content.ContentValues state = userData.planState(plan.id);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Ouvir descrição e progresso");
        actions.add(() -> {
            int completed = state == null ? 0 : userData.planCompletedDays(plan.id).size();
            String started = state == null ? "ainda não iniciado" : state.getAsString("started_at").substring(0, 10);
            showTextScreen(plan.name, plan.description + "\n\nDuração: " + plan.days() + " dias.\n\nConcluídos: " +
                    completed + ".\n\nInício: " + started + ".", this::showPlans);
        });
        if (state == null || "Concluído".equals(state.getAsString("status"))) {
            labels.add(state == null ? "Iniciar plano" : "Recomeçar plano");
            actions.add(() -> {
                userData.startPlan(plan.id, state != null);
                toast("Plano iniciado. Bom começo!");
                showPlans();
            });
        } else {
            int day = state.getAsInteger("current_day");
            labels.add("Ler o dia " + day + ": " + String.join(", ", plan.day(day)));
            actions.add(() -> chooseReading(plan, day));
            labels.add("Marcar o dia " + day + " como lido");
            actions.add(() -> {
                boolean marked = userData.togglePlanDay(plan.id, day, plan.days());
                toast(marked ? "Dia " + day + " concluído." : "Dia " + day + " desmarcado.");
                showPlans();
            });
            boolean paused = "Pausado".equals(state.getAsString("status"));
            labels.add(paused ? "Continuar plano" : "Pausar plano");
            actions.add(() -> {
                userData.setPlanStatus(plan.id, paused ? "Ativo" : "Pausado");
                toast(paused ? "Plano retomado." : "Plano pausado.");
                showPlans();
            });
            labels.add("Recomeçar do dia 1");
            actions.add(() -> confirm("Recomeçar plano", "Apagar o progresso deste plano e começar de novo?", () -> {
                userData.startPlan(plan.id, true);
                showPlans();
            }));
            labels.add("Abandonar plano");
            actions.add(() -> confirm("Abandonar plano", "Remover este plano e todo o progresso?", () -> {
                userData.abandonPlan(plan.id);
                showPlans();
            }));
        }
        showChoiceDialog("Plano " + plan.name, "Escolha uma ação.", labels.toArray(new String[0]),
                which -> actions.get(which).run());
    }

    private void chooseReading(ReadingPlans.Plan plan, int day) {
        List<String> chapters = ReadingPlans.expand(plan.day(day));
        if (chapters.size() == 1) {
            openReading(chapters.get(0));
            return;
        }
        showChoiceDialog("Leituras do dia " + day, "Escolha o capítulo para abrir.",
                chapters.toArray(new String[0]), which -> openReading(chapters.get(which)));
    }

    /** Abre o plano ativo diretamente, a partir do lembrete. */
    private void openActivePlan() {
        List<android.content.ContentValues> active = userData.activePlans();
        if (active.isEmpty()) {
            showPlans();
            return;
        }
        ReadingPlans.Plan plan = plans().get(active.get(0).getAsString("plan_id"));
        if (plan == null) showPlans();
        else chooseReading(plan, active.get(0).getAsInteger("current_day"));
    }

    private void confirm(String title, String message, Runnable action) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Sim", (dialog, which) -> action.run())
                .setNegativeButton("Não", null).show();
    }

    // Diário de oração ---------------------------------------------------------
    private String prayerFilter = "Todas";

    private void showPrayers() {
        LinearLayout root = screen("Diário de oração", this::showMoreOptions);
        List<android.content.ContentValues> prayers = userData.prayers(prayerFilter);
        root.addView(actionButton("Novo pedido de oração", "Escrever um novo pedido", this::createPrayer));
        root.addView(actionButton("Filtro: " + prayerFilter, "Mostrar Todas, Orando, Respondida ou Arquivada", () -> {
            String[] filters = {"Todas", "Orando", "Respondida", "Arquivada"};
            showChoiceDialog("Filtrar pedidos", "Escolha quais pedidos mostrar.", filters, which -> {
                prayerFilter = filters[which];
                showPrayers();
            });
        }));
        List<String> labels = new ArrayList<>();
        for (android.content.ContentValues prayer : prayers) {
            labels.add(prayer.getAsString("title") + ". " + prayer.getAsString("status") + ". Criado em " +
                    shortDay(prayer.getAsString("created_at")) + ".");
        }
        if (labels.isEmpty()) labels.add("Nenhum pedido neste filtro.");
        ListView list = listView(labels);
        list.setContentDescription("Pedidos de oração");
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (!prayers.isEmpty()) showPrayerActions(prayers.get(position));
        });
        root.addView(list, fill());
        setContentView(root);
        focusForTalkBack(list, "Diário de oração. " + prayers.size() + " pedidos.");
    }

    private void createPrayer() {
        LinearLayout layout = rootLayout();
        EditText title = editText("Título do pedido", false);
        EditText request = editText("Pedido de oração", true);
        EditText category = editText("Categoria, opcional", false);
        layout.addView(title);
        layout.addView(request);
        layout.addView(category);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Novo pedido de oração").setView(layout)
                .setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (title.getText().toString().trim().isEmpty() || request.getText().toString().trim().isEmpty()) {
                (title.getText().toString().trim().isEmpty() ? title : request).setError("Preencha este campo.");
                return;
            }
            userData.addPrayer(title.getText().toString(), request.getText().toString(), category.getText().toString());
            dialog.dismiss();
            toast("Pedido de oração salvo.");
            showPrayers();
        }));
        dialog.show();
        focusForTalkBack(title, "Título do pedido.");
    }

    private void showPrayerActions(android.content.ContentValues prayer) {
        long id = prayer.getAsLong("id");
        String[] actions = {"Ler pedido completo", "Marcar como oração respondida", "Arquivar",
                "Voltar para Orando", "Excluir pedido"};
        showChoiceDialog("Pedido " + prayer.getAsString("title"), "Escolha uma ação.", actions, which -> {
            switch (which) {
                case 0: {
                    String text = "Pedido: " + prayer.getAsString("request") + "\n\nStatus: " + prayer.getAsString("status") +
                            "\n\nCategoria: " + (prayer.getAsString("category").isEmpty() ? "sem categoria" : prayer.getAsString("category"));
                    if (prayer.getAsString("answered_at") != null) {
                        text += "\n\nRespondida em " + shortDay(prayer.getAsString("answered_at")) +
                                ".\n\nComo Deus respondeu: " + prayer.getAsString("answer_text");
                    }
                    showTextScreen(prayer.getAsString("title"), text, this::showPrayers);
                    break;
                }
                case 1: {
                    EditText answer = editText("Como Deus respondeu", true);
                    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Oração respondida").setView(answer)
                            .setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).create();
                    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                        if (answer.getText().toString().trim().isEmpty()) {
                            answer.setError("Descreva como Deus respondeu.");
                            return;
                        }
                        userData.answerPrayer(id, answer.getText().toString());
                        dialog.dismiss();
                        toast("Glória a Deus! Oração marcada como respondida.");
                        showPrayers();
                    }));
                    dialog.show();
                    focusForTalkBack(answer, "Como Deus respondeu.");
                    break;
                }
                case 2: userData.setPrayerStatus(id, "Arquivada"); showPrayers(); break;
                case 3: userData.setPrayerStatus(id, "Orando"); showPrayers(); break;
                default: confirm("Excluir pedido", "Excluir este pedido de oração?", () -> {
                    userData.deletePrayer(id);
                    showPrayers();
                });
            }
        });
    }

    private static String shortDay(String iso) {
        if (iso == null || iso.length() < 10) return "";
        try {
            return LocalDate.parse(iso.substring(0, 10)).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception error) {
            return iso.substring(0, 10);
        }
    }

    // Meu momento com Deus -----------------------------------------------------
    private void showMoment() {
        String today = LocalDate.now().toString();
        android.content.ContentValues saved = userData.devotionalFor(today);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        String reference = saved != null ? saved.getAsString("verse_reference") : Reminders.dailyReference();
        EditText verse = editText("Versículo do dia ou outra referência", false);
        verse.setText(reference);
        TextView verseText = paragraph(Reminders.verseText(bible, translationId, reference));
        EditText learned = editText("O que Deus falou comigo hoje", true);
        EditText practice = editText("O que quero colocar em prática", true);
        EditText prayer = editText("Minha oração de hoje", true);
        EditText note = editText("Anotação pessoal", true);
        if (saved != null) {
            learned.setText(saved.getAsString("learned"));
            practice.setText(saved.getAsString("practice"));
            prayer.setText(saved.getAsString("prayer"));
            note.setText(saved.getAsString("personal_note"));
        }
        content.addView(verse);
        content.addView(actionButton("Carregar versículo", "Mostrar o texto da referência digitada",
                () -> verseText.setText(Reminders.verseText(bible, translationId, verse.getText().toString()))));
        content.addView(verseText);
        for (EditText field : new EditText[]{learned, practice, prayer, note}) {
            field.setMinLines(3);
            content.addView(field);
        }
        content.addView(actionButton("Concluir e salvar", "Salvar o momento com Deus de hoje", () -> {
            userData.saveDevotional(today, verse.getText().toString().trim(), verseText.getText().toString(),
                    learned.getText().toString(), practice.getText().toString(),
                    prayer.getText().toString(), note.getText().toString());
            toast("Momento com Deus de hoje salvo.");
        }));
        content.addView(actionButton("Histórico do momento com Deus", "Ler os dias anteriores", this::showMomentHistory));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        showCustomScreen("Meu momento com Deus", scroll, this::showMoreOptions);
        focusForTalkBack(verse, "Meu momento com Deus. Versículo do dia: " + reference + ".");
    }

    private void showMomentHistory() {
        List<android.content.ContentValues> entries = userData.devotionalEntries();
        if (entries.isEmpty()) {
            toast("Ainda não há momentos salvos.");
            return;
        }
        String[] labels = new String[entries.size()];
        for (int index = 0; index < entries.size(); index++) {
            labels[index] = shortDay(entries.get(index).getAsString("devotional_date")) + ". " +
                    entries.get(index).getAsString("verse_reference");
        }
        showChoiceDialog("Histórico", "Escolha um dia para ler.", labels, which -> {
            android.content.ContentValues row = entries.get(which);
            showTextScreen("Momento com Deus de " + shortDay(row.getAsString("devotional_date")),
                    "Versículo: " + row.getAsString("verse_reference") + "\n\n" + row.getAsString("verse_text") +
                            "\n\nO que Deus falou comigo: " + row.getAsString("learned") +
                            "\n\nPrática: " + row.getAsString("practice") + "\n\nOração: " + row.getAsString("prayer") +
                            "\n\nAnotação: " + row.getAsString("personal_note"), this::showMoment);
        });
    }

    // Favoritos, histórico e memorização ---------------------------------------
    private String bookName(String code, String saved) {
        if (saved != null && !saved.isEmpty()) return saved;
        Models.Book book = null;
        for (Models.Book candidate : bible.books(translationId)) {
            if (candidate.code.equals(code)) book = candidate;
        }
        return book == null ? code : book.name;
    }

    private void showFavorites() {
        List<android.content.ContentValues> favorites = userData.favorites();
        LinearLayout root = screen("Favoritos", this::showMoreOptions);
        List<String> labels = new ArrayList<>();
        for (android.content.ContentValues favorite : favorites) {
            String verse = favorite.getAsString("verse");
            labels.add(bookName(favorite.getAsString("book_code"), favorite.getAsString("book_name")) + " " +
                    favorite.getAsInteger("chapter") + ("*".equals(verse) ? "" : ":" + verse) +
                    (favorite.getAsString("category_name") != null ? ". " + favorite.getAsString("category_name") : ""));
        }
        if (labels.isEmpty()) labels.add("Nenhum favorito. No menu de um versículo, escolha Adicionar marcador.");
        ListView list = listView(labels);
        list.setContentDescription("Versículos favoritos");
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (favorites.isEmpty()) return;
            android.content.ContentValues favorite = favorites.get(position);
            showChoiceDialog(labels.get(position), "Escolha uma ação.", new String[]{"Abrir passagem", "Remover dos favoritos"},
                    which -> {
                        String verse = favorite.getAsString("verse");
                        if (which == 0) {
                            openSaved(favorite.getAsString("translation_id"), favorite.getAsString("book_code"),
                                    favorite.getAsInteger("chapter"), "*".equals(verse) ? "1" : verse);
                        } else {
                            userData.removeFavorite(favorite.getAsString("translation_id"), favorite.getAsString("book_code"),
                                    favorite.getAsInteger("chapter"), verse);
                            toast("Removido dos favoritos.");
                            showFavorites();
                        }
                    });
        });
        root.addView(list, fill());
        setContentView(root);
        focusForTalkBack(list, "Favoritos. " + favorites.size() + " itens.");
    }

    /** Abre uma posição salva, trocando para a tradução em que ela foi guardada. */
    private void openSaved(String translation, String bookCode, int chapter, String verse) {
        for (Models.Translation item : bible.translations()) {
            if (item.id.equals(translation)) translationId = translation;
        }
        Models.Book book = null;
        for (Models.Book candidate : bible.books(translationId)) {
            if (candidate.code.equals(bookCode)) book = candidate;
        }
        if (book == null) {
            toast("Este livro não existe na tradução atual.");
            return;
        }
        selectedBook = book;
        selectedChapter = Math.max(1, Math.min(chapter, bible.chapterCount(translationId, bookCode)));
        selectVerse(verse == null ? "1" : verse.split("-")[0]);
        savePosition();
        showVerses();
    }

    private void showReadingHistory() {
        List<android.content.ContentValues> history = userData.readingHistory();
        LinearLayout root = screen("Histórico de leitura", this::showMoreOptions);
        int streak = Reminders.readingStreak(userData.readingDays());
        root.addView(paragraph(streak > 0 ? "Você leu em " + streak + (streak == 1 ? " dia seguido." : " dias seguidos.")
                : "Comece hoje uma nova sequência de leitura."));
        String today = LocalDate.now().toString();
        String yesterday = LocalDate.now().minusDays(1).toString();
        List<String> labels = new ArrayList<>();
        for (android.content.ContentValues row : history) {
            String day = row.getAsString("accessed_at").substring(0, 10);
            labels.add((day.equals(today) ? "Hoje" : day.equals(yesterday) ? "Ontem" : shortDay(day)) + ": " +
                    row.getAsString("book_name") + " " + row.getAsInteger("chapter"));
        }
        if (labels.isEmpty()) labels.add("Nenhuma leitura registrada ainda.");
        ListView list = listView(labels);
        list.setContentDescription("Capítulos lidos recentemente");
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (history.isEmpty()) return;
            android.content.ContentValues row = history.get(position);
            openSaved(row.getAsString("translation_id"), row.getAsString("book_code"), row.getAsInteger("chapter"),
                    row.getAsString("verse"));
        });
        root.addView(list, fill());
        root.addView(actionButton("Limpar histórico", "Apagar todo o histórico de leitura",
                () -> confirm("Limpar histórico", "Apagar todo o histórico de leitura?", () -> {
                    userData.clearReadingHistory();
                    showReadingHistory();
                })));
        setContentView(root);
        focusForTalkBack(list, "Histórico de leitura.");
    }

    private void showMemorization() {
        List<android.content.ContentValues> verses = userData.memorizedVerses();
        LinearLayout root = screen("Memorização de versículos", this::showMoreOptions);
        root.addView(paragraph("Para adicionar, abra o menu de um versículo e escolha Adicionar à memorização."));
        List<String> labels = new ArrayList<>();
        for (android.content.ContentValues row : verses) {
            labels.add(row.getAsString("book_name") + " " + row.getAsInteger("chapter") + ":" + row.getAsString("verse"));
        }
        if (labels.isEmpty()) labels.add("Nenhum versículo para memorizar ainda.");
        ListView list = listView(labels);
        list.setContentDescription("Versículos para memorizar");
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (verses.isEmpty()) return;
            android.content.ContentValues row = verses.get(position);
            String[] levels = {"Praticar no nível fácil", "Praticar no nível médio", "Praticar no nível difícil",
                    "Revelar o versículo", "Remover da memorização"};
            showChoiceDialog(labels.get(position), "Escolha como praticar.", levels, which -> {
                String text = row.getAsString("verse_text");
                if (which <= 2) {
                    showTextScreen("Complete o versículo", hideWords(text, 3 - which) +
                            "\n\nTente dizer o versículo completo. Depois, escolha Revelar o versículo.", this::showMemorization);
                } else if (which == 3) {
                    showTextScreen(labels.get(position), text, this::showMemorization);
                } else {
                    Models.Book book = new Models.Book(0, row.getAsString("book_code"), row.getAsString("book_name"));
                    userData.toggleMemorized(row.getAsString("translation_id"), book, row.getAsInteger("chapter"),
                            new Models.Verse(row.getAsString("verse"), text));
                    showMemorization();
                }
            });
        });
        root.addView(list, fill());
        setContentView(root);
        focusForTalkBack(list, "Memorização.");
    }

    /** Esconde uma palavra a cada "step" palavras; fácil esconde menos. */
    static String hideWords(String text, int step) {
        String[] words = text.split(" ");
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < words.length; index++) {
            if (result.length() > 0) result.append(' ');
            boolean letters = words[index].replaceAll("[^\\p{L}]", "").length() > 2;
            result.append(letters && index % step == step - 1 ? "lacuna" : words[index]);
        }
        return result.toString();
    }

    // Lembrete diário -----------------------------------------------------------
    private void showReminderSettings() {
        Map<String, Runnable> options = new LinkedHashMap<>();
        boolean enabled = Reminders.enabled(preferences);
        options.put((enabled ? "Desativar" : "Ativar") + " o lembrete diário. Agora " +
                (enabled ? "ativado" : "desativado"), () -> {
            preferences.edit().putBoolean("reminder_enabled", !enabled).apply();
            Reminders.schedule(this);
            if (!enabled) ensureNotificationPermission(true);
            showReminderSettings();
        });
        options.put("Horário do lembrete: " + Reminders.timeLabel(preferences), () -> {
            String[] times = new String[48];
            for (int index = 0; index < 48; index++) {
                times[index] = String.format(Locale.ROOT, "%02d:%02d", index / 2, (index % 2) * 30);
            }
            showChoiceDialog("Horário do lembrete", "Escolha a hora do lembrete diário.", times, which -> {
                preferences.edit().putInt("reminder_minutes", which * 30).apply();
                Reminders.schedule(this);
                toast("Lembrete marcado para " + times[which] + ".");
                showReminderSettings();
            });
        });
        options.put("Ouvir o lembrete de hoje agora", () -> {
            String message = Reminders.buildMessage(this);
            showTextScreen("Lembrete de hoje", message, this::showReminderSettings);
        });
        options.put("Permitir notificações da Bíblia" + (notificationsAllowed() ? ". Já permitidas" : ""),
                () -> ensureNotificationPermission(true));
        showMenuScreen("Lembrete diário", this::showMoreOptions, options);
    }

    // Backup compatível com o Windows ------------------------------------------
    private static final int BACKUP_EXPORT = 7003;
    private static final int BACKUP_IMPORT = 7004;

    private void showBackup() {
        Map<String, Runnable> options = new LinkedHashMap<>();
        options.put("Exportar backup", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, "biblia-agb-backup-" + LocalDate.now() + ".json");
            startActivityForResult(intent, BACKUP_EXPORT);
        });
        options.put("Importar backup", () -> confirm("Importar backup",
                "As anotações, favoritos, planos e orações deste aparelho serão substituídos pelos do backup. Continuar?",
                () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), BACKUP_IMPORT)));
        LinearLayout root = screen("Backup e restauração", this::showMoreOptions);
        root.addView(paragraph("O backup usa o mesmo formato da Bíblia AGB para Windows: dá para levar anotações, " +
                "favoritos, planos, orações e quiz do celular para o computador e do computador para o celular."));
        List<String> labels = new ArrayList<>(options.keySet());
        ListView list = listView(labels);
        list.setContentDescription("Backup e restauração");
        list.setOnItemClickListener((parent, view, position, id) -> options.get(labels.get(position)).run());
        root.addView(list, fill());
        setContentView(root);
        focusForTalkBack(list, "Backup e restauração.");
    }

    private void handleBackupResult(int requestCode, Uri uri) {
        new Thread(() -> {
            String message;
            try {
                if (requestCode == BACKUP_EXPORT) {
                    byte[] json = userData.exportPayload().toString(2).getBytes(StandardCharsets.UTF_8);
                    try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                        if (output == null) throw new IllegalStateException();
                        output.write(json);
                    }
                    message = "Backup exportado. Ele pode ser importado no Windows ou em outro celular.";
                } else {
                    String text;
                    try (InputStream input = getContentResolver().openInputStream(uri);
                         ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                        if (input == null) throw new IllegalStateException();
                        byte[] chunk = new byte[8192];
                        int read;
                        while ((read = input.read(chunk)) >= 0) {
                            buffer.write(chunk, 0, read);
                            if (buffer.size() > 50_000_000) throw new IllegalArgumentException("Arquivo grande demais.");
                        }
                        text = buffer.toString(StandardCharsets.UTF_8.name());
                    }
                    userData.importPayload(new org.json.JSONObject(text));
                    message = "Backup importado com sucesso.";
                }
            } catch (IllegalArgumentException error) {
                message = error.getMessage() == null ? "Arquivo de backup inválido." : error.getMessage();
            } catch (Throwable error) {
                message = requestCode == BACKUP_EXPORT ? "Não foi possível exportar o backup."
                        : "Não foi possível importar: o arquivo não é um backup válido da Bíblia AGB.";
            }
            final String result = message;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    new AlertDialog.Builder(this).setTitle("Backup").setMessage(result)
                            .setPositiveButton("OK", null).show();
                }
            });
        }, "backup").start();
    }

    // Leitura contínua ----------------------------------------------------------
    private boolean continuousActive;
    private long continuousStopAt;
    private boolean continuousStopAtChapterEnd;
    private List<Models.Verse> continuousVerses = new ArrayList<>();

    private void chooseContinuousReading() {
        if (!ttsReady) {
            toast("A voz do Android ainda não está pronta. Tente novamente em instantes.");
            return;
        }
        String[] timers = {"Sem temporizador", "Parar no fim deste capítulo", "Parar depois de 15 minutos",
                "Parar depois de 30 minutos", "Parar depois de 60 minutos"};
        showChoiceDialog("Ler em voz alta", "Escolha quando a leitura deve parar.", timers, which -> {
            long[] minutes = {0, 0, 15, 30, 60};
            continuousStopAtChapterEnd = which == 1;
            continuousStopAt = minutes[which] > 0 ? System.currentTimeMillis() + minutes[which] * 60_000 : 0;
            startContinuousReading();
        });
    }

    private void startContinuousReading() {
        continuousActive = true;
        continuousVerses = bible.chapter(translationId, selectedBook.code, selectedChapter);
        int start = Math.max(0, verseIndex(continuousVerses, selectedVerse == null ? "1" : selectedVerse.number));
        applyVoice();
        tts.speak(currentChapterReference(), TextToSpeech.QUEUE_FLUSH, null, "titulo");
        for (int index = start; index < continuousVerses.size(); index++) {
            Models.Verse verse = continuousVerses.get(index);
            tts.speak(verse.number + ". " + verse.text, TextToSpeech.QUEUE_ADD, null, "continuo-" + index);
        }
        showVerses();
    }

    /** Acompanha o versículo lido e passa ao próximo capítulo no fim. */
    private void continueReading(String utteranceId) {
        if (!continuousActive || isFinishing() || isDestroyed() || bible == null) return;
        int index;
        try {
            index = Integer.parseInt(utteranceId.substring("continuo-".length()));
        } catch (NumberFormatException error) {
            return;
        }
        if (index >= 0 && index < continuousVerses.size()) {
            selectedVerse = continuousVerses.get(index);
            savePosition();
        }
        if (continuousStopAt > 0 && System.currentTimeMillis() >= continuousStopAt) {
            stopContinuousReading("O temporizador terminou. Leitura em voz alta encerrada.");
            return;
        }
        if (index < continuousVerses.size() - 1) return;
        if (continuousStopAtChapterEnd) {
            stopContinuousReading("Fim do capítulo. Leitura em voz alta encerrada.");
            return;
        }
        if (selectedChapter >= bible.chapterCount(translationId, selectedBook.code)) {
            stopContinuousReading("Fim do livro de " + selectedBook.name + ". Leitura em voz alta encerrada.");
            return;
        }
        selectedChapter++;
        selectVerse("1");
        savePosition();
        startContinuousReading();
    }

    private void stopContinuousReading(String message) {
        boolean wasActive = continuousActive;
        continuousActive = false;
        if (ttsReady) {
            try { tts.stop(); } catch (Exception ignored) { }
        }
        if (wasActive) {
            toast(message);
            if (!isFinishing() && !isDestroyed() && chapterSwipeEnabled) showVerses();
        }
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
        if ((requestCode == BACKUP_EXPORT || requestCode == BACKUP_IMPORT) && resultCode == RESULT_OK &&
                data != null && data.getData() != null) {
            handleBackupResult(requestCode, data.getData());
            return;
        }
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

    /** Cria tela textual rolável com foco inicial no conteúdo. */
    private void showTextScreen(String title, String text, Runnable back) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView body = paragraph(text);
        body.setTextIsSelectable(true);
        body.setFocusable(true);
        // Texto longo dentro de um ScrollView, que já traz a barra de rolagem pronta. Ligar a barra
        // direto num TextView criado por código fechava o app ao desenhar (ScrollBarDrawable nulo).
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setScrollbarFadingEnabled(false);
        scroll.addView(body);
        content.addView(scroll, fill());
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
        layout.setFitsSystemWindows(true);
        layout.setOrientation(LinearLayout.VERTICAL);
        // Papel de parede AGB em todas as telas, escurecido para o texto continuar legível.
        layout.setBackground(new WallpaperDrawable(getResources(), 165));
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
        // Azul do logo com borda dourada; o efeito de toque continua o padrão do Android.
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(0xE600285E);
        shape.setCornerRadius(dp(12));
        shape.setStroke(dp(2), getColor(R.color.agb_dourado));
        // A margem interna separa botões vizinhos sem mudar a área de toque.
        button.setBackground(new android.graphics.drawable.InsetDrawable(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x55F4C95D), shape, null), 0, dp(4), 0, dp(4)));
        button.setTextColor(0xFFFFFFFF);
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Bíblia AGB", text));
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

    /** Normaliza nomes para as pesquisas locais tolerarem acentos e maiúsculas. */
    private String normalizeForSearch(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }

    /** Só para Android 8 a 12: do 13 em diante, o OnBackInvokedCallback registrado no onCreate trata o voltar. */
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (backAction != null) backAction.run();
        else super.onBackPressed();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(Reminders.OPEN_PLANS, false) && bible != null) {
            openActivePlan();
        } else if (intent.getBooleanExtra(DevotionalNotifications.OPEN_DEVOTIONALS, false) && bible != null) {
            showOnlineDevotionals();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (bible != null) deliverPendingAi();
    }

    /** Se o aplicativo fechou na última vez, explica e permite copiar o relatório. */
    private void showLastCrashReport() {
        String report = CrashLog.consume(this);
        if (report.isEmpty()) return;
        new AlertDialog.Builder(this).setTitle("A Bíblia fechou inesperadamente")
                .setMessage("Na última vez, o aplicativo fechou por um erro. Seus dados estão preservados. " +
                        "Se quiser ajudar a corrigir, copie o relatório e envie para Diego.")
                .setPositiveButton("Copiar relatório", (dialog, which) -> copy(report))
                .setNeutralButton("Enviar por e-mail", (dialog, which) -> {
                    Uri uri = Uri.parse("mailto:diego@audiogames.com.br").buildUpon()
                            .appendQueryParameter("subject", "Relatório de erro da Bíblia AGB")
                            .appendQueryParameter("body", report).build();
                    try {
                        startActivity(new Intent(Intent.ACTION_SENDTO, uri));
                    } catch (Exception error) {
                        copy(report);
                    }
                })
                .setNegativeButton("Fechar", null).show();
    }

    @Override protected void onDestroy() {
        continuousActive = false;
        ttsReady = false;
        if (aiProgressDialog != null) {
            try { aiProgressDialog.dismiss(); } catch (Exception ignored) { }
            aiProgressDialog = null;
        }
        stopAndReleaseRadio();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        synchronized (this) {
            if (bible != null) bible.close();
        }
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
