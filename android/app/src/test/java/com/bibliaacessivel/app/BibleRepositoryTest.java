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
}
