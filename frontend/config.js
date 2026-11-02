// MovieClash Frontend Configuration
// Automatically detects localhost or remote host based on window.location
const isLocal = !window.location.hostname || window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';

const CONFIG = {
  BACKEND_HTTP_URL: isLocal
    ? 'http://localhost:8080'
    : `${window.location.protocol}//${window.location.host}`,
  
  BACKEND_WS_URL: isLocal
    ? 'ws://localhost:8080/ws/game'
    : `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws/game`,

  LIVEKIT_SFU_URL: 'wss://movieclash-demo.livekit.cloud', // Provided or overridden by server config
  
  PING_INTERVAL_MS: 5000,
  RECONNECT_TIMEOUT_MS: 60000,
  DEFAULT_ROUND_DURATION_SEC: 60
};

export default CONFIG;

