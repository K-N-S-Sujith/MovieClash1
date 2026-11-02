# MovieClash — WebSocket Protocol Specification

All WebSocket frames are exchanged as JSON envelopes adhering to the uniform schema:

```json
{
  "type": "EVENT_TYPE",
  "eventId": "3c90e54d-bfeb-433e-b816-5381f1e31e50",
  "roomId": "ABC123",
  "payload": { ... }
}
```

---

## 1. Client-to-Server Events

### 1.1 `PING`
Measures round-trip latency.
```json
{ "type": "PING", "payload": { "clientTimestamp": 1729000000000 } }
```

### 1.2 `CREATE_ROOM`
Creates a new game room in lobby state.
```json
{
  "type": "CREATE_ROOM",
  "payload": {
    "playerName": "Alice",
    "gameMode": "INDIVIDUAL", // or "TEAM"
    "teamCount": 2, // Optional, 2-5
    "config": {
      "roundDurationSec": 60,
      "totalRounds": 2
    }
  }
}
```

### 1.3 `JOIN_ROOM`
Joins an existing room via 6-character room code.
```json
{
  "type": "JOIN_ROOM",
  "roomId": "XYZ987",
  "payload": {
    "roomId": "XYZ987",
    "playerName": "Bob"
  }
}
```

### 1.4 `LEAVE_ROOM`
Voluntarily leaves the current room.
```json
{ "type": "LEAVE_ROOM", "roomId": "XYZ987", "payload": {} }
```

### 1.5 `ASSIGN_TEAM`
Moves the player to a target team (Team Battle mode, lobby only, max 5 players).
```json
{
  "type": "ASSIGN_TEAM",
  "roomId": "XYZ987",
  "payload": { "teamId": "TEAM_B" }
}
```

### 1.6 `START_GAME`
Host initiates gameplay (transitions from `LOBBY` to `IN_PROGRESS`).
```json
{ "type": "START_GAME", "roomId": "XYZ987", "payload": {} }
```

### 1.7 `NEXT_ROUND`
Host manually advances to next round between turns.
```json
{ "type": "NEXT_ROUND", "roomId": "XYZ987", "payload": {} }
```

### 1.8 `SUBMIT_GUESS`
Guesser submits an answer attempt.
```json
{
  "type": "SUBMIT_GUESS",
  "roomId": "XYZ987",
  "payload": { "guessText": "Inception" }
}
```

### 1.9 `SCORE_UPDATE`
Authoritative score validation submitted **strictly by the active clue-giver**.
```json
{
  "type": "SCORE_UPDATE",
  "eventId": "evt-unique-uuid",
  "roomId": "XYZ987",
  "payload": {
    "guesserId": "player-bob-id",
    "guesserTeam": "TEAM_B",
    "guessSeq": 1,
    "isCorrect": true,
    "points": 150,
    "clueGiverPoints": 25,
    "guessText": "Inception"
  }
}
```

### 1.10 `CLUE_MESSAGE`
Clue text posted **strictly by the active clue-giver** during active round (max 200 chars, rate limit 1/sec).
```json
{
  "type": "CLUE_MESSAGE",
  "roomId": "XYZ987",
  "payload": { "message": "Directed by Christopher Nolan with dreams inside dreams" }
}
```

### 1.11 `CHAT_MESSAGE`
General chat message in lobby or between turns (disabled for guessers during round).
```json
{
  "type": "CHAT_MESSAGE",
  "roomId": "XYZ987",
  "payload": { "message": "Good luck everyone!" }
}
```

### 1.12 `RECONNECT`
Reconnects a dropped session within the 60s grace window.
```json
{
  "type": "RECONNECT",
  "roomId": "XYZ987",
  "payload": {
    "roomId": "XYZ987",
    "playerId": "player-alice-id",
    "reconnectToken": "32-byte-hex-token"
  }
}
```

### 1.13 `CANCEL_PROPOSE`
Host proposes terminating an active match.
```json
{ "type": "CANCEL_PROPOSE", "roomId": "XYZ987", "payload": {} }
```

