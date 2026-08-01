package com.bibliaacessivel.app;

import java.util.Objects;

/** Modelos imutáveis compartilhados pelas telas e bancos do aplicativo. */
final class Models {
    private Models() {
        // Impede instanciação de uma classe que serve apenas como namespace.
    }

    /** Identifica uma tradução distribuída no banco bíblico. */
    static final class Translation {
        final String id;
        final String name;

        Translation(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override public String toString() { return name; }
    }

    /** Representa um livro com número canônico e código estável. */
    static final class Book {
        final int number;
        final String code;
        final String name;

        Book(int number, String code, String name) {
            this.number = number;
            this.code = code;
            this.name = name;
        }

        @Override public String toString() { return name; }
    }

    /** Mantém número e texto de uma linha bíblica. */
    static final class Verse {
        final String number;
        final String text;

        Verse(String number, String text) {
            this.number = number;
            this.text = text;
        }

        @Override public String toString() { return number + ". " + text; }
    }

    /** Registro pessoal exibido por dia e por título. */
    static final class Note {
        final long id;
        final String bookCode;
        final String bookName;
        final int chapter;
        final String verse;
        final String verseText;
        final String title;
        final String body;
        final String createdAt;

        Note(long id, String bookCode, String bookName, int chapter, String verse,
             String verseText, String title, String body, String createdAt) {
            this.id = id;
            this.bookCode = bookCode;
            this.bookName = bookName;
            this.chapter = chapter;
            this.verse = verse;
            this.verseText = verseText;
            this.title = title;
            this.body = body;
            this.createdAt = createdAt;
        }

        String reference() { return bookName + " " + chapter + ":" + verse; }

        @Override public boolean equals(Object other) {
            return other instanceof Note && ((Note) other).id == id;
        }

        @Override public int hashCode() { return Objects.hash(id); }
    }
}
