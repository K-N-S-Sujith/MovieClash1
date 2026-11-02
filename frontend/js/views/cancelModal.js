/**
 * MovieClash — Room Cancellation Voting Modal
 * Handles host cancel proposals, majority voting countdown, and vote casting.
 */

import store from '../state.js';
import wsClient from '../api/websocket.js';

export class CancelModalView {
  constructor() {
    this.modalEl = document.getElementById('cancelVoteModal');
    this.proposerNameEl = document.getElementById('cancelProposerName');
    this.countdownSecondsEl = document.getElementById('cancelVoteSeconds');
    this.btnVoteYes = document.getElementById('btnVoteCancelYes');
    this.btnVoteNo = document.getElementById('btnVoteCancelNo');

    this.timer = null;

    this.initEvents();
  }

  initEvents() {
    if (this.btnVoteYes) {
      this.btnVoteYes.addEventListener('click', () => this.castVote(true));
    }
    if (this.btnVoteNo) {
      this.btnVoteNo.addEventListener('click', () => this.castVote(false));
    }
  }

  show(payload) {
    if (!this.modalEl) return;

    const { proposerName, timeoutMs = 15000 } = payload;
    if (this.proposerNameEl) {
      this.proposerNameEl.textContent = proposerName || 'The Host';
    }

    this.modalEl.classList.remove('hidden');

    let seconds = Math.ceil(timeoutMs / 1000);
    if (this.countdownSecondsEl) this.countdownSecondsEl.textContent = seconds;

    if (this.timer) clearInterval(this.timer);
    this.timer = setInterval(() => {
      seconds--;
      if (this.countdownSecondsEl) this.countdownSecondsEl.textContent = seconds;
      if (seconds <= 0) {
        this.hide();
      }
    }, 1000);
  }

  castVote(vote) {
    wsClient.send('CANCEL_VOTE', { vote });
    this.hide();
  }

  hide() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    if (this.modalEl) {
      this.modalEl.classList.add('hidden');
    }
  }
}

export const cancelModalView = new CancelModalView();
export default cancelModalView;

