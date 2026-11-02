/**
 * MovieClash — Server Health & Wakeup Pinger
 * Hits GET /health immediately on page load to pre-warm cold/sleeping hosts.
 */

import CONFIG from '../../config.js';
import store from '../state.js';

export class HealthClient {
  constructor() {
    this.pollTimer = null;
    this.badgeEl = document.getElementById('serverStatusBadge');
  }

  start() {
    this.checkHealth();
    // Re-check periodically every 15 seconds
    this.pollTimer = setInterval(() => this.checkHealth(), 15000);
  }

  stop() {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }

  async checkHealth() {
    const url = `${CONFIG.BACKEND_HTTP_URL}/health`;
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 4000);

      const resp = await fetch(url, { signal: controller.signal, mode: 'cors' });
      clearTimeout(timeoutId);

      if (resp.ok) {
        this.updateStatus(true, 'Server: Online');
        store.set({ connection: { ...store.get().connection, serverHealthy: true } });
        return true;
      } else {
        this.updateStatus(false, 'Server: Waking up...');
        return false;
      }
    } catch (err) {
      // Offline or still waking up
      this.updateStatus(false, 'Server: Connecting...');
      return false;
    }
  }

  updateStatus(isOnline, label) {
    if (!this.badgeEl) return;
    const dot = this.badgeEl.querySelector('.dot');
    const labelSpan = this.badgeEl.querySelector('.label');

    if (labelSpan) labelSpan.textContent = label;

    this.badgeEl.classList.remove('connected', 'connecting');
    if (isOnline) {
      this.badgeEl.classList.add('connected');
    } else {
      this.badgeEl.classList.add('connecting');
    }
  }
}

export const healthClient = new HealthClient();
export default healthClient;

