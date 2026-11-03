# 🎬 MovieClash

> **Terrible Clues. Wild Guesses. Pure Chaos.**  
> The real-time multiplayer movie party game where your friend describes *Titanic* as *"big boat gets cold"* and expects you to guess it in 3 seconds.

Built as an ultra-low-latency, real-time multiplayer game featuring **Java 21**, **Spring Boot 3.3.2**, native **WebSockets**, synthesized **Web Audio API** effects, and a modern **Vanilla JavaScript (ES Modules)** frontend with zero heavy frameworks or bloated bundlers.

---

## 🌟 Key Highlights & Features

- **🎨 Retro Cinema + Central Perk Aesthetic:**
  - Dark cinema base with warm vintage accents (velvet red, mustard yellow, warm purple, teal, and cream).
  - Glowing marquee bulbs, ticket-shaped room cards, clapperboard round indicators, and animated film-strip frames.
  - Witty, party-focused humor across all screens—no boring corporate tech jargon.

- **🔊 100% Native Web Audio Synthesis (0 External Audio Files):**
  - Integrated retro sound engine running directly via the browser's native `AudioContext`:
    - Wooden clapperboard snap on round start (`playClapper()`).
    - Suspenseful ticking countdown in the final 10 seconds (`playTick()`).
    - Victory chimes, celebration fanfare on podium, and subtle reaction pops.
    - Zero MP3 asset downloads with instant volume/mute control in the header.

- **🍿 Real-Time Crowd Emoji Reactions:**
  - Interactive reaction dock (🍿 Munch, 👏 Bravo, 😂 Laugh, 😱 Shock, 🔥 Cinema Fire, 🎯 Bullseye).
  - Usable both in the **Lobby** and **inside the Active Game Arena**.
  - Rate-limited with GPU-accelerated floating animations (`contain: layout style; will-change: transform, opacity`).

- **🔤 Competitive Progressive Letter Reveal Curve:**
  - **0% to 30% of round:** 0 letters revealed (pure clue listening & movie trivia deduction).
  - **30% mark to 10s remaining:** Letters reveal progressively and evenly according to title length.
  - **At 10 seconds remaining:** A frequency-boosted hint guarantees **at least 50% (half) of unique letters** are revealed.
  - **Final 10 seconds:** Pure rapid typing showdown with zero additional reveals and an urgent ticking audio clock.
  - Full alphanumeric and symbol masking (e.g. *"100% Love"* masks all characters in *"100%"* as `_ _ _ _`).

- **🕵️ Private Clue-Giver Hint Panel:**
  - Movie plot hints and trivia synopses are kept strictly private to the clue-giver (and their teammates in Team mode).
  - Guessers see only the masked letter tiles and release year in the public marquee, preventing spoilers.

- **⚙️ In-Lobby Room Settings Editor:**
  - Room hosts can dynamically configure rules directly in the lobby before starting:
    - **Round Timers:** 30s, 45s, 60s, 90s, 120s, or Custom Timer (10–600 seconds).
    - **Total Turns:** 1 to 5 turns, or Custom Turns (1–50).
    - **Movie Selection:** Manual Clue-Giver vs. Server Auto-Pick.
    - **Game Mode:** Toggle between Free-for-All and Team Battle on the fly.

- **🍿 Audience & Spectator Mode:**
  - Players can join as or switch to **Spectator** (`🍿 Watch as Spectator`).
  - Spectators can watch live clues, letter reveals, participate in chat, and send crowd reactions without interfering with active competitor rosters or cancellation quorums.

- **🗳️ Democratic Cancellation System:**
  - Any host or active player can propose game cancellation with an automated 15-second vote.
  - Spectators are excluded from proposing and voting to preserve game integrity.

- **🔄 Seamless Return to Lobby & Rematch:**
  - Clicking "Back to Lobby" on the podium screen sends a room reset event to the backend, zeroes scores for a fresh match, and transitions all players back to the lobby together.

- **⚡ Butter-Smooth 60fps Performance:**
  - DOM caching for letter tiles, scoreboard rows, and incremental feed appending for chat and clues.
  - Eliminates lag and layout thrashing even during high-frequency typing and reaction rushes.

---

## 🎮 Game Modes

### 1. 🍿 Free-for-All Brawl (Individual Mode)
- **2 to 20 players:** Every cinephile for themselves.
- Rotating clue-giver turns.
- Fast-paced guess submissions with first-correct bonus points.

### 2. 🎬 Squad War (Team Battle)
- **2 to 5 teams (up to 5 players per team):**
- Rotating team turns with intra-team rotation.
- Clue-giver's teammates see the secret movie and cannot guess their own movie.
- Opposing teams compete to buzz in first.

### 3. 🍿 Audience & Spectator Mode
- Watch the battle unfold live, enjoy the clues, and cheer with emoji reactions without competing on the scoreboard.

---

## ⏱️ Letter Reveal Curve Breakdown

| Time Elapsed | Letter Reveal Behavior | Audio & Visual Cues |
|---|---|---|
| **0% – 30% of Round** | **0 letters revealed** (pure mystery) | Clapperboard snap at start; clue-giver sets the stage |
| **30% – (Total - 10s)** | **Progressive reveals** spaced evenly by title length | Pop sounds as letters uncover in retro marquee flip tiles |
| **At 10s Remaining** | **Final boost: $\ge 50\%$ of letters revealed** | Highest frequency letters revealed to ensure half the title is visible |
| **Last 10 Seconds** | **No more letters revealed** (pure typing sprint) | Urgent audio ticking countdown; buzzer ticks intensify below 5s |

