/**
 * MovieClash — Landing & Room Creation/Join View Controller
 */

import store from '../state.js';
import wsClient from '../api/websocket.js';
import userProfile from '../profile.js';

export class LandingView {
  constructor() {
    this.viewLanding = document.getElementById('viewLanding');
    this.viewCreate = document.getElementById('viewCreateRoom');
    this.viewJoin = document.getElementById('viewJoinRoom');

    this.btnNavCreate = document.getElementById('btnNavCreateRoom');
    this.btnNavJoin = document.getElementById('btnNavJoinRoom');
    this.createForm = document.getElementById('createRoomForm');
    this.joinForm = document.getElementById('joinRoomForm');

    // Profile elements
    this.profileDisplayName = document.getElementById('profileDisplayName');
    this.btnEditProfile = document.getElementById('btnEditProfile');
    this.profileEditBox = document.getElementById('profileEditBox');
    this.inputProfileName = document.getElementById('inputProfileName');
    this.btnSaveProfile = document.getElementById('btnSaveProfile');
    this.btnCancelProfile = document.getElementById('btnCancelProfile');

    this.createPlayerNameInput = document.getElementById('createPlayerName');
    this.joinPlayerNameInput = document.getElementById('joinPlayerName');

    this.initProfile();
    this.initEvents();
  }

  initProfile() {
    const currentName = userProfile.getName();
    if (this.profileDisplayName) this.profileDisplayName.textContent = currentName;
    if (this.createPlayerNameInput) this.createPlayerNameInput.value = currentName;
    if (this.joinPlayerNameInput) this.joinPlayerNameInput.value = currentName;
    if (this.inputProfileName) this.inputProfileName.value = currentName;

    // Auto-sync store with initial user profile identity
    store.updatePlayer({
      userId: userProfile.getUserId(),
      name: currentName
    });
  }

