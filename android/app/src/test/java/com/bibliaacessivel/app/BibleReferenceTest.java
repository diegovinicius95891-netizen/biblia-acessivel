package com.bibliaacessivel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Testa referências de capítulo, versículo e intervalo usadas no Modo culto. */
public final class BibleReferenceTest {
    @Test public void parsesChapterVerseAndRange() {
        BibleReference reference = BibleReference.parse("Salmos 23:1-6");
        assertEquals("Salmos", reference.book);
        assertEquals(23, reference.chapter);
        assertEquals("1", reference.startVerse);
        assertEquals("6", reference.endVerse);
    }

    @Test public void acceptsWholeChapter() {
        BibleReference reference = BibleReference.parse("Romanos 8");
        assertEquals("Romanos", reference.book);
        assertEquals(8, reference.chapter);
        assertNull(reference.startVerse);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsReversedRange() {
        BibleReference.parse("João 3:20-16");
    }
}
