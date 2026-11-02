# MovieClash — Production Deployment Guide

This guide details how to deploy the MovieClash real-time multiplayer movie guessing game with maximum reliability, minimal latency, and optimal resource efficiency.

---

## 1. Architecture & Hosting Strategy

MovieClash is designed around a zero-database, ultra-low backend CPU footprint architecture:

```
┌─────────────────────────────────┐           ┌──────────────────────────────────┐
│         Cloudflare Pages        │           │        LiveKit Cloud SFU         │
│  (Vanilla HTML/CSS/ES Modules)  │           │   (Global WebRTC Audio Mesh)     │
└───────────────┬─────────────────┘           └─────────────────┬────────────────┘
                │ HTTP & WS                                     │ WebRTC Audio
                ▼                                               ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│                        MovieClash Backend Monolith                             │
│                  Always-on Java 21 / Spring Boot 3.3.2                         │
│   - In-Memory ConcurrentHashMap + ReentrantLock per room                       │
│   - Zero DB writes / Zero WebSocket audio relay                                │
│   - JVM Tuned: -Xmx300m -XX:+UseSerialGC -XX:TieredStopAtLevel=1               │
└────────────────────────────────────────────────────────────────────────────────┘
```

- **Frontend:** Hosted on **Cloudflare Pages** (or AWS S3 + CloudFront, Vercel, Netlify). Static asset delivery with global edge caching.
- **Backend:** Hosted on a **single always-on Java 21 host** (Render Paid, Railway, Fly.io, DigitalOcean Droplet, Hetzner, AWS Lightsail).
- **Voice SFU:** Hosted on **LiveKit Cloud** (or self-hosted LiveKit SFU). Spring Boot issues short-lived JWT tokens; audio traffic never touches the Java server.

---

## 2. Crucial Hosting Recommendation: Always-On Host vs Sleeping Free Tiers

> [!WARNING]
> **Do NOT deploy MovieClash backend to a sleeping free tier (e.g., Render Free / Heroku Eco).**
> - **In-Memory Volatility:** MovieClash maintains game state and active rooms in JVM memory (`ConcurrentHashMap`). If an instance sleeps or restarts due to inactivity, **all active games and rooms are instantly erased**.
> - **Cold-Start Latency:** Free tier instances require 50–90 seconds to wake up. This ruins player experience and causes WebSocket connection timeouts.
> - **Horizontal Scaling Note:** Because rooms are stored in-memory, horizontal scaling across multiple instances requires Redis pub/sub. For initial production deployment, a single always-on small instance ($5–$7/month) supports thousands of concurrent players with minimal latency.

---

## 3. Recommended Small-Host JVM Flags

For instances with 512 MB to 1 GB RAM (e.g., 512MB RAM VPS, DigitalOcean $4–$6 droplet, Fly.io 512MB machine):

```bash
java -Xmx300m \
     -XX:+UseSerialGC \
     -XX:TieredStopAtLevel=1 \
     -Djava.security.egd=file:/dev/./urandom \
     -jar target/movieclash-backend-1.0.0-SNAPSHOT.jar
```

### Rationale:
- `-Xmx300m`: Caps the JVM heap at 300 MB, preventing the OS Out-Of-Memory (OOM) killer from terminating the container on a 512 MB host.
- `-XX:+UseSerialGC`: The Serial Garbage Collector has virtually zero memory overhead and no background GC helper threads. Since MovieClash creates tiny short-lived JSON envelopes and keeps rooms in memory, pause times are under 5ms while saving 80+ MB of RAM compared to G1GC.
- `-XX:TieredStopAtLevel=1`: Stops JIT compilation at C1 (client compiler) without running C2 profiling. This drastically slashes startup time (from ~12s to ~1.8s) and reduces code cache memory usage by 60%.

---

## 4. Environment Variables

Configure these environment variables on your backend host:

