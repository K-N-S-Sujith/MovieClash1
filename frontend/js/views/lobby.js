/**
 * MovieClash — Lobby View Controller
 * Handles player roster, team selection, invite links, and host start game trigger.
 */

import store from '../state.js';
import wsClient from '../api/websocket.js';

export class LobbyView {
  constructor() {
    this.viewLobby = document.getElementById('viewLobby');
    this.roomCodeEl = document.getElementById('lobbyRoomCode');
    this.modeBadgeEl = document.getElementById('lobbyModeBadge');
    this.playerCountBadgeEl = document.getElementById('lobbyPlayerCountBadge');
    this.individualListEl = document.getElementById('individualPlayerList');
    this.teamSelectorBlock = document.getElementById('teamSelectorBlock');
    this.teamButtonsEl = document.getElementById('teamSelectButtons');
    this.teamsContainerEl = document.getElementById('teamsContainer');
    this.btnStartGame = document.getElementById('btnStartGame');
    this.hostNotice = document.getElementById('hostNotice');
    this.btnLeaveRoom = document.getElementById('btnLeaveRoom');
    this.btnCopyCode = document.getElementById('btnCopyRoomCode');
    this.btnCopyLink = document.getElementById('btnCopyInviteLink');
    this.btnToggleRole = document.getElementById('btnToggleRole');
    this.spectatorsSection = document.getElementById('spectatorsSection');
    this.spectatorCountNum = document.getElementById('spectatorCountNum');
    this.spectatorPlayerList = document.getElementById('spectatorPlayerList');

    this.initEvents();
  }

  initEvents() {
    if (this.btnToggleRole) {
      this.btnToggleRole.addEventListener('click', () => {
        const isCurrentlySpectator = Boolean(store.get().player.isSpectator);
        wsClient.send('SWITCH_ROLE', { spectator: !isCurrentlySpectator });
      });
    }

    if (this.btnStartGame) {
      this.btnStartGame.addEventListener('click', () => {
        wsClient.send('START_GAME', {});
      });
    }

    if (this.btnLeaveRoom) {
      this.btnLeaveRoom.addEventListener('click', () => {
        wsClient.send('LEAVE_ROOM', {});
        store.clearSavedSession();
        store.reset();
        window.location.href = window.location.pathname;
      });
    }

    if (this.btnCopyCode) {
      this.btnCopyCode.addEventListener('click', () => {
        const code = store.get().room.roomId;
        if (code) {
          navigator.clipboard.writeText(code);
          this.btnCopyCode.textContent = 'Copied!';
          setTimeout(() => { this.btnCopyCode.textContent = 'Copy Code'; }, 1800);
        }
      });
    }

    if (this.btnCopyLink) {
      this.btnCopyLink.addEventListener('click', () => {
        const code = store.get().room.roomId;
        if (code) {
          const url = `${window.location.origin}${window.location.pathname}?room=${code}`;
          navigator.clipboard.writeText(url);
          this.btnCopyLink.textContent = 'Link Copied!';
          setTimeout(() => { this.btnCopyLink.textContent = 'Copy Link'; }, 1800);
        }
      });
    }

    // Subscribe to state changes to re-render lobby elements
    store.subscribe((state) => {
      if (state.room.status === 'LOBBY' && state.room.roomId) {
        this.render(state);
      }
    });
  }

