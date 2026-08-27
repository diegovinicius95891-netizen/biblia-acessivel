package com.bibliaacessivel.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Referência digitada com livro, capítulo e intervalo opcional de versículos. */
final class BibleReference {
    private static final Pattern PATTERN = Pattern.compile(
            "^\\s*(.+?)\\s+(\\d+)(?::(\\d+[a-z]?)(?:\\s*[-–]\\s*(\\d+[a-z]?))?)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    final String book;
    final int chapter;
    final String startVerse;
    final String endVerse;

    private BibleReference(String book, int chapter, String startVerse, String endVerse) {
        this.book = book;
        this.chapter = chapter;
        this.startVerse = startVerse;
        this.endVerse = endVerse;
    }

    /** Aceita exemplos como João 3:16, Romanos 8 e Salmos 23:1-6. */
    static BibleReference parse(String value) {
        Matcher match = PATTERN.matcher(value == null ? "" : value);
        if (!match.matches()) {
            throw new IllegalArgumentException("Use um formato como João 3:16, Romanos 8 ou Salmos 23:1-6.");
        }
        int chapter = Integer.parseInt(match.group(2));
        if (chapter < 1) throw new IllegalArgumentException("O capítulo deve ser maior que zero.");
        String start = match.group(3);
        String end = match.group(4);
        if (start != null && end != null && number(end) < number(start)) {
            throw new IllegalArgumentException("O fim do intervalo deve vir depois do início.");
        }
        return new BibleReference(match.group(1).trim(), chapter, start, end);
    }

    /** Extrai a parte numérica de itens que também podem possuir uma letra. */
    static int number(String verse) {
        Matcher matcher = Pattern.compile("^(\\d+)").matcher(verse);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
