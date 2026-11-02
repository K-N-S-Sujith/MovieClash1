package com.movieclash.gateway;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding window rate limiter per WebSocket session.
 * Protects server against message spam.
 */
@Component
public class RateLimiter {
    private static final int MAX_REQUESTS_PER_SECOND = 15;
    private final Map<String, TokenBucket> sessionBuckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(String sessionId) {
        if (sessionId == null) return true;
        TokenBucket bucket = sessionBuckets.computeIfAbsent(sessionId, id -> new TokenBucket(MAX_REQUESTS_PER_SECOND));
        return bucket.tryConsume();
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) {
            sessionBuckets.remove(sessionId);
        }
    }

    private static class TokenBucket {
        private final int capacity;
        private double tokens;
        private long lastRefillTimestamp;

        public TokenBucket(int capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillTimestamp = System.currentTimeMillis();
        }

        public synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            double secondsElapsed = (now - lastRefillTimestamp) / 1000.0;
            if (secondsElapsed > 0) {
                tokens = Math.min(capacity, tokens + (secondsElapsed * capacity));
                lastRefillTimestamp = now;
            }
        }
    }
}

