package com.movieclash.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class AnswerValidator {
    private static final Pattern LEADING_ARTICLES = Pattern.compile("^(the|a|an|le|la|les|der|die|das|el|los|las)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_NOISE = Pattern.compile("\\s+(movie|film|cinema|part)$", Pattern.CASE_INSENSITIVE);
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
        // Remove trailing noise words like "movie" or "film"
        text = TRAILING_NOISE.matcher(text).replaceFirst("").trim();
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
        String spacelessGuess = normGuess.replaceAll("\\s+", "");

        Set<String> targets = new LinkedHashSet<>();
        if (targetTitle != null && !targetTitle.isBlank()) {
            targets.add(targetTitle.trim());
            // If target contains colon or hyphen (e.g. "Baahubali: The Beginning"), add prefix before separator
            if (targetTitle.contains(":")) {
                String prefix = targetTitle.substring(0, targetTitle.indexOf(':')).trim();
                if (!prefix.isEmpty()) targets.add(prefix);
            }
            if (targetTitle.contains(" - ")) {
                String prefix = targetTitle.substring(0, targetTitle.indexOf(" - ")).trim();
                if (!prefix.isEmpty()) targets.add(prefix);
            }
            if (targetTitle.contains("(")) {
                String prefix = targetTitle.substring(0, targetTitle.indexOf('(')).trim();
                if (!prefix.isEmpty()) targets.add(prefix);
            }
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    targets.add(alias.trim());
                    if (alias.contains(":")) {
                        String prefix = alias.substring(0, alias.indexOf(':')).trim();
                        if (!prefix.isEmpty()) targets.add(prefix);
                    }
                }
            }
        }

        for (String target : targets) {
            if (target == null || target.isBlank()) continue;
            String normTarget = normalize(target);
            if (normTarget.isEmpty()) continue;
            String spacelessTarget = normTarget.replaceAll("\\s+", "");

            // 1. Exact match (with or without spaces)
            if (normGuess.equals(normTarget) || spacelessGuess.equals(spacelessTarget)) {
                return new MatchResult(true, 0, target);
            }

            // 2. Extra letters check: Guess length is greater than target by 1 or 2 letters
            // Handles cases like "Pushpaa", "Pushpaaa", "Kalkii", "Inceptions", "RRRR", "Jailerr"
            if (spacelessGuess.startsWith(spacelessTarget)) {
                int extraChars = spacelessGuess.length() - spacelessTarget.length();
                if (extraChars <= 2) {
                    return new MatchResult(true, extraChars, target);
                }
            }

            // Also check if target starts with guess by up to 2 letters (e.g. "Inceptio" for "Inception")
            if (spacelessTarget.startsWith(spacelessGuess) && spacelessGuess.length() >= 4) {
                int missingChars = spacelessTarget.length() - spacelessGuess.length();
                if (missingChars <= 2) {
                    return new MatchResult(true, missingChars, target);
                }
            }

            // 3. Levenshtein typo distance calculation (word-level and spaceless-level)
            int distWord = levenshteinDistance(normGuess, normTarget);
            int distSpaceless = levenshteinDistance(spacelessGuess, spacelessTarget);
            int dist = Math.min(distWord, distSpaceless);

            int allowedDistance;
            int targetLen = spacelessTarget.length();
            if (targetLen <= 3) {
                allowedDistance = 1;
            } else if (targetLen <= 6) {
                allowedDistance = 2;
            } else if (targetLen <= 12) {
                allowedDistance = (spacelessGuess.length() >= targetLen) ? 3 : 2;
            } else {
                allowedDistance = (spacelessGuess.length() >= targetLen) ? 4 : 3;
            }

            // If guess has 1-2 extra letters overall, grant extra tolerance
            if (spacelessGuess.length() > targetLen && (spacelessGuess.length() - targetLen) <= 2) {
                allowedDistance = Math.max(allowedDistance, 2);
            }

            if (dist <= allowedDistance) {
                return new MatchResult(true, dist, target);
            }
        }

        return new MatchResult(false, 999, null);
    }
}
