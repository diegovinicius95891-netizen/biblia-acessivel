package com.bibliaacessivel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/** Confere no Android o mesmo catálogo de estudo distribuído ao Windows. */
public final class QuizCatalogTest {
    /** Carrega o TSV adicionado ao classpath de teste pelo Gradle. */
    private QuizCatalog catalog() throws Exception {
        InputStream input = getClass().getClassLoader().getResourceAsStream("quiz_questions.tsv");
        assertNotNull(input);
        return QuizCatalog.parse(input);
    }

    @Test public void sharedCatalogHas298UniqueQuestions() throws Exception {
        QuizCatalog catalog = catalog();
        assertEquals(298, catalog.questions.size());
        Set<String> identifiers = new HashSet<>();
        for (QuizCatalog.Question question : catalog.questions) identifiers.add(question.id);
        assertEquals(298, identifiers.size());
        assertTrue(catalog.categories.length >= 9);
    }

    @Test public void everyQuestionHasAnswersExplanationAndReference() throws Exception {
        for (QuizCatalog.Question question : catalog().questions) {
            assertEquals(4, question.answers.length);
            assertTrue(question.correct >= 0 && question.correct < 4);
            assertFalse(question.explanation.trim().isEmpty());
            assertFalse(question.reference.trim().isEmpty());
        }
    }
}
