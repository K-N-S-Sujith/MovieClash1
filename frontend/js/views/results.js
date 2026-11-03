/**
 * MovieClash — Round & Final Game Results Controller
 * Handles post-round movie reveal overlay and final podium / scoreboard.
 */

import store from '../state.js';
import wsClient from '../api/websocket.js';
import confettiEngine from '../engine/confetti.js';

export class ResultsView {
  constructor() {
    this.roundResultsOverlay = document.getElementById('roundResultsOverlay');
    this.revealedMovieTitle = document.getElementById('revealedMovieTitle');
    this.revealedMovieMeta = document.getElementById('revealedMovieMeta');
    this.roundPointsList = document.getElementById('roundPointsList');
    this.btnHostNextRound = document.getElementById('btnHostNextRound');
    this.btnNextRoundReady = document.getElementById('btnNextRoundReady');
    this.nextRoundCountdownEl = document.getElementById('nextRoundCountdown');

    // Final results view
    this.viewResults = document.getElementById('viewResults');
    this.winnerBanner = document.getElementById('gameWinnerBanner');
    this.finalScoreboardList = document.getElementById('finalScoreboardList');
    this.btnPlayAgain = document.getElementById('btnPlayAgain');
    this.btnExitToHome = document.getElementById('btnExitToHome');

    this.roundCountdownTimer = null;

    this.initEvents();
  }

  initEvents() {
    if (this.btnHostNextRound) {
      this.btnHostNextRound.addEventListener('click', () => {
        this.hideRoundOverlay();
        wsClient.send('NEXT_ROUND', {});
      });
    }

    if (this.btnNextRoundReady) {
      this.btnNextRoundReady.addEventListener('click', () => {
        this.hideRoundOverlay();
        wsClient.send('NEXT_ROUND', {});
      });
    }

    if (this.btnPlayAgain) {
      this.btnPlayAgain.addEventListener('click', () => {
        confettiEngine.clear();
        // Return to lobby
        store.set({
          room: { ...store.get().room, status: 'LOBBY' }
        });
      });
    }

    if (this.btnExitToHome) {
      this.btnExitToHome.addEventListener('click', () => {
        confettiEngine.clear();
        store.clearSavedSession();
        window.location.href = window.location.pathname;
      });
    }
  }

  showRoundFinished(payload) {
    if (!this.roundResultsOverlay) return;

    const { revealedMovie, roundScores, reason } = payload;
    const state = store.get();

    if (this.revealedMovieTitle) {
      this.revealedMovieTitle.textContent = revealedMovie?.title || 'Unknown Title';
    }
    if (this.revealedMovieMeta) {
      this.revealedMovieMeta.textContent = revealedMovie?.year ? `Year: ${revealedMovie.year}` : '';
    }

    // Points earned this round
    if (this.roundPointsList) {
      this.roundPointsList.innerHTML = '';
      if (!roundScores || roundScores.length === 0) {
        this.roundPointsList.innerHTML = '<span class="text-muted text-sm">No players scored this round!</span>';
      } else {
        roundScores.forEach(entry => {
          const item = document.createElement('div');
          item.className = 'score-item';
          item.innerHTML = `
            <span>${this.escapeHtml(entry.playerName || entry.teamName || 'Player')}</span>
            <span class="score-points">+${entry.pointsEarned} pts</span>
          `;
          this.roundPointsList.appendChild(item);
        });
      }
    }

    const isHost = state.player.id === state.room.hostId;
    this.roundResultsOverlay.classList.remove('hidden');

    if (this.roundCountdownTimer) {
      clearInterval(this.roundCountdownTimer);
      this.roundCountdownTimer = null;
    }

    if (payload.autoAdvance === false) {
      // Manual mode: No countdown. User clicks button to proceed when ready.
      if (this.nextRoundCountdownEl) {
        this.nextRoundCountdownEl.textContent = 'Round complete! When ready to choose the next movie:';
      }
      if (this.btnNextRoundReady) {
        this.btnNextRoundReady.classList.remove('hidden');
      }
      if (this.btnHostNextRound) {
        this.btnHostNextRound.classList.add('hidden');
      }
    } else {
      // Auto mode: countdown
      if (this.btnNextRoundReady) {
        this.btnNextRoundReady.classList.add('hidden');
      }
      if (this.btnHostNextRound) {
        if (isHost) {
          this.btnHostNextRound.classList.remove('hidden');
        } else {
          this.btnHostNextRound.classList.add('hidden');
        }
      }

      let secondsLeft = 7;
      if (this.nextRoundCountdownEl) {
        this.nextRoundCountdownEl.textContent = `Next round starting in ${secondsLeft}s...`;
      }

      this.roundCountdownTimer = setInterval(() => {
        secondsLeft--;
        if (this.nextRoundCountdownEl) {
          this.nextRoundCountdownEl.textContent = `Next round starting in ${secondsLeft}s...`;
        }
        if (secondsLeft <= 0) {
          clearInterval(this.roundCountdownTimer);
          this.roundCountdownTimer = null;
          this.hideRoundOverlay();
        }
      }, 1000);
    }
  }

  hideRoundOverlay() {
    if (this.roundCountdownTimer) {
      clearInterval(this.roundCountdownTimer);
      this.roundCountdownTimer = null;
    }
    if (this.roundResultsOverlay) {
      this.roundResultsOverlay.classList.add('hidden');
    }
  }

  showGameFinished(payload) {
    this.hideRoundOverlay();
    const { winners, finalScores } = payload;

    if (this.winnerBanner) {
      if (!winners || winners.length === 0) {
        this.winnerBanner.textContent = "It's a Tie!";
      } else if (winners.length === 1) {
        this.winnerBanner.textContent = `${winners[0].name} Wins!`;
      } else {
        this.winnerBanner.textContent = `Tied: ${winners.map(w => w.name).join(' & ')}!`;
      }
    }

    if (this.finalScoreboardList) {
      this.finalScoreboardList.innerHTML = '';
      (finalScores || []).forEach((entry, idx) => {
        const item = document.createElement('div');
        item.className = `score-item ${idx === 0 ? 'leader' : ''}`;
        item.innerHTML = `
          <span>#${idx + 1} ${this.escapeHtml(entry.name)}</span>
          <span class="score-points">${entry.score} pts</span>
        `;
        this.finalScoreboardList.appendChild(item);
      });
    }

    // Switch view to game finished
    document.querySelectorAll('.view-section').forEach(el => el.classList.remove('active'));
    if (this.viewResults) {
      this.viewResults.classList.remove('hidden');
      this.viewResults.classList.add('active');
    }
  }

  escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
}

export const resultsView = new ResultsView();
export default resultsView;

