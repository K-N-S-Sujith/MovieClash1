/**
 * MovieClash — Landing & Room Creation/Join View Controller
 */

import store from '../state.js';
import wsClient from '../api/websocket.js';
import userProfile from '../profile.js';
import toast from '../utils/toast.js';

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
    const currentName = userProfile.getName().slice(0, 14);
    if (this.profileDisplayName) this.profileDisplayName.textContent = currentName;
    if (this.createPlayerNameInput) this.createPlayerNameInput.value = currentName;
    if (this.joinPlayerNameInput) this.joinPlayerNameInput.value = currentName;
    if (this.inputProfileName) this.inputProfileName.value = currentName;

    const countText = `${currentName.length}/14`;
    const createCounter = document.getElementById('createNameCounter');
    const joinCounter = document.getElementById('joinNameCounter');
    if (createCounter) createCounter.textContent = countText;
    if (joinCounter) joinCounter.textContent = countText;

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
      this.btnNavCreate.addEventListener('click', () => this.showCard('create', true));
    }
    if (this.btnNavJoin) {
      this.btnNavJoin.addEventListener('click', () => this.showCard('join', true));
    }

    // Back buttons
    document.querySelectorAll('.btn-back').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.preventDefault();
        if (window.location.hash === '#create' || window.location.hash === '#join') {
          window.history.back();
        } else {
          this.showCard('landing', true);
        }
      });
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

    // Custom Round Duration toggle
    const roundDurationSelect = document.getElementById('roundDuration');
    const customDurationBox = document.getElementById('customDurationBox');
    const customRoundDuration = document.getElementById('customRoundDuration');

    if (roundDurationSelect && customDurationBox) {
      roundDurationSelect.addEventListener('change', () => {
        if (roundDurationSelect.value === 'custom') {
          customDurationBox.classList.remove('hidden');
          if (customRoundDuration) {
            if (!customRoundDuration.value) customRoundDuration.value = '75';
            customRoundDuration.focus();
            customRoundDuration.select();
          }
        } else {
          customDurationBox.classList.add('hidden');
        }
      });
    }

    // Custom Total Rounds toggle
    const totalRoundsSelect = document.getElementById('totalRounds');
    const customRoundsBox = document.getElementById('customRoundsBox');
    const customTotalRounds = document.getElementById('customTotalRounds');

    if (totalRoundsSelect && customRoundsBox) {
      totalRoundsSelect.addEventListener('change', () => {
        if (totalRoundsSelect.value === 'custom') {
          customRoundsBox.classList.remove('hidden');
          if (customTotalRounds) {
            if (!customTotalRounds.value) customTotalRounds.value = '4';
            customTotalRounds.focus();
            customTotalRounds.select();
          }
        } else {
          customRoundsBox.classList.add('hidden');
        }
      });
    }

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

    // Check for room code in URL query string (?room=ABC123) or hash (#create, #join)
    const saved = store.getSavedSession();
    const urlParams = new URLSearchParams(window.location.search);
    const roomParam = urlParams.get('room');
    if (roomParam && !saved && this.viewJoin) {
      const codeInput = document.getElementById('joinRoomCode');
      if (codeInput) codeInput.value = roomParam.toUpperCase().slice(0, 6);
      this.showCard('join', false);
    } else if (!saved) {
      const hash = window.location.hash;
      if (hash === '#create') {
        this.showCard('create', false);
      } else if (hash === '#join') {
        this.showCard('join', false);
      }
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
    const clean = userProfile.setName((val || '').slice(0, 14));
    if (this.profileDisplayName) this.profileDisplayName.textContent = clean;
    if (this.createPlayerNameInput && this.createPlayerNameInput.value !== clean) {
      this.createPlayerNameInput.value = clean;
    }
    if (this.joinPlayerNameInput && this.joinPlayerNameInput.value !== clean) {
      this.joinPlayerNameInput.value = clean;
    }
    const countText = `${clean.length}/14`;
    const createCounter = document.getElementById('createNameCounter');
    const joinCounter = document.getElementById('joinNameCounter');
    if (createCounter) createCounter.textContent = countText;
    if (joinCounter) joinCounter.textContent = countText;

    store.updatePlayer({ name: clean });
  }

  showCard(name, pushHistory = true) {
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
      if (pushHistory && window.location.hash !== '#create') {
        history.pushState({ card: 'create' }, '', '#create');
      }
    } else if (name === 'join' && this.viewJoin) {
      this.viewJoin.classList.remove('hidden');
      this.viewJoin.classList.add('active');
      document.getElementById('joinRoomCode')?.focus();
      if (pushHistory && window.location.hash !== '#join') {
        history.pushState({ card: 'join' }, '', '#join');
      }
    } else if (this.viewLanding) {
      this.viewLanding.classList.remove('hidden');
      this.viewLanding.classList.add('active');
      if (pushHistory && window.location.hash) {
        const cleanUrl = window.location.pathname + (window.location.search || '');
        history.pushState({ card: 'landing' }, '', cleanUrl);
      }
    }
  }

  handleCreateRoom() {
    const rawName = document.getElementById('createPlayerName').value.trim();
    const playerName = (rawName || userProfile.getName()).slice(0, 14);
    if (!playerName || playerName.length < 2) {
      toast.show('Please enter a player name with at least 2 characters.', 'warning');
      document.getElementById('createPlayerName')?.focus();
      return;
    }
    this.syncName(playerName);

    const gameMode = document.querySelector('input[name="gameMode"]:checked')?.value || 'INDIVIDUAL';
    const movieSelection = document.querySelector('input[name="movieSelection"]:checked')?.value || 'manual';
    const autoStartRound = (movieSelection === 'auto');

    // Parse round duration (preset or custom)
    let roundDurationSec = 60;
    const durationSelectVal = document.getElementById('roundDuration')?.value;
    if (durationSelectVal === 'custom') {
      const customVal = parseInt(document.getElementById('customRoundDuration')?.value, 10);
      if (!customVal || customVal < 10 || customVal > 600) {
        toast.show('Please enter a valid round duration between 10 and 600 seconds.', 'warning');
        document.getElementById('customRoundDuration')?.focus();
        return;
      }
      roundDurationSec = customVal;
    } else {
      roundDurationSec = parseInt(durationSelectVal, 10) || 60;
    }

    // Parse total rounds / turns (preset or custom)
    let totalRounds = 2;
    const roundsSelectVal = document.getElementById('totalRounds')?.value;
    if (roundsSelectVal === 'custom') {
      const customVal = parseInt(document.getElementById('customTotalRounds')?.value, 10);
      if (!customVal || customVal < 1 || customVal > 50) {
        toast.show('Please enter a valid number of turns/rounds between 1 and 50.', 'warning');
        document.getElementById('customTotalRounds')?.focus();
        return;
      }
      totalRounds = customVal;
    } else {
      totalRounds = parseInt(roundsSelectVal, 10) || 2;
    }

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
      toast.show('Cannot connect to game server. Please make sure the Spring Boot backend is running on port 8080.', 'error');
    }
  }

  handleJoinRoom() {
    const rawName = document.getElementById('joinPlayerName').value.trim();
    const playerName = (rawName || userProfile.getName()).slice(0, 14);
    if (!playerName || playerName.length < 2) {
      toast.show('Please enter a player name with at least 2 characters.', 'warning');
      document.getElementById('joinPlayerName')?.focus();
      return;
    }
    this.syncName(playerName);

    const roomCode = document.getElementById('joinRoomCode').value.trim().toUpperCase();

    if (!roomCode || roomCode.length < 4) {
      toast.show('Please enter a valid 6-character room code.', 'warning');
      document.getElementById('joinRoomCode')?.focus();
      return;
    }

    const userId = userProfile.getUserId();
    const asSpectator = Boolean(document.getElementById('joinAsSpectator')?.checked);
    store.updatePlayer({ userId, name: playerName, isHost: false, isSpectator: asSpectator });

    const sent = wsClient.send('JOIN_ROOM', {
      userId,
      roomId: roomCode,
      playerName,
      asSpectator
    });

    if (!sent) {
      toast.show('Cannot connect to game server. Please make sure the Spring Boot backend is running on port 8080.', 'error');
    }
  }
}

export const landingView = new LandingView();
export default landingView;
