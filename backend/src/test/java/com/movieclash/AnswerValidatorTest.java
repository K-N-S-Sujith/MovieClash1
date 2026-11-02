package com.movieclash;

import com.movieclash.util.AnswerValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AnswerValidatorTest {

    @Test
    void testExactMatch() {
        AnswerValidator.MatchResult res = AnswerValidator.evaluate("Inception", "Inception", List.of());
        assertTrue(res.isMatch());
        assertEquals(0, res.distance());
        assertEquals("Inception", res.matchedWith());
    }

    @Test
    void testArticleStrippingAndNormalization() {
        AnswerValidator.MatchResult res = AnswerValidator.evaluate("dark knight", "The Dark Knight", List.of());
        assertTrue(res.isMatch());
        assertEquals(0, res.distance());
    }

    @Test
    void testLevenshteinTypoTolerance() {
        // "Inceptoin" vs "Inception" (length 9 -> allowed distance 2)
        AnswerValidator.MatchResult res = AnswerValidator.evaluate("Inceptoin", "Inception", List.of());
        assertTrue(res.isMatch());
    }

    @Test
    void testAliasMatch() {
        AnswerValidator.MatchResult res = AnswerValidator.evaluate("Fellowship", "The Lord of the Rings: The Fellowship of the Ring", List.of("Fellowship of the Ring", "Fellowship"));
        assertTrue(res.isMatch());
        assertEquals("Fellowship", res.matchedWith());
    }

    @Test
    void testWrongGuessFails() {
        AnswerValidator.MatchResult res = AnswerValidator.evaluate("Avatar", "Inception", List.of());
        assertFalse(res.isMatch());
    }
}

