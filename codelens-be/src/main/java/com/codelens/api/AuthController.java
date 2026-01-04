package com.codelens.api;

import com.codelens.model.entity.User;
import com.codelens.repository.UserRepository;
import com.codelens.security.AuthenticatedUser;
import com.codelens.security.JwtService;
import com.codelens.security.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final TokenBlacklistService tokenBlacklistService;

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) {
        if (request.refreshToken() == null || request.refreshToken().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Refresh token required"));
        }

        if (!jwtService.isRefreshToken(request.refreshToken())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid refresh token"));
        }

        return jwtService.getEmailFromToken(request.refreshToken())
                .map(email -> {
                    // Look up user details from database for refresh token
                    User user = userRepository.findByEmail(email).orElse(null);
                    String name = user != null ? user.getName() : null;
                    String picture = user != null ? user.getAvatarUrl() : null;
                    String providerId = user != null ? user.getProviderId() : null;

                    String accessToken = jwtService.generateAccessToken(email, name, picture, providerId);
                    return ResponseEntity.ok(Map.of(
                            "accessToken", accessToken,
                            "expiresIn", jwtService.getAccessTokenExpirationMs() / 1000
                    ));
                })
                .orElse(ResponseEntity.status(401).body(Map.of("error", "Invalid refresh token")));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        return ResponseEntity.ok(Map.of(
                "email", user.email(),
                "name", user.name() != null ? user.name() : "",
                "picture", user.picture() != null ? user.picture() : "",
                "providerId", user.providerId() != null ? user.providerId() : ""
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Blacklist the token to prevent reuse after logout
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            // Get token expiration so we only keep it blacklisted until it expires
            jwtService.getTokenExpiration(token).ifPresent(expiration ->
                tokenBlacklistService.blacklist(token, expiration));
            log.debug("Token blacklisted on logout");
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    public record RefreshTokenRequest(String refreshToken) {}
}
