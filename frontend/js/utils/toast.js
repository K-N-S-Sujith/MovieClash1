/**
 * MovieClash — Subtle Non-Intrusive Toast System
 * Replaces disruptive alert() modal pop-ups with floating cinema-themed toasts.
 */

class ToastManager {
  constructor() {
    this.container = null;
    this.initContainer();
  }

  initContainer() {
    if (typeof document === 'undefined') return;
    let existing = document.getElementById('toastContainer');
    if (!existing) {
      existing = document.createElement('div');
      existing.id = 'toastContainer';
      existing.className = 'toast-container';
      document.body.appendChild(existing);
    }
    this.container = existing;
  }

  /**
   * Displays a non-intrusive floating toast
   * @param {string} message - Message text to display
   * @param {'info'|'success'|'warning'|'error'} [type='info'] - Toast category
   * @param {number} [duration=2500] - Display duration in ms
   */
  show(message, type = 'info', duration = 2500) {
    if (!message || typeof document === 'undefined') return;
    if (!this.container) this.initContainer();

    const toast = document.createElement('div');
    toast.className = `toast-pill toast-${type}`;

    let icon = 'ℹ️';
    if (type === 'success') icon = '✨';
    if (type === 'warning') icon = '⚠️';
    if (type === 'error') icon = '🛑';

    toast.innerHTML = `
      <span class="toast-icon">${icon}</span>
      <span class="toast-text">${this.escapeHtml(message)}</span>
    `;

    this.container.appendChild(toast);

    // Trigger slide-in animation
    requestAnimationFrame(() => {
      toast.classList.add('visible');
    });

    setTimeout(() => {
      toast.classList.remove('visible');
      toast.classList.add('fade-out');
      setTimeout(() => {
        if (toast.parentNode) {
          toast.parentNode.removeChild(toast);
        }
      }, 300);
    }, duration);
  }

  escapeHtml(str) {
    if (!str) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }
}

export const toast = new ToastManager();
export default toast;

