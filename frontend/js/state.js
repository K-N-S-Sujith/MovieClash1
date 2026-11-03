/**
 * MovieClash — Reactive Client State Store
 * In-memory client-side state with event-based subscribers.
 */

class GameStateStore {
  constructor() {
    this.listeners = new Set();
    this.reset();
  }

  reset() {
    this.state = {
      // Local player session
      player: {
        id: null,
        userId: null,
        name: null,
        reconnectToken: null,
        score: 0,
        teamId: null,
        isHost: false,
        isCoHost: false
      },

      // Room metadata
      room: {
        roomId: null,
        hostId: null,
        coHostId: null,
        creatorUserId: null,
        gameMode: 'INDIVIDUAL', // 'INDIVIDUAL' | 'TEAM'
        status: 'LOBBY',        // 'LOBBY' | 'IN_PROGRESS' | 'PAUSED' | 'FINISHED' | 'CANCELLED'
        config: {
          roundDurationSec: 60,
          totalRounds: 2,
          correctGuessPoints: 100,
          firstGuessBonus: 50,
          clueGiverRewardPerGuesser: 25
        },
        players: [], // Array of player objects
        teams: {
          TEAM_A: { name: 'Team Alpha', players: [] },
          TEAM_B: { name: 'Team Beta', players: [] }
        }
      },

      // Active round state
      round: {
        roundNumber: 0,
        totalRounds: 0,
        clueGiverId: null,
        clueGiverName: null,
        clueGiverTeam: null,
        isMyClueGiverTurn: false,
        secretMovie: null,       // Loaded only if isMyClueGiverTurn = true
        serverTime: 0,
        deadline: 0,
        roundDurationMs: 60000,
        isRoundActive: false,
        myGuessSubmitted: false,
        alreadyScoredThisRound: false,
        firstCorrectGuesserFound: false,
        validatedGuessSeqs: new Set(), // Prevents duplicate score processing on client
        roundScores: [],
        maskedTitle: '',
        revealedLettersCount: 0,
        totalLettersCount: 0,
        movieHint: '',
        movieYear: ''
      },

      // Feeds
      clues: [],                 // Clues posted by clue-giver
      chatMessages: [],          // Lobby / between-rounds chat
      auditLog: [],              // Clue-giver validation stream
      roundActivity: [],         // Real-time events (guesses, points)

      // Active cancellation proposal
      cancelVote: {
        active: false,
        proposerId: null,
        proposerName: null,
        timeoutMs: 15000,
        hasVoted: false
      },

      // Connection & Latency
      connection: {
        serverHealthy: false,
        wsConnected: false,
        latencyMs: null,
        reconnecting: false
      }
    };
  }

  get() {
    return this.state;
  }

  set(partial) {
    this.state = { ...this.state, ...partial };
    this.notify();
  }

  saveSession() {
    const { player, room } = this.state;
    // Prefer room.roomId from state, or check url query param
    const urlParams = new URLSearchParams(window.location.search);
    const roomId = room.roomId || urlParams.get('room');

    if (player.id && player.reconnectToken && roomId) {
      const data = {
        playerId: player.id,
        userId: player.userId,
        playerName: player.name,
        reconnectToken: player.reconnectToken,
        roomId: roomId,
        isHost: !!player.isHost,
        isCoHost: !!player.isCoHost
      };
      sessionStorage.setItem('movieclash_session', JSON.stringify(data));
      localStorage.setItem('movieclash_session', JSON.stringify(data));

      // Keep room code in URL so refresh always knows room
      if (roomId && !window.location.search.includes(roomId)) {
        window.history.replaceState({}, '', `?room=${roomId}`);
      }
    }
  }

  updatePlayer(partial) {
    this.state.player = { ...this.state.player, ...partial };
    this.saveSession();
    this.notify();
  }

  updateRoom(partial) {
    this.state.room = { ...this.state.room, ...partial };
    this.saveSession();
    this.notify();
  }

  updateRound(partial) {
    this.state.round = { ...this.state.round, ...partial };
    this.notify();
  }

  addClue(clue) {
    this.state.clues.push(clue);
    this.notify();
  }

  addChatMessage(msg) {
    this.state.chatMessages.push(msg);
    if (this.state.chatMessages.length > 50) {
      this.state.chatMessages.shift();
    }
    this.notify();
  }

  addAuditLog(entry) {
    this.state.auditLog.push(entry);
    if (this.state.auditLog.length > 50) {
      this.state.auditLog.shift();
    }
    this.notify();
  }

  addActivity(text) {
    this.state.roundActivity.push({ id: Math.random(), text, time: new Date().toLocaleTimeString() });
    if (this.state.roundActivity.length > 30) {
      this.state.roundActivity.shift();
    }
    this.notify();
  }

  subscribe(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  notify() {
    for (const listener of this.listeners) {
      try {
        listener(this.state);
      } catch (err) {
        console.error('State subscriber error:', err);
      }
    }
  }

  getSavedSession() {
    try {
      const urlParams = new URLSearchParams(window.location.search);
      const urlRoom = urlParams.get('room');

      let raw = sessionStorage.getItem('movieclash_session');
      if (!raw) {
        raw = localStorage.getItem('movieclash_session');
      }
      if (!raw) return null;
      const data = JSON.parse(raw);
      if (!data.roomId) {
        data.roomId = urlRoom;
      }

      // If user opened an invite link (?room=XYZ) for a DIFFERENT room than their saved session,
      // do not reconnect to the old room!
      if (urlRoom && data.roomId && urlRoom.toUpperCase() !== data.roomId.toUpperCase()) {
        console.log(`URL room parameter [${urlRoom}] does not match saved session room [${data.roomId}]. Ignoring old session.`);
        return null;
      }

      return (data.playerId && data.reconnectToken && data.roomId) ? data : null;
    } catch (e) {
      return null;
    }
  }

  clearSavedSession() {
    sessionStorage.removeItem('movieclash_session');
    localStorage.removeItem('movieclash_session');
    window.history.replaceState({}, '', window.location.pathname);
  }
}

export const store = new GameStateStore();
export default store;

