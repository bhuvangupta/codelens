package com.codelens.api;

import com.codelens.model.entity.MembershipRequest;
import com.codelens.model.entity.Organization;
import com.codelens.model.entity.User;
import com.codelens.repository.OrganizationRepository;
import com.codelens.repository.UserRepository;
import com.codelens.security.AuthenticatedUser;
import com.codelens.service.MembershipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final MembershipService membershipService;

    public UserController(UserRepository userRepository, OrganizationRepository organizationRepository,
                          MembershipService membershipService) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipService = membershipService;
    }

    /**
     * Get current user profile
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return userRepository.findByEmail(auth.email())
            .map(user -> ResponseEntity.ok(UserResponse.from(user)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update current user profile
     */
    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateCurrentUser(
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestBody UpdateUserRequest request) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return userRepository.findByEmail(auth.email())
            .map(user -> {
                if (request.name() != null) {
                    user.setName(request.name());
                }
                if (request.avatarUrl() != null) {
                    user.setAvatarUrl(request.avatarUrl());
                }
                user = userRepository.save(user);
                return ResponseEntity.ok(UserResponse.from(user));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get user's organization
     */
    @GetMapping("/me/organization")
    public ResponseEntity<OrgResponse> getUserOrganization(@AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return userRepository.findByEmail(auth.email())
            .flatMap(user -> {
                Organization org = user.getOrganization();
                return org != null ? java.util.Optional.of(org) : java.util.Optional.empty();
            })
            .map(org -> ResponseEntity.ok(OrgResponse.from(org)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new organization
     */
    @PostMapping("/me/organizations")
    public ResponseEntity<OrgResponse> createOrganization(
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestBody CreateOrgRequest request) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return userRepository.findByEmail(auth.email())
            .map(user -> {
                Organization org = new Organization();
                org.setName(request.name());
                org.setDefaultLlmProvider("glm");
                org.setAutoReviewEnabled(true);
                org.setPostCommentsEnabled(true);
                org.setSecurityScanEnabled(true);
                org.setStaticAnalysisEnabled(true);
                org.getMembers().add(user);
                org = organizationRepository.save(org);
                user.setOrganization(org);
                userRepository.save(user);
                return ResponseEntity.status(HttpStatus.CREATED).body(OrgResponse.from(org));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Sync user from JWT - called on every page load to ensure user exists
     * and update last login time
     */
    @PostMapping("/sync")
    public ResponseEntity<UserResponse> syncUser(@AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            log.warn("Sync called without authentication");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String email = auth.email();
        String providerId = auth.providerId();
        String name = auth.name();
        String avatarUrl = auth.picture();

        log.info("Syncing user: {} ({})", email, providerId);

        // Find by email first (since email is unique), then by provider ID
        User user = userRepository.findByEmail(email)
            .or(() -> providerId != null ? userRepository.findByProviderAndProviderId("google", providerId) : Optional.empty())
            .or(() -> providerId != null ? userRepository.findByProviderAndProviderId("github", providerId) : Optional.empty())
            .orElseGet(() -> {
                // Create new user - detect provider from providerId format
                // GitHub IDs are numeric, Google IDs are longer alphanumeric
                String provider = providerId != null && providerId.matches("\\d+") ? "github" : "google";
                // Use email-based ID if providerId is null (fallback for incomplete JWT)
                String effectiveProviderId = providerId != null ? providerId : "email:" + email;
                log.info("Creating new user: {} via {} (providerId={})", email, provider, effectiveProviderId);
                User newUser = new User();
                newUser.setEmail(email);
                newUser.setProvider(provider);
                newUser.setProviderId(effectiveProviderId);
                newUser.setRole(User.UserRole.MEMBER);
                return newUser;
            });

        // Update user info
        if (name != null && !name.isEmpty()) {
            user.setName(name);
        } else if (user.getName() == null) {
            user.setName("User");
        }

        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            user.setAvatarUrl(avatarUrl);
        }

        // Update provider ID if missing (for users created by email lookup)
        if (user.getProviderId() == null || user.getProviderId().isEmpty()) {
            user.setProviderId(providerId);
        }

        user.setLastLoginAt(LocalDateTime.now());

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Check if it's a duplicate key error (email already exists)
            String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (message.contains("duplicate") || message.contains("unique")) {
                // Race condition: another request created the user simultaneously
                log.info("Duplicate key on user sync, retrying lookup for: {}", email);
                user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new IllegalStateException("User not found after duplicate key error: " + email));
                // Update last login on the existing user
                user.setLastLoginAt(LocalDateTime.now());
                user = userRepository.save(user);
            } else {
                // Other constraint violation (e.g., NOT NULL) - rethrow
                log.error("Database constraint violation while syncing user {}: {}", email, e.getMessage());
                throw e;
            }
        }

        // Check for pending membership request
        MembershipRequest pendingRequest = null;
        if (user.getOrganization() == null) {
            pendingRequest = membershipService.getUserPendingRequest(user).orElse(null);
        }

        log.info("User synced: {} (id={}, role={}, hasPendingRequest={})",
                user.getEmail(), user.getId(), user.getRole(), pendingRequest != null);
        return ResponseEntity.ok(UserResponse.from(user, pendingRequest));
    }

    /**
     * Internal: Create or update user from OAuth (legacy)
     */
    @PostMapping("/oauth/callback")
    public ResponseEntity<UserResponse> handleOAuthCallback(
            @RequestBody OAuthUserRequest request) {

        User user = userRepository.findByEmail(request.email())
            .orElseGet(() -> {
                User newUser = new User();
                newUser.setEmail(request.email());
                newUser.setProvider("google");
                newUser.setProviderId(request.googleId());
                return newUser;
            });

        user.setName(request.name());
        user.setAvatarUrl(request.avatarUrl());
        user.setLastLoginAt(LocalDateTime.now());
        user = userRepository.save(user);

        return ResponseEntity.ok(UserResponse.from(user));
    }

    // DTOs

    public record UserResponse(
        UUID id,
        String email,
        String name,
        String avatarUrl,
        String role,
        String defaultLlmProvider,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt,
        PendingMembershipResponse pendingMembership
    ) {
        public static UserResponse from(User user) {
            return from(user, null);
        }

        public static UserResponse from(User user, MembershipRequest pendingRequest) {
            return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getAvatarUrl(),
                user.getRole() != null ? user.getRole().name() : "MEMBER",
                user.getDefaultLlmProvider(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                pendingRequest != null ? PendingMembershipResponse.from(pendingRequest) : null
            );
        }
    }

    public record PendingMembershipResponse(
        UUID requestId,
        UUID organizationId,
        String organizationName,
        String status,
        String requestedAt
    ) {
        public static PendingMembershipResponse from(MembershipRequest request) {
            return new PendingMembershipResponse(
                request.getId(),
                request.getOrganization().getId(),
                request.getOrganization().getName(),
                request.getStatus().name(),
                request.getRequestedAt() != null ? request.getRequestedAt().toString() : null
            );
        }
    }

    public record UpdateUserRequest(
        String name,
        String avatarUrl
    ) {}

    public record OrgResponse(
        UUID id,
        String name,
        boolean autoReviewEnabled
    ) {
        public static OrgResponse from(Organization org) {
            return new OrgResponse(
                org.getId(),
                org.getName(),
                Boolean.TRUE.equals(org.getAutoReviewEnabled())
            );
        }
    }

    public record CreateOrgRequest(
        String name
    ) {}

    public record OAuthUserRequest(
        String email,
        String googleId,
        String name,
        String avatarUrl
    ) {}
}
