# 🎬 MovieClash

**MovieClash** is an ultra-low-latency, real-time multiplayer movie guessing game featuring integrated WebRTC voice streaming, interactive text clues, and a client-authoritative validation model that minimizes backend server workload.

Built as a high-performance **modular monolith** with **Java 21**, **Spring Boot 3.3.2**, native **WebSockets**, and a modern **Vanilla JavaScript (ES Modules)** frontend with zero bundlers or heavy frameworks.

---

## 🌟 Key Highlights

- **⚡ Minimal Backend Workload:** The clue-giver's browser acts as the validation authority—handling string normalization, alias checks, and Levenshtein typo matching. The server enforces permissions, orchestrates room lifecycles, and synchronizes scores without recalculating formulas.
- **🎙️ WebRTC Voice (LiveKit Cloud):** High-fidelity, one-to-many voice broadcast via an external SFU. Zero audio bytes traverse the Java backend. Backend issues secure, short-lived JWT tokens allowing only the active clue-giver to publish audio.
- **🛡️ In-Memory Concurrency & Thread-Safety:** Game state is maintained in JVM memory (`ConcurrentHashMap`) with per-room mutation serialization via `ReentrantLock` wrappers (`room.executeLocked(...)`).
- **🎮 Two Distinct Game Modes:**
  - **Individual Battle:** 2–20 players, rotating clue-givers, +100 base, +50 first-correct bonus, +25 clue-giver reward.
  - **Team Battle:** 2–5 teams (up to 5 players each), round-robin team turns with intra-team rotation, teammate guess lockout, and opposing team points.
- **🔄 Resilient Reconnection:** 60-second grace window authenticated by secure 32-byte hex reconnect tokens. Clue-giver turn pause with automatic answer re-delivery upon reconnect.
- **🗳️ Democratic Cancellation Voting:** Host proposes cancellation, all connected players vote YES/NO with a 15-second countdown, and a strict majority threshold returns the room cleanly to the lobby.
- **⏱️ Drift-Free Local Countdown:** Server provides authoritative deadlines; frontend renders smooth, frame-rate independent countdowns via `requestAnimationFrame`. Zero per-second server tick spam.

---

## 🏗️ Architecture & Documentation

For in-depth specifications, refer to our detailed documentation suite:

- 📖 **[DEPLOYMENT.md](DEPLOYMENT.md):** Production guides for Cloudflare Pages & always-on Java VPS hosts with small-host JVM flags (`-Xmx300m -XX:+UseSerialGC -XX:TieredStopAtLevel=1`).
- 🏛️ **[ARCHITECTURE.md](ARCHITECTURE.md):** Modular monolith architecture, sequence diagrams, concurrency models, and network optimization principles.
- 📡 **[WEBSOCKET_EVENTS.md](WEBSOCKET_EVENTS.md):** Complete catalog of all 14 client-to-server and 20 server-to-client JSON WebSocket envelopes and error codes.
- 🔌 **[API_DOCUMENTATION.md](API_DOCUMENTATION.md):** REST endpoints for `/health` and LiveKit voice token minting (`/api/livekit/token`).

---

## 📁 Repository Structure

```
movieclash/
├── backend/
│   ├── src/main/java/com/movieclash/
│   │   ├── config/             # GameConfig, LiveKitProperties, WebSocketConfig
│   │   ├── controller/         # HealthController (GET /health), LiveKitTokenController
│   │   ├── coordinator/        # GameCoordinator (Central event router)
│   │   ├── gateway/            # WebSocketGateway, WebSocketSessionTracker
│   │   ├── manager/            # RoomManager, RoundManager, ReconnectionManager,
│   │   │                       # CancellationManager, ScoreSynchronizer, MovieCatalogService
│   │   ├── model/              # Room, Player, Team, Movie, ChatMessage, Enums, DTOs
│   │   └── service/            # RoomBroadcaster, ChatRelayService, LiveKitService, PlayerSynchronizer
│   ├── src/main/resources/
│   │   ├── movies.json         # Bundled catalog of 108 curated movies with aliases
│   │   └── application.properties
│   ├── src/test/java/          # 35 Unit and End-to-End WebSocket Integration Tests
│   ├── Dockerfile              # Multi-stage production container build
│   └── pom.xml                 # Maven project configuration (Java 21, Spring Boot 3.3.2)
├── frontend/
│   ├── css/                    # Dark gaming theme, modular components, responsive layouts
│   ├── js/
│   │   ├── api/                # WebSocket client, latency tracker, health pinger
│   │   ├── engine/             # AnswerValidator (Levenshtein), ScoringEngine, TimerEngine
│   │   ├── views/              # Landing, Lobby, Game, Chat, Results, CancelModal controllers
│   │   ├── voice/              # LiveKit WebRTC client integration
│   │   ├── state.js            # Reactive state management store
│   │   └── app.js              # Application entrypoint & WebSocket event dispatcher
│   ├── test/                   # Frontend unit tests for validator and scoring engines
│   ├── index.html              # Single-page application HTML5 structure
│   └── config.js               # Dynamic backend URL configuration
├── docker-compose.yml          # Containerized local development stack
├── DEPLOYMENT.md
├── ARCHITECTURE.md
├── WEBSOCKET_EVENTS.md
├── API_DOCUMENTATION.md
└── README.md
```

---

## 🚀 Quickstart: Running Locally

### Prerequisites
- **Java 21 JDK** installed (`java -version`)
- **Maven 3.8+** installed (`mvn -v`)
- **Node.js** installed (optional, for frontend local static server & frontend unit tests)

### 1. Run the Spring Boot Backend
```bash
cd backend
mvn clean spring-boot:run
```
The backend starts at `http://localhost:8080`.
Verify with health check:
```bash
curl http://localhost:8080/health
```

### 2. Run the Frontend
Because the frontend uses modern ES modules (`import`/`export`), serve the `frontend/` folder with any local static HTTP server:

```bash
# Option A: Python 3
cd frontend
python -m http.server 3000

# Option B: Node.js npx serve
npx serve frontend -p 3000
```
Open `http://localhost:3000` in your web browser!

---

## 🧪 Running Tests

### Backend Test Suite (35 Tests)
Runs all unit and end-to-end WebSocket integration tests:
```bash
cd backend
mvn test
```

### Frontend Engine Unit Tests
Tests the client-side `AnswerValidator` (normalization, alias matching, edit distance) and `ScoringEngine`:
```bash
node frontend/test/engine-test.mjs
```

---

## 🎯 Gameplay Rules & Scoring

| Scenario | Individual Battle | Team Battle |
|---|---|---|
| **Guesser Correct (1st)** | +150 pts (+50 first bonus) | +150 pts to guessing team |
| **Guesser Correct (Subsequent)** | +100 pts | +100 pts to guessing team |
| **Clue-Giver Reward** | +25 pts per correct guesser | +25 pts to clue-giver's team per opposing team |
| **Teammate Guessing** | N/A | **Prohibited** (cannot guess own movie) |
| **Round Duration** | 60s (server-enforced) | 60s (server-enforced) |
| **Early Round End** | When all eligible players score | When all eligible opposing teams score |
| **Winning Condition** | Highest player score (ties share) | Highest team score (ties share) |

---

## 📄 License
MIT License. Built for real-time multiplayer movie entertainment.

