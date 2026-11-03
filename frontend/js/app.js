/**
 * MovieClash — Main Application Controller & Router
 * Connects WebSocket events to state store, view controllers, and validation engine.
 */

import CONFIG from '../config.js';
import store from './state.js';
import healthClient from './api/health.js';
import wsClient from './api/websocket.js';
import roundTimer from './engine/timer.js';
import AnswerValidator from './engine/validator.js';
import ScoringEngine from './engine/scoring.js';
import liveKitVoice from './voice/livekit-voice.js';

import landingView from './views/landing.js';
import lobbyView from './views/lobby.js';
import gameView from './views/game.js';
import resultsView from './views/results.js';
import chatView from './views/chat.js';
import cancelModalView from './views/cancelModal.js';

class MovieClashApp {
  constructor() {
    this.currentViewId = 'viewLanding';
  }

  init() {
    console.log('🎬 MovieClash App initializing...');

    // 1. Check for saved session from before refresh
    const urlParams = new URLSearchParams(window.location.search);
    const roomParam = urlParams.get('room');
    const saved = store.getSavedSession();

    if (saved && saved.roomId) {
      if (!roomParam || roomParam.toUpperCase() === saved.roomId.toUpperCase()) {
        console.log('Found saved session for room:', saved.roomId, 'isHost:', saved.isHost);
        const banner = document.getElementById('reconnectBanner');
        const bannerText = document.getElementById('reconnectBannerText');
        if (banner) banner.classList.remove('hidden');
        if (bannerText) bannerText.textContent = `Reconnecting to room ${saved.roomId}...`;

        // Hide landing view to avoid flicker while reconnecting
        document.querySelectorAll('.view-section').forEach(sec => {
          sec.classList.remove('active');
          sec.classList.add('hidden');
        });
      }
    }

    // 2. Start server health pinger
    healthClient.start();

    // 3. Wire up WebSocket message handlers
    this.registerWebSocketHandlers();

    // 4. Connect WebSocket
    wsClient.connect();

    // 5. Handle window beforeunload / cleanup
    window.addEventListener('beforeunload', () => {
      // Session is preserved in sessionStorage & localStorage
    });

    // 6. Set up browser history navigation routing (Undo / Back support)
    this.initHistoryRouting();
  }

  async initVoice(roomId, playerId) {
    // [TEMPORARILY DISABLED VOICE CLUE - COMMENTED OUT]
    /*
    if (!liveKitVoice.isAvailable()) return;
    try {
      const res = await fetch(`${CONFIG.BACKEND_HTTP_URL}/api/livekit/token?roomId=${encodeURIComponent(roomId)}&playerId=${encodeURIComponent(playerId)}`);
      if (res.ok) {
        const data = await res.json();
        if (data.token) {
          await liveKitVoice.connect(data.livekitUrl, data.token);
        }
      }
    } catch (err) {
      console.warn('LiveKit voice token fetch skipped:', err);
    }
    */
  }

  switchView(viewId, pushHistory = true) {
    document.querySelectorAll('.view-section').forEach(sec => {
      sec.classList.remove('active');
      sec.classList.add('hidden');
    });

    const target = document.getElementById(viewId);
    if (target) {
      target.classList.remove('hidden');
      target.classList.add('active');
      this.currentViewId = viewId;

      if (pushHistory) {
        const roomId = store.get()?.room?.roomId;
        if (viewId === 'viewLobby' && roomId) {
          history.pushState({ view: 'lobby', roomId }, '', `?room=${roomId}`);
        } else if (viewId === 'viewGame' && roomId) {
          history.pushState({ view: 'game', roomId }, '', `?room=${roomId}`);
        } else if (viewId === 'viewResults') {
          history.pushState({ view: 'results' }, '', window.location.pathname);
        } else if (viewId === 'viewLanding') {
          history.pushState({ view: 'landing' }, '', window.location.pathname);
        }
      }
    }
  }

