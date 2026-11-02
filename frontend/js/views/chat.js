/**
 * MovieClash — Text Chat & Clue Broadcast Controller
 * Rules:
 * - Clue-giver clues: CLUE_MESSAGE -> CLUE_BROADCAST
 * - Chat: CHAT_MESSAGE -> CHAT_BROADCAST (disabled during round for guessers)
 * - Rate limited: 1 msg/second, max 200 chars, plain text sanitized
 */

import store from '../state.js';
import wsClient from '../api/websocket.js';

export class ChatView {
  constructor() {
    // Lobby Chat
    this.lobbyChatForm = document.getElementById('lobbyChatForm');
    this.lobbyChatInput = document.getElementById('lobbyChatInput');
    this.lobbyChatFeed = document.getElementById('lobbyChatMessages');

    // Clue Form & Feed
    this.clueForm = document.getElementById('clueMessageForm');
    this.clueInput = document.getElementById('clueTextInput');
    this.clueFeed = document.getElementById('clueMessagesFeed');
    this.feedClueGiverName = document.getElementById('feedClueGiverName');

    // Round Chat (Between rounds)
    this.roundChatForm = document.getElementById('roundChatForm');
    this.roundChatInput = document.getElementById('roundChatInput');
    this.roundChatFeed = document.getElementById('roundChatFeed');
    this.chatDisabledNotice = document.getElementById('chatDisabledNotice');

    this.lastMessageTime = 0;

    this.initEvents();
  }

  initEvents() {
    // Lobby chat submission
    if (this.lobbyChatForm) {
      this.lobbyChatForm.addEventListener('submit', (e) => {
        e.preventDefault();
        this.handleSendChatMessage(this.lobbyChatInput);
      });
    }

    // Round clue submission (Clue-giver only)
    if (this.clueForm) {
      this.clueForm.addEventListener('submit', (e) => {
        e.preventDefault();
        this.handleSendClue();
      });
    }

    // Round between-turn chat
    if (this.roundChatForm) {
      this.roundChatForm.addEventListener('submit', (e) => {
        e.preventDefault();
        this.handleSendChatMessage(this.roundChatInput);
      });
    }

    // Subscribe to state updates
    store.subscribe((state) => {
      this.render(state);
    });
  }

  handleSendChatMessage(inputEl) {
    if (!inputEl) return;
    const text = inputEl.value.trim();
    if (!text) return;

    if (text.length > 200) {
      alert('Message must be under 200 characters.');
      return;
    }

    // Rate limit: 1 msg/sec
    const now = Date.now();
    if (now - this.lastMessageTime < 1000) {
      return;
    }
    this.lastMessageTime = now;

    wsClient.send('CHAT_MESSAGE', { message: text });
    inputEl.value = '';
  }

  handleSendClue() {
    if (!this.clueInput) return;
    const text = this.clueInput.value.trim();
    if (!text) return;

    if (text.length > 200) {
      alert('Clue must be under 200 characters.');
      return;
    }

    const state = store.get();
    const isClueTeam = state.round.isMyClueGiverTurn || (state.room.gameMode === 'TEAM' && state.player.teamId && state.player.teamId === state.round.clueGiverTeam);
    if (!isClueTeam) {
      alert('Only members of the active clue team can post clues.');
      return;
    }

    wsClient.send('CLUE_MESSAGE', { message: text });
    this.clueInput.value = '';
  }

  render(state) {
    const { room, round, clues, chatMessages, player } = state;

    // 1. Clue Form visibility (visible to clue-giver and teammates in Team mode)
    if (this.clueForm) {
      const isClueTeam = round.isMyClueGiverTurn || (room.gameMode === 'TEAM' && player.teamId && player.teamId === round.clueGiverTeam);
      if (isClueTeam && round.isRoundActive) {
        this.clueForm.classList.remove('hidden');
        if (this.clueInput) {
          this.clueInput.placeholder = round.isMyClueGiverTurn
            ? "Type your clue for opposing teams..."
            : "Help your team: Type a clue for opposing teams...";
        }
      } else {
        this.clueForm.classList.add('hidden');
      }
    }

    if (this.feedClueGiverName) {
      this.feedClueGiverName.textContent = round.clueGiverName || 'Clue-Giver';
    }

    // 2. Chat disabled state during round
    const isRoundRunning = round.isRoundActive;
    if (this.chatDisabledNotice && this.roundChatForm) {
      if (isRoundRunning) {
        this.chatDisabledNotice.classList.remove('hidden');
        this.roundChatForm.classList.add('hidden');
      } else {
        this.chatDisabledNotice.classList.add('hidden');
        this.roundChatForm.classList.remove('hidden');
      }
    }

    // 3. Render Clues Feed
    if (this.clueFeed) {
      this.clueFeed.innerHTML = '';
      if (clues.length === 0) {
        this.clueFeed.innerHTML = '<span class="text-muted text-sm">No clues posted yet. Listen to voice or watch here!</span>';
      } else {
        clues.forEach(clue => {
          const bubble = document.createElement('div');
          bubble.className = 'clue-bubble';
          bubble.textContent = `💡 "${clue.message}"`;
          this.clueFeed.appendChild(bubble);
        });
        this.clueFeed.scrollTop = this.clueFeed.scrollHeight;
      }
    }

    // 4. Render Lobby Chat Messages
    if (this.lobbyChatFeed) {
      this.lobbyChatFeed.innerHTML = '';
      chatMessages.forEach(msg => {
        const item = document.createElement('div');
        item.className = 'chat-msg';
        item.innerHTML = `
          <span class="sender">${this.escapeHtml(msg.senderName || 'Player')}:</span>
          <span class="text">${this.escapeHtml(msg.message)}</span>
        `;
        this.lobbyChatFeed.appendChild(item);
      });
      this.lobbyChatFeed.scrollTop = this.lobbyChatFeed.scrollHeight;
    }

    // 5. Render Round Chat Messages
    if (this.roundChatFeed) {
      this.roundChatFeed.innerHTML = '';
      chatMessages.slice(-15).forEach(msg => {
        const item = document.createElement('div');
        item.className = 'chat-msg';
        item.innerHTML = `
          <span class="sender">${this.escapeHtml(msg.senderName || 'Player')}:</span>
          <span class="text">${this.escapeHtml(msg.message)}</span>
        `;
        this.roundChatFeed.appendChild(item);
      });
      this.roundChatFeed.scrollTop = this.roundChatFeed.scrollHeight;
    }
  }

  escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
}

export const chatView = new ChatView();
export default chatView;