  initEvents() {
    // Profile Edit toggle
    if (this.btnEditProfile) {
      this.btnEditProfile.addEventListener('click', () => {
        if (this.profileEditBox) {
          this.profileEditBox.classList.remove('hidden');
          if (this.inputProfileName) {
            this.inputProfileName.value = userProfile.getName();
            this.inputProfileName.focus();
          }
        }
      });
    }

    if (this.btnCancelProfile) {
      this.btnCancelProfile.addEventListener('click', () => {
        if (this.profileEditBox) this.profileEditBox.classList.add('hidden');
      });
    }

    if (this.btnSaveProfile) {
      this.btnSaveProfile.addEventListener('click', () => {
        this.saveInlineProfile();
      });
    }

    if (this.inputProfileName) {
      this.inputProfileName.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          this.saveInlineProfile();
        } else if (e.key === 'Escape') {
          if (this.profileEditBox) this.profileEditBox.classList.add('hidden');
        }
      });
    }

    // Auto-sync form inputs when user types
    if (this.createPlayerNameInput) {
      this.createPlayerNameInput.addEventListener('input', (e) => {
        this.syncName(e.target.value);
      });
    }
    if (this.joinPlayerNameInput) {
      this.joinPlayerNameInput.addEventListener('input', (e) => {
        this.syncName(e.target.value);
      });
    }

    // Navigation between landing cards
    if (this.btnNavCreate) {
      this.btnNavCreate.addEventListener('click', () => this.showCard('create'));
    }
    if (this.btnNavJoin) {
      this.btnNavJoin.addEventListener('click', () => this.showCard('join'));
    }

    // Back buttons
    document.querySelectorAll('.btn-back').forEach(btn => {
      btn.addEventListener('click', () => this.showCard('landing'));
    });

    // Radio toggle styling scoped to group
    document.querySelectorAll('.radio-toggle-group input[type="radio"]').forEach(radio => {
      radio.addEventListener('change', (e) => {
        const group = e.target.closest('.radio-toggle-group');
        if (group) {
          group.querySelectorAll('.toggle-option').forEach(el => el.classList.remove('selected'));
        }
        e.target.closest('.toggle-option')?.classList.add('selected');
      });
    });

    // Create Room Submit
    if (this.createForm) {
      this.createForm.addEventListener('submit', (e) => {
        e.preventDefault();
        this.handleCreateRoom();
      });
    }

    // Join Room Submit
    if (this.joinForm) {
      this.joinForm.addEventListener('submit', (e) => {
        e.preventDefault();
        this.handleJoinRoom();
      });
    }

    // Check for room code in URL query string (?room=ABC123)
    const saved = store.getSavedSession();
    const urlParams = new URLSearchParams(window.location.search);
    const roomParam = urlParams.get('room');
    if (roomParam && !saved && this.viewJoin) {
      const codeInput = document.getElementById('joinRoomCode');
      if (codeInput) codeInput.value = roomParam.toUpperCase().slice(0, 6);
      this.showCard('join');
    }
  }

  saveInlineProfile() {
    const val = this.inputProfileName ? this.inputProfileName.value.trim() : '';
    if (val) {
      this.syncName(val);
    }
    if (this.profileEditBox) this.profileEditBox.classList.add('hidden');
  }

  syncName(val) {
    if (!val || !val.trim()) return;
    const clean = userProfile.setName(val);
    if (this.profileDisplayName) this.profileDisplayName.textContent = clean;
    if (this.createPlayerNameInput && this.createPlayerNameInput.value !== clean) {
      this.createPlayerNameInput.value = clean;
    }
    if (this.joinPlayerNameInput && this.joinPlayerNameInput.value !== clean) {
      this.joinPlayerNameInput.value = clean;
    }
    store.updatePlayer({ name: clean });
  }

  showCard(name) {
    if (this.viewLanding) {
      this.viewLanding.classList.remove('active');
      this.viewLanding.classList.add('hidden');
    }
    if (this.viewCreate) {
      this.viewCreate.classList.remove('active');
      this.viewCreate.classList.add('hidden');
    }
    if (this.viewJoin) {
      this.viewJoin.classList.remove('active');
      this.viewJoin.classList.add('hidden');
    }

    if (name === 'create' && this.viewCreate) {
      this.viewCreate.classList.remove('hidden');
      this.viewCreate.classList.add('active');
      // Already filled from profile; no need to focus on name
    } else if (name === 'join' && this.viewJoin) {
      this.viewJoin.classList.remove('hidden');
      this.viewJoin.classList.add('active');
      document.getElementById('joinRoomCode')?.focus();
    } else if (this.viewLanding) {
      this.viewLanding.classList.remove('hidden');
      this.viewLanding.classList.add('active');
    }
  }

  handleCreateRoom() {
    const rawName = document.getElementById('createPlayerName').value.trim();
    const playerName = rawName || userProfile.getName();
    this.syncName(playerName);

    const gameMode = document.querySelector('input[name="gameMode"]:checked')?.value || 'INDIVIDUAL';
    const movieSelection = document.querySelector('input[name="movieSelection"]:checked')?.value || 'manual';
    const autoStartRound = (movieSelection === 'auto');
    const roundDurationSec = parseInt(document.getElementById('roundDuration').value, 10) || 60;
    const totalRounds = parseInt(document.getElementById('totalRounds').value, 10) || 2;

    const userId = userProfile.getUserId();
    store.updatePlayer({ userId, name: playerName, isHost: true });

    const sent = wsClient.send('CREATE_ROOM', {
      userId,
      playerName,
      gameMode,
      config: {
        roundDurationSec,
        totalRounds,
        correctGuessPoints: 100,
        firstGuessBonus: 50,
        clueGiverRewardPerGuesser: 25,
        autoStartRound
      }
    });

    if (!sent) {
      alert('Cannot connect to game server. Please make sure the Spring Boot backend is running on port 8080.');
    }
  }

  handleJoinRoom() {
    const rawName = document.getElementById('joinPlayerName').value.trim();
    const playerName = rawName || userProfile.getName();
    this.syncName(playerName);

    const roomCode = document.getElementById('joinRoomCode').value.trim().toUpperCase();

    if (!roomCode) {
      alert('Please enter a valid 6-character room code.');
      return;
    }

    const userId = userProfile.getUserId();
    store.updatePlayer({ userId, name: playerName, isHost: false });

    const sent = wsClient.send('JOIN_ROOM', {
      userId,
      roomId: roomCode,
      playerName
    });

    if (!sent) {
      alert('Cannot connect to game server. Please make sure the Spring Boot backend is running on port 8080.');
    }
  }
}

export const landingView = new LandingView();
export default landingView;
