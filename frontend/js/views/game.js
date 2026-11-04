/**
 * MovieClash — Active Game Arena View Controller
 * Manages clue-giver private view, guesser submission panel, live scoreboard, and activity.
 */

import store from '../state.js';
import wsClient from '../api/websocket.js';
import toast from '../utils/toast.js';

const FALLBACK_MOVIES = [
  { title: "Mayabazar", hint: "Mythological fantasy about the Pandavas, Krishna and Ghatotkacha", year: "1957" },
  { title: "Sagara Sangamam", hint: "Classical dancer battling alcoholism and heartbreak", year: "1983" },
  { title: "Shiva", hint: "College student stands up against local mobster and cycle chain fight", year: "1989" },
  { title: "Pokiri", hint: "Undercover IPS officer infiltrates the mafia underworld", year: "2006" },
  { title: "Magadheera", hint: "Reincarnated warrior seeks to reunite with his lost princess", year: "2009" },
  { title: "Eega", hint: "Murdered man reincarnates as a housefly to protect his lover", year: "2012" },
  { title: "Baahubali: The Beginning", hint: "A young tribal man discovers his royal heritage in Mahishmati", year: "2015" },
  { title: "Baahubali 2: The Conclusion", hint: "Why Kattappa killed Baahubali and the battle for the throne", year: "2017" },
  { title: "Arjun Reddy", hint: "High-functioning alcoholic surgeon spirals after heartbreak", year: "2017" },
  { title: "Jersey", hint: "Late 30s cricketer aims to play for Indian national team for his son", year: "2019" },
  { title: "RRR", hint: "Two legendary revolutionaries fight against British colonial rule", year: "2022" },
  { title: "Committee Kurrollu", hint: "Group of childhood friends reunite and confront old tensions", year: "2024" }
];

export class GameView {
  constructor() {
    this.viewGame = document.getElementById('viewGame');
    this.roundNumberEl = document.getElementById('gameRoundNumber');
    this.clueGiverNameEl = document.getElementById('activeClueGiverName');
    this.clueGiverTeamEl = document.getElementById('activeClueGiverTeam');
    this.scoreboardListEl = document.getElementById('liveScoreboardList');

    // Clue-giver movie choice elements
    this.movieChoiceCard = document.getElementById('movieChoiceCard');
    this.chooseMovieForm = document.getElementById('chooseMovieForm');
    this.customMovieTitle = document.getElementById('customMovieTitle');
    this.customMovieHint = document.getElementById('customMovieHint');
    this.customMovieYear = document.getElementById('customMovieYear');
    this.btnSuggestMovie = document.getElementById('btnSuggestMovie');

    // Guesser waiting for movie choice
    this.guesserWaitingCard = document.getElementById('guesserWaitingForMovieCard');
    this.waitingClueGiverName = document.getElementById('waitingClueGiverName');

    // Clue-giver private elements
    this.clueGiverSecretPanel = document.getElementById('clueGiverSecretPanel');
    this.secretMovieTitleEl = document.getElementById('secretMovieTitle');
    this.secretMovieYearEl = document.getElementById('secretMovieYear');
    this.secretMovieHintEl = document.getElementById('secretMovieHint');
    this.secretMovieAliasesEl = document.getElementById('secretMovieAliases');
    this.clueGiverAuditLog = document.getElementById('clueGiverAuditLog');
    this.auditFeedEl = document.getElementById('auditLogFeed');

    // Guesser elements
    this.guesserPromptPanel = document.getElementById('guesserPromptPanel');
    this.guesserSubText = document.getElementById('guesserSubText');
    this.hangmanWordSlots = document.getElementById('hangmanWordSlots');
    this.marqueeRevealedCount = document.getElementById('marqueeRevealedCount');
    this.marqueeTotalCount = document.getElementById('marqueeTotalCount');
    this.marqueeYearPill = document.getElementById('marqueeYearPill');
    this.marqueeYearValue = document.getElementById('marqueeYearValue');
    this.marqueeHintPill = document.getElementById('marqueeHintPill');
    this.marqueeHintValue = document.getElementById('marqueeHintValue');
    this.marqueeRevealAlert = document.getElementById('marqueeRevealAlert');
    this.marqueeRevealAlertText = document.getElementById('marqueeRevealAlertText');
    this.revealAlertTimeout = null;

    this.guessSection = document.getElementById('guessSubmissionSection');
    this.spectatorAudienceBanner = document.getElementById('spectatorAudienceBanner');
    this.guessForm = document.getElementById('guessForm');
    this.guessInput = document.getElementById('guessInput');
    this.guessFeedbackEl = document.getElementById('guessFeedbackMsg');

    // Compact Player Status List (Replaces Round Activity)
    this.playerStatusListEl = document.getElementById('gamePlayerStatusList');
    this.gamePlayerCountEl = document.getElementById('gamePlayerCount');

    // Cancellation
    this.btnProposeCancel = document.getElementById('btnProposeCancel');

    // Performance optimization caches
    this.lastRenderedMaskedTitle = null;
    this.lastScoreboardKey = null;
    this.lastAuditLength = 0;

    this.initEvents();
  }