  initHistoryRouting() {
    // Ensure initial baseline state exists
    if (!history.state) {
      const urlParams = new URLSearchParams(window.location.search);
      const roomParam = urlParams.get('room');
      const hash = window.location.hash;
      if (hash === '#create') {
        history.replaceState({ view: 'landing', card: 'create' }, '', window.location.href);
      } else if (hash === '#join') {
        history.replaceState({ view: 'landing', card: 'join' }, '', window.location.href);
      } else if (roomParam) {
        history.replaceState({ view: 'lobby', roomId: roomParam }, '', window.location.href);
      } else {
        history.replaceState({ view: 'landing', card: 'landing' }, '', window.location.href);
      }
    }

    // Intercept browser Back / Forward ("undo" button) navigation
    window.addEventListener('popstate', (e) => {
      // 1. If currently in an active game arena
      if (this.currentViewId === 'viewGame') {
        const leave = confirm('A game round is in progress. Do you want to leave the game?');
        if (!leave) {
          const roomId = store.get()?.room?.roomId;
          history.pushState({ view: 'game', roomId }, '', window.location.href);
          return;
        }
        wsClient.send('LEAVE_ROOM', {});
        store.clearSavedSession();
        store.reset();
        this.switchView('viewLanding', false);
        landingView.showCard('landing', false);
        return;
      }

      // 2. If currently in room lobby
      if (this.currentViewId === 'viewLobby') {
        const leave = confirm('Do you want to leave this game room?');
        if (!leave) {
          const roomId = store.get()?.room?.roomId;
          history.pushState({ view: 'lobby', roomId }, '', window.location.href);
          return;
        }
        wsClient.send('LEAVE_ROOM', {});
        store.clearSavedSession();
        store.reset();
        this.switchView('viewLanding', false);
        landingView.showCard('landing', false);
        return;
      }

      // 3. If on Landing screen (or switching between Landing / Create / Join)
      if (this.currentViewId === 'viewLanding' || !this.currentViewId) {
        const hash = window.location.hash;
        const card = e.state?.card;

        if (card === 'create' || hash === '#create') {
          landingView.showCard('create', false);
        } else if (card === 'join' || hash === '#join') {
          landingView.showCard('join', false);
        } else {
          landingView.showCard('landing', false);
        }
        return;
      }

      // 4. If on Results screen
      if (this.currentViewId === 'viewResults') {
        this.switchView('viewLanding', false);
        landingView.showCard('landing', false);
      }
    });
  }