  render(state) {
    const { room, player } = state;

    if (this.roomCodeEl) this.roomCodeEl.textContent = room.roomId || '------';
    if (this.modeBadgeEl) this.modeBadgeEl.textContent = room.gameMode;

    const activePlayers = room.players.filter(p => !p.spectator);
    const spectators = room.players.filter(p => p.spectator);

    if (this.playerCountBadgeEl) {
      this.playerCountBadgeEl.textContent = `${activePlayers.length} Player${activePlayers.length === 1 ? '' : 's'}${spectators.length > 0 ? ` (${spectators.length} 🍿)` : ''}`;
    }

    if (this.btnToggleRole) {
      if (player.isSpectator) {
        this.btnToggleRole.textContent = '🎮 Join as Player';
        this.btnToggleRole.classList.add('active-spectator');
      } else {
        this.btnToggleRole.textContent = '🍿 Watch as Spectator';
        this.btnToggleRole.classList.remove('active-spectator');
      }
    }

    const isHost = player.id && player.id === room.hostId;
    const isCoHost = (player.id && player.id === room.coHostId) || player.isCoHost;
    const canControl = isHost || isCoHost;

    if (this.btnStartGame && this.hostNotice) {
      if (canControl) {
        this.btnStartGame.classList.remove('hidden');
        this.hostNotice.classList.add('hidden');

        if (isCoHost && !isHost) {
          this.btnStartGame.textContent = 'Start Game (Co-Host)';
          this.btnStartGame.title = 'You are Acting Co-Host while the original Host is away';
        } else {
          this.btnStartGame.textContent = 'Start Game';
          this.btnStartGame.title = '';
        }

        // Can start if >= 2 active players (or Team conditions met)
        let canStart = activePlayers.length >= 2;
        if (room.gameMode === 'TEAM') {
          // At least 2 teams with at least 1 player each
          const activeTeams = Object.values(room.teams || {}).filter(t => t.players && t.players.some(p => !p.spectator));
          canStart = activeTeams.length >= 2;
        }
        this.btnStartGame.disabled = !canStart;
      } else {
        this.btnStartGame.classList.add('hidden');
        this.hostNotice.classList.remove('hidden');
      }
    }

    if (room.gameMode === 'TEAM') {
      if (this.individualListEl) this.individualListEl.classList.add('hidden');
      if (this.teamSelectorBlock) this.teamSelectorBlock.classList.remove('hidden');
      this.renderTeamMode(room, player);
    } else {
      if (this.teamSelectorBlock) this.teamSelectorBlock.classList.add('hidden');
      if (this.individualListEl) this.individualListEl.classList.remove('hidden');
      this.renderIndividualMode(room, player);
    }

    this.renderSpectators(spectators, player);
  }

  renderSpectators(spectators, currentPlayer) {
    if (!this.spectatorsSection || !this.spectatorPlayerList) return;

    if (!spectators || spectators.length === 0) {
      this.spectatorsSection.classList.add('hidden');
      return;
    }

    this.spectatorsSection.classList.remove('hidden');
    if (this.spectatorCountNum) {
      this.spectatorCountNum.textContent = spectators.length;
    }

    this.spectatorPlayerList.innerHTML = '';
    spectators.forEach(p => {
      const item = document.createElement('div');
      item.className = `roster-item spectator-item ${p.id === currentPlayer?.id ? 'is-me' : ''}`;
      const playerName = p.name || 'Spectator';
      const initial = playerName.charAt(0).toUpperCase();

      item.innerHTML = `
        <div class="roster-player-info">
          <div class="player-avatar spectator-avatar">${initial}</div>
          <span class="player-name">${this.escapeHtml(playerName)} ${p.id === currentPlayer?.id ? '(You)' : ''}</span>
        </div>
        <div>
          <span class="spectator-tag">🍿 Audience</span>
        </div>
      `;
      this.spectatorPlayerList.appendChild(item);
    });
  }

  renderIndividualMode(room, currentPlayer) {
    if (!this.individualListEl) return;
    this.individualListEl.innerHTML = '';

    const activePlayers = room.players.filter(p => !p.spectator);

    activePlayers.forEach(p => {
      const item = document.createElement('div');
      item.className = `roster-item ${p.id === currentPlayer?.id ? 'is-me' : ''}`;

      const isHost = p.id === room.hostId || p.isHost;
      const isCoHost = (p.id === room.coHostId || p.isCoHost) && !isHost;
      const playerName = p.name || (p.id === currentPlayer?.id ? currentPlayer?.name : null) || 'Player';
      const initial = playerName.charAt(0).toUpperCase();

      item.innerHTML = `
        <div class="roster-player-info">
          <div class="player-avatar">${initial}</div>
          <span class="player-name">${this.escapeHtml(playerName)} ${p.id === currentPlayer?.id ? '(You)' : ''}</span>
        </div>
        <div>
          ${isHost ? '<span class="host-tag">👑 Host</span>' : ''}
          ${isCoHost ? '<span class="co-host-tag">⭐ Co-Host</span>' : ''}
        </div>
      `;
      this.individualListEl.appendChild(item);
    });
  }

