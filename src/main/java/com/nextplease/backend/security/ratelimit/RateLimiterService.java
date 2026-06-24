package com.nextplease.backend.security.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * In-memory token-bucket rate limiter.
 *
 * <p>Each {@code key} maps to a {@link TokenBucket} that refills continuously up to a
 * capacity (= the per-minute limit). One request consumes one token; when the bucket
 * is empty the request is rejected.
 *
 * <p>This is intentionally dependency-free and process-local — perfect for a single
 * instance MVP. When the app scales to multiple instances, swap the {@code buckets}
 * map for a Redis-backed store (e.g. Bucket4j + Redis) so the limit is shared; the
 * {@link #tryConsume} contract can stay identical.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    /** Buckets idle longer than this are evicted to bound memory. */
    private static final long IDLE_EVICTION_MS = 10 * 60 * 1000L;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param key             unique bucket identity, e.g. "auth:ip:1.2.3.4"
     * @param ratePerMinute   max sustained requests per minute (also the burst capacity)
     * @return true if a token was available (request allowed), false if rate exceeded
     */
    public boolean tryConsume(String key, int ratePerMinute) {
        if (ratePerMinute <= 0) {
            return true; // a non-positive limit disables that policy
        }
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(ratePerMinute));
        return bucket.tryConsume(ratePerMinute);
    }

    @Scheduled(fixedDelay = IDLE_EVICTION_MS)
    void evictIdleBuckets() {
        long cutoff = System.nanoTime() - IDLE_EVICTION_MS * 1_000_000L;
        int before = buckets.size();
        buckets.entrySet().removeIf(entry -> entry.getValue().lastAccessNanos() < cutoff);
        int removed = before - buckets.size();
        if (removed > 0) {
            log.debug("Rate limiter evicted {} idle buckets ({} remaining)", removed, buckets.size());
        }
    }

    /** Thread-safe lazy-refill token bucket. */
    private static final class TokenBucket {
        private static final double NANOS_PER_MINUTE = 60_000_000_000.0;

        private double tokens;
        private long lastRefillNanos;
        private long lastAccessNanos;

        TokenBucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
            this.lastAccessNanos = this.lastRefillNanos;
        }

        synchronized boolean tryConsume(int ratePerMinute) {
            long now = System.nanoTime();
            this.lastAccessNanos = now;

            // Refill proportionally to elapsed time, capped at capacity (= ratePerMinute).
            double refill = (now - lastRefillNanos) / NANOS_PER_MINUTE * ratePerMinute;
            if (refill > 0) {
                tokens = Math.min(ratePerMinute, tokens + refill);
                lastRefillNanos = now;
            }

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized long lastAccessNanos() {
            return lastAccessNanos;
        }
    }
}
