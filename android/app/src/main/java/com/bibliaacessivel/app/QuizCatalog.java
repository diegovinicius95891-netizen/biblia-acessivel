package com.bibliaacessivel.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Lê o mesmo catálogo TSV de 298 perguntas utilizado pelo aplicativo Windows. */
final class QuizCatalog {
    static final String[] DIFFICULTIES = {"todas", "fácil", "médio", "difícil"};
    final List<Question> questions;
    final String[] categories;

    private QuizCatalog(List<Question> questions) {
        this.questions = Collections.unmodifiableList(questions);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add("todas");
        for (Question question : questions) values.add(question.category);
        categories = values.toArray(new String[0]);
    }

    /** Carrega o catálogo empacotado no APK e fecha o fluxo automaticamente. */
    static QuizCatalog load(Context context) throws Exception {
        try (InputStream input = context.getAssets().open("quiz_questions.tsv")) {
            return parse(input);
        }
    }

    /** Analisa TSV estrito; este método puro também permite teste unitário. */
    static QuizCatalog parse(InputStream input) throws Exception {
        List<Question> questions = new ArrayList<>();
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null || !header.startsWith("id\tdifficulty\tcategory\tquestion")) {
                throw new IllegalArgumentException("Cabeçalho inválido no quiz.");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 11 || !identifiers.add(fields[0])) {
                    throw new IllegalArgumentException("Pergunta duplicada ou incompleta.");
                }
                String[] answers = {fields[4], fields[5], fields[6], fields[7]};
                int correct = Integer.parseInt(fields[8]);
                if (correct < 0 || correct >= answers.length) {
                    throw new IllegalArgumentException("Resposta correta fora do intervalo.");
                }
                for (String field : fields) {
                    if (field.trim().isEmpty()) throw new IllegalArgumentException("Campo vazio no quiz.");
                }
                questions.add(new Question(fields[0], fields[1], fields[2], fields[3],
                        answers, correct, fields[9], fields[10]));
            }
        }
        if (questions.size() < 200) throw new IllegalArgumentException("Catálogo de quiz incompleto.");
        return new QuizCatalog(questions);
    }

    /** Retorna as perguntas que correspondem aos filtros selecionados. */
    List<Question> filtered(String difficulty, String category) {
        List<Question> result = new ArrayList<>();
        for (Question question : questions) {
            if (!"todas".equals(difficulty) && !difficulty.equals(question.difficulty)) continue;
            if (!"todas".equals(category) && !category.equals(question.category)) continue;
            result.add(question);
        }
        return result;
    }

    /** Pergunta imutável com identificador persistente para estatísticas. */
    static final class Question {
        final String id;
        final String difficulty;
        final String category;
        final String text;
        final String[] answers;
        final int correct;
        final String explanation;
        final String reference;

        Question(String id, String difficulty, String category, String text, String[] answers,
                 int correct, String explanation, String reference) {
            this.id = id;
            this.difficulty = difficulty;
            this.category = category;
            this.text = text;
            this.answers = answers;
            this.correct = correct;
            this.explanation = explanation;
            this.reference = reference;
        }

        @Override public String toString() {
            return text + ". Dificuldade: " + difficulty + ". Categoria: " + category + ".";
        }
    }
}
