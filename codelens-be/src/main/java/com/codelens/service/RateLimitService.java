package com.codelens.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory rate limiting service using sliding window algorithm.
 * Provides protection against abuse of expensive operations.
 */
@Slf4j
@Service
public class RateLimitService {

    // Rate limit configurations
    public static final int OPTIMIZATION_REQUESTS_PER_HOUR = 10;
    public static final int WEBHOOK_DELIVERIES_PER_HOUR = 100;
    public static final int REVIEW_REQUESTS_PER_HOUR = 50;

    private static final long WINDOW_MS = 3600_000; // 1 hour in milliseconds

    // Sliding window counters: key -> (windowStart, count)
    private final Map<String, RateLimitWindow> rateLimitWindows = new ConcurrentHashMap<>();

    /**
     * Check if an operation is allowed under rate limits.
     * Returns true if allowed, false if rate limited.
     */
    public boolean isAllowed(String key, int maxRequests) {
        return checkAndIncrement(key, maxRequests, false);
    }

    /**
     * Check and consume a rate limit token if available.
     * Returns true if allowed and token consumed, false if rate limited.
     */
    public boolean tryAcquire(String key, int maxRequests) {
        return checkAndIncrement(key, maxRequests, true);
    }

    /**
     * Get remaining requests for a key.
     */
    public int getRemaining(String key, int maxRequests) {
        RateLimitWindow window = rateLimitWindows.get(key);
        if (window == null) {
            return maxRequests;
        }

        long now = Instant.now().toEpochMilli();
        if (now - window.windowStart > WINDOW_MS) {
            return maxRequests;
        }

        return Math.max(0, maxRequests - window.count.get());
    }

    /**
     * Check rate limit for optimization requests (per user).
     */
    public boolean allowOptimization(UUID userId) {
        String key = "optimization:" + userId;
        return tryAcquire(key, OPTIMIZATION_REQUESTS_PER_HOUR);
    }

    /**
     * Check rate limit for webhook deliveries (per organization).
     */
    public boolean allowWebhookDelivery(UUID organizationId) {
        String key = "webhook:" + organizationId;
        return tryAcquire(key, WEBHOOK_DELIVERIES_PER_HOUR);
    }

    /**
     * Check rate limit for review submissions (per user).
     */
    public boolean allowReviewSubmission(UUID userId) {
        String key = "review:" + userId;
        return tryAcquire(key, REVIEW_REQUESTS_PER_HOUR);
    }

    /**
     * Get rate limit status for a given operation and entity.
     */
    public RateLimitStatus getStatus(String operation, UUID entityId, int maxRequests) {
        String key = operation + ":" + entityId;
        int remaining = getRemaining(key, maxRequests);
        long resetTime = getResetTime(key);
        return new RateLimitStatus(remaining, maxRequests, resetTime);
    }

    private boolean checkAndIncrement(String key, int maxRequests, boolean increment) {
        long now = Instant.now().toEpochMilli();

        RateLimitWindow window = rateLimitWindows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart > WINDOW_MS) {
                // Start new window
                return new RateLimitWindow(now, new AtomicInteger(0));
            }
            return existing;
        });

        int currentCount = window.count.get();
        if (currentCount >= maxRequests) {
            log.warn("Rate limit exceeded for key '{}': {}/{} requests in window",
                    key, currentCount, maxRequests);
            return false;
        }

        if (increment) {
            window.count.incrementAndGet();
        }

        return true;
    }

    private long getResetTime(String key) {
        RateLimitWindow window = rateLimitWindows.get(key);
        if (window == null) {
            return Instant.now().toEpochMilli() + WINDOW_MS;
        }
        return window.windowStart + WINDOW_MS;
    }

    /**
     * Clean up expired windows to prevent memory leaks.
     * Runs every hour to remove windows older than 2 hours.
     */
    @Scheduled(fixedRate = 3600_000) // Every hour
    public void cleanupExpiredWindows() {
        int sizeBefore = rateLimitWindows.size();
        long now = Instant.now().toEpochMilli();
        rateLimitWindows.entrySet().removeIf(entry ->
                now - entry.getValue().windowStart > WINDOW_MS * 2);
        int removed = sizeBefore - rateLimitWindows.size();
        if (removed > 0) {
            log.debug("Cleaned up {} expired rate limit windows", removed);
        }
    }

    private static class RateLimitWindow {
        final long windowStart;
        final AtomicInteger count;

        RateLimitWindow(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }

    public record RateLimitStatus(int remaining, int limit, long resetTime) {}
}