  initEvents() {
    if (this.chooseMovieForm) {
      this.chooseMovieForm.addEventListener('submit', (e) => {
        e.preventDefault();
        this.handleChooseMovieSubmit();
      });
    }

    if (this.btnSuggestMovie) {
      this.btnSuggestMovie.addEventListener('click', () => {
        const fallback = FALLBACK_MOVIES[Math.floor(Math.random() * FALLBACK_MOVIES.length)];
        if (this.customMovieTitle) this.customMovieTitle.value = fallback.title;
        if (this.customMovieHint) this.customMovieHint.value = fallback.hint;
        if (this.customMovieYear) this.customMovieYear.value = fallback.year;
        wsClient.send('SUGGEST_MOVIE', {});
      });
    }

    if (this.guessForm) {
      this.guessForm.addEventListener('submit', (e) => {
        e.preventDefault();
        this.handleGuessSubmit();
      });
    }

    if (this.btnProposeCancel) {
      this.btnProposeCancel.addEventListener('click', () => {
        if (confirm('Are you sure you want to propose cancelling the game? A room-wide vote will be triggered.')) {
          wsClient.send('CANCEL_PROPOSE', {});
        }
      });
    }

    store.subscribe((state) => {
      if (state.room.status === 'IN_PROGRESS' || state.room.status === 'PAUSED') {
        this.render(state);
      }
    });
  }

  handleChooseMovieSubmit() {
    const title = this.customMovieTitle?.value.trim();
    if (!title) {
      toast.show('Please enter a secret movie title.', 'warning');
      return;
    }
    const hint = this.customMovieHint?.value.trim() || '';
    const year = this.customMovieYear?.value.trim() || '';

    wsClient.send('CHOOSE_MOVIE', {
      title,
      hint,
      year
    });
  }

  handleGuessSubmit() {
    const text = this.guessInput.value.trim();
    if (!text) return;

    const state = store.get();
    if (state.player.isSpectator) return; // Spectators cannot submit guesses
    const isClueTeam = state.round.isMyClueGiverTurn || (state.room.gameMode === 'TEAM' && state.player.teamId && state.player.teamId === state.round.clueGiverTeam);
    if (isClueTeam) return; // Clue team members cannot submit guesses
    if (state.round.alreadyScoredThisRound) return; // Already scored

    // Send guess to backend for sequence-stamped forwarding
    wsClient.send('SUBMIT_GUESS', {
      guessText: text
    });

    this.guessInput.value = '';
    this.showFeedback('Guess submitted, checking...', 'neutral');
  }

  showFeedback(text, type) {
    if (!this.guessFeedbackEl) return;
    this.guessFeedbackEl.textContent = text;
    this.guessFeedbackEl.className = `guess-feedback ${type}`;
    this.guessFeedbackEl.classList.remove('hidden');

    if (type !== 'correct') {
      setTimeout(() => {
        if (this.guessFeedbackEl && this.guessFeedbackEl.textContent === text) {
          this.guessFeedbackEl.classList.add('hidden');
        }
      }, 3000);
    }
  }

