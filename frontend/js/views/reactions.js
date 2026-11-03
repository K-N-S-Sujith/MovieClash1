/**
 * MovieClash — Real-Time Crowd Emoji Reactions
 * Renders floating animated emoji bubbles with sound & sends room broadcasts.
 */

import wsClient from '../api/websocket.js';
import store from '../state.js';
import audioEngine from '../engine/audio.js';

export class ReactionsView {
  constructor() {
    this.container = null;
    this.dock = null;
    this.lastSentTime = 0;
    this.COOLDOWN_MS = 250;

    this.init();
  }

  init() {
    this.container = document.getElementById('floatingReactionsContainer');
    this.dock = document.getElementById('reactionDock');

    if (this.dock) {
      this.dock.querySelectorAll('.reaction-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
          e.preventDefault();
          const emoji = btn.getAttribute('data-emoji');
          this.triggerReaction(emoji);
        });
      });
    }

    // Subscribe to room status to show/hide reaction dock
    store.subscribe((state) => {
      const inRoom = state.room.roomId && (state.room.status === 'LOBBY' || state.room.status === 'PLAYING');
      if (this.dock) {
        if (inRoom) {
          this.dock.classList.remove('hidden');
        } else {
          this.dock.classList.add('hidden');
        }
      }
    });
  }

  triggerReaction(emoji) {
    if (!emoji) return;

    const now = Date.now();
    if (now - this.lastSentTime < this.COOLDOWN_MS) {
      return; // Anti-spam rate limiting
    }
    this.lastSentTime = now;

    const state = store.get();
    if (!state.room.roomId) return;

    // Send to WebSocket
    wsClient.send('EMOJI_REACTION', { emoji });

    // Optimistically spawn locally if immediate feedback desired
    // (Broadcast handler will display it for everyone)
  }

  /**
   * Spawns an animated floating reaction bubble on screen
   * @param {Object} payload - { emoji, senderId, senderName }
   */
  spawn(payload) {
    if (!this.container) {
      this.container = document.getElementById('floatingReactionsContainer');
      if (!this.container) return;
    }

    const { emoji, senderName } = payload;
    const isMe = payload.senderId === store.get().player.id;

    // Play subtle pop audio
    audioEngine.playPop();

    // Create reaction element
    const el = document.createElement('div');
    el.className = `floating-reaction ${isMe ? 'is-me' : ''}`;

    // Randomize initial horizontal offset (0 - 80px)
    const randomOffset = Math.floor(Math.random() * 80) - 40;
    el.style.setProperty('--sway-offset', `${randomOffset}px`);

    const nameTag = senderName ? `<span class="reaction-sender">${this.escapeHtml(senderName)}</span>` : '';
    el.innerHTML = `
      <span class="reaction-emoji">${emoji}</span>
      ${nameTag}
    `;

    this.container.appendChild(el);

    // Remove from DOM when animation completes
    setTimeout(() => {
      if (el.parentNode) {
        el.parentNode.removeChild(el);
      }
    }, 2000);
  }

  escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
}

export const reactionsView = new ReactionsView();
export default reactionsView;
