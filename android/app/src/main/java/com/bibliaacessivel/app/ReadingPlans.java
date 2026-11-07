package com.bibliaacessivel.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mesmos 17 planos do Windows, gerados com os capítulos da tradução atual. */
final class ReadingPlans {
    private ReadingPlans() { }

    static final class Plan {
        final String id;
        final String name;
        final String description;
        final List<List<String>> readings;

        Plan(String id, String name, String description, List<List<String>> readings) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.readings = readings;
        }

        int days() {
            return readings.size();
        }

        List<String> day(int number) {
            return readings.get(Math.max(1, Math.min(days(), number)) - 1);
        }
    }

    /** Par livro e capítulo usado para distribuir as leituras. */
    static final class Chapter {
        final String book;
        final int number;

        Chapter(String book, int number) {
            this.book = book;
            this.number = number;
        }
    }

    /** Divide capítulos em dias equilibrados e junta sequências do mesmo livro, como no Windows. */
    static List<List<String>> distribute(List<Chapter> chapters, int days) {
        List<List<String>> result = new ArrayList<>();
        for (int day = 0; day < days; day++) {
            int start = (int) Math.round(day * (double) chapters.size() / days);
            int end = (int) Math.round((day + 1) * (double) chapters.size() / days);
            List<Chapter> portion = chapters.subList(start, end);
            List<String> readings = new ArrayList<>();
            int index = 0;
            while (index < portion.size()) {
                Chapter first = portion.get(index);
                int last = first.number;
                index++;
                while (index < portion.size() && portion.get(index).book.equals(first.book) &&
                        portion.get(index).number == last + 1) {
                    last = portion.get(index).number;
                    index++;
                }
                readings.add(first.number == last ? first.book + " " + first.number
                        : first.book + " " + first.number + "-" + last);
            }
            result.add(readings.isEmpty() ? Collections.singletonList("Dia de revisão") : readings);
        }
        return result;
    }

    static List<List<String>> thematic(int days, String... references) {
        List<List<String>> result = new ArrayList<>();
        for (int index = 0; index < days; index++) {
            result.add(Collections.singletonList(references[index % references.length]));
        }
        return result;
    }

    static Map<String, Plan> build(BibleRepository bible, String translationId) {
        List<Models.Book> books = bible.books(translationId);
        Map<String, Models.Book> byCode = new LinkedHashMap<>();
        List<Chapter> all = new ArrayList<>();
        List<Chapter> newTestament = new ArrayList<>();
        for (Models.Book book : books) {
            byCode.put(book.code, book);
            int count = bible.chapterCount(translationId, book.code);
            for (int chapter = 1; chapter <= count; chapter++) {
                Chapter item = new Chapter(book.name, chapter);
                all.add(item);
                if (book.number > 39) newTestament.add(item);
            }
        }
        java.util.function.Function<String[], List<Chapter>> chaptersFor = codes -> {
            List<Chapter> result = new ArrayList<>();
            for (String code : codes) {
                Models.Book book = byCode.get(code);
                if (book == null) continue;
                int count = bible.chapterCount(translationId, code);
                for (int chapter = 1; chapter <= count; chapter++) result.add(new Chapter(book.name, chapter));
            }
            return result;
        };
        String[] gospels = {"MAT", "MRK", "LUK", "JHN"};
        List<Plan> plans = Arrays.asList(
                new Plan("bible_365", "Bíblia inteira em 1 ano", "Leitura de toda a Bíblia em 365 dias.", distribute(all, 365)),
                new Plan("bible_180", "Bíblia inteira em 6 meses", "Leitura intensiva de toda a Bíblia em 180 dias.", distribute(all, 180)),
                new Plan("nt_90", "Novo Testamento em 90 dias", "Percurso completo do Novo Testamento.", distribute(newTestament, 90)),
                new Plan("nt_30", "Novo Testamento em 30 dias", "Percurso intensivo do Novo Testamento.", distribute(newTestament, 30)),
                new Plan("psalms_30", "Salmos em 30 dias", "Cinco salmos por dia.", distribute(chaptersFor.apply(new String[]{"PSA"}), 30)),
                new Plan("proverbs_31", "Provérbios em 31 dias", "Um capítulo de Provérbios por dia.", distribute(chaptersFor.apply(new String[]{"PRO"}), 31)),
                new Plan("gospels_30", "Evangelhos em 30 dias", "Mateus, Marcos, Lucas e João em trinta dias.", distribute(chaptersFor.apply(gospels), 30)),
                new Plan("john", "Evangelho de João", "Um capítulo do Evangelho de João por dia.", distribute(chaptersFor.apply(new String[]{"JHN"}), 21)),
                new Plan("four_gospels", "Os quatro Evangelhos", "Leitura sequencial dos quatro Evangelhos.", distribute(chaptersFor.apply(gospels), 45)),
                new Plan("life_jesus", "Vida de Jesus", "Passagens centrais do nascimento, ministério, morte e ressurreição de Jesus.",
                        thematic(30, "Lucas 2", "Mateus 3", "João 1", "Mateus 5", "Marcos 5", "Lucas 15", "João 11", "Mateus 26", "João 19", "João 20")),
                new Plan("paul_letters", "Cartas de Paulo", "Leitura das cartas tradicionalmente associadas a Paulo.",
                        distribute(chaptersFor.apply(new String[]{"ROM", "1CO", "2CO", "GAL", "EPH", "PHP", "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM"}), 60)),
                new Plan("women", "Mulheres da Bíblia", "Narrativas e textos relacionados a mulheres bíblicas.",
                        thematic(14, "Gênesis 16", "Gênesis 24", "Juízes 4", "Rute 1", "1 Samuel 1", "Ester 4", "Lucas 1", "João 4", "Atos 16")),
                new Plan("men", "Homens da Bíblia", "Narrativas de homens bíblicos e sua caminhada com Deus.",
                        thematic(14, "Gênesis 12", "Gênesis 37", "Êxodo 3", "1 Samuel 17", "1 Reis 18", "Daniel 6", "Mateus 14", "Atos 9")),
                new Plan("faith", "Fé", "Quatorze dias sobre fé e confiança.",
                        thematic(14, "Hebreus 11", "Romanos 4", "Marcos 5", "Tiago 2", "Salmos 37")),
                new Plan("prayer", "Oração", "Quatorze dias sobre oração.",
                        thematic(14, "Mateus 6", "Lucas 11", "Filipenses 4", "Salmos 5", "1 Samuel 1")),
                new Plan("promises", "Promessas de Deus", "Trinta dias de textos sobre fidelidade e promessas.",
                        thematic(30, "Gênesis 12", "Josué 1", "Salmos 23", "Isaías 41", "Jeremias 29", "João 14", "Romanos 8", "2 Coríntios 1")),
                new Plan("hard_psalms", "Salmos para momentos difíceis", "Trinta dias de consolo, oração e esperança nos Salmos.",
                        thematic(30, "Salmos 3", "Salmos 13", "Salmos 23", "Salmos 27", "Salmos 34", "Salmos 42", "Salmos 46", "Salmos 55", "Salmos 91", "Salmos 121"))
        );
        Map<String, Plan> result = new LinkedHashMap<>();
        for (Plan plan : plans) result.put(plan.id, plan);
        return result;
    }

    /** Expande "Êxodo 13-15" em capítulos que podem ser abertos um a um. */
    static List<String> expand(List<String> readings) {
        List<String> result = new ArrayList<>();
        for (String reading : readings) {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("^(.+?)\\s+(\\d+)(?:[-–](\\d+))?$").matcher(reading);
            if (!match.matches()) {
                result.add(reading);
                continue;
            }
            int start = Integer.parseInt(match.group(2));
            int end = match.group(3) == null ? start : Integer.parseInt(match.group(3));
            for (int chapter = start; chapter <= end; chapter++) result.add(match.group(1) + " " + chapter);
        }
        return result;
    }
}