  render(state) {
    const { room, round, player } = state;

    // 1. Round indicator
    if (this.roundNumberEl) {
      this.roundNumberEl.textContent = `${round.roundNumber}/${round.totalRounds || room.config.totalRounds || '?'}`;
    }

    // 2. Active Clue-Giver
    if (this.clueGiverNameEl) {
      this.clueGiverNameEl.textContent = round.clueGiverName || 'Unknown';
    }
    if (this.clueGiverTeamEl) {
      if (room.gameMode === 'TEAM' && round.clueGiverTeam) {
        this.clueGiverTeamEl.textContent = room.teams[round.clueGiverTeam]?.name || round.clueGiverTeam;
        this.clueGiverTeamEl.classList.remove('hidden');
      } else {
        this.clueGiverTeamEl.classList.add('hidden');
      }
    }

    // 3. Clue-giver vs Guesser visibility
    const isClueGiver = round.isMyClueGiverTurn;
    const isTeammate = room.gameMode === 'TEAM' && player.teamId && player.teamId === round.clueGiverTeam;
    const isClueTeam = isClueGiver || isTeammate;
    const isPreparing = round.isPreparing && !round.isRoundActive;

    if (isPreparing) {
      // Round is in movie-selection preparation stage
      if (isClueGiver) {
        if (this.movieChoiceCard) this.movieChoiceCard.classList.remove('hidden');
        if (this.guesserWaitingCard) this.guesserWaitingCard.classList.add('hidden');
      } else {
        if (this.movieChoiceCard) this.movieChoiceCard.classList.add('hidden');
        if (this.guesserWaitingCard) {
          this.guesserWaitingCard.classList.remove('hidden');
          if (this.waitingClueGiverName) {
            this.waitingClueGiverName.textContent = isTeammate
              ? `Your teammate ${round.clueGiverName || ''} is picking your team's movie...`
              : `${round.clueGiverName || 'Clue-giver'} is picking a secret movie...`;
          }
        }
      }
      if (this.clueGiverSecretPanel) this.clueGiverSecretPanel.classList.add('hidden');
      if (this.clueGiverAuditLog) this.clueGiverAuditLog.classList.add('hidden');
      if (this.guesserPromptPanel) this.guesserPromptPanel.classList.add('hidden');
      if (this.guessSection) this.guessSection.classList.add('hidden');
      if (this.spectatorAudienceBanner) this.spectatorAudienceBanner.classList.add('hidden');
    } else {
      // Round is active or concluded
      if (this.movieChoiceCard) this.movieChoiceCard.classList.add('hidden');
      if (this.guesserWaitingCard) this.guesserWaitingCard.classList.add('hidden');

      if (isClueTeam) {
        // ENTIRE CLUE TEAM SEES THE SECRET MOVIE!
        if (this.clueGiverSecretPanel) this.clueGiverSecretPanel.classList.remove('hidden');
        if (this.guesserPromptPanel) this.guesserPromptPanel.classList.add('hidden');
        if (this.guessSection) this.guessSection.classList.add('hidden');
        if (this.spectatorAudienceBanner) this.spectatorAudienceBanner.classList.add('hidden');

        // Audit log only for lead clue-giver
        if (this.clueGiverAuditLog) {
          if (isClueGiver) {
            this.clueGiverAuditLog.classList.remove('hidden');
          } else {
            this.clueGiverAuditLog.classList.add('hidden');
          }
        }

        if (round.secretMovie) {
          if (this.secretMovieTitleEl) this.secretMovieTitleEl.textContent = round.secretMovie.title || 'Movie Title';
          if (this.secretMovieYearEl) this.secretMovieYearEl.textContent = `Year: ${round.secretMovie.year || 'N/A'}`;
          if (this.secretMovieHintEl) this.secretMovieHintEl.textContent = `Hint: ${round.secretMovie.hint || 'None'}`;
          if (this.secretMovieAliasesEl) {
            const aliases = round.secretMovie.aliases || [];
            this.secretMovieAliasesEl.textContent = aliases.length > 0 ? aliases.join(', ') : 'None';
          }
        }
      } else {
        // Guesser / Spectator mode (Opposing teams / Audience / Other players)
        if (this.clueGiverSecretPanel) this.clueGiverSecretPanel.classList.add('hidden');
        if (this.clueGiverAuditLog) this.clueGiverAuditLog.classList.add('hidden');
        if (this.guesserPromptPanel) this.guesserPromptPanel.classList.remove('hidden');

        if (player.isSpectator) {
          if (this.guessSection) this.guessSection.classList.add('hidden');
          if (this.spectatorAudienceBanner) this.spectatorAudienceBanner.classList.remove('hidden');
          if (this.guesserSubText) {
            this.guesserSubText.textContent = 'You are spectating! Follow the clues, watch the letter reveals, and cheer with live reactions.';
          }
        } else {
          if (this.spectatorAudienceBanner) this.spectatorAudienceBanner.classList.add('hidden');
          if (round.alreadyScoredThisRound) {
            if (this.guessSection) this.guessSection.classList.add('hidden');
            if (this.guesserSubText) this.guesserSubText.textContent = 'You have already answered correctly this round! Sit back and enjoy the remaining time.';
          } else {
            if (this.guessSection) this.guessSection.classList.remove('hidden');
            if (this.guesserSubText) this.guesserSubText.textContent = 'Type your answer below as fast as you can. Minor typos and articles are accepted!';
          }
        }

        // Render Hangman Marquee Board
        this.renderMarqueeBoard(round);
      }
    }

    // 4. Render Scoreboard
    this.renderScoreboard(room);

    // 5. Render Audit Log (if clue-giver)
    if (isClueGiver) {
      this.renderAuditLog(state.auditLog);
    }

    // 6. Render Compact Player Status (Replaces Round Activity)
    this.renderPlayerStatus(room, player, round);

    // 7. Cancellation propose visibility (Hosts/Co-Hosts only, not spectators)
    if (this.btnProposeCancel) {
      if (player.isSpectator || (!player.isHost && !player.isCoHost)) {
        this.btnProposeCancel.classList.add('hidden');
      } else {
        this.btnProposeCancel.classList.remove('hidden');
      }
    }
  }

