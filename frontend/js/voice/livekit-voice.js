/**
 * MovieClash — LiveKit WebRTC Voice Integration
 * [TEMPORARILY DISABLED BY REQUEST — COMMENTED OUT — TEXT CLUES ACTIVE]
 */

// import CONFIG from '../../config.js';

export class LiveKitVoiceClient {
  constructor() {
    this.room = null;
    this.isMuted = true;
    this.canPublish = false;
    /*
    this.micToggleBtn = document.getElementById('micToggleBtn');
    this.voiceBadge = document.getElementById('voiceControlBadge');
    this.voiceStatusText = document.getElementById('voiceStatusText');
    this.initControls();
    */
  }

  initControls() {
    /*
    if (this.micToggleBtn) {
      this.micToggleBtn.addEventListener('click', () => this.toggleMute());
    }
    */
  }

  isAvailable() {
    // Temporarily disabled
    return false;
  }

  async connect(livekitUrl, token) {
    // Temporarily disabled: voice clues are inactive
    return false;
  }

  async enablePublishing() {
    // Temporarily disabled: voice clues are inactive
    this.canPublish = false;
  }

  async disablePublishing() {
    // Temporarily disabled: voice clues are inactive
    this.canPublish = false;
  }

  async toggleMute() {
    // Temporarily disabled
  }

  updateBadge(isActive, statusText) {
    // Temporarily disabled
  }

  async disconnect() {
    // Temporarily disabled
  }
}

export const liveKitVoice = new LiveKitVoiceClient();
export default liveKitVoice;
