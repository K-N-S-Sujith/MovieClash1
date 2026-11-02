/**
 * MovieClash — Scoring Engine (Client-Side Authority)
 * Computes guesser points and clue-giver reward points according to room config.
 */

export class ScoringEngine {
  /**
   * Calculates points for a correct guess
   * @param {Object} options
   * @param {boolean} options.isFirstCorrect - Whether this is the first correct guess in the round
   * @param {Object} options.config - Game configuration from room
   * @returns {{ guesserPoints: number, clueGiverPoints: number }}
   */
  static calculatePoints({ isFirstCorrect = false, config = {} }) {
    const basePoints = config.correctGuessPoints || 100;
    const firstBonus = config.firstGuessBonus || 50;
    const clueGiverReward = config.clueGiverRewardPerGuesser || 25;

    const guesserPoints = isFirstCorrect ? (basePoints + firstBonus) : basePoints;

    return {
      guesserPoints,
      clueGiverPoints: clueGiverReward
    };
  }
}

export default ScoringEngine;