  renderScoreboard(room) {
    if (!this.scoreboardListEl) return;

    let cacheKey = '';
    if (room.gameMode === 'TEAM') {
      cacheKey = Object.entries(room.teams || {})
        .map(([id, t]) => `${id}:${t.score}:${t.name}`)
        .join('|');
    } else {
      cacheKey = (room.players || [])
        .map(p => `${p.id}:${p.score}:${p.name}`)
        .join('|');
    }

    if (cacheKey === this.lastScoreboardKey) return;
    this.lastScoreboardKey = cacheKey;

    this.scoreboardListEl.innerHTML = '';

    if (room.gameMode === 'TEAM') {
      // Team standings
      const teamsArr = Object.entries(room.teams || {}).map(([id, t]) => ({
        id,
        name: t.name || id,
        score: t.score || 0
      })).sort((a, b) => b.score - a.score);

      const topScore = teamsArr[0]?.score || 0;

      teamsArr.forEach(t => {
        const item = document.createElement('div');
        item.className = `score-item ${t.score === topScore && topScore > 0 ? 'leader' : ''}`;
        item.innerHTML = `
          <span>${this.escapeHtml(t.name)}</span>
          <span class="score-points">${t.score} pts</span>
        `;
        this.scoreboardListEl.appendChild(item);
      });
    } else {
      // Individual standings
      const playersArr = [...(room.players || [])].sort((a, b) => (b.score || 0) - (a.score || 0));
      const topScore = playersArr[0]?.score || 0;

      playersArr.forEach(p => {
        const item = document.createElement('div');
        item.className = `score-item ${p.score === topScore && topScore > 0 ? 'leader' : ''}`;
        item.innerHTML = `
          <span>${this.escapeHtml(p.name)}</span>
          <span class="score-points">${p.score || 0} pts</span>
        `;
        this.scoreboardListEl.appendChild(item);
      });
    }
  }

  renderAuditLog(auditLogs) {
    if (!this.auditFeedEl) return;
    if (!auditLogs || auditLogs.length === 0) {
      this.auditFeedEl.innerHTML = '';
      this.lastAuditLength = 0;
      return;
    }

    if (auditLogs.length <= this.lastAuditLength) return;

    const newEntries = auditLogs.slice(this.lastAuditLength);
    this.lastAuditLength = auditLogs.length;

    newEntries.forEach(log => {
      const line = document.createElement('div');
      line.className = `audit-entry ${log.isMatch ? 'match' : ''}`;
      line.textContent = `[#${log.seq}] ${log.guesserName}: "${log.guessText}" -> ${log.isMatch ? `CORRECT! (${log.matchedWith})` : 'NO MATCH'}`;
      this.auditFeedEl.appendChild(line);
    });

    while (this.auditFeedEl.children.length > 25) {
      this.auditFeedEl.removeChild(this.auditFeedEl.firstChild);
    }
    this.auditFeedEl.scrollTop = this.auditFeedEl.scrollHeight;
  }

