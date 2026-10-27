package com.bibliaacessivel.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Catálogo estável de estudos oferecidos pelo Teólogo de IA. */
final class AiStudyCatalog {
    private static final String[] STUDY_ANGLE_LABELS = {
            "visão bíblica", "textos principais", "Antigo e Novo Testamento", "contexto histórico",
            "teologia bíblica", "interpretações cristãs", "aplicação prática", "erros comuns",
            "roteiro de estudo", "perguntas para reflexão"
    };
    private static final String[] STUDY_ANGLE_PROMPTS = {
            "Explique o que a Bíblia ensina sobre %s, citando referências relevantes e respeitando o contexto.",
            "Apresente e explique os principais textos bíblicos relacionados a %s.",
            "Mostre como %s aparece no Antigo e no Novo Testamento e como as duas partes se relacionam.",
            "Explique %s considerando os contextos históricos e culturais dos textos bíblicos relevantes.",
            "Desenvolva uma teologia bíblica de %s, acompanhando o tema ao longo das Escrituras.",
            "Apresente com equilíbrio as principais interpretações cristãs sobre %s, indicando concordâncias e diferenças.",
            "Explique como aplicar com responsabilidade o ensino bíblico sobre %s na vida atual.",
            "Mostre erros comuns ao ensinar ou aplicar %s e como evitá-los à luz do contexto bíblico.",
            "Crie um roteiro de estudo bíblico sobre %s, com introdução, passagens, perguntas e conclusão.",
            "Prepare uma explicação bíblica de %s seguida de perguntas pessoais e comunitárias para reflexão."
    };
    private static List<Topic> studyThemes;
    static final List<Topic> TOPICS = Collections.unmodifiableList(Arrays.asList(
            new Topic("Exegese do texto",
                    "Faça uma exegese desta passagem, considerando contexto literário, histórico e palavras importantes."),
            new Topic("Hermenêutica",
                    "Explique como interpretar esta passagem com responsabilidade e quais princípios permanecem aplicáveis hoje."),
            new Topic("Contexto histórico e cultural",
                    "Explique o contexto histórico, cultural, geográfico e religioso desta passagem."),
            new Topic("Teologia bíblica",
                    "Mostre os principais temas teológicos desta passagem e sua relação com o restante da Bíblia."),
            new Topic("Comparar interpretações",
                    "Apresente as principais interpretações cristãs desta passagem, indicando convergências e diferenças sem impor uma tradição."),
            new Topic("Palavras e expressões",
                    "Explique as palavras e expressões importantes desta passagem. Só mencione hebraico ou grego quando houver segurança e traduza os termos."),
            new Topic("Aplicação prática",
                    "Mostre aplicações pessoais e comunitárias possíveis, distinguindo claramente o sentido original da aplicação atual."),
            new Topic("Esboço para estudo",
                    "Crie um esboço acessível para estudar ou ensinar esta passagem, com introdução, pontos principais, perguntas e conclusão."),
            new Topic("Pergunta livre", "")
    ));

    private AiStudyCatalog() {
        // Classe utilitária: o catálogo é imutável e não possui estado.
    }

    /** Lê 200 assuntos compartilhados e os expande em 2.000 estudos. */
    static synchronized List<Topic> studyThemes(Context context) {
        if (studyThemes != null) return studyThemes;
        List<Topic> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("ai_study_topics.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String subject = line.trim();
                if (subject.isEmpty() || subject.startsWith("#")) continue;
                for (int index = 0; index < STUDY_ANGLE_LABELS.length; index++) {
                    result.add(new Topic(
                            subject + " — " + STUDY_ANGLE_LABELS[index],
                            String.format(Locale.ROOT, STUDY_ANGLE_PROMPTS[index], subject)));
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("Não foi possível preparar os temas para estudo.", error);
        }
        studyThemes = Collections.unmodifiableList(result);
        return studyThemes;
    }

    /** Pesquisa por assunto ou abordagem ignorando acentos e maiúsculas. */
    static List<Topic> searchThemes(Context context, String query) {
        List<Topic> all = studyThemes(context);
        String wanted = normalize(query);
        if (wanted.isEmpty()) return new ArrayList<>(all);
        String[] words = wanted.split("\\s+");
        List<Topic> result = new ArrayList<>();
        for (Topic topic : all) {
            String searchable = normalize(topic.label);
            boolean matches = true;
            for (String word : words) {
                if (!searchable.contains(word)) { matches = false; break; }
            }
            if (matches) result.add(topic);
        }
        return result;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }

    /** Cria uma instrução consistente sem apresentar a IA como autoridade religiosa. */
    static String instruction(Topic topic, String question, String reference) {
        String cleanQuestion = question == null ? "" : question.trim();
        if (cleanQuestion.isEmpty()) {
            throw new IllegalArgumentException("Digite uma pergunta para o Teólogo de IA.");
        }
        return "Atue como um assistente de estudo bíblico cuidadoso, não como autoridade pastoral. " +
                "Categoria: " + topic.label + ". Passagem: " + reference + ". " +
                "Pergunta do usuário: " + cleanQuestion + " " +
                "Baseie a resposta no texto fornecido, diferencie texto, contexto e interpretação, " +
                "indique quando existirem leituras cristãs diferentes, não invente citações e responda " +
                "em até 650 palavras, com uma conclusão completa.";
    }

    /** Tema selecionável com pergunta inicial editável. */
    static final class Topic {
        final String label;
        final String suggestedQuestion;

        Topic(String label, String suggestedQuestion) {
            this.label = label;
            this.suggestedQuestion = suggestedQuestion;
        }

        @Override public String toString() {
            return label;
        }
    }
}
