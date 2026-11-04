/**
 * MovieClash — Drift-Free Client Countdown Timer
 * Synchronizes with server-enforced deadline using clock offset.
 * No high-frequency server ticks needed over the wire.
 */

import audioEngine from './audio.js';

export class RoundTimer {
  constructor() {
    this.intervalId = null;
    this.clockOffsetMs = 0;
    this.deadline = 0;
    this.lastTickedSec = null;
    this.timerCircleEl = document.getElementById('timerCircle');
    this.timerValueEl = document.getElementById('timerValue');
    this.onExpireCallback = null;
  }

  /**
   * Starts a countdown timer using server reference times
   * @param {number} serverTime - Current server epoch timestamp (ms)
   * @param {number} deadline - Server deadline epoch timestamp (ms)
   * @param {Function} [onExpire] - Optional callback when timer reaches zero
   */
  start(serverTime, deadline, onExpire = null) {
    this.stop();
    this.onExpireCallback = onExpire;
    this.lastTickedSec = null;

    // Calculate clock skew between local machine and server
    const localNow = Date.now();
    this.clockOffsetMs = (serverTime || localNow) - localNow;
    this.deadline = deadline;

    this.tick();
    this.intervalId = setInterval(() => this.tick(), 250);
  }

  stop() {
    if (this.intervalId) {
      clearInterval(this.intervalId);
      this.intervalId = null;
    }
  }

  pause() {
    this.stop();
    if (this.timerValueEl) {
      this.timerValueEl.textContent = '⏸';
    }
  }

  tick() {
    const currentAdjustedTime = Date.now() + this.clockOffsetMs;
    const remainingMs = Math.max(0, this.deadline - currentAdjustedTime);
    const remainingSec = Math.ceil(remainingMs / 1000);

    if (remainingSec !== this.lastTickedSec) {
      this.lastTickedSec = remainingSec;

      if (this.timerValueEl) {
        this.timerValueEl.textContent = remainingSec;
      }

      if (this.timerCircleEl) {
        if (remainingSec <= 10) {
          this.timerCircleEl.classList.add('warning');
        } else {
          this.timerCircleEl.classList.remove('warning');
        }
      }

      // Audio suspense tick in final 10 seconds
      if (remainingSec <= 10 && remainingSec > 0) {
        audioEngine.playTick(remainingSec <= 5);
      }
    }

    if (remainingMs <= 0) {
      this.stop();
      if (this.onExpireCallback) {
        this.onExpireCallback();
      }
    }
  }

  getRemainingSeconds() {
    const currentAdjustedTime = Date.now() + this.clockOffsetMs;
    return Math.max(0, Math.ceil((this.deadline - currentAdjustedTime) / 1000));
  }
}

export const roundTimer = new RoundTimer();
export default roundTimer;

