/**
 * MovieClash — Resilient WebSocket Client
 * Protocol: Uniform JSON envelope { type, eventId, roomId, payload }
 * Features: Ping/Pong latency tracking, event routing, auto-reconnect backoff
 */

import CONFIG from '../../config.js';
import store from '../state.js';

export class GameWebSocketClient {
  constructor() {
    this.ws = null;
    this.eventListeners = new Map();
    this.pingTimer = null;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 6;
    this.reconnectTimer = null;
    this.isExplicitClose = false;
    this.latencyBadgeEl = document.getElementById('latencyBadge');
  }

  connect() {
    this.isExplicitClose = false;
    const url = CONFIG.BACKEND_WS_URL;

    try {
      this.ws = new WebSocket(url);
    } catch (err) {
      console.error('Failed to create WebSocket:', err);
      this.scheduleReconnect();
      return;
    }

    this.ws.onopen = () => {
      this.reconnectAttempts = 0;
      store.set({ connection: { ...store.get().connection, wsConnected: true, reconnecting: false } });
      this.hideReconnectBanner();
      this.startPingLoop();
      this.dispatch('WS_OPEN', {});
    };

    this.ws.onmessage = (event) => {
      try {
        const envelope = JSON.parse(event.data);
        this.handleIncoming(envelope);
      } catch (err) {
        console.error('Failed to parse WebSocket JSON frame:', err, event.data);
      }
    };

    this.ws.onerror = (err) => {
      console.warn('WebSocket error observed:', err);
    };

    this.ws.onclose = (event) => {
      store.set({ connection: { ...store.get().connection, wsConnected: false } });
      this.stopPingLoop();
      this.updateLatencyUI(null);

      if (!this.isExplicitClose) {
        this.scheduleReconnect();
      }
      this.dispatch('WS_CLOSE', event);
    };
  }

  close() {
    this.isExplicitClose = true;
    this.stopPingLoop();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }

  send(type, payload = {}) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn(`Cannot send ${type}: WebSocket is not open.`);
      return false;
    }

    const currentState = store.get();
    const envelope = {
      type,
      eventId: this.generateUUID(),
      roomId: currentState.room.roomId || null,
      payload
    };

    this.ws.send(JSON.stringify(envelope));
    return true;
  }

  on(type, callback) {
    if (!this.eventListeners.has(type)) {
      this.eventListeners.set(type, new Set());
    }
    this.eventListeners.get(type).add(callback);
    return () => this.eventListeners.get(type).delete(callback);
  }

  dispatch(type, payload, rawEnvelope) {
    const listeners = this.eventListeners.get(type);
    if (listeners) {
      for (const listener of listeners) {
        try {
          listener(payload, rawEnvelope);
        } catch (err) {
          console.error(`Error in listener for ${type}:`, err);
        }
      }
    }
  }

  handleIncoming(envelope) {
    const { type, payload } = envelope;

    if (type === 'PONG') {
      const now = Date.now();
      const clientSent = payload.clientTimestamp || now;
      const rtt = Math.max(0, now - clientSent);
      if (store.state && store.state.connection) {
        store.state.connection.latencyMs = rtt;
      }
      this.updateLatencyUI(rtt);
      return;
    }

    // Dispatch all other domain events
    this.dispatch(type, payload, envelope);
  }

  startPingLoop() {
    this.stopPingLoop();
    this.ping();
    this.pingTimer = setInterval(() => this.ping(), CONFIG.PING_INTERVAL_MS);
  }

  stopPingLoop() {
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  ping() {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.send('PING', { clientTimestamp: Date.now() });
    }
  }

  updateLatencyUI(latencyMs) {
    if (!this.latencyBadgeEl) return;
    const labelSpan = this.latencyBadgeEl.querySelector('.label');

    this.latencyBadgeEl.classList.remove('lat-good', 'lat-med', 'lat-high');

    if (latencyMs === null) {
      if (labelSpan) labelSpan.textContent = 'WS: -- ms';
      return;
    }

    if (labelSpan) labelSpan.textContent = `WS: ${latencyMs} ms`;

    if (latencyMs < 60) {
      this.latencyBadgeEl.classList.add('lat-good');
    } else if (latencyMs < 160) {
      this.latencyBadgeEl.classList.add('lat-med');
    } else {
      this.latencyBadgeEl.classList.add('lat-high');
    }
  }

  scheduleReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      this.showReconnectBanner('Connection lost. Please refresh the page.', false);
      return;
    }

    this.reconnectAttempts++;
    const delay = Math.min(1000 * Math.pow(1.5, this.reconnectAttempts), 8000);
    this.showReconnectBanner(`Reconnecting... (Attempt ${this.reconnectAttempts}/${this.maxReconnectAttempts})`, true);

    store.set({ connection: { ...store.get().connection, reconnecting: true } });

    this.reconnectTimer = setTimeout(() => {
      this.connect();
    }, delay);
  }

  showReconnectBanner(text, showSpinner) {
    const banner = document.getElementById('reconnectBanner');
    const textEl = document.getElementById('reconnectBannerText');
    const spinner = banner?.querySelector('.spinner');

    if (banner && textEl) {
      banner.classList.remove('hidden');
      textEl.textContent = text;
      if (spinner) spinner.style.display = showSpinner ? 'inline-block' : 'none';
    }
  }

  hideReconnectBanner() {
    const banner = document.getElementById('reconnectBanner');
    if (banner) banner.classList.add('hidden');
  }

  generateUUID() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
      return crypto.randomUUID();
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      const v = c === 'x' ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }
}

export const wsClient = new GameWebSocketClient();
export default wsClient;

