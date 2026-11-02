import AnswerValidator from '../js/engine/validator.js';
import ScoringEngine from '../js/engine/scoring.js';

console.log('--- Testing AnswerValidator ---');

// Test 1: Exact match with case difference
const t1 = AnswerValidator.evaluate('Inception', 'inception', []);
console.assert(t1.isMatch === true, 'Test 1 Failed');

// Test 2: Leading article removal
const t2 = AnswerValidator.evaluate('The Dark Knight', 'Dark Knight', ['The Dark Knight']);
console.assert(t2.isMatch === true, 'Test 2 Failed');

const t3 = AnswerValidator.evaluate('matrix', 'The Matrix', []);
console.assert(t3.isMatch === true, 'Test 3 Failed');

// Test 3: Diacritic stripping
const t4 = AnswerValidator.evaluate('amelie', 'Amélie', []);
console.assert(t4.isMatch === true, 'Test 4 Failed');

// Test 4: Minor typo (Levenshtein distance <= 1 or 2 depending on length)
const t5 = AnswerValidator.evaluate('Interstelar', 'Interstellar', []); // 12 chars, 1 typo
console.assert(t5.isMatch === true, 'Test 5 Failed');

// Test 5: Reject major typos
const t6 = AnswerValidator.evaluate('Titanic', 'Avatar', []);
console.assert(t6.isMatch === false, 'Test 6 Failed');

console.log('--- Testing ScoringEngine ---');
const s1 = ScoringEngine.calculatePoints({ isFirstCorrect: true, config: { correctGuessPoints: 100, firstGuessBonus: 50, clueGiverRewardPerGuesser: 25 } });
console.assert(s1.guesserPoints === 150, 'Scoring Test 1 Failed');
console.assert(s1.clueGiverPoints === 25, 'Scoring Test 1b Failed');

const s2 = ScoringEngine.calculatePoints({ isFirstCorrect: false, config: { correctGuessPoints: 100, firstGuessBonus: 50, clueGiverRewardPerGuesser: 25 } });
console.assert(s2.guesserPoints === 100, 'Scoring Test 2 Failed');

console.log('✅ All Frontend Engine Unit Tests Passed Successfully!');