### 1.14 `CANCEL_VOTE`
Player casts democratic cancellation vote.
```json
{
  "type": "CANCEL_VOTE",
  "roomId": "XYZ987",
  "payload": { "vote": true } // true = YES, false = NO
}
```

---

## 2. Server-to-Client Events

| Event Type | Destination | Description | Key Payload Fields |
|---|---|---|---|
| `PONG` | Sender | Latency probe reply | `clientTimestamp`, `serverTimestamp` |
| `ROOM_CREATED` | Host | Room created | `roomId`, `playerId`, `reconnectToken`, `room` |
| `PLAYER_JOINED` | Room / Joiner | New player presence | `yourPlayerId`, `player`, `room` |
| `PLAYER_LEFT` | Room | Player left room | `playerId`, `playerName`, `newHostId` (if migrated) |
| `PLAYER_DISCONNECTED`| Room | Socket closed (grace window) | `playerId`, `playerName`, `gracePeriodMs` |
| `PLAYER_RECONNECTED` | Room / Sender | Session restored | Full snapshot for reconnector, delta for room |
| `ROOM_UPDATED` | Room | Delta attribute sync | `delta`: `{ players, teams, hostId, ... }` |
| `GAME_STARTED` | Room | Match started | `gameMode`, `totalRounds`, `config` |
| `ROUND_STARTED` | Room | Round begins | `roundNumber`, `clueGiverId`, `clueGiverName`, `serverTime`, `deadline` |
| `CLUE_GIVER_ANSWER` | **Clue-Giver ONLY** | Private secret movie | `movieId`, `title`, `year`, `aliases`, `hint` |
| `FORWARD_GUESS` | **Clue-Giver ONLY** | Private forwarded guess | `guesserId`, `guesserName`, `guessText`, `guessSeq` |
| `GUESS_RESULT` | **Guesser ONLY** | Private guess feedback | `isCorrect`, `points`, `guessText` |
| `SCORE_BROADCAST` | Room | Room score updated | `guesserName`, `guesserPoints`, `clueGiverPoints`, `scoreboard`, `teams` |
| `CLUE_BROADCAST` | Room | Clue posted by clue-giver | `clueGiverId`, `message`, `timestamp` |
| `CHAT_BROADCAST` | Room | Lobby / post-round chat | `senderId`, `senderName`, `message`, `timestamp` |
| `ROUND_FINISHED` | Room | Round ended, answer revealed | `reason`, `revealedMovie`, `roundScores` |
| `GAME_FINISHED` | Room | Match ended, winners declared | `winners`, `finalScores` |
| `CANCEL_VOTE_REQUESTED`| Room | Host proposed cancellation | `proposerName`, `timeoutMs`, `totalEligible` |
| `CANCEL_VOTE_RESULT` | Room | Vote conclusion | `approved`, `yesVotes`, `noVotes` |
| `GAME_CANCELLED` | Room | Match terminated, back to lobby| `reason` |
| `ERROR` | Sender | Error notification | `code`, `message` |

---

## 3. Error Codes

| Error Code | HTTP Status / Meaning |
|---|---|
| `ROOM_NOT_FOUND` | Room code does not match any active room |
| `ROOM_FULL` | Room capacity limit reached (max 20) |
| `INVALID_NAME` | Player name contains illegal characters or is empty |
| `INVALID_ROOM_CODE`| Room code malformed |
| `GAME_ALREADY_STARTED`| Attempted to join or change teams during an active match |
| `UNAUTHORIZED` | Operation prohibited (e.g., chat during round, guesser teammate) |
| `NOT_HOST` | Host-only operation attempted by non-host |
| `NOT_CLUE_GIVER` | Clue/score update submitted by someone other than the clue-giver |
| `INVALID_TEAM` | Target team full (max 5) or invalid team key |
| `RATE_LIMIT_EXCEEDED` | Message throttled (max 1 message/second) |
| `MESSAGE_TOO_LONG` | Exceeded 200 characters limit |
| `INVALID_PAYLOAD` | Missing required fields in envelope |
| `RECONNECT_FAILED` | Token mismatch or player not in room |
| `EXPIRED_SESSION` | 60-second reconnection grace period has expired |
| `ALREADY_VOTED` | Player already cast a vote in active cancellation ballot |

