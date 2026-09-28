"use strict";

const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const STORAGE = "bibliaAcessivelWeb.";
const state = {
  manifest: null,
  translation: "bpm",
  book: "GEN",
  chapter: 1,
  verse: "1",
  bookData: null,
  bookCache: new Map(),
  hymns: null,
  quiz: null,
  searchRun: 0
};

const studyPrompts = {
  "summary-chapter": "Resuma este capítulo em até 320 palavras, destacando sua mensagem central e concluindo completamente.",
  "summary-book": "Resuma este livro em até 550 palavras, apresentando estrutura, temas principais e uma conclusão completa.",
  exegesis: "Faça uma exegese da passagem, considerando contexto literário, histórico e palavras importantes.",
  hermeneutics: "Explique como interpretar esta passagem com responsabilidade e quais princípios permanecem aplicáveis hoje.",
  history: "Explique o contexto histórico, cultural, geográfico e religioso desta passagem.",
  theology: "Mostre os principais temas teológicos desta passagem e sua relação com o restante da Bíblia.",
  interpretations: "Apresente as principais interpretações cristãs desta passagem, indicando convergências e diferenças sem impor uma tradição.",
  words: "Explique palavras e expressões importantes. Só mencione hebraico ou grego quando houver segurança e traduza os termos.",
  application: "Mostre aplicações pessoais e comunitárias possíveis, distinguindo o sentido original da aplicação atual.",
  outline: "Crie um esboço acessível para estudar ou ensinar esta passagem, com introdução, pontos principais, perguntas e conclusão.",
  free: ""
};

function readJson(key, fallback) {
  try { return JSON.parse(localStorage.getItem(STORAGE + key)) ?? fallback; }
  catch { return fallback; }
}

function writeJson(key, value) {
  localStorage.setItem(STORAGE + key, JSON.stringify(value));
}

function toast(message) {
  const node = $("#toast");
  node.textContent = message;
  node.classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => node.classList.remove("show"), 3200);
}

function normalize(value) {
  return value.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();
}

function translation() {
  return state.manifest.translations.find(item => item.id === state.translation) || state.manifest.translations[0];
}

function book() {
  return translation().books.find(item => item.code === state.book) || translation().books[0];
}

function verses() {
  return state.bookData?.chapters[state.chapter - 1] || [];
}

function selectedVerse() {
  return verses().find(item => item.number === state.verse) || verses()[0];
}

function reference() {
  const item = selectedVerse();
  return `${book().name} ${state.chapter}:${item?.number || "1"}`;
}

function savePosition() {
  writeJson("position", { translation: state.translation, book: state.book, chapter: state.chapter, verse: state.verse });
}

async function fetchJson(path) {
  const response = await fetch(path);
  if (!response.ok) throw new Error(`Não foi possível carregar ${path}.`);
  return response.json();
}

async function loadBook(translationId = state.translation, bookCode = state.book) {
  const cacheKey = `${translationId}/${bookCode}`;
  if (!state.bookCache.has(cacheKey)) {
    state.bookCache.set(cacheKey, fetchJson(`data/${translationId}/${bookCode}.json`));
  }
  return state.bookCache.get(cacheKey);
}

function populateTranslations() {
  const select = $("#translation-select");
  select.replaceChildren();
  for (const item of state.manifest.translations) {
    const option = new Option(`${item.name} — ${item.language}`, item.id);
    select.add(option);
  }
  select.value = state.translation;
}

function populateBooks() {
  const select = $("#book-select");
  select.replaceChildren();
  for (const testamentName of ["Antigo Testamento", "Novo Testamento"]) {
    const group = document.createElement("optgroup");
    group.label = testamentName;
    for (const item of translation().books.filter(candidate => candidate.testament === testamentName)) {
      group.append(new Option(item.name, item.code));
    }
    select.append(group);
  }
  select.value = state.book;
}

function populateChapters() {
  const select = $("#chapter-select");
  select.replaceChildren();
  for (let number = 1; number <= book().chapters; number += 1) {
    select.add(new Option(`Capítulo ${number}`, String(number)));
  }
  select.value = String(state.chapter);
}

function renderVerses(focusSelected = false) {
  const list = $("#verse-list");
  list.replaceChildren();
  for (const item of verses()) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = `${item.number}. ${item.text}`;
    button.dataset.verse = item.number;
    button.setAttribute("aria-current", item.number === state.verse ? "true" : "false");
    button.addEventListener("click", () => {
      state.verse = item.number;
      savePosition();
      renderVerses();
      renderReading(true);
    });
    list.append(button);
  }
  if (focusSelected) list.querySelector('[aria-current="true"]')?.focus();
}

