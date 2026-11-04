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
        // "Inceptoin" vs "Inception"
        AnswerValidator.MatchResult res = AnswerValidator.evaluate("Inceptoin", "Inception", List.of());
        assertTrue(res.isMatch());
    }

    @Test
    void testExtraLettersMatching() {
        // 1 or 2 extra letters as requested by user
        assertTrue(AnswerValidator.evaluate("Pushpaa", "Pushpa", List.of()).isMatch());
        assertTrue(AnswerValidator.evaluate("Pushpaaa", "Pushpa", List.of()).isMatch());
        assertTrue(AnswerValidator.evaluate("Inceptions", "Inception", List.of()).isMatch());
        assertTrue(AnswerValidator.evaluate("RRRR", "RRR", List.of()).isMatch());
        assertTrue(AnswerValidator.evaluate("Kalkii", "Kalki", List.of()).isMatch());
        assertTrue(AnswerValidator.evaluate("Jailerr", "Jailer", List.of()).isMatch());
    }

    @Test
    void testColonAndSubtitlePrefixMatching() {
        // Subtitle titles matched by main name
        assertTrue(AnswerValidator.evaluate("Baahubali", "Baahubali: The Beginning", List.of()).isMatch());
        assertTrue(AnswerValidator.evaluate("KGF", "K.G.F: Chapter 1", List.of()).isMatch());
    }

    @Test
    void testSpacelessAndTrailingNoiseMatching() {
        // "spiderman" vs "Spider Man", trailing "movie"
        assertTrue(AnswerValidator.evaluate("spiderman", "Spider-Man", List.of()).isMatch());
        assertTrue(AnswerValidator.evaluate("Pushpa movie", "Pushpa", List.of()).isMatch());
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
