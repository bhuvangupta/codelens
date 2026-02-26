package com.codelens.api;

import com.codelens.model.entity.RepoLearning;
import com.codelens.model.entity.RepoPromptHint;
import com.codelens.model.entity.Repository;
import com.codelens.model.entity.User;
import com.codelens.repository.RepoPromptHintRepository;
import com.codelens.repository.RepositoryRepository;
import com.codelens.repository.UserRepository;
import com.codelens.security.AuthenticatedUser;
import com.codelens.service.LearningService;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningController {

    private final LearningService learningService;
    private final RepositoryRepository repositoryRepository;
    private final RepoPromptHintRepository repoPromptHintRepository;
    private final UserRepository userRepository;

    /**
     * Get learning stats for a repository.
     */
    @GetMapping("/repositories/{repoId}/stats")
    public ResponseEntity<?> getLearningStats(
            @PathVariable UUID repoId,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = findUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Repository> repoOpt = repositoryRepository.findById(repoId);
        if (repoOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Repository repo = repoOpt.get();
        if (!belongsToUserOrg(user, repo)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only access repositories in your organization"));
        }

        LearningService.LearningStats stats = learningService.getLearningStats(repoId);
        return ResponseEntity.ok(stats);
    }

    /**
     * Get suppressed rules for a repository.
     */
    @GetMapping("/repositories/{repoId}/suppressed-rules")
    public ResponseEntity<?> getSuppressedRules(
            @PathVariable UUID repoId,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = findUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Repository> repoOpt = repositoryRepository.findById(repoId);
        if (repoOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Repository repo = repoOpt.get();
        if (!belongsToUserOrg(user, repo)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only access repositories in your organization"));
        }

        List<RepoLearning> suppressed = learningService.getSuppressedRules(repoId);
        return ResponseEntity.ok(suppressed);
    }

    /**
     * Get all prompt hints (active and inactive) for a repository.
     */
    @GetMapping("/repositories/{repoId}/hints")
    public ResponseEntity<?> getHints(
            @PathVariable UUID repoId,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = findUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Repository> repoOpt = repositoryRepository.findById(repoId);
        if (repoOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Repository repo = repoOpt.get();
        if (!belongsToUserOrg(user, repo)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only access repositories in your organization"));
        }

        List<RepoPromptHint> hints = repoPromptHintRepository.findByRepositoryId(repoId);
        return ResponseEntity.ok(hints);
    }

    /**
     * Add a new prompt hint for a repository.
     */
    @PostMapping("/repositories/{repoId}/hints")
    public ResponseEntity<?> addHint(
            @PathVariable UUID repoId,
            @RequestBody AddHintRequest request,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = findUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Repository> repoOpt = repositoryRepository.findById(repoId);
        if (repoOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Repository repo = repoOpt.get();
        if (!belongsToUserOrg(user, repo)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only modify repositories in your organization"));
        }

        if (request.hint() == null || request.hint().isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Hint text is required"));
        }

        RepoPromptHint hint = learningService.addPromptHint(repoId, request.hint(), repo);
        return ResponseEntity.status(HttpStatus.CREATED).body(hint);
    }

    /**
     * Toggle a prompt hint active/inactive.
     */
    @PutMapping("/repositories/{repoId}/hints/{hintId}")
    public ResponseEntity<?> toggleHint(
            @PathVariable UUID repoId,
            @PathVariable UUID hintId,
            @RequestBody ToggleHintRequest request,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = findUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Repository> repoOpt = repositoryRepository.findById(repoId);
        if (repoOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Repository repo = repoOpt.get();
        if (!belongsToUserOrg(user, repo)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only modify repositories in your organization"));
        }

        Optional<RepoPromptHint> hintOpt = repoPromptHintRepository.findById(hintId);
        if (hintOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RepoPromptHint hint = hintOpt.get();
        if (!hint.getRepository().getId().equals(repoId)) {
            return ResponseEntity.notFound().build();
        }

        if (Boolean.FALSE.equals(request.active())) {
            learningService.deactivateHint(hintId);
            hint.setActive(false);
        } else {
            hint.setActive(true);
            repoPromptHintRepository.save(hint);
        }

        return ResponseEntity.ok(hint);
    }

    /**
     * Delete a prompt hint.
     */
    @DeleteMapping("/repositories/{repoId}/hints/{hintId}")
    public ResponseEntity<?> deleteHint(
            @PathVariable UUID repoId,
            @PathVariable UUID hintId,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = findUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Repository> repoOpt = repositoryRepository.findById(repoId);
        if (repoOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Repository repo = repoOpt.get();
        if (!belongsToUserOrg(user, repo)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only modify repositories in your organization"));
        }

        Optional<RepoPromptHint> hintOpt = repoPromptHintRepository.findById(hintId);
        if (hintOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        RepoPromptHint hint = hintOpt.get();
        if (!hint.getRepository().getId().equals(repoId)) {
            return ResponseEntity.notFound().build();
        }

        repoPromptHintRepository.delete(hint);
        return ResponseEntity.noContent().build();
    }

    /**
     * Unsuppress a rule for a repository.
     * ruleKey format: "ruleId:analyzer"
     */
    @PostMapping("/repositories/{repoId}/rules/{ruleKey}/unsuppress")
    public ResponseEntity<?> unsuppressRule(
            @PathVariable UUID repoId,
            @PathVariable String ruleKey,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = findUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Repository> repoOpt = repositoryRepository.findById(repoId);
        if (repoOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Repository repo = repoOpt.get();
        if (!belongsToUserOrg(user, repo)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only modify repositories in your organization"));
        }

        String[] parts = ruleKey.split(":", 2);
        if (parts.length != 2) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Invalid ruleKey format. Expected 'ruleId:analyzer'"));
        }

        String ruleId = parts[0];
        String analyzer = parts[1];

        learningService.unsuppressRule(repoId, ruleId, analyzer);
        return ResponseEntity.ok(Map.of("message", "Rule unsuppressed successfully"));
    }

    /**
     * Reset all learned data for a repository.
     */
    @PostMapping("/repositories/{repoId}/reset")
    public ResponseEntity<?> resetLearning(
            @PathVariable UUID repoId,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = findUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Repository> repoOpt = repositoryRepository.findById(repoId);
        if (repoOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Repository repo = repoOpt.get();
        if (!belongsToUserOrg(user, repo)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only modify repositories in your organization"));
        }

        learningService.resetLearning(repoId);
        log.info("Learning data reset for repository {} by user {}", repoId, user.getEmail());
        return ResponseEntity.ok(Map.of("message", "Learning data reset successfully"));
    }

    // --- Helper methods ---

    private User findUser(AuthenticatedUser auth) {
        return userRepository.findByEmail(auth.email()).orElse(null);
    }

    private boolean belongsToUserOrg(User user, Repository repo) {
        if (user.getOrganization() == null || repo.getOrganization() == null) {
            return false;
        }
        return user.getOrganization().getId().equals(repo.getOrganization().getId());
    }

    // --- Request DTOs ---

    public record AddHintRequest(String hint) {}

    public record ToggleHintRequest(Boolean active) {}
}
