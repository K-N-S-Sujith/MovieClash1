/**
 * MovieClash — Lobby Chat & Clue Broadcast Controller
 * Note: In-game chat has been removed per UI/UX cleanup. Lobby Chat is preserved.
 */

import store from '../state.js';
import wsClient from '../api/websocket.js';
import toast from '../utils/toast.js';

export class ChatView {
  constructor() {
    // Lobby Chat
    this.lobbyChatForm = document.getElementById('lobbyChatForm');
    this.lobbyChatInput = document.getElementById('lobbyChatInput');
    this.lobbyChatFeed = document.getElementById('lobbyChatMessages');

    // Clue Form & Feed (During active rounds)
    this.clueForm = document.getElementById('clueMessageForm');
    this.clueInput = document.getElementById('clueTextInput');
    this.clueFeed = document.getElementById('clueMessagesFeed');
    this.feedClueGiverName = document.getElementById('feedClueGiverName');

    this.lastMessageTime = 0;
    this.renderedCluesCount = 0;
    this.renderedLobbyChatCount = 0;

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
      toast.show('Message must be under 200 characters.', 'warning');
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
      toast.show('Clue must be under 200 characters.', 'warning');
      return;
    }

    const state = store.get();
    const isClueTeam = state.round.isMyClueGiverTurn || (state.room.gameMode === 'TEAM' && state.player.teamId && state.player.teamId === state.round.clueGiverTeam);
    if (!isClueTeam) {
      toast.show('Only members of the active clue team can post clues.', 'warning');
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

    // 2. Render Clues Feed (Incremental)
    if (this.clueFeed) {
      if (clues.length === 0) {
        if (this.renderedCluesCount !== 0) {
          this.clueFeed.innerHTML = '<span class="text-muted text-sm">No clues posted yet. Listen to voice or watch here!</span>';
          this.renderedCluesCount = 0;
        }
      } else {
        if (this.renderedCluesCount === 0) {
          this.clueFeed.innerHTML = '';
        }
        if (clues.length > this.renderedCluesCount) {
          const newClues = clues.slice(this.renderedCluesCount);
          this.renderedCluesCount = clues.length;

          newClues.forEach(clue => {
            const bubble = document.createElement('div');
            bubble.className = 'clue-bubble';
            bubble.textContent = `💡 "${clue.message}"`;
            this.clueFeed.appendChild(bubble);
          });
          this.clueFeed.scrollTop = this.clueFeed.scrollHeight;
        }
      }
    }

    // 3. Render Lobby Chat Messages (Only while in lobby)
    if (this.lobbyChatFeed && room.status === 'LOBBY') {
      if (chatMessages.length === 0) {
        if (this.renderedLobbyChatCount !== 0) {
          this.lobbyChatFeed.innerHTML = '';
          this.renderedLobbyChatCount = 0;
        }
      } else if (chatMessages.length > this.renderedLobbyChatCount) {
        const newMsgs = chatMessages.slice(this.renderedLobbyChatCount);
        this.renderedLobbyChatCount = chatMessages.length;

        newMsgs.forEach(msg => {
          const item = document.createElement('div');
          item.className = 'chat-msg';
          item.innerHTML = `
            <span class="sender">${this.escapeHtml(msg.senderName || 'Player')}:</span>
            <span class="text">${this.escapeHtml(msg.message)}</span>
          `;
          this.lobbyChatFeed.appendChild(item);
        });
        while (this.lobbyChatFeed.children.length > 50) {
          this.lobbyChatFeed.removeChild(this.lobbyChatFeed.firstChild);
        }
        this.lobbyChatFeed.scrollTop = this.lobbyChatFeed.scrollHeight;
      }
    }
  }

  escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
}

export const chatView = new ChatView();
export default chatView;
