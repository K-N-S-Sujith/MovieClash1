package com.movieclash.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class AnswerValidator {
    private static final Pattern LEADING_ARTICLES = Pattern.compile("^(the|a|an|le|la|les|der|die|das|el|los|las)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9\\s]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    public record MatchResult(boolean isMatch, int distance, String matchedWith) {}

    public static String normalize(String input) {
        if (input == null) return "";
        String text = input.trim().toLowerCase();
        // Remove accents / diacritics
        text = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        // Remove leading articles
        text = LEADING_ARTICLES.matcher(text).replaceFirst("");
        // Replace non-alphanumeric punctuation with spaces
        text = NON_ALPHANUMERIC.matcher(text).replaceAll(" ");
        // Collapse spaces
        text = MULTI_SPACE.matcher(text).replaceAll(" ").trim();
        return text;
    }

    public static int levenshteinDistance(String a, String b) {
        if (a.equals(b)) return 0;
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();

        int[] costs = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) costs[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= b.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        a.charAt(i - 1) == b.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[b.length()];
    }

    public static MatchResult evaluate(String guess, String targetTitle, List<String> aliases) {
        String normGuess = normalize(guess);
        if (normGuess.isEmpty()) {
            return new MatchResult(false, 999, null);
        }

        List<String> targets = new ArrayList<>();
        if (targetTitle != null && !targetTitle.isBlank()) {
            targets.add(targetTitle);
        }
        if (aliases != null) {
            targets.addAll(aliases);
        }

        for (String target : targets) {
            if (target == null || target.isBlank()) continue;
            String normTarget = normalize(target);
            if (normTarget.isEmpty()) continue;

            if (normGuess.equals(normTarget)) {
                return new MatchResult(true, 0, target);
            }

            int dist = levenshteinDistance(normGuess, normTarget);
            int allowedDistance;
            if (normTarget.length() <= 4) {
                allowedDistance = 0;
            } else if (normTarget.length() <= 8) {
                allowedDistance = 1;
            } else {
                allowedDistance = 2;
            }

            if (dist <= allowedDistance) {
                return new MatchResult(true, dist, target);
            }
        }

        return new MatchResult(false, 999, null);
    }
}

