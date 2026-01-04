package com.codelens.api;

import com.codelens.config.EncryptionService;
import com.codelens.model.entity.MembershipRequest;
import com.codelens.model.entity.Organization;
import com.codelens.model.entity.Repository;
import com.codelens.model.entity.Review;
import com.codelens.model.entity.User;
import com.codelens.repository.OrganizationRepository;
import com.codelens.repository.RepositoryRepository;
import com.codelens.repository.ReviewRepository;
import com.codelens.repository.UserRepository;
import com.codelens.service.MembershipService;
import com.codelens.security.AuthenticatedUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final RepositoryRepository repositoryRepository;
    private final ReviewRepository reviewRepository;
    private final EncryptionService encryptionService;
    private final MembershipService membershipService;

    public SettingsController(UserRepository userRepository,
                              OrganizationRepository organizationRepository,
                              RepositoryRepository repositoryRepository,
                              ReviewRepository reviewRepository,
                              EncryptionService encryptionService,
                              MembershipService membershipService) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.repositoryRepository = repositoryRepository;
        this.reviewRepository = reviewRepository;
        this.encryptionService = encryptionService;
        this.membershipService = membershipService;
    }

    /**
     * Helper to find user by authenticated principal
     */
    private Optional<User> findAuthenticatedUser(AuthenticatedUser auth) {
        if (auth == null) return Optional.empty();
        return userRepository.findByEmail(auth.email());
    }

    /**
     * Get user settings
     */
    @GetMapping("/user")
    public ResponseEntity<UserSettingsResponse> getUserSettings(
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return findAuthenticatedUser(auth)
            .map(user -> {
                // Backfill: If user has no organization, check their reviews for one
                // Only auto-associate if email domain matches org name
                // Uses efficient query instead of loading all reviews (N+1 fix)
                if (user.getOrganization() == null) {
                    List<Organization> orgsFromReviews = userRepository.findOrganizationsFromReviews(user.getId());
                    for (Organization org : orgsFromReviews) {
                        if (isEmailDomainMatchingOrg(user.getEmail(), org.getName())) {
                            user.setOrganization(org);
                            user = userRepository.save(user);
                            log.info("Backfilled organization {} for user {} (email domain match)",
                                org.getName(), user.getEmail());
                            break;
                        }
                    }
                }
                return ResponseEntity.ok(UserSettingsResponse.from(user));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Check if user's email domain contains the organization name.
     */
    private boolean isEmailDomainMatchingOrg(String email, String orgName) {
        if (email == null || orgName == null) {
            return false;
        }

        int atIndex = email.indexOf('@');
        if (atIndex == -1 || atIndex == email.length() - 1) {
            return false;
        }
        String domain = email.substring(atIndex + 1).toLowerCase();
        String normalizedOrg = orgName.toLowerCase().replaceAll("[^a-z0-9]", "");
        String normalizedDomain = domain.replaceAll("[^a-z0-9]", "");
        return normalizedDomain.contains(normalizedOrg);
    }

    /**
     * Update user settings
     */
    @PutMapping("/user")
    public ResponseEntity<UserSettingsResponse> updateUserSettings(
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestBody UpdateUserSettingsRequest request) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return findAuthenticatedUser(auth)
            .map(user -> {
                if (request.name() != null) {
                    user.setName(request.name());
                }
                if (request.defaultLlmProvider() != null) {
                    user.setDefaultLlmProvider(request.defaultLlmProvider());
                }
                user = userRepository.save(user);
                return ResponseEntity.ok(UserSettingsResponse.from(user));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get current user's organization
     */
    @GetMapping("/organization")
    public ResponseEntity<OrgSettingsResponse> getCurrentUserOrg(
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<User> userOpt = findAuthenticatedUser(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Organization org = userOpt.get().getOrganization();
        if (org == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(OrgSettingsResponse.from(org));
    }

    /**
     * Get organization settings by ID
     */
    @GetMapping("/organization/{id}")
    public ResponseEntity<OrgSettingsResponse> getOrgSettings(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Verify user belongs to this organization
        Optional<User> userOpt = findAuthenticatedUser(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User currentUser = userOpt.get();
        if (currentUser.getOrganization() == null || !currentUser.getOrganization().getId().equals(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return organizationRepository.findById(id)
            .map(org -> ResponseEntity.ok(OrgSettingsResponse.from(org)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update organization settings (Admin only)
     */
    @PutMapping("/organization/{id}")
    public ResponseEntity<?> updateOrgSettings(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestBody UpdateOrgSettingsRequest request) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Verify user exists and is admin of this organization
        Optional<User> userOpt = findAuthenticatedUser(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User currentUser = userOpt.get();
        if (currentUser.getOrganization() == null || !currentUser.getOrganization().getId().equals(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only modify settings of your own organization"));
        }

        if (currentUser.getRole() != User.UserRole.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Only organization admins can modify settings"));
        }

        return organizationRepository.findById(id)
            .map(org -> {
                if (request.name() != null) {
                    org.setName(request.name());
                }
                if (request.defaultLlmProvider() != null) {
                    org.setDefaultLlmProvider(request.defaultLlmProvider());
                }
                if (request.autoReviewEnabled() != null) {
                    org.setAutoReviewEnabled(request.autoReviewEnabled());
                }
                if (request.postCommentsEnabled() != null) {
                    org.setPostCommentsEnabled(request.postCommentsEnabled());
                }
                if (request.postInlineCommentsEnabled() != null) {
                    org.setPostInlineCommentsEnabled(request.postInlineCommentsEnabled());
                }
                if (request.securityScanEnabled() != null) {
                    org.setSecurityScanEnabled(request.securityScanEnabled());
                }
                if (request.staticAnalysisEnabled() != null) {
                    org.setStaticAnalysisEnabled(request.staticAnalysisEnabled());
                }
                if (request.autoApproveMembers() != null) {
                    org.setAutoApproveMembers(request.autoApproveMembers());
                }
                // Git tokens - encrypt before storing (allow empty string to clear)
                if (request.githubToken() != null) {
                    org.setGithubToken(request.githubToken().isEmpty() ? null : encryptionService.encrypt(request.githubToken()));
                }
                if (request.gitlabToken() != null) {
                    org.setGitlabToken(request.gitlabToken().isEmpty() ? null : encryptionService.encrypt(request.gitlabToken()));
                }
                if (request.gitlabUrl() != null) {
                    org.setGitlabUrl(request.gitlabUrl().isEmpty() ? null : request.gitlabUrl());
                }
                org = organizationRepository.save(org);
                return ResponseEntity.ok(OrgSettingsResponse.from(org));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get LLM provider settings
     */
    @GetMapping("/llm-providers")
    public ResponseEntity<Map<String, Object>> getLlmProviderSettings(
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // Return available providers and their status
        // In production, check which are configured
        return ResponseEntity.ok(Map.of(
            "providers", List.of("glm", "claude", "gemini", "ollama", "openai"),
            "defaultProvider", "gemini"
        ));
    }

    /**
     * Get repositories for current user's organization
     */
    @GetMapping("/repositories")
    public ResponseEntity<List<RepositoryResponse>> getRepositories(
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return findAuthenticatedUser(auth)
            .map(user -> {
                Organization org = user.getOrganization();
                if (org == null) {
                    return ResponseEntity.ok(List.<RepositoryResponse>of());
                }
                List<Repository> repos = repositoryRepository.findByOrganization(org);
                return ResponseEntity.ok(repos.stream().map(RepositoryResponse::from).toList());
            })
            .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    /**
     * Update repository settings
     */
    @PutMapping("/repositories/{id}")
    public ResponseEntity<?> updateRepository(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestBody UpdateRepositoryRequest request) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Verify user exists
        Optional<User> userOpt = findAuthenticatedUser(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User currentUser = userOpt.get();
        if (currentUser.getOrganization() == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "User does not belong to any organization"));
        }

        Optional<Repository> repoOpt = repositoryRepository.findById(id);
        if (repoOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Repository repo = repoOpt.get();

        // Verify repository belongs to user's organization
        if (repo.getOrganization() == null || !repo.getOrganization().getId().equals(currentUser.getOrganization().getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only modify repositories in your organization"));
        }

        if (request.autoReviewEnabled() != null) {
            repo.setAutoReviewEnabled(request.autoReviewEnabled());
        }
        repo = repositoryRepository.save(repo);
        return ResponseEntity.ok(RepositoryResponse.from(repo));
    }

    // ==================== Organization Member Management (Admin) ====================

    /**
     * Get organization members
     */
    @GetMapping("/organization/{id}/members")
    public ResponseEntity<List<OrgMemberResponse>> getOrgMembers(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // Verify user has access to this org
        Optional<User> userOpt = findAuthenticatedUser(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User currentUser = userOpt.get();
        if (currentUser.getOrganization() == null || !currentUser.getOrganization().getId().equals(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<User> members = userRepository.findByOrganizationId(id);
        return ResponseEntity.ok(members.stream().map(OrgMemberResponse::from).toList());
    }

    /**
     * Add member to organization (Admin only)
     */
    @PostMapping("/organization/{id}/members")
    public ResponseEntity<?> addOrgMember(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestBody AddMemberRequest request) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // Verify user is admin of this org
        Optional<User> userOpt = findAuthenticatedUser(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User currentUser = userOpt.get();
        if (currentUser.getRole() != User.UserRole.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Only admins can add members"));
        }

        if (currentUser.getOrganization() == null || !currentUser.getOrganization().getId().equals(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only manage members of your own organization"));
        }

        Optional<Organization> orgOpt = organizationRepository.findById(id);
        if (orgOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Organization org = orgOpt.get();

        // Check if user already exists by email
        Optional<User> existingUser = userRepository.findByEmail(request.email());
        User member;

        if (existingUser.isPresent()) {
            member = existingUser.get();
            if (member.getOrganization() != null && !member.getOrganization().getId().equals(id)) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "User already belongs to another organization"));
            }
        } else {
            // Create new user (they'll complete profile on first login)
            member = User.builder()
                .email(request.email())
                .name(request.name() != null ? request.name() : request.email().split("@")[0])
                .provider("pending") // Will be updated on first OAuth login
                .providerId("pending-" + UUID.randomUUID())
                .role(request.role() != null ? User.UserRole.valueOf(request.role()) : User.UserRole.MEMBER)
                .build();
        }

        member.setOrganization(org);
        member.setRole(request.role() != null ? User.UserRole.valueOf(request.role()) : User.UserRole.MEMBER);
        if (request.githubUsername() != null) {
            member.setGithubUsername(request.githubUsername());
        }
        if (request.gitlabUsername() != null) {
            member.setGitlabUsername(request.gitlabUsername());
        }

        member = userRepository.save(member);
        log.info("Admin {} added member {} to organization {}", currentUser.getEmail(), member.getEmail(), org.getName());

        return ResponseEntity.ok(OrgMemberResponse.from(member));
    }

    /**
     * Update member in organization (Admin only)
     */
    @PutMapping("/organization/{orgId}/members/{userId}")
    public ResponseEntity<?> updateOrgMember(
            @PathVariable UUID orgId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal AuthenticatedUser auth,
            @RequestBody UpdateMemberRequest request) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // Verify user is admin of this org
        Optional<User> userOpt = findAuthenticatedUser(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User currentUser = userOpt.get();
        if (currentUser.getRole() != User.UserRole.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Only admins can update members"));
        }

        if (currentUser.getOrganization() == null || !currentUser.getOrganization().getId().equals(orgId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only manage members of your own organization"));
        }

        Optional<User> memberOpt = userRepository.findById(userId);
        if (memberOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User member = memberOpt.get();
        if (member.getOrganization() == null || !member.getOrganization().getId().equals(orgId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "User is not a member of this organization"));
        }

        if (request.role() != null) {
            member.setRole(User.UserRole.valueOf(request.role()));
        }
        if (request.githubUsername() != null) {
            member.setGithubUsername(request.githubUsername());
        }
        if (request.gitlabUsername() != null) {
            member.setGitlabUsername(request.gitlabUsername());
        }

        member = userRepository.save(member);
        log.info("Admin {} updated member {} in organization", currentUser.getEmail(), member.getEmail());

        return ResponseEntity.ok(OrgMemberResponse.from(member));
    }

    /**
     * Remove member from organization (Admin only)
     */
    @DeleteMapping("/organization/{orgId}/members/{userId}")
    public ResponseEntity<?> removeOrgMember(
            @PathVariable UUID orgId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // Verify user is admin of this org
        Optional<User> userOpt = findAuthenticatedUser(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User currentUser = userOpt.get();
        if (currentUser.getRole() != User.UserRole.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Only admins can remove members"));
        }

        if (currentUser.getOrganization() == null || !currentUser.getOrganization().getId().equals(orgId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only manage members of your own organization"));
        }

        // Prevent self-removal
        if (currentUser.getId().equals(userId)) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "You cannot remove yourself from the organization"));
        }

        Optional<User> memberOpt = userRepository.findById(userId);
        if (memberOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        User member = memberOpt.get();
        if (member.getOrganization() == null || !member.getOrganization().getId().equals(orgId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "User is not a member of this organization"));
        }

        member.setOrganization(null);
        userRepository.save(member);
        log.info("Admin {} removed member {} from organization", currentUser.getEmail(), member.getEmail());

        return ResponseEntity.ok(Map.of("message", "Member removed successfully"));
    }

    // ==================== Membership Request Management (Admin) ====================

    /**
     * Get pending membership requests for an organization
     */
    @GetMapping("/organization/{id}/membership-requests")
    public ResponseEntity<?> getPendingMembershipRequests(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<User> userOpt = findAuthenticatedUser(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User currentUser = userOpt.get();
        if (currentUser.getRole() != User.UserRole.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Only admins can view membership requests"));
        }

        if (currentUser.getOrganization() == null || !currentUser.getOrganization().getId().equals(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only view membership requests for your own organization"));
        }

        List<MembershipRequest> requests = membershipService.getPendingRequests(currentUser.getOrganization());
        return ResponseEntity.ok(requests.stream().map(MembershipRequestResponse::from).toList());
    }

    /**
     * Approve a membership request (Admin only)
     */
    @PostMapping("/organization/{orgId}/membership-requests/{requestId}/approve")
    public ResponseEntity<?> approveMembershipRequest(
            @PathVariable UUID orgId,
            @PathVariable UUID requestId,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<User> userOpt = findAuthenticatedUser(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User currentUser = userOpt.get();
        if (currentUser.getRole() != User.UserRole.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Only admins can approve membership requests"));
        }

        if (currentUser.getOrganization() == null || !currentUser.getOrganization().getId().equals(orgId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only approve requests for your own organization"));
        }

        // Verify the request belongs to this organization
        Optional<MembershipRequest> requestOpt = membershipService.getRequest(requestId);
        if (requestOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!requestOpt.get().getOrganization().getId().equals(orgId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Request does not belong to this organization"));
        }

        try {
            MembershipRequest request = membershipService.approveMembership(requestId, currentUser);
            return ResponseEntity.ok(MembershipRequestResponse.from(request));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Reject a membership request (Admin only)
     */
    @PostMapping("/organization/{orgId}/membership-requests/{requestId}/reject")
    public ResponseEntity<?> rejectMembershipRequest(
            @PathVariable UUID orgId,
            @PathVariable UUID requestId,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<User> userOpt = findAuthenticatedUser(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User currentUser = userOpt.get();
        if (currentUser.getRole() != User.UserRole.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Only admins can reject membership requests"));
        }

        if (currentUser.getOrganization() == null || !currentUser.getOrganization().getId().equals(orgId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only reject requests for your own organization"));
        }

        // Verify the request belongs to this organization
        Optional<MembershipRequest> requestOpt = membershipService.getRequest(requestId);
        if (requestOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (!requestOpt.get().getOrganization().getId().equals(orgId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Request does not belong to this organization"));
        }

        try {
            MembershipRequest request = membershipService.rejectMembership(requestId, currentUser);
            return ResponseEntity.ok(MembershipRequestResponse.from(request));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get count of pending membership requests (for badge display)
     */
    @GetMapping("/organization/{id}/membership-requests/count")
    public ResponseEntity<?> getPendingMembershipRequestsCount(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<User> userOpt = findAuthenticatedUser(auth);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User currentUser = userOpt.get();
        if (currentUser.getOrganization() == null || !currentUser.getOrganization().getId().equals(id)) {
            return ResponseEntity.ok(Map.of("count", 0));
        }

        if (currentUser.getRole() != User.UserRole.ADMIN) {
            return ResponseEntity.ok(Map.of("count", 0));
        }

        long count = membershipService.countPendingRequests(currentUser.getOrganization());
        return ResponseEntity.ok(Map.of("count", count));
    }

    // DTOs

    public record OrgMemberResponse(
        UUID id,
        String email,
        String name,
        String avatarUrl,
        String role,
        String githubUsername,
        String gitlabUsername,
        String lastLoginAt
    ) {
        public static OrgMemberResponse from(User user) {
            return new OrgMemberResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getAvatarUrl(),
                user.getRole() != null ? user.getRole().name() : "MEMBER",
                user.getGithubUsername(),
                user.getGitlabUsername(),
                user.getLastLoginAt() != null ? user.getLastLoginAt().toString() : null
            );
        }
    }

    public record AddMemberRequest(
        String email,
        String name,
        String role,
        String githubUsername,
        String gitlabUsername
    ) {}

    public record UpdateMemberRequest(
        String role,
        String githubUsername,
        String gitlabUsername
    ) {}

    public record MembershipRequestResponse(
        UUID id,
        UUID userId,
        String userEmail,
        String userName,
        String userAvatarUrl,
        String status,
        String requestedAt,
        String reviewedAt,
        String reviewedByEmail
    ) {
        public static MembershipRequestResponse from(MembershipRequest request) {
            return new MembershipRequestResponse(
                request.getId(),
                request.getUser().getId(),
                request.getUser().getEmail(),
                request.getUser().getName(),
                request.getUser().getAvatarUrl(),
                request.getStatus().name(),
                request.getRequestedAt() != null ? request.getRequestedAt().toString() : null,
                request.getReviewedAt() != null ? request.getReviewedAt().toString() : null,
                request.getReviewedBy() != null ? request.getReviewedBy().getEmail() : null
            );
        }
    }

    public record UserSettingsResponse(
        UUID id,
        String email,
        String name,
        String avatarUrl,
        String role,
        String defaultLlmProvider,
        UUID organizationId,
        String organizationName
    ) {
        public static UserSettingsResponse from(User user) {
            return new UserSettingsResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getAvatarUrl(),
                user.getRole() != null ? user.getRole().name() : "MEMBER",
                user.getDefaultLlmProvider(),
                user.getOrganization() != null ? user.getOrganization().getId() : null,
                user.getOrganization() != null ? user.getOrganization().getName() : null
            );
        }
    }

    public record UpdateUserSettingsRequest(
        String name,
        String defaultLlmProvider
    ) {}

    public record OrgSettingsResponse(
        UUID id,
        String name,
        String defaultLlmProvider,
        boolean autoReviewEnabled,
        boolean postCommentsEnabled,
        boolean postInlineCommentsEnabled,
        boolean securityScanEnabled,
        boolean staticAnalysisEnabled,
        boolean autoApproveMembers,
        boolean hasGithubToken,
        boolean hasGitlabToken,
        String gitlabUrl
    ) {
        public static OrgSettingsResponse from(Organization org) {
            return new OrgSettingsResponse(
                org.getId(),
                org.getName(),
                org.getDefaultLlmProvider(),
                Boolean.TRUE.equals(org.getAutoReviewEnabled()),
                Boolean.TRUE.equals(org.getPostCommentsEnabled()),
                Boolean.TRUE.equals(org.getPostInlineCommentsEnabled()),
                Boolean.TRUE.equals(org.getSecurityScanEnabled()),
                Boolean.TRUE.equals(org.getStaticAnalysisEnabled()),
                Boolean.TRUE.equals(org.getAutoApproveMembers()),
                org.getGithubToken() != null && !org.getGithubToken().isEmpty(),
                org.getGitlabToken() != null && !org.getGitlabToken().isEmpty(),
                org.getGitlabUrl()
            );
        }
    }

    public record UpdateOrgSettingsRequest(
        String name,
        String defaultLlmProvider,
        Boolean autoReviewEnabled,
        Boolean postCommentsEnabled,
        Boolean postInlineCommentsEnabled,
        Boolean securityScanEnabled,
        Boolean staticAnalysisEnabled,
        Boolean autoApproveMembers,
        String githubToken,
        String gitlabToken,
        String gitlabUrl
    ) {}

    public record RepositoryResponse(
        UUID id,
        String fullName,
        String name,
        String owner,
        String provider,
        String description,
        String language,
        boolean isPrivate,
        boolean autoReviewEnabled,
        String createdAt
    ) {
        public static RepositoryResponse from(Repository repo) {
            return new RepositoryResponse(
                repo.getId(),
                repo.getFullName(),
                repo.getName(),
                repo.getOwner(),
                repo.getProvider() != null ? repo.getProvider().name() : null,
                repo.getDescription(),
                repo.getLanguage(),
                Boolean.TRUE.equals(repo.getIsPrivate()),
                Boolean.TRUE.equals(repo.getAutoReviewEnabled()),
                repo.getCreatedAt() != null ? repo.getCreatedAt().toString() : null
            );
        }
    }

    public record UpdateRepositoryRequest(
        Boolean autoReviewEnabled
    ) {}
}