function renderReading(focusText = false) {
  const item = selectedVerse();
  if (!item) return;
  state.verse = item.number;
  const ref = reference();
  $("#current-reference").textContent = `${ref} — ${translation().name}`;
  $("#selected-reference").textContent = ref;
  $("#selected-text").textContent = item.text;
  $("#previous-chapter").disabled = state.chapter <= 1;
  $("#next-chapter").disabled = state.chapter >= book().chapters;
  renderFavoriteButton();
  savePosition();
  if (focusText) $("#selected-text").focus();
}

async function changePassage({ translationChanged = false, bookChanged = false, focusVerse = false } = {}) {
  if (translationChanged) {
    state.translation = $("#translation-select").value;
    if (!translation().books.some(item => item.code === state.book)) state.book = translation().books[0].code;
    populateBooks();
  }
  if (bookChanged) state.book = $("#book-select").value;
  state.chapter = Math.min(Math.max(1, state.chapter), book().chapters);
  state.bookData = await loadBook();
  const available = verses();
  if (!available.some(item => item.number === state.verse)) state.verse = available[0]?.number || "1";
  populateChapters();
  $("#chapter-select").value = String(state.chapter);
  renderVerses(focusVerse);
  renderReading();
}

async function navigateTo(saved) {
  if (!state.manifest.translations.some(item => item.id === saved.translation)) return;
  state.translation = saved.translation;
  populateTranslations();
  populateBooks();
  if (translation().books.some(item => item.code === saved.book)) state.book = saved.book;
  $("#book-select").value = state.book;
  state.chapter = Math.min(Math.max(1, Number(saved.chapter) || 1), book().chapters);
  state.verse = String(saved.verse || "1");
  await changePassage();
  showView("reading");
  $("#selected-text").focus();
}