  registerWebSocketHandlers() {
    // ---------------- Connection Lifecycle ----------------
    wsClient.on('WS_OPEN', () => {
      console.log('WebSocket connected.');
      // Attempt session restoration if token exists
      const saved = store.getSavedSession();
      if (saved && saved.playerId && saved.reconnectToken && saved.roomId) {
        console.log('Restoring previous session with token...', saved);
        const banner = document.getElementById('reconnectBanner');
        const bannerText = document.getElementById('reconnectBannerText');
        if (banner) banner.classList.remove('hidden');
        if (bannerText) bannerText.textContent = `Reconnecting to room ${saved.roomId}...`;

        wsClient.send('RECONNECT', {
          roomId: saved.roomId,
          playerId: saved.playerId,
          reconnectToken: saved.reconnectToken
        });
      }
    });

    // ---------------- Room Management ----------------
    wsClient.on('ROOM_CREATED', (payload) => {
      const { roomId, playerId, reconnectToken, room } = payload;
      store.updateRoom({
        roomId,
        hostId: playerId,
        gameMode: room.gameMode,
        status: 'LOBBY',
        config: room.config || store.get().room.config,
        players: room.players || [],
        teams: room.teams || store.get().room.teams
      });
      const myTeam = room.gameMode === 'TEAM' ? 'TEAM_A' : null;
      store.updatePlayer({ id: playerId, reconnectToken, isHost: true, teamId: myTeam });
      store.saveSession();

      this.switchView('viewLobby');
    });

    wsClient.on('PLAYER_JOINED', (payload) => {
      const { player, room } = payload;
      const state = store.get();

      if (room) {
        store.updateRoom({
          roomId: room.roomId,
          hostId: room.hostId,
          coHostId: room.coHostId || null,
          creatorUserId: room.creatorUserId || null,
          gameMode: room.gameMode,
          status: room.status,
          players: room.players || [],
          teams: room.teams || state.room.teams
        });
      }

      if (payload.yourPlayerId) {
        const isHost = room ? (payload.yourPlayerId === room.hostId) : false;
        const isCoHost = room ? (payload.yourPlayerId === room.coHostId) : false;
        let myTeamId = null;
        if (room && room.teams) {
          for (const [tKey, tVal] of Object.entries(room.teams)) {
            if ((tVal.players || []).some(p => p.id === payload.yourPlayerId)) {
              myTeamId = tKey;
              break;
            }
          }
        }
        if (!myTeamId && room?.players) {
          const found = room.players.find(p => p.id === payload.yourPlayerId);
          if (found && found.teamId) myTeamId = found.teamId;
        }
        store.updatePlayer({
          id: payload.yourPlayerId,
          reconnectToken: payload.reconnectToken,
          teamId: myTeamId,
          isHost,
          isCoHost
        });
      } else if (player) {
        const existing = state.room.players.filter(p => p.id !== player.id);
        store.updateRoom({ players: [...existing, player] });
      }

      store.saveSession();
      store.addActivity(`${player ? player.name : 'A player'} joined the room`);
      this.switchView('viewLobby');
    });

    wsClient.on('HOST_RESTORED', (payload) => {
      const { hostId, hostName, coHostId } = payload;
      const state = store.get();
      const amIHost = state.player.id === hostId;
      const amICoHost = state.player.id === coHostId;

      store.updateRoom({
        hostId,
        coHostId: coHostId || null
      });

      store.updatePlayer({
        isHost: amIHost,
        isCoHost: amICoHost
      });

      store.saveSession();

      if (amIHost) {
        store.addActivity('👑 Welcome back! You have resumed Host controls.');
      } else {
        store.addActivity(`👑 ${hostName || 'The original host'} returned and resumed Host control.`);
      }
    });

    wsClient.on('PLAYER_LEFT', (payload) => {
      const { playerId, newHostId, playerName, isCoHost } = payload;
      const state = store.get();
      const updatedPlayers = state.room.players.filter(p => p.id !== playerId);
      
      store.updateRoom({
        players: updatedPlayers,
        hostId: newHostId || state.room.hostId,
        coHostId: isCoHost ? newHostId : state.room.coHostId
      });

      if (newHostId && newHostId === state.player.id) {
        store.updatePlayer({ isHost: true, isCoHost: !!isCoHost });
        alert('The room host left or disconnected. You are now Acting Co-Host with room controls!');
      }

      store.addActivity(`${playerName || 'A player'} left the room`);
    });

    wsClient.on('PLAYER_DISCONNECTED', (payload) => {
      const { playerName } = payload;
      store.addActivity(`${playerName || 'A player'} disconnected (grace period active)`);
    });

    wsClient.on('PLAYER_RECONNECTED', (payload) => {
      const {
        player,
        room,
        yourPlayerId,
        status,
        currentRound,
        totalRounds,
        clueGiverId,
        clueGiverName,
        clueGiverTeam,
        serverTime,
        deadline,
        scoreboard,
        teams,
        recentChat
      } = payload;
      const state = store.get();

      if (yourPlayerId) {
        // This is the reconnecting client restoring its full session
        const saved = store.getSavedSession();
        const isHost = room ? (yourPlayerId === room.hostId) : (saved?.isHost ?? state.player.isHost);

        if (room) {
          store.updateRoom({
            ...room,
            hostId: room.hostId || (isHost ? yourPlayerId : state.room.hostId)
          });
        }

        store.updatePlayer({
          id: yourPlayerId,
          name: player?.name || saved?.playerName || state.player.name,
          reconnectToken: saved?.reconnectToken || state.player.reconnectToken,
          isHost: isHost
        });

        if (scoreboard) {
          store.updateRoom({ players: scoreboard });
        }
        if (teams) {
          store.updateRoom({ teams });
        }
        if (recentChat && Array.isArray(recentChat)) {
          store.set({ chatMessages: recentChat });
        }

        store.saveSession();

        // Hide reconnect banner
        const banner = document.getElementById('reconnectBanner');
        if (banner) banner.classList.add('hidden');

        const isGameRunning = status === 'IN_PROGRESS';
        if (isGameRunning) {
          const isMyTurn = yourPlayerId === clueGiverId;
          const isRoundActive = deadline && deadline > Date.now();
          store.updateRound({
            roundNumber: currentRound,
            totalRounds: totalRounds,
            clueGiverId,
            clueGiverName,
            clueGiverTeam,
            isMyClueGiverTurn: isMyTurn,
            isPreparing: !isRoundActive,
            serverTime,
            deadline,
            isRoundActive
          });

          if (isRoundActive) {
            roundTimer.start(serverTime || Date.now(), deadline, () => {
              store.updateRound({ isRoundActive: false });
            });
          }

          /* [TEMPORARILY DISABLED VOICE CLUE]
          if (isMyTurn) {
            liveKitVoice.enablePublishing();
          } else {
            liveKitVoice.disablePublishing();
          }
          */

          this.switchView('viewGame');
        } else {
          this.switchView('viewLobby');
        }

        store.addActivity('You have successfully reconnected!');
      } else {
        // Another player in the room reconnected
        if (room) {
          store.updateRoom(room);
        }
        store.addActivity(`${player ? player.name : 'A player'} reconnected`);
      }
    });

    wsClient.on('ROOM_UPDATED', (payload) => {
      if (payload.delta) {
        store.updateRoom(payload.delta);

        // Update local player state if present in delta
        const state = store.get();
        if (state.player.id && payload.delta.players) {
          const me = payload.delta.players.find(p => p.id === state.player.id);
          if (me) {
            store.updatePlayer({
              teamId: me.teamId || state.player.teamId,
              isHost: me.isHost,
              isCoHost: me.isCoHost
            });
          }
        }
      }
    });

    // ---------------- Game & Round Flow ----------------
    wsClient.on('GAME_STARTED', (payload) => {
      const { gameMode, totalRounds, config } = payload;
      store.updateRoom({
        status: 'IN_PROGRESS',
        gameMode: gameMode || store.get().room.gameMode,
        config: config || store.get().room.config
      });

      store.updateRound({
        totalRounds: totalRounds || 2
      });

      // [TEMPORARILY DISABLED VOICE CLUE]
      /*
      const { livekitToken, livekitUrl } = payload;
      if (livekitToken) {
        liveKitVoice.connect(livekitUrl, livekitToken);
      } else {
        const state = store.get();
        if (state.room.roomId && state.player.id) {
          this.initVoice(state.room.roomId, state.player.id);
        }
      }
      */

      this.switchView('viewGame');
    });

    wsClient.on('ROUND_PREPARING', (payload) => {
      const {
        roundNumber,
        totalRounds,
        clueGiverId,
        clueGiverName,
        clueGiverTeam
      } = payload;

      const state = store.get();
      const isMyTurn = state.player.id === clueGiverId || (state.room.gameMode === 'TEAM' && state.player.teamId && state.player.teamId === clueGiverTeam);

      roundTimer.stop();
      resultsView.hideRoundOverlay();

      store.updateRound({
        roundNumber,
        totalRounds: totalRounds || state.round.totalRounds,
        clueGiverId,
        clueGiverName,
        clueGiverTeam,
        isMyClueGiverTurn: isMyTurn,
        isPreparing: true,
        isRoundActive: false,
        secretMovie: null,
        myGuessSubmitted: false,
        alreadyScoredThisRound: false,
        roundScores: []
      });

      // Clear round clue feed for new round
      store.set({ clues: [] });

      // Clear movie selection fields
      const titleInput = document.getElementById('customMovieTitle');
      const hintInput = document.getElementById('customMovieHint');
      const yearInput = document.getElementById('customMovieYear');
      if (titleInput) titleInput.value = '';
      if (hintInput) hintInput.value = '';
      if (yearInput) yearInput.value = '';

      this.switchView('viewGame');
      store.addActivity(`Round ${roundNumber}: Clue-Giver ${clueGiverName} is choosing a secret movie`);
    });

    wsClient.on('SUGGESTED_MOVIE', (payload) => {
      const titleInput = document.getElementById('customMovieTitle');
      const hintInput = document.getElementById('customMovieHint');
      const yearInput = document.getElementById('customMovieYear');
      if (titleInput && payload.title) titleInput.value = payload.title;
      if (hintInput && payload.hint) hintInput.value = payload.hint;
      if (yearInput && payload.year) yearInput.value = payload.year;
    });

    wsClient.on('ROUND_STARTED', (payload) => {
      const {
        roundNumber,
        clueGiverId,
        clueGiverName,
        clueGiverTeam,
        serverTime,
        deadline,
        roundDurationMs
      } = payload;

      const state = store.get();
      const isMyTurn = state.player.id === clueGiverId || (state.room.gameMode === 'TEAM' && state.player.teamId && state.player.teamId === clueGiverTeam);

      resultsView.hideRoundOverlay();

      store.updateRound({
        roundNumber,
        clueGiverId,
        clueGiverName,
        clueGiverTeam,
        isMyClueGiverTurn: isMyTurn,
        isPreparing: false,
        secretMovie: state.round.secretMovie || null, // Preserve if already arrived via CLUE_GIVER_ANSWER
        serverTime,
        deadline,
        roundDurationMs,
        isRoundActive: true,
        myGuessSubmitted: false,
        alreadyScoredThisRound: false,
        firstCorrectGuesserFound: false,
        validatedGuessSeqs: new Set(),
        roundScores: []
      });

      // Clear round clue feed for new round
      store.set({ clues: [] });

      /* [TEMPORARILY DISABLED VOICE CLUE]
      // LiveKit voice role: clue-giver publishes, others listen
      if (isMyTurn) {
        liveKitVoice.enablePublishing();
      } else {
        liveKitVoice.disablePublishing();
      }
      */

      // Start client-side drift-free countdown
      roundTimer.start(serverTime, deadline, () => {
        store.updateRound({ isRoundActive: false });
      });

      this.switchView('viewGame');
    });

    // ---------------- Answer Validation & Authority ----------------
    // Clue-giver receives secret movie privately from backend
    wsClient.on('CLUE_GIVER_ANSWER', (payload) => {
      console.log('Secret movie received for clue-giver:', payload.title);
      store.updateRound({
        secretMovie: {
          id: payload.movieId,
          title: payload.title,
          year: payload.year,
          aliases: payload.aliases || [],
          hint: payload.hint || ''
        }
      });
    });

    // Clue-giver & clue-team receive forwarded guesses from guessers
    wsClient.on('FORWARD_GUESS', (payload) => {
      const state = store.get();
      const isClueTeam = state.round.isMyClueGiverTurn || (state.room.gameMode === 'TEAM' && state.player.teamId && state.player.teamId === state.round.clueGiverTeam);
      if (!isClueTeam) {
        return;
      }

      const { guesserId, guesserName, guesserTeam, guessText, guessSeq, isMatch, matchedWith } = payload;
      const secret = state.round.secretMovie;

      // Determine match from server or fallback to client validator if available
      let match = isMatch;
      let matchedTitle = matchedWith;
      if (match === undefined && secret && secret.title) {
        const result = AnswerValidator.evaluate(guessText, secret.title, secret.aliases);
        match = result.isMatch;
        matchedTitle = result.matchedWith;
      }

      // Log in clue-giver's validation audit panel
      store.addAuditLog({
        seq: guessSeq,
        guesserName,
        guessText,
        isMatch: !!match,
        matchedWith: matchedTitle || ''
      });

      // If matched and client authority is needed as backup
      if (match) {
        const validatedSeqs = state.round.validatedGuessSeqs;
        if (validatedSeqs.has(guessSeq)) {
          return;
        }

        // In Team mode, prevent duplicate scores for the same team
        if (state.room.gameMode === 'TEAM' && guesserTeam) {
          if (!state.round.scoredTeams) state.round.scoredTeams = new Set();
          if (state.round.scoredTeams.has(guesserTeam)) {
            return;
          }
          state.round.scoredTeams.add(guesserTeam);
        }

        validatedSeqs.add(guessSeq);

        const isFirstCorrect = !state.round.firstCorrectGuesserFound;
        store.updateRound({ firstCorrectGuesserFound: true });

        // Calculate score
        const pointsObj = ScoringEngine.calculatePoints({
          isFirstCorrect,
          config: state.room.config
        });

        // Clue-giver sends authoritative SCORE_UPDATE to backend (idempotent on server)
        wsClient.send('SCORE_UPDATE', {
          guesserId,
          guesserTeam,
          guessSeq,
          isCorrect: true,
          points: pointsObj.guesserPoints,
          clueGiverPoints: pointsObj.clueGiverPoints,
          guessText
        });
      }
    });

    // Guesser receives private result
    wsClient.on('GUESS_RESULT', (payload) => {
      const { isCorrect, points } = payload;
      if (isCorrect) {
        store.updateRound({ alreadyScoredThisRound: true, myGuessSubmitted: true });
        gameView.showFeedback(`Correct! You scored +${points} points! 🌟`, 'correct');
      } else {
        store.updateRound({ myGuessSubmitted: false });
        gameView.showFeedback('Incorrect guess. Try again!', 'incorrect');
      }
    });

    // Room-wide score broadcast
    wsClient.on('SCORE_BROADCAST', (payload) => {
      const { guesserName, guesserPoints, clueGiverName, clueGiverPoints, scoreboard, teams } = payload;
      
      // Update scoreboard in state
      if (scoreboard) {
        store.updateRoom({ players: scoreboard });
      }
      if (teams) {
        store.updateRoom({ teams });
      }

      store.addActivity(`${guesserName} guessed correctly! (+${guesserPoints} pts)`);
    });

    // ---------------- Clue & Chat Broadcasts ----------------
    wsClient.on('CLUE_BROADCAST', (payload) => {
      store.addClue({
        clueGiverId: payload.clueGiverId,
        message: payload.message,
        timestamp: payload.timestamp
      });
    });

    wsClient.on('CHAT_BROADCAST', (payload) => {
      store.addChatMessage({
        senderId: payload.senderId,
        senderName: payload.senderName,
        message: payload.message,
        timestamp: payload.timestamp
      });
    });

    // ---------------- Round & Game Termination ----------------
    wsClient.on('ROUND_FINISHED', (payload) => {
      roundTimer.stop();
      // liveKitVoice.disablePublishing(); // [TEMPORARILY DISABLED VOICE CLUE]
      store.updateRound({ isRoundActive: false });
      resultsView.showRoundFinished(payload);
    });

    wsClient.on('GAME_FINISHED', (payload) => {
      roundTimer.stop();
      // liveKitVoice.disconnect(); // [TEMPORARILY DISABLED VOICE CLUE]
      store.updateRound({ isRoundActive: false });
      store.updateRoom({ status: 'FINISHED' });
      resultsView.showGameFinished(payload);
    });

    // ---------------- Cancellation ----------------
    wsClient.on('CANCEL_VOTE_REQUESTED', (payload) => {
      cancelModalView.show(payload);
    });

    wsClient.on('CANCEL_VOTE_RESULT', (payload) => {
      const { approved, yesVotes, noVotes } = payload;
      alert(`Cancel vote finished. Result: ${approved ? 'Game Cancelled' : 'Keep Playing'} (${yesVotes} Yes vs ${noVotes} No)`);
    });

    wsClient.on('GAME_CANCELLED', (payload) => {
      roundTimer.stop();
      // liveKitVoice.disconnect(); // [TEMPORARILY DISABLED VOICE CLUE]
      alert(`Game cancelled: ${payload.reason || 'Vote passed'}`);
      store.updateRoom({ status: 'LOBBY' });
      this.switchView('viewLobby');
    });

    // ---------------- Error Handling ----------------
    wsClient.on('ERROR', (payload) => {
      console.error('Server error received:', payload);
      const code = payload.code;
      const message = payload.message || '';

      // Check if this error was a fatal reconnection failure
      const banner = document.getElementById('reconnectBanner');
      const isReconnecting = banner && !banner.classList.contains('hidden');
      const isReconnectFailure = (code === 'EXPIRED_SESSION' || code === 'RECONNECT_FAILED' || (code === 'ROOM_NOT_FOUND' && isReconnecting) || message.includes('Invalid reconnect token') || message.includes('grace period expired'));

      if (isReconnectFailure) {
        store.clearSavedSession();
        if (banner) banner.classList.add('hidden');

        const urlParams = new URLSearchParams(window.location.search);
        const roomParam = urlParams.get('room');
        if (roomParam) {
          landingView.showCard('join');
          const codeInput = document.getElementById('joinRoomCode');
          if (codeInput) codeInput.value = roomParam.toUpperCase().slice(0, 6);
        } else {
          this.switchView('viewLanding');
        }

        alert(`Session ended: ${message || 'Room no longer exists or session expired'}`);
        return;
      }

      // Normal in-game or lobby errors (e.g. invalid guess, deadline passed, unauthorized guesser)
      // must NEVER clear the session or kick the player out!
      if (gameView && typeof gameView.showFeedback === 'function') {
        gameView.showFeedback(message || 'Action not permitted', 'warning');
      } else {
        alert(`Notice: ${message || 'An error occurred'}`);
      }
    });
  }
}

// Instantiate and start app on DOM ready
document.addEventListener('DOMContentLoaded', () => {
  const app = new MovieClashApp();
  app.init();
});

