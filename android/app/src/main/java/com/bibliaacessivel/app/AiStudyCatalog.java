package com.bibliaacessivel.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Catálogo estável de estudos oferecidos pelo Teólogo de IA. */
final class AiStudyCatalog {
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
