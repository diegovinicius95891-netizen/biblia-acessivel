package com.bibliaacessivel.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Catálogo local de perguntas, sem rede, pontuação ou coleta de dados. */
final class QuizCatalog {
    static final String[] DIFFICULTIES = {"todas", "fácil", "médio", "difícil"};
    static final String[] CATEGORIES = {
            "todas", "Antigo Testamento", "Novo Testamento", "Jesus",
            "personagens", "livros da Bíblia", "perguntas gerais"
    };

    static final List<Question> QUESTIONS = Collections.unmodifiableList(Arrays.asList(
            q("fácil", "Antigo Testamento", "Quem construiu a arca?", a("Moisés", "Noé", "Davi", "Paulo"), 1, "Noé obedeceu à ordem de construir a arca.", "Gênesis 6"),
            q("fácil", "Antigo Testamento", "Qual mar se abriu para Israel sair do Egito?", a("Mar Morto", "Mar da Galileia", "Mar Vermelho", "Mediterrâneo"), 2, "Israel atravessou o mar em terra seca.", "Êxodo 14"),
            q("médio", "Antigo Testamento", "Qual profeta confrontou os profetas de Baal no Carmelo?", a("Isaías", "Elias", "Eliseu", "Jeremias"), 1, "Elias convocou o confronto no monte Carmelo.", "1 Reis 18"),
            q("médio", "Antigo Testamento", "Quem sucedeu Moisés na liderança de Israel?", a("Calebe", "Arão", "Josué", "Samuel"), 2, "Josué conduziu o povo na entrada em Canaã.", "Josué 1"),
            q("difícil", "Antigo Testamento", "Qual rei encontrou o Livro da Lei durante reformas no templo?", a("Ezequias", "Acaz", "Josias", "Jeroboão"), 2, "O livro foi encontrado durante o reinado de Josias.", "2 Reis 22"),
            q("difícil", "Antigo Testamento", "Qual profeta teve a visão de um vale de ossos secos?", a("Daniel", "Ezequiel", "Amós", "Oséias"), 1, "Ezequiel viu os ossos receberem vida.", "Ezequiel 37"),
            q("fácil", "Novo Testamento", "Quem batizou Jesus?", a("Pedro", "João Batista", "Tiago", "André"), 1, "João batizou Jesus no Jordão.", "Mateus 3"),
            q("fácil", "Novo Testamento", "Qual apóstolo negou Jesus três vezes?", a("João", "Tomé", "Pedro", "Filipe"), 2, "Pedro negou conhecer Jesus e depois se arrependeu.", "Lucas 22:54-62"),
            q("médio", "Novo Testamento", "Quem foi escolhido para ocupar o lugar de Judas?", a("Barnabé", "Matias", "Silas", "Marcos"), 1, "Matias foi contado com os onze apóstolos.", "Atos 1:26"),
            q("médio", "Novo Testamento", "Em qual ilha João recebeu a visão do Apocalipse?", a("Creta", "Chipre", "Malta", "Patmos"), 3, "João estava na ilha de Patmos.", "Apocalipse 1:9"),
            q("difícil", "Novo Testamento", "A quem foi dirigida a carta pessoal sobre Onésimo?", a("Tito", "Timóteo", "Filemom", "Silas"), 2, "Paulo escreveu a Filemom a respeito de Onésimo.", "Filemom 1"),
            q("difícil", "Novo Testamento", "Quem caiu da janela enquanto Paulo falava?", a("Êutico", "Trófimo", "Aristarco", "Ágabo"), 0, "Êutico caiu do terceiro andar e foi socorrido.", "Atos 20:7-12"),
            q("fácil", "Jesus", "Em qual cidade Jesus nasceu?", a("Nazaré", "Jerusalém", "Belém", "Roma"), 2, "Os relatos do nascimento situam Jesus em Belém.", "Mateus 2:1"),
            q("fácil", "Jesus", "Quantos apóstolos Jesus escolheu?", a("Sete", "Dez", "Doze", "Quarenta"), 2, "Jesus chamou doze para estarem com ele.", "Marcos 3:13-19"),
            q("médio", "Jesus", "Qual foi o primeiro sinal de Jesus narrado por João?", a("Multiplicação dos pães", "Água transformada em vinho", "Cura de um cego", "Ressurreição de Lázaro"), 1, "O sinal ocorreu numa festa de casamento em Caná.", "João 2:1-11"),
            q("médio", "Jesus", "Quem Jesus chamou para sair do túmulo?", a("Jairo", "Bartimeu", "Lázaro", "Zaqueu"), 2, "Jesus chamou Lázaro depois de quatro dias.", "João 11:43"),
            q("difícil", "Jesus", "Em qual aldeia Jesus encontrou dois discípulos após ressuscitar?", a("Betânia", "Emaús", "Caná", "Naim"), 1, "Dois discípulos caminhavam para Emaús.", "Lucas 24:13-35"),
            q("difícil", "Jesus", "Qual discípulo pediu: Senhor, mostra-nos o Pai?", a("Filipe", "André", "Mateus", "Tiago"), 0, "Filipe fez a pergunta durante o ensino de Jesus.", "João 14:8"),
            q("fácil", "personagens", "Quem enfrentou Golias?", a("Saul", "Samuel", "Davi", "Salomão"), 2, "Davi enfrentou o guerreiro filisteu.", "1 Samuel 17"),
            q("fácil", "personagens", "Quem foi lançado numa cova de leões?", a("Daniel", "José", "Elias", "Neemias"), 0, "Daniel foi preservado na cova dos leões.", "Daniel 6"),
            q("médio", "personagens", "Quem interpretou sonhos no Egito e se tornou governador?", a("Daniel", "José", "Neemias", "Esdras"), 1, "José interpretou os sonhos de Faraó.", "Gênesis 41"),
            q("médio", "personagens", "Qual mulher se tornou rainha e intercedeu por seu povo?", a("Rute", "Débora", "Ester", "Miriã"), 2, "Ester arriscou a vida ao procurar o rei.", "Ester 4-7"),
            q("difícil", "personagens", "O que abriu a prisão enquanto Paulo e Silas cantavam?", a("Um terremoto", "Barnabé", "Um anjo", "O governador"), 0, "Um terremoto abriu as portas e soltou as correntes.", "Atos 16:25-26"),
            q("difícil", "personagens", "Qual juiz fez um voto precipitado envolvendo sua filha?", a("Gideão", "Sansão", "Jefté", "Otniel"), 2, "Jefté fez um voto antes da batalha.", "Juízes 11"),
            q("fácil", "livros da Bíblia", "Qual é o primeiro livro da Bíblia?", a("Êxodo", "Gênesis", "Salmos", "Mateus"), 1, "Gênesis abre a ordem bíblica comum.", "Gênesis 1"),
            q("fácil", "livros da Bíblia", "Qual é o último livro do Novo Testamento?", a("Judas", "Hebreus", "Apocalipse", "Atos"), 2, "Apocalipse encerra o Novo Testamento.", "Apocalipse 1"),
            q("médio", "livros da Bíblia", "Qual livro vem depois de Atos?", a("Hebreus", "Romanos", "João", "Tiago"), 1, "Romanos segue Atos na ordem comum.", "Romanos 1"),
            q("médio", "livros da Bíblia", "Quantos Evangelhos há no Novo Testamento?", a("Três", "Quatro", "Cinco", "Doze"), 1, "Mateus, Marcos, Lucas e João são os quatro Evangelhos.", "Mateus 1"),
            q("difícil", "livros da Bíblia", "Qual livro não menciona explicitamente o nome de Deus?", a("Ester", "Rute", "Neemias", "Cantares"), 0, "Ester não menciona explicitamente o nome de Deus.", "Ester 1"),
            q("difícil", "livros da Bíblia", "Qual é a carta mais curta de Paulo em número de versículos?", a("Tito", "Filemom", "2 Timóteo", "Gálatas"), 1, "Filemom possui apenas um capítulo.", "Filemom 1"),
            q("fácil", "perguntas gerais", "Qual oração Jesus ensinou como modelo?", a("O Pai Nosso", "O cântico de Maria", "O Salmo 23", "A oração de Jabez"), 0, "Jesus apresentou o modelo conhecido como Pai Nosso.", "Mateus 6:9-13"),
            q("fácil", "perguntas gerais", "Qual é o maior mandamento segundo Jesus?", a("Amar a Deus", "Guardar o sábado", "Jejuar", "Dar ofertas"), 0, "Jesus ensinou a amar a Deus de todo o coração.", "Mateus 22:36-38"),
            q("médio", "perguntas gerais", "Onde os discípulos foram chamados cristãos pela primeira vez?", a("Éfeso", "Corinto", "Antioquia", "Jerusalém"), 2, "O nome aparece ligado à comunidade de Antioquia.", "Atos 11:26"),
            q("médio", "perguntas gerais", "Qual capítulo é conhecido como o capítulo do amor?", a("Romanos 8", "1 Coríntios 13", "Hebreus 11", "Salmos 119"), 1, "Paulo descreve as características do amor.", "1 Coríntios 13"),
            q("difícil", "perguntas gerais", "Qual casal morreu após mentir sobre uma propriedade?", a("Áquila e Priscila", "Herodes e Herodias", "Ananias e Safira", "Félix e Drusila"), 2, "Ananias e Safira mentiram sobre a oferta.", "Atos 5:1-11"),
            q("difícil", "perguntas gerais", "Qual igreja do Apocalipse foi chamada de morna?", a("Éfeso", "Esmirna", "Filadélfia", "Laodiceia"), 3, "A mensagem a Laodiceia repreende sua condição morna.", "Apocalipse 3:14-22")
    ));

