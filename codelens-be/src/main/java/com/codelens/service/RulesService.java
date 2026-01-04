package com.codelens.service;

import com.codelens.model.entity.ReviewRule;
import com.codelens.model.entity.User;
import com.codelens.repository.ReviewRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
@Service
@RequiredArgsConstructor
public class RulesService {

    private final ReviewRuleRepository ruleRepository;

    /**
     * Get all rules for an organization (including global rules)
     */
    public List<ReviewRule> getRulesForOrganization(UUID organizationId) {
        List<ReviewRule> rules = new ArrayList<>();

        // Add organization-specific rules
        if (organizationId != null) {
            rules.addAll(ruleRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId));
        }

        // Add global rules (org_id = null)
        rules.addAll(ruleRepository.findByOrganizationIdIsNullOrderByCreatedAtDesc());

        return rules;
    }

    /**
     * Get only enabled rules for an organization (for review execution)
     */
    public List<ReviewRule> getEnabledRulesForOrganization(UUID organizationId) {
        List<ReviewRule> rules = new ArrayList<>();

        // Add organization-specific enabled rules
        if (organizationId != null) {
            rules.addAll(ruleRepository.findByOrganizationIdAndEnabledTrue(organizationId));
        }

        // Add global enabled rules
        rules.addAll(ruleRepository.findByOrganizationIdIsNullAndEnabledTrue());

        return rules;
    }

    /**
     * Get a single rule by ID
     */
    public Optional<ReviewRule> getRule(UUID ruleId) {
        return ruleRepository.findById(ruleId);
    }

    /**
     * Create a new custom rule
     */
    @Transactional
    public ReviewRule createRule(CreateRuleRequest request, User createdBy) {
        // Validate regex pattern
        validatePattern(request.pattern());

        ReviewRule rule = ReviewRule.builder()
                .name(request.name())
                .description(request.description())
                .severity(request.severity() != null ? request.severity() : ReviewRule.Severity.MEDIUM)
                .category(request.category() != null ? request.category() : "CUSTOM")
                .pattern(request.pattern())
                .suggestion(request.suggestion())
                .enabled(request.enabled() != null ? request.enabled() : true)
                .languages(request.languages())
                .organization(createdBy.getOrganization())
                .createdBy(createdBy)
                .build();

        log.info("Creating custom rule '{}' for organization {}",
                rule.getName(),
                createdBy.getOrganization() != null ? createdBy.getOrganization().getName() : "global");

        return ruleRepository.save(rule);
    }

    /**
     * Update an existing rule
     */
    @Transactional
    public ReviewRule updateRule(UUID ruleId, UpdateRuleRequest request) {
        ReviewRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));

        if (request.name() != null) {
            rule.setName(request.name());
        }
        if (request.description() != null) {
            rule.setDescription(request.description());
        }
        if (request.severity() != null) {
            rule.setSeverity(request.severity());
        }
        if (request.category() != null) {
            rule.setCategory(request.category());
        }
        if (request.pattern() != null) {
            validatePattern(request.pattern());
            rule.setPattern(request.pattern());
        }
        if (request.suggestion() != null) {
            rule.setSuggestion(request.suggestion());
        }
        if (request.enabled() != null) {
            rule.setEnabled(request.enabled());
        }
        if (request.languages() != null) {
            rule.setLanguages(request.languages());
        }

        log.info("Updated rule '{}'", rule.getName());
        return ruleRepository.save(rule);
    }

    /**
     * Delete a rule
     */
    @Transactional
    public void deleteRule(UUID ruleId) {
        ReviewRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));

        if (!rule.getIsCustom()) {
            throw new IllegalArgumentException("Cannot delete built-in rules");
        }

        log.info("Deleting rule '{}'", rule.getName());
        ruleRepository.deleteById(ruleId);
    }

    /**
     * Validate that a pattern is a valid regex
     */
    private void validatePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("Pattern cannot be empty");
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage());
        }
    }

    // Request DTOs
    public record CreateRuleRequest(
            String name,
            String description,
            ReviewRule.Severity severity,
            String category,
            String pattern,
            String suggestion,
            Boolean enabled,
            List<String> languages
    ) {}

    public record UpdateRuleRequest(
            String name,
            String description,
            ReviewRule.Severity severity,
            String category,
            String pattern,
            String suggestion,
            Boolean enabled,
            List<String> languages
    ) {}
}
