package com.codelens.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token blacklist for JWT logout.
 * Tokens are stored with their expiration time and cleaned up periodically.
 *
 * For production with multiple instances, consider using Redis or a shared cache.
 */
@Slf4j
@Service
public class TokenBlacklistService {

    // Map of token -> expiration timestamp (epoch millis)
    private final Map<String, Long> blacklistedTokens = new ConcurrentHashMap<>();

    /**
     * Add a token to the blacklist.
     *
     * @param token The JWT token to blacklist
     * @param expirationMs When the token expires (epoch millis)
     */
    public void blacklist(String token, long expirationMs) {
        if (token == null || token.isBlank()) {
            return;
        }
        blacklistedTokens.put(token, expirationMs);
        log.debug("Token blacklisted, expires at {}", Instant.ofEpochMilli(expirationMs));
    }

    /**
     * Check if a token is blacklisted.
     *
     * @param token The JWT token to check
     * @return true if the token is blacklisted
     */
    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long expiration = blacklistedTokens.get(token);
        if (expiration == null) {
            return false;
        }
        // Check if token has expired (can be removed from blacklist)
        if (System.currentTimeMillis() > expiration) {
            blacklistedTokens.remove(token);
            return false;
        }
        return true;
    }

    /**
     * Clean up expired tokens from the blacklist.
     * Runs every 15 minutes to prevent memory buildup.
     */
    @Scheduled(fixedRate = 15 * 60 * 1000) // Every 15 minutes
    public void cleanupExpiredTokens() {
        long now = System.currentTimeMillis();
        int sizeBefore = blacklistedTokens.size();

        blacklistedTokens.entrySet().removeIf(entry -> now > entry.getValue());

        int removed = sizeBefore - blacklistedTokens.size();
        if (removed > 0) {
            log.debug("Cleaned up {} expired blacklisted tokens", removed);
        }
    }

    /**
     * Get the current size of the blacklist (for monitoring).
     */
    public int getBlacklistSize() {
        return blacklistedTokens.size();
    }
}