  renderTeamMode(room, currentPlayer) {
    if (!this.teamButtonsEl || !this.teamsContainerEl) return;

    const availableTeams = room.teams || {
      TEAM_A: { name: 'Team Alpha', players: [] },
      TEAM_B: { name: 'Team Beta', players: [] }
    };

    // Detect current player's actual team from roster if not set
    let myTeamId = currentPlayer?.teamId;
    for (const [tKey, tVal] of Object.entries(availableTeams)) {
      if ((tVal.players || []).some(p => p.id === currentPlayer?.id)) {
        myTeamId = tKey;
        break;
      }
    }
    // Update store silently to avoid triggering recursive render loops
    if (myTeamId && myTeamId !== currentPlayer?.teamId) {
      if (store.state && store.state.player) {
        store.state.player.teamId = myTeamId;
      }
    }

    // Render team select pills at top
    this.teamButtonsEl.innerHTML = '';
    Object.keys(availableTeams).forEach(teamKey => {
      const team = availableTeams[teamKey];
      const btn = document.createElement('button');
      btn.type = 'button';
      const isMyTeam = (myTeamId === teamKey);
      btn.className = `team-pill-btn ${isMyTeam ? 'active' : ''}`;
      btn.innerHTML = `${this.escapeHtml(team.name || teamKey)} ${isMyTeam ? '✓ (Your Team)' : ''}`;
      btn.addEventListener('click', () => {
        if (!isMyTeam) {
          wsClient.send('ASSIGN_TEAM', { teamId: teamKey });
        }
      });
      this.teamButtonsEl.appendChild(btn);
    });

    // Render team columns with direct switch action
    this.teamsContainerEl.innerHTML = '';
    Object.keys(availableTeams).forEach(teamKey => {
      const team = availableTeams[teamKey];
      const isMyTeam = (myTeamId === teamKey);
      const teamCount = (team.players || []).length;
      const isFull = teamCount >= 5;

      const box = document.createElement('div');
      box.className = `team-box ${isMyTeam ? 'my-team-box' : ''}`;

      let playersHtml = '';
      (team.players || []).forEach(p => {
        const isHost = p.id === room.hostId || p.isHost;
        const isCoHost = (p.id === room.coHostId || p.isCoHost) && !isHost;
        const playerName = p.name || (p.id === currentPlayer?.id ? currentPlayer?.name : null) || 'Player';
        playersHtml += `
          <div class="roster-item ${p.id === currentPlayer?.id ? 'is-me' : ''}" style="margin-bottom:6px">
            <span class="player-name">${this.escapeHtml(playerName)} ${p.id === currentPlayer?.id ? '(You)' : ''}</span>
            ${isHost ? '<span class="host-tag">👑 Host</span>' : ''}
            ${isCoHost ? '<span class="co-host-tag">⭐ Co-Host</span>' : ''}
          </div>
        `;
      });

      if (!team.players || team.players.length === 0) {
        playersHtml = '<span class="text-muted text-sm">No players yet</span>';
      }

      let actionHtml = '';
      if (isMyTeam) {
        actionHtml = `<span class="badge-team-current">✓ Your Team</span>`;
      } else if (isFull) {
        actionHtml = `<span class="text-muted text-xs">Team Full (5/5)</span>`;
      } else {
        actionHtml = `<button type="button" class="btn-sm btn-primary btn-switch-team" data-team="${teamKey}">⇄ Switch to ${this.escapeHtml(team.name || teamKey)}</button>`;
      }

      box.innerHTML = `
        <div class="team-box-header">
          <div class="team-title-row">
            <span class="team-name">${this.escapeHtml(team.name || teamKey)}</span>
            <span class="team-count text-muted text-xs">(${teamCount}/5)</span>
          </div>
          <div class="team-action-slot">${actionHtml}</div>
        </div>
        <div class="team-roster">${playersHtml}</div>
      `;

      const switchBtn = box.querySelector('.btn-switch-team');
      if (switchBtn) {
        switchBtn.addEventListener('click', () => {
          wsClient.send('ASSIGN_TEAM', { teamId: teamKey });
        });
      }

      this.teamsContainerEl.appendChild(box);
    });
  }

  escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;')
              .replace(/</g, '&lt;')
              .replace(/>/g, '&gt;')
              .replace(/"/g, '&quot;')
              .replace(/'/g, '&#039;');
  }
}

export const lobbyView = new LobbyView();
export default lobbyView;