    private QuizCatalog() {
        // Catálogo estático; não há estado a instanciar.
    }

    /** Retorna perguntas que correspondem aos dois filtros escolhidos. */
    static List<Question> filtered(String difficulty, String category) {
        List<Question> result = new ArrayList<>();
        for (Question question : QUESTIONS) {
            if (!"todas".equals(difficulty) && !difficulty.equals(question.difficulty)) continue;
            if (!"todas".equals(category) && !category.equals(question.category)) continue;
            result.add(question);
        }
        return result;
    }

    /** Atalho que mantém os quatro textos de resposta na ordem declarada. */
    private static String[] a(String first, String second, String third, String fourth) {
        return new String[]{first, second, third, fourth};
    }

    /** Atalho legível para declarar uma pergunta imutável. */
    private static Question q(String difficulty, String category, String text, String[] answers,
                              int correct, String explanation, String reference) {
        return new Question(difficulty, category, text, answers, correct, explanation, reference);
    }

    /** Pergunta com resposta correta, explicação curta e referência. */
    static final class Question {
        final String difficulty;
        final String category;
        final String text;
        final String[] answers;
        final int correct;
        final String explanation;
        final String reference;

        Question(String difficulty, String category, String text, String[] answers, int correct,
                 String explanation, String reference) {
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
