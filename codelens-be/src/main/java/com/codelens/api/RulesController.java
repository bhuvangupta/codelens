package com.codelens.api;

import com.codelens.model.entity.ReviewRule;
import com.codelens.model.entity.User;
import com.codelens.repository.UserRepository;
import com.codelens.security.AuthenticatedUser;
import com.codelens.service.RulesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/rules")
@RequiredArgsConstructor
public class RulesController {

    private final RulesService rulesService;
    private final UserRepository userRepository;

    /**
     * Get all rules for the current user's organization
     */
    @GetMapping
    public ResponseEntity<List<RuleDto>> getRules(@AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UUID orgId = user.getOrganization() != null ? user.getOrganization().getId() : null;
        List<ReviewRule> rules = rulesService.getRulesForOrganization(orgId);

        return ResponseEntity.ok(rules.stream().map(RuleDto::from).toList());
    }

    /**
     * Create a new custom rule
     */
    @PostMapping
    public ResponseEntity<?> createRule(
            @RequestBody RulesService.CreateRuleRequest request,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            ReviewRule rule = rulesService.createRule(request, user);
            return ResponseEntity.status(HttpStatus.CREATED).body(RuleDto.from(rule));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Update an existing rule
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateRule(
            @PathVariable UUID id,
            @RequestBody RulesService.UpdateRuleRequest request,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Verify rule belongs to user's organization
        ReviewRule existingRule = rulesService.getRule(id).orElse(null);
        if (existingRule == null) {
            return ResponseEntity.notFound().build();
        }

        UUID userOrgId = user.getOrganization() != null ? user.getOrganization().getId() : null;
        UUID ruleOrgId = existingRule.getOrganization() != null ? existingRule.getOrganization().getId() : null;

        if (ruleOrgId != null && !ruleOrgId.equals(userOrgId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("You can only modify rules in your organization"));
        }

        try {
            ReviewRule rule = rulesService.updateRule(id, request);
            return ResponseEntity.ok(RuleDto.from(rule));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Delete a rule
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteRule(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser auth) {
        if (auth == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = getUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Verify rule belongs to user's organization
        ReviewRule existingRule = rulesService.getRule(id).orElse(null);
        if (existingRule == null) {
            return ResponseEntity.notFound().build();
        }

        UUID userOrgId = user.getOrganization() != null ? user.getOrganization().getId() : null;
        UUID ruleOrgId = existingRule.getOrganization() != null ? existingRule.getOrganization().getId() : null;

        if (ruleOrgId != null && !ruleOrgId.equals(userOrgId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("You can only delete rules in your organization"));
        }

        try {
            rulesService.deleteRule(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    private User getUser(AuthenticatedUser auth) {
        return userRepository.findByEmail(auth.email()).orElse(null);
    }

    // Response DTOs
    public record RuleDto(
            String id,
            String name,
            String description,
            String severity,
            String category,
            String pattern,
            String suggestion,
            boolean enabled,
            List<String> languages,
            boolean isCustom,
            String createdAt
    ) {
        public static RuleDto from(ReviewRule rule) {
            return new RuleDto(
                    rule.getId().toString(),
                    rule.getName(),
                    rule.getDescription(),
                    rule.getSeverity().name(),
                    rule.getCategory(),
                    rule.getPattern(),
                    rule.getSuggestion(),
                    rule.getEnabled(),
                    rule.getLanguages(),
                    rule.getIsCustom(),
                    rule.getCreatedAt() != null ? rule.getCreatedAt().toString() : null
            );
        }
    }

    public record ErrorResponse(String message) {}
}
