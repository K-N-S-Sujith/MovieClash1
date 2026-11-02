# MovieClash — REST API Documentation

While gameplay mutations and real-time synchronization occur over WebSockets (`ws://` / `wss://`), MovieClash provides lightweight REST endpoints for server health diagnostics and WebRTC voice token minting.

---

## 1. Health Probe (`GET /health`)

Lightweight, unauthenticated endpoint with zero database or session dependencies.

- **URL:** `/health`
- **Method:** `GET`
- **Auth:** None
- **CORS:** Enabled (`*`)
- **Use Case:**
  - Automated cloud container readiness and liveness probes (Kubernetes, Docker, Fly.io, Railway, Render).
  - Pre-waking cold servers when a player opens the landing page.
  - Initial HTTP ping check.

### Response: `200 OK`
```json
{
  "status": "UP",
  "app": "MovieClash",
  "version": "1.0.0",
  "activeRooms": 3,
  "timestamp": 1729000000000
}
```

---

## 2. LiveKit Voice Token (`GET /api/livekit/token`)

Mints an HMAC-SHA256 signed LiveKit JWT access token for WebRTC SFU voice communication.

- **URL:** `/api/livekit/token`
- **Method:** `GET`
- **Query Parameters:**
  - `roomId` *(required)*: 6-character room code (e.g. `ABC123`)
  - `playerId` *(required)*: UUID of the requesting player (e.g. `c4b3...`)

### Response: `200 OK`
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "livekitUrl": "wss://movieclash-demo.livekit.cloud",
  "canPublish": true,
  "roomId": "ABC123",
  "playerId": "c4b321...",
  "playerName": "Alice"
}
```

### Error Responses:
- `400 Bad Request`: `roomId` or `playerId` query parameter missing.
- `404 Not Found`: Room does not exist or has expired.
- `401 Unauthorized`: Player is not a member of the requested room.

---

## 3. LiveKit Voice Token (`POST /api/livekit/token`)

Alternative JSON-payload endpoint for token minting.

- **URL:** `/api/livekit/token`
- **Method:** `POST`
- **Content-Type:** `application/json`
- **Request Body:**
  ```json
  {
    "roomId": "ABC123",
    "playerId": "c4b321..."
  }
  ```

### Response: `200 OK`
Same payload as the `GET` endpoint.

