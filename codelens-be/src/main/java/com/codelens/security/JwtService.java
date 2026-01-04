package com.codelens.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class JwtService {

    private final SecretKey secretKey;
    private final JwtParser jwtParser;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;

    public JwtService(
            @Value("${codelens.security.jwt.secret}") String secret,
            @Value("${codelens.security.jwt.access-token-expiration:3600000}") long accessTokenExpiration,
            @Value("${codelens.security.jwt.refresh-token-expiration:604800000}") long refreshTokenExpiration
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        // Cache the parser to avoid ServiceLoader issues on repeated calls
        this.jwtParser = Jwts.parser().verifyWith(secretKey).build();
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    public String generateAccessToken(String email, String name, String picture, String providerId) {
        return generateToken(email, name, picture, providerId, accessTokenExpiration, "access");
    }

    public String generateRefreshToken(String email) {
        return Jwts.builder()
                .subject(email)
                .claim("type", "refresh")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusMillis(refreshTokenExpiration)))
                .signWith(secretKey)
                .compact();
    }

    private String generateToken(String email, String name, String picture, String providerId,
                                  long expiration, String type) {
        return Jwts.builder()
                .subject(email)
                .claim("name", name)
                .claim("picture", picture)
                .claim("providerId", providerId)
                .claim("type", type)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusMillis(expiration)))
                .signWith(secretKey)
                .compact();
    }

    public Optional<Claims> validateToken(String token) {
        try {
            Claims claims = jwtParser
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> getEmailFromToken(String token) {
        return validateToken(token).map(Claims::getSubject);
    }

    public Optional<Map<String, Object>> getUserInfoFromToken(String token) {
        return validateToken(token).map(claims -> {
            Map<String, Object> userInfo = new java.util.HashMap<>();
            userInfo.put("email", claims.getSubject());
            userInfo.put("name", claims.get("name", String.class));
            userInfo.put("picture", claims.get("picture", String.class));
            userInfo.put("providerId", claims.get("providerId", String.class));
            return userInfo;
        });
    }

    public boolean isAccessToken(String token) {
        return validateToken(token)
                .map(claims -> "access".equals(claims.get("type", String.class)))
                .orElse(false);
    }

    public boolean isRefreshToken(String token) {
        return validateToken(token)
                .map(claims -> "refresh".equals(claims.get("type", String.class)))
                .orElse(false);
    }

    public long getAccessTokenExpirationMs() {
        return accessTokenExpiration;
    }

    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpiration;
    }

    /**
     * Get the expiration time of a token in epoch milliseconds.
     * Returns empty if the token is invalid.
     */
    public Optional<Long> getTokenExpiration(String token) {
        return validateToken(token)
                .map(Claims::getExpiration)
                .map(Date::getTime);
    }
}
