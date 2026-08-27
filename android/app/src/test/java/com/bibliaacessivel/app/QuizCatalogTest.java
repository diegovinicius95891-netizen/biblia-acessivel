package com.bibliaacessivel.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Confere variedade e integridade das perguntas Android. */
public final class QuizCatalogTest {
    @Test public void everyCombinationHasTwoQuestions() {
        assertEquals(36, QuizCatalog.QUESTIONS.size());
        for (String difficulty : QuizCatalog.DIFFICULTIES) {
            if ("todas".equals(difficulty)) continue;
            for (String category : QuizCatalog.CATEGORIES) {
                if ("todas".equals(category)) continue;
                assertEquals(2, QuizCatalog.filtered(difficulty, category).size());
            }
        }
    }

    @Test public void everyQuestionHasAnswersExplanationAndReference() {
        for (QuizCatalog.Question question : QuizCatalog.QUESTIONS) {
            assertEquals(4, question.answers.length);
            assertTrue(question.correct >= 0 && question.correct < 4);
            assertFalse(question.explanation.trim().isEmpty());
            assertFalse(question.reference.trim().isEmpty());
        }
    }
}
