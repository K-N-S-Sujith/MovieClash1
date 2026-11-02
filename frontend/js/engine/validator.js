/**
 * MovieClash — Clue-Giver Client Validation Engine
 * Normalizes movie titles/guesses and evaluates matches using Levenshtein distance.
 */

export class AnswerValidator {
  /**
   * Normalizes an input string:
   * 1. Strips diacritics/accents (e.g. Amélie -> amelie)
   * 2. Lowercases
   * 3. Removes leading articles (the, a, an, le, la, etc.)
   * 4. Strips non-alphanumeric punctuation and collapses whitespace
   */
  static normalize(input) {
    if (!input || typeof input !== 'string') return '';

    let text = input.trim().toLowerCase();

    // Remove accents
    text = text.normalize('NFD').replace(/[\u0300-\u036f]/g, '');

    // Remove leading articles
    const leadingArticlesRegex = /^(the|a|an|le|la|les|der|die|das|el|los|las)\s+/i;
    text = text.replace(leadingArticlesRegex, '');

    // Remove punctuation and non-alphanumeric chars (keep spaces and numbers)
    text = text.replace(/[^a-z0-9\s]/g, ' ');

    // Collapse multiple spaces
    text = text.replace(/\s+/g, ' ').trim();

    return text;
  }

  /**
   * Calculates the Levenshtein distance between two strings
   */
  static levenshteinDistance(a, b) {
    if (a === b) return 0;
    if (a.length === 0) return b.length;
    if (b.length === 0) return a.length;

    const matrix = [];

    for (let i = 0; i <= b.length; i++) {
      matrix[i] = [i];
    }

    for (let j = 0; j <= a.length; j++) {
      matrix[0][j] = j;
    }

    for (let i = 1; i <= b.length; i++) {
      for (let j = 1; j <= a.length; j++) {
        if (b.charAt(i - 1) === a.charAt(j - 1)) {
          matrix[i][j] = matrix[i - 1][j - 1];
        } else {
          matrix[i][j] = Math.min(
            matrix[i - 1][j - 1] + 1, // substitution
            matrix[i][j - 1] + 1,     // insertion
            matrix[i - 1][j] + 1      // deletion
          );
        }
      }
    }

    return matrix[b.length][a.length];
  }

  /**
   * Evaluates if a guess matches a target title or any of its aliases.
   * Tolerates minor typos based on word length:
   * - Length <= 4: Exact match (dist = 0)
   * - Length 5 to 8: dist <= 1
   * - Length > 8: dist <= 2
   */
  static evaluate(guess, targetTitle, aliases = []) {
    const normGuess = this.normalize(guess);
    if (!normGuess) {
      return { isMatch: false, distance: 999, matchedWith: null };
    }

    const targets = [targetTitle, ...(aliases || [])]
      .filter(Boolean)
      .map(t => ({ original: t, normalized: this.normalize(t) }));

    let bestMatch = null;
    let minDistance = 999;

    for (const target of targets) {
      const normTarget = target.normalized;
      if (!normTarget) continue;

      // Exact normalized match shortcut
      if (normGuess === normTarget) {
        return {
          isMatch: true,
          distance: 0,
          matchedWith: target.original
        };
      }

      const dist = this.levenshteinDistance(normGuess, normTarget);
      if (dist < minDistance) {
        minDistance = dist;
        bestMatch = target.original;
      }

      // Dynamic threshold based on target length
      let maxAllowedDistance = 0;
      if (normTarget.length >= 5 && normTarget.length <= 8) {
        maxAllowedDistance = 1;
      } else if (normTarget.length > 8) {
        maxAllowedDistance = 2;
      }

      if (dist <= maxAllowedDistance) {
        return {
          isMatch: true,
          distance: dist,
          matchedWith: target.original
        };
      }
    }

    return {
      isMatch: false,
      distance: minDistance,
      matchedWith: bestMatch
    };
  }
}

export default AnswerValidator;