---

## 🏗️ Architecture & Technology Stack

- **Backend:**
  - **Java 21** with **Spring Boot 3.3.2**
  - Native **Spring WebSocket (`WebSocketHandler`)** with text JSON envelopes
  - In-memory concurrency with `ConcurrentHashMap` and thread-safe per-room mutation locks (`room.executeLocked(...)`)
  - Drift-free server-authoritative deadline timestamps (`deadline = serverNow + durationMs`)
  - Resilience: 5-minute reconnect grace window authenticated by 32-byte hex tokens

- **Frontend:**
  - **Vanilla JavaScript (ES2022 Modules)** — zero build tools, bundlers, or external UI libraries
  - **HTML5 & CSS3** with CSS custom properties (`:root`), Flexbox/Grid, and GPU-composited animations
  - **Web Audio API** — native algorithmic audio synthesis
  - **HTML5 Canvas** — celebratory confetti particles

- **Containerization:**
  - **Docker & Docker Compose** using lightweight Alpine-based OpenJDK JRE 21 images.

---

## 📁 Repository Structure

```
movieclash/
├── backend/
│   ├── src/main/java/com/movieclash/
│   │   ├── config/             # GameConfig, WebSocketConfig, StaticResourceConfig
│   │   ├── controller/         # HealthController (GET /health)
│   │   ├── coordinator/        # GameCoordinator (Central WebSocket event router)
│   │   ├── gateway/            # WebSocketGateway, WebSocketSessionTracker
│   │   ├── manager/            # RoomManager, RoundManager, CancellationManager,
│   │   │                       # ReconnectionManager, ScoreSynchronizer, MovieCatalogService
│   │   ├── model/              # Room, Player, Team, Movie, Enums, DTOs
│   │   └── service/            # RoomBroadcaster, ChatRelayService, PlayerSynchronizer
│   ├── src/main/resources/
│   │   ├── movies.json         # Curated catalog of movies with clues & aliases
│   │   └── application.properties
│   ├── src/test/java/          # Unit & End-to-End WebSocket Integration Tests
│   ├── Dockerfile.local        # Production/Local Alpine JRE 21 container image
│   └── pom.xml                 # Maven configuration (Java 21, Spring Boot 3.3.2)
├── frontend/
│   ├── css/                    # Cinema + Friends retro theme, components & responsive styles
│   ├── js/
│   │   ├── api/                # WebSocket client, latency tracker, health monitor
│   │   ├── engine/             # AnswerValidator, ScoringEngine, RoundTimer, AudioEngine, Confetti
│   │   ├── views/              # Landing, Lobby, Game, Chat, Reactions, Results, CancelModal
│   │   ├── state.js            # Reactive centralized store & session management
│   │   └── app.js              # Application entrypoint & WebSocket event dispatcher
│   ├── test/                   # Frontend unit tests
│   └── index.html              # Single-page application HTML5 structure
├── docker-compose.yml          # Containerized application stack
├── DEPLOYMENT.md               # Production deployment specifications
├── ARCHITECTURE.md             # Architecture blueprints & concurrency model
├── WEBSOCKET_EVENTS.md         # WebSocket event protocol reference
├── API_DOCUMENTATION.md        # REST & WebSocket endpoint documentation
└── README.md
```

---

## 🚀 Quickstart: Running Locally

### Option 1: Docker Compose (Recommended)

Run the entire application in a container with a single command:

```bash
docker compose up --build -d
```

- App is live at: **`http://localhost:8080`**
- Check health status:
```bash
docker ps --filter "name=movieclash-app"
```
- Stop the container:
```bash
docker compose down
```

---

### Option 2: Standalone Maven & Local Server

#### 1. Start the Spring Boot Backend:
```bash
cd backend
mvn clean spring-boot:run
```
The backend starts at `http://localhost:8080`.

#### 2. Serve the Frontend:
Because the frontend uses modern ES modules (`import`/`export`), serve the `frontend/` directory with any static HTTP server:

```bash
# Python 3:
cd frontend
python -m http.server 3000

# OR Node.js npx:
npx serve frontend -p 3000
```
Open **`http://localhost:3000`** in your browser!

---

## 🎯 Scoring Rules Table

| Scenario | Free-for-All Brawl | Squad War (Team Mode) |
|---|---|---|
| **1st Correct Guesser** | **+150 pts** (+50 first bonus) | **+150 pts** to guessing team |
| **Subsequent Guessers** | **+100 pts** | **+100 pts** to guessing team |
| **Clue-Giver Reward** | **+25 pts** per correct guesser | **+25 pts** to clue-giver team per opposing team |
| **Teammate Guessing** | N/A | **Prohibited** (cannot guess own movie) |
| **Spectators** | Watch & react only | Watch & react only |
| **Winning Condition** | Highest individual player score | Highest total team score |

---

## 🧪 Running Tests

### Backend Test Suite
Runs unit tests and WebSocket integration suites:
```bash
cd backend
mvn test
```

### Frontend Validation Tests
Tests the client-side `AnswerValidator` (case normalization, alias matching, Levenshtein edit distance):
```bash
node frontend/test/engine-test.mjs
```

---

## 📄 License

MIT License. Built for real-time multiplayer movie entertainment.