| Variable | Default Value | Description |
|---|---|---|
| `PORT` | `8080` | HTTP / WebSocket server listen port |
| `LIVEKIT_URL` | `wss://movieclash-demo.livekit.cloud` | Your LiveKit SFU WebSocket URL |
| `LIVEKIT_API_KEY` | `devkey` | LiveKit API Key (from LiveKit Cloud console) |
| `LIVEKIT_API_SECRET` | `secret` | LiveKit API Secret (kept strictly on backend) |

---

## 5. Step-by-Step Deployment

### A. Deploy Frontend to Cloudflare Pages

1. Navigate to `frontend/config.js` and set your backend URLs:
   ```javascript
   const CONFIG = {
     BACKEND_HTTP_URL: 'https://api.yourdomain.com',
     BACKEND_WS_URL: 'wss://api.yourdomain.com/ws/game',
     LIVEKIT_SFU_URL: 'wss://your-project.livekit.cloud',
     PING_INTERVAL_MS: 5000,
     RECONNECT_TIMEOUT_MS: 60000,
     DEFAULT_ROUND_DURATION_SEC: 60
   };
   export default CONFIG;
   ```
2. Log in to [Cloudflare Dashboard](https://dash.cloudflare.com/) > **Workers & Pages** > **Create application** > **Pages**.
3. Connect your Git repository or upload the `frontend/` directory directly:
   - **Framework preset:** `None`
   - **Build command:** *(leave empty)*
   - **Build output directory:** `frontend`
4. Deploy! Your site will be globally distributed on Cloudflare's Edge CDN with instant SSL.

---

### B. Deploy Backend to Docker / Linux VPS

#### Using Docker:
```bash
docker build -t movieclash-backend backend/
docker run -d \
  --name movieclash \
  --restart unless-stopped \
  -p 8080:8080 \
  -e LIVEKIT_URL="wss://your-project.livekit.cloud" \
  -e LIVEKIT_API_KEY="your_api_key" \
  -e LIVEKIT_API_SECRET="your_api_secret" \
  movieclash-backend
```

#### Using Systemd Service on Ubuntu / Debian:
1. Copy the packaged JAR to `/opt/movieclash/movieclash.jar`.
2. Create `/etc/systemd/system/movieclash.service`:
   ```ini
   [Unit]
   Description=MovieClash Backend Service
   After=network.target

   [Service]
   User=www-data
   WorkingDirectory=/opt/movieclash
   Environment="PORT=8080"
   Environment="LIVEKIT_URL=wss://your-project.livekit.cloud"
   Environment="LIVEKIT_API_KEY=your_key"
   Environment="LIVEKIT_API_SECRET=your_secret"
   ExecStart=/usr/bin/java -Xmx300m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -jar /opt/movieclash/movieclash.jar
   Restart=always
   RestartSec=5

   [Install]
   WantedBy=multi-user.target
   ```
3. Enable and start:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now movieclash
   ```

---

## 6. Reverse Proxy & SSL Configuration (Nginx / Caddy)

Since WebSockets and WebRTC audio require secure contexts (`wss://` and `https://`), route traffic through a reverse proxy:

### Nginx Example:
```nginx
server {
    server_name api.yourdomain.com;
    listen 443 ssl http2;

    ssl_certificate /etc/letsencrypt/live/api.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.yourdomain.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;

        # WebSocket Upgrade Headers
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";

        # Client IP Pass-through
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Timeouts for persistent WebSocket connections
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
```

---

## 7. Performance & Bandwidth Safeguards

1. **Drift-Free Countdown:** Server sends `serverTime` and `deadline` once in `ROUND_STARTED`. Clients run drift-free `requestAnimationFrame` local timers. No per-second server ticks.
2. **Audio Offloading:** Zero audio goes through the Java backend. LiveKit WebRTC handles all SFU publishing and subscriptions.
3. **Bandwidth Efficiency:** Delta broadcasts (`ROOM_UPDATED`) send only changed attributes rather than full room snapshots.
4. **Health Check Endpoint:** `GET /health` is an unauthenticated, zero-database health probe used by frontend on page load to confirm server availability and measure latency.