  renderPlayerStatus(room, currentPlayer, round) {
    if (!this.playerStatusListEl) return;

    const players = room?.players || [];
    if (this.gamePlayerCountEl) {
      this.gamePlayerCountEl.textContent = players.length;
    }

    const hostId = room?.hostId;
    const clueGiverId = round?.clueGiverId || room?.currentClueGiverId;

    this.playerStatusListEl.innerHTML = '';

    players.forEach(p => {
      const isConnected = p.connected !== false;
      const isHost = p.id === hostId || p.isHost;
      const isClueGiver = p.id === clueGiverId;
      const isSpectator = Boolean(p.spectator);
      const isMe = p.id === currentPlayer?.id;

      const playerName = p.name || 'Player';
      const initial = playerName.charAt(0).toUpperCase();

      const item = document.createElement('div');
      item.className = `player-status-item ${isConnected ? 'is-connected' : 'is-disconnected'} ${isMe ? 'is-me' : ''} ${isHost ? 'is-host' : ''}`;

      item.innerHTML = `
        <div class="status-avatar-wrapper">
          <div class="player-avatar-sm ${isHost ? 'host-avatar' : ''}">${initial}</div>
          <span class="presence-dot ${isConnected ? 'online' : 'offline'}" title="${isConnected ? 'Connected' : 'Disconnected'}"></span>
        </div>
        <div class="status-player-details">
          <div class="status-name-row">
            <span class="player-status-name" title="${this.escapeHtml(playerName)}">
              ${this.escapeHtml(playerName)} ${isMe ? '<span class="you-tag">(You)</span>' : ''}
            </span>
          </div>
          <div class="player-status-tags">
            ${isHost ? '<span class="status-tag-mini host">👑 Host</span>' : ''}
            ${isClueGiver ? '<span class="status-tag-mini clue">🎙️ Clue</span>' : ''}
            ${isSpectator ? '<span class="status-tag-mini spectator">🍿 Audience</span>' : ''}
            ${!isConnected ? '<span class="status-tag-mini offline">🔴 Disconnected</span>' : ''}
          </div>
        </div>
      `;

      this.playerStatusListEl.appendChild(item);
    });
  }

  renderMarqueeBoard(round) {
    if (!this.hangmanWordSlots) return;

    const maskedTitle = round.maskedTitle || '';
    if (!maskedTitle) {
      this.hangmanWordSlots.innerHTML = '<span class="text-muted text-sm">Waiting for round to begin...</span>';
      this.lastRenderedMaskedTitle = null;
      return;
    }

    // 1. Update counts
    if (this.marqueeRevealedCount) {
      this.marqueeRevealedCount.textContent = round.revealedLettersCount || 0;
    }
    if (this.marqueeTotalCount) {
      this.marqueeTotalCount.textContent = round.totalLettersCount || 0;
    }

    // 2. Metadata pills (Year only; plot hint is kept private for clue-givers)
    if (this.marqueeYearPill && this.marqueeYearValue) {
      if (round.movieYear) {
        this.marqueeYearValue.textContent = round.movieYear;
        this.marqueeYearPill.classList.remove('hidden');
      } else {
        this.marqueeYearPill.classList.add('hidden');
      }
    }

    if (this.marqueeHintPill) {
      this.marqueeHintPill.classList.add('hidden'); // Never show full secret plot hint in public marquee
    }

    // 3. Render word clusters and letter tiles only if maskedTitle changed
    if (maskedTitle === this.lastRenderedMaskedTitle) {
      return;
    }
    this.lastRenderedMaskedTitle = maskedTitle;

    const words = maskedTitle.split(' ');
    this.hangmanWordSlots.innerHTML = '';

    words.forEach(wordStr => {
      const wordContainer = document.createElement('div');
      wordContainer.className = 'hangman-word';

      for (let i = 0; i < wordStr.length; i++) {
        const ch = wordStr[i];
        const tile = document.createElement('span');

        if (ch === '_') {
          tile.className = 'hangman-tile blank';
          tile.textContent = '';
        } else if (/[a-zA-Z0-9%]/.test(ch)) {
          tile.className = 'hangman-tile revealed';
          tile.textContent = ch.toUpperCase();
        } else {
          tile.className = 'hangman-tile punct';
          tile.textContent = ch;
        }
        wordContainer.appendChild(tile);
      }

      this.hangmanWordSlots.appendChild(wordContainer);
    });
  }

  showLetterRevealAlert(letter) {
    if (!this.marqueeRevealAlert || !this.marqueeRevealAlertText) return;

    this.marqueeRevealAlertText.innerHTML = `Marquee Clue: Letter <strong>${this.escapeHtml(letter.toUpperCase())}</strong> revealed!`;
    this.marqueeRevealAlert.classList.remove('hidden');
    this.marqueeRevealAlert.classList.remove('pulse-in');
    void this.marqueeRevealAlert.offsetWidth; // trigger reflow
    this.marqueeRevealAlert.classList.add('pulse-in');

    if (this.revealAlertTimeout) {
      clearTimeout(this.revealAlertTimeout);
    }
    this.revealAlertTimeout = setTimeout(() => {
      if (this.marqueeRevealAlert) {
        this.marqueeRevealAlert.classList.add('hidden');
      }
    }, 3500);
  }

  escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
}

export const gameView = new GameView();
export default gameView;

