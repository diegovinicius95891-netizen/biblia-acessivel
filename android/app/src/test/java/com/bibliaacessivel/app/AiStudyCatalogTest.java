package com.bibliaacessivel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** Garante que os estudos prontos e a pergunta livre permaneçam utilizáveis. */
public final class AiStudyCatalogTest {
    @Test public void exposesDiverseStudyTopicsAndFreeQuestion() {
        assertEquals(9, AiStudyCatalog.TOPICS.size());
        assertEquals("Exegese do texto", AiStudyCatalog.TOPICS.get(0).label);
        assertEquals("Hermenêutica", AiStudyCatalog.TOPICS.get(1).label);
        assertEquals("Pergunta livre", AiStudyCatalog.TOPICS.get(8).label);
        assertEquals("", AiStudyCatalog.TOPICS.get(8).suggestedQuestion);
    }

    @Test public void instructionIncludesCategoryReferenceAndQuestion() {
        String instruction = AiStudyCatalog.instruction(
                AiStudyCatalog.TOPICS.get(0), "Qual é o contexto?", "João 3:16");
        assertTrue(instruction.contains("Exegese do texto"));
        assertTrue(instruction.contains("João 3:16"));
        assertTrue(instruction.contains("Qual é o contexto?"));
        assertTrue(instruction.contains("leituras cristãs diferentes"));
    }

    @Test public void rejectsEmptyQuestion() {
        assertThrows(IllegalArgumentException.class, () -> AiStudyCatalog.instruction(
                AiStudyCatalog.TOPICS.get(8), "   ", "Salmos 23:1"));
    }
}
