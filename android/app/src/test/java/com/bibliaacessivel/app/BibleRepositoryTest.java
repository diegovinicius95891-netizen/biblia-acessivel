package com.bibliaacessivel.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Testa regras puras usadas para resolver livros digitados em português. */
public final class BibleRepositoryTest {
    @Test public void normalizationIgnoresAccentsSpacesAndCase() {
        assertEquals("joao", BibleRepository.normalize(" João "));
        assertEquals("1corintios", BibleRepository.normalize("1 Coríntios"));
        assertEquals("genesis", BibleRepository.normalize("GÊNESIS"));
    }

    @Test public void spokenOrdinalsAndLetterNamesUseTheSameBookKey() {
        assertEquals("2pedro", BibleRepository.normalizeBookQuery("segunda Pedro"));
        assertEquals("2pedro", BibleRepository.normalizeBookQuery("segundo Pedro"));
        assertEquals("2pedro", BibleRepository.normalizeBookQuery("2ª Pedro"));
        assertEquals("2pedro", BibleRepository.normalizeBookQuery("II Pedro"));
        assertEquals("2pedro", BibleRepository.normalizeBookQuery("segunda carta de Pedro"));
        assertEquals("2corintios", BibleRepository.normalizeBookQuery("segunda carta aos Coríntios"));
        assertEquals("1samuel", BibleRepository.normalizeBookQuery("primeiro livro de Samuel"));
        assertEquals("2reis", BibleRepository.normalizeBookQuery("segunda de Reis"));
    }

    @Test public void numberedBookFamilyAllowsAnAccessibleFallback() {
        assertEquals("pedro", BibleRepository.numberedBookFamily(
                BibleRepository.normalizeBookQuery("terceira Pedro")));
        assertEquals("pedro", BibleRepository.numberedBookFamily(
                BibleRepository.normalizeBookQuery("1 Pedro")));
    }

    @Test public void everyNumberedBookFamilySupportsGenericAndOrdinalSearches() {
        List<Models.Book> books = numberedBooks();
        assertEquals("1 Samuel|2 Samuel", names(BibleRepository.matchBookCandidates(books, "Samuel")));
        assertEquals("1 Reis|2 Reis", names(BibleRepository.matchBookCandidates(books, "Reis")));
        assertEquals("1 Crônicas|2 Crônicas", names(BibleRepository.matchBookCandidates(books, "Crônicas")));
        assertEquals("1 Coríntios|2 Coríntios", names(BibleRepository.matchBookCandidates(books, "Coríntios")));
        assertEquals("1 Tessalonicenses|2 Tessalonicenses",
                names(BibleRepository.matchBookCandidates(books, "Tessalonicenses")));
        assertEquals("1 Timóteo|2 Timóteo", names(BibleRepository.matchBookCandidates(books, "Timóteo")));
        assertEquals("1 Pedro|2 Pedro", names(BibleRepository.matchBookCandidates(books, "Pedro")));
        assertEquals("João|1 João|2 João|3 João", names(BibleRepository.matchBookCandidates(books, "João")));
        assertEquals("João", names(BibleRepository.matchBookCandidates(books, "Evangelho de João")));
        assertEquals("2 Coríntios", names(BibleRepository.matchBookCandidates(
                books, "segunda carta aos Coríntios")));
        assertEquals("1 Samuel", names(BibleRepository.matchBookCandidates(
                books, "primeiro livro de Samuel")));
    }

    private static List<Models.Book> numberedBooks() {
        String[] names = {"1 Samuel", "2 Samuel", "1 Reis", "2 Reis", "1 Crônicas", "2 Crônicas",
                "1 Coríntios", "2 Coríntios", "1 Tessalonicenses", "2 Tessalonicenses",
                "1 Timóteo", "2 Timóteo", "1 Pedro", "2 Pedro", "João", "1 João", "2 João", "3 João"};
        List<Models.Book> result = new ArrayList<>();
        for (int index = 0; index < names.length; index++) {
            result.add(new Models.Book(index + 1, "B" + index, names[index]));
        }
        return result;
    }

    private static String names(List<Models.Book> books) {
        StringBuilder result = new StringBuilder();
        for (Models.Book book : books) {
            if (result.length() > 0) result.append('|');
            result.append(book.name);
        }
        return result.toString();
    }
}
