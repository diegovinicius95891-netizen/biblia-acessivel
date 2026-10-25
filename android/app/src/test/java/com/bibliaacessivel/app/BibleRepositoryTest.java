package com.bibliaacessivel.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

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
    }

    @Test public void numberedBookFamilyAllowsAnAccessibleFallback() {
        assertEquals("pedro", BibleRepository.numberedBookFamily(
                BibleRepository.normalizeBookQuery("terceira Pedro")));
        assertEquals("pedro", BibleRepository.numberedBookFamily(
                BibleRepository.normalizeBookQuery("1 Pedro")));
    }
}