function showView(name) {
  $$(".view").forEach(view => { view.hidden = view.id !== `view-${name}`; view.classList.toggle("active", !view.hidden); });
  $$(".section-nav button").forEach(button => {
    if (button.dataset.view === name) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  location.hash = name;
  const heading = $(`#view-${name} h2`);
  heading?.setAttribute("tabindex", "-1");
  heading?.focus();
  if (name === "hymnal") loadHymns();
  if (name === "quiz") loadQuiz();
  if (name === "more") renderSavedItems();
  if (name === "studies") updateStudyQuestion(false);
}

async function changeChapter(delta) {
  const target = state.chapter + delta;
  if (target < 1 || target > book().chapters) return;
  state.chapter = target;
  state.verse = "1";
  await changePassage({ focusVerse: true });
  toast(`Capítulo ${state.chapter}.`);
}

function favorites() { return readJson("favorites", []); }
function favoriteKey(item) { return `${item.translation}/${item.book}/${item.chapter}/${item.verse}`; }

function renderFavoriteButton() {
  const current = { translation: state.translation, book: state.book, chapter: state.chapter, verse: state.verse };
  const marked = favorites().some(item => favoriteKey(item) === favoriteKey(current));
  $("#favorite-verse").textContent = marked ? "Remover favorito" : "Favoritar";
  $("#favorite-verse").setAttribute("aria-pressed", String(marked));
}

function toggleFavorite() {
  const list = favorites();
  const current = { translation: state.translation, book: state.book, chapter: state.chapter, verse: state.verse, reference: reference(), text: selectedVerse().text };
  const index = list.findIndex(item => favoriteKey(item) === favoriteKey(current));
  if (index >= 0) { list.splice(index, 1); toast("Favorito removido."); }
  else { list.unshift(current); toast("Versículo adicionado aos favoritos."); }
  writeJson("favorites", list);
  renderFavoriteButton();
}

function openNote() {
  $("#note-reference").textContent = `${reference()} — ${selectedVerse().text}`;
  $("#note-text").value = "";
  $("#note-dialog").showModal();
  $("#note-text").focus();
}

function saveNote(event) {
  event.preventDefault();
  const text = $("#note-text").value.trim();
  if (!text) { $("#note-text").setCustomValidity("Digite sua anotação."); $("#note-text").reportValidity(); return; }
  $("#note-text").setCustomValidity("");
  const list = readJson("notes", []);
  list.unshift({ id: Date.now(), translation: state.translation, book: state.book, chapter: state.chapter, verse: state.verse, reference: reference(), verseText: selectedVerse().text, text, date: new Date().toISOString() });
  writeJson("notes", list);
  $("#note-dialog").close();
  toast("Anotação salva neste navegador.");
}

function renderSavedItems() {
  const favoriteList = $("#favorites-list");
  favoriteList.replaceChildren();
  const savedFavorites = favorites();
  if (!savedFavorites.length) favoriteList.append(Object.assign(document.createElement("li"), { textContent: "Nenhum favorito." }));
  for (const item of savedFavorites) {
    const li = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = `${item.reference}. ${item.text}`;
    button.addEventListener("click", () => navigateTo(item));
    li.append(button); favoriteList.append(li);
  }
  const noteList = $("#notes-list");
  noteList.replaceChildren();
  const notes = readJson("notes", []);
  if (!notes.length) noteList.append(Object.assign(document.createElement("li"), { textContent: "Nenhuma anotação." }));
  for (const item of notes) {
    const li = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = `${item.reference}: ${item.text}`;
    button.addEventListener("click", () => navigateTo(item));
    li.append(button); noteList.append(li);
  }
}

async function searchBible(event) {
  event.preventDefault();
  const query = normalize($("#search-query").value.trim());
  if (!query) return;
  const run = ++state.searchRun;
  const results = [];
  const output = $("#search-results");
  output.replaceChildren();
  for (let index = 0; index < translation().books.length && results.length < 100; index += 1) {
    if (run !== state.searchRun) return;
    const currentBook = translation().books[index];
    $("#search-progress").textContent = `Pesquisando em ${currentBook.name}. ${index + 1} de ${translation().books.length}.`;
    const data = await loadBook(state.translation, currentBook.code);
    data.chapters.forEach((chapter, chapterIndex) => chapter.forEach(verse => {
      if (results.length < 100 && normalize(verse.text).includes(query)) results.push({ translation: state.translation, book: currentBook.code, chapter: chapterIndex + 1, verse: verse.number, reference: `${currentBook.name} ${chapterIndex + 1}:${verse.number}`, text: verse.text });
    }));
  }
  $("#search-progress").textContent = results.length === 100 ? "Mostrando os primeiros 100 resultados." : `${results.length} resultados.`;
  for (const item of results) {
    const li = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button"; button.textContent = `${item.reference}. ${item.text}`;
    button.addEventListener("click", () => navigateTo(item));
    li.append(button); output.append(li);
  }
}

async function loadHymns() {
  if (!state.hymns) state.hymns = await fetchJson("data/harpa.json");
  renderHymns();
}

function renderHymns() {
  const query = normalize($("#hymn-query").value.trim());
  const matches = state.hymns.filter(item => !query || String(item.numero) === query || normalize(item.titulo).includes(query) || normalize(item.letra).includes(query)).slice(0, 100);
  const list = $("#hymn-results"); list.replaceChildren();
  for (const hymn of matches) {
    const li = document.createElement("li"); const button = document.createElement("button");
    button.type = "button"; button.textContent = `${hymn.numero}. ${hymn.titulo}`;
    button.addEventListener("click", () => { $("#hymn-content").textContent = `${hymn.numero}. ${hymn.titulo}\n\n${hymn.letra}`; $("#hymn-content").focus(); });
    li.append(button); list.append(li);
  }
}

async function loadQuiz() {
  if (!state.quiz) state.quiz = await fetchJson("data/quiz.json");
  nextQuestion();
}

function quizAttempts() { return readJson("quizAttempts", {}); }

function quizSummary() {
  const attempts = Object.values(quizAttempts());
  const correct = attempts.filter(Boolean).length;
  $("#quiz-status").textContent = `${attempts.length} respondidas · ${correct} acertos · ${attempts.length - correct} erros`;
}

function nextQuestion() {
  if (!state.quiz) return;
  quizSummary();
  const attempts = quizAttempts();
  const available = state.quiz.filter(item => !(item.id in attempts));
  if (!available.length) {
    $("#quiz-meta").textContent = "Catálogo concluído";
    $("#quiz-question").textContent = "Você respondeu todas as perguntas disponíveis.";
    $("#quiz-answers").replaceChildren(); $("#quiz-feedback").textContent = "Seu resultado permanece salvo neste navegador."; $("#next-question").disabled = true; return;
  }
  $("#next-question").disabled = false;
  const question = available[Math.floor(Math.random() * available.length)];
  $("#quiz-meta").textContent = `${question.difficulty} · ${question.category} · ${question.reference}`;
  $("#quiz-question").textContent = question.question;
  $("#quiz-feedback").textContent = "";
  const answers = $("#quiz-answers"); answers.replaceChildren();
  question.answers.forEach((answer, index) => {
    const button = document.createElement("button"); button.type = "button"; button.textContent = answer;
    button.addEventListener("click", () => answerQuestion(question, index)); answers.append(button);
  });
}

function answerQuestion(question, selected) {
  const attempts = quizAttempts();
  if (question.id in attempts) return;
  const correct = selected === question.correct; attempts[question.id] = correct; writeJson("quizAttempts", attempts);
  $$("#quiz-answers button").forEach((button, index) => { button.disabled = true; if (index === question.correct) button.classList.add("correct"); else if (index === selected) button.classList.add("wrong"); });
  $("#quiz-feedback").textContent = `${correct ? "Resposta correta." : "Resposta incorreta."} ${question.explanation} Referência: ${question.reference}.`;
  quizSummary();
}

function updateStudyQuestion(force = true) {
  const type = $("#study-type").value;
  if (force || !$("#study-question").value.trim()) $("#study-question").value = studyPrompts[type];
}

function apiKey() { return sessionStorage.getItem(STORAGE + "apiKey") || localStorage.getItem(STORAGE + "apiKey") || ""; }

function saveSettings() {
  const key = $("#api-key").value.trim();
  localStorage.removeItem(STORAGE + "apiKey"); sessionStorage.removeItem(STORAGE + "apiKey");
  if (key) ($("#remember-key").checked ? localStorage : sessionStorage).setItem(STORAGE + "apiKey", key);
  localStorage.setItem(STORAGE + "model", $("#ai-model").value);
  toast("Configurações salvas.");
}

async function askAi() {
  const key = apiKey();
  if (!key) { showView("more"); $("#api-key").focus(); toast("Informe sua chave do Google Gemini."); return; }
  const question = $("#study-question").value.trim();
  if (!question) { $("#study-question").focus(); toast("Digite uma pergunta."); return; }
  const type = $("#study-type").value;
  const currentBook = state.bookData;
  const text = type === "summary-book" ? currentBook.chapters.map((chapter, index) => `Capítulo ${index + 1}\n${chapter.map(verse => `${verse.number}. ${verse.text}`).join(" ")}`).join("\n\n") : verses().map(verse => `${verse.number}. ${verse.text}`).join(" ");
  const instruction = `Atue como assistente de estudo bíblico cuidadoso, não como autoridade pastoral. Passagem atual: ${reference()}. Pergunta: ${question} Baseie-se no texto fornecido, diferencie texto, contexto e interpretação, indique leituras cristãs diferentes quando relevante, não invente citações e conclua completamente.`;
  const button = $("#ask-ai"); button.disabled = true; button.textContent = "Gerando resposta…";
  const output = $("#ai-result"); output.textContent = "Aguarde. O estudo está sendo preparado."; output.setAttribute("aria-busy", "true");
  try {
    const model = localStorage.getItem(STORAGE + "model") || "gemini-2.5-flash-lite";
    const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, { method: "POST", headers: { "Content-Type": "application/json", "x-goog-api-key": key }, body: JSON.stringify({ system_instruction: { parts: [{ text: "Responda em português do Brasil, em texto simples e sem Markdown. Avise que a resposta é gerada por IA e pode conter erros." }] }, contents: [{ role: "user", parts: [{ text: `Tarefa: ${instruction}\n\nTexto bíblico:\n${text}` }] }], generationConfig: { maxOutputTokens: 3072, temperature: .3 } }) });
    if (!response.ok) throw new Error(`O Google Gemini devolveu o erro ${response.status}.`);
    const payload = await response.json();
    if (payload.candidates?.[0]?.finishReason === "MAX_TOKENS") throw new Error("A resposta atingiu o limite. Tente uma pergunta mais específica.");
    const result = payload.candidates?.[0]?.content?.parts?.map(part => part.text || "").join("\n\n").replace(/[\*`]/g, "").trim();
    if (!result) throw new Error("A IA não devolveu uma resposta.");
    output.textContent = result; output.focus();
  } catch (error) { output.textContent = `Não foi possível gerar o estudo. ${error.message}`; output.focus(); }
  finally { output.removeAttribute("aria-busy"); button.disabled = false; button.textContent = "Perguntar sobre a passagem atual"; }
}

function initializeSettings() {
  $("#api-key").value = apiKey();
  $("#remember-key").checked = Boolean(localStorage.getItem(STORAGE + "apiKey"));
  $("#ai-model").value = localStorage.getItem(STORAGE + "model") || "gemini-2.5-flash-lite";
  const scale = Number(localStorage.getItem(STORAGE + "fontScale") || 100);
  $("#font-size").value = String(scale); document.documentElement.style.setProperty("--base-scale", String(scale / 100));
  const contrast = localStorage.getItem(STORAGE + "contrast") === "true"; $("#high-contrast").checked = contrast; document.body.classList.toggle("high-contrast", contrast);
}

function bindEvents() {
  $$(".section-nav button").forEach(button => button.addEventListener("click", () => showView(button.dataset.view)));
  $("#translation-select").addEventListener("change", async () => { state.verse = "1"; await changePassage({ translationChanged: true }); });
  $("#book-select").addEventListener("change", async () => { state.chapter = 1; state.verse = "1"; await changePassage({ bookChanged: true }); });
  $("#chapter-select").addEventListener("change", async () => { state.chapter = Number($("#chapter-select").value); state.verse = "1"; await changePassage({ focusVerse: true }); });
  $("#previous-chapter").addEventListener("click", () => changeChapter(-1)); $("#next-chapter").addEventListener("click", () => changeChapter(1));
  $("#copy-verse").addEventListener("click", async () => { await navigator.clipboard.writeText(`${reference()} — ${selectedVerse().text}`); toast("Versículo copiado."); });
  $("#favorite-verse").addEventListener("click", toggleFavorite); $("#note-verse").addEventListener("click", openNote); $("#save-note").addEventListener("click", saveNote);
  $("#study-verse").addEventListener("click", () => { $("#study-type").value = "exegesis"; updateStudyQuestion(); showView("studies"); });
  $("#read-aloud").addEventListener("click", () => { speechSynthesis.cancel(); speechSynthesis.speak(new SpeechSynthesisUtterance(`${reference()}. ${selectedVerse().text}`)); });
  $("#stop-speaking").addEventListener("click", () => speechSynthesis.cancel());
  $("#search-form").addEventListener("submit", searchBible); $("#search-hymn").addEventListener("click", renderHymns); $("#hymn-query").addEventListener("search", renderHymns);
  $("#next-question").addEventListener("click", nextQuestion); $("#study-type").addEventListener("change", () => updateStudyQuestion(true)); $("#ask-ai").addEventListener("click", askAi);
  $("#save-settings").addEventListener("click", saveSettings);
  $("#font-size").addEventListener("input", event => { const value = Number(event.target.value); document.documentElement.style.setProperty("--base-scale", String(value / 100)); localStorage.setItem(STORAGE + "fontScale", String(value)); });
  $("#high-contrast").addEventListener("change", event => { document.body.classList.toggle("high-contrast", event.target.checked); localStorage.setItem(STORAGE + "contrast", String(event.target.checked)); });
  addEventListener("hashchange", () => { const view = location.hash.slice(1); if ($(`#view-${view}`)) showView(view); });
}

async function start() {
  try {
    state.manifest = await fetchJson("data/manifest.json");
    const saved = readJson("position", {});
    if (state.manifest.translations.some(item => item.id === saved.translation)) state.translation = saved.translation;
    if (translation().books.some(item => item.code === saved.book)) state.book = saved.book;
    state.chapter = Math.min(Math.max(1, Number(saved.chapter) || 1), book().chapters);
    state.verse = String(saved.verse || "1");
    populateTranslations(); populateBooks(); populateChapters();
    state.bookData = await loadBook();
    if (!verses().some(item => item.number === state.verse)) state.verse = verses()[0]?.number || "1";
    renderVerses(); renderReading(); initializeSettings(); bindEvents();
    const initialView = location.hash.slice(1); showView($(`#view-${initialView}`) ? initialView : "reading");
    if ("serviceWorker" in navigator && location.protocol.startsWith("http")) navigator.serviceWorker.register("service-worker.js");
  } catch (error) {
    $("#current-reference").textContent = "Falha ao carregar";
    $("#selected-text").textContent = `Não foi possível abrir a Bíblia. ${error.message}`;
  }
}

start();
