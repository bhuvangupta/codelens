package com.codelens.service;

import com.codelens.model.entity.*;
import com.codelens.repository.RepoLearningRepository;
import com.codelens.repository.RepoPromptHintRepository;
import com.codelens.repository.ReviewIssueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningService {

    private final RepoLearningRepository learningRepository;
    private final RepoPromptHintRepository hintRepository;
    private final ReviewIssueRepository issueRepository;

    @Value("${codelens.learning.auto-suppress-threshold:5}")
    private int autoSuppressThreshold;

    @Value("${codelens.learning.severity-downgrade-threshold:3}")
    private int severityDowngradeThreshold;

    /**
     * Submit feedback for a review issue.
     */
    @Transactional
    public void submitFeedback(UUID issueId, FeedbackRequest request, User user) {
        ReviewIssue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new IllegalArgumentException("Issue not found: " + issueId));

        // Update issue feedback fields
        issue.setIsHelpful(request.isHelpful());
        issue.setIsFalsePositive(request.isFalsePositive());
        issue.setFeedbackNote(request.note());
        issue.setFeedbackAt(LocalDateTime.now());
        issue.setFeedbackBy(user);
        issueRepository.save(issue);

        // Update repository learning based on feedback
        Repository repository = issue.getReview().getRepository();
        if (repository != null) {
            updateRepoLearning(repository, issue, request);
        }

        log.info("Feedback submitted for issue {} by user {}: helpful={}, falsePositive={}",
                issueId, user.getId(), request.isHelpful(), request.isFalsePositive());
    }

    /**
     * Update repository learning based on feedback.
     */
    private void updateRepoLearning(Repository repository, ReviewIssue issue, FeedbackRequest request) {
        String ruleId = issue.getRule();
        String analyzer = issue.getAnalyzer();

        // Find or create learning entry
        RepoLearning learning = learningRepository
                .findByRepositoryIdAndRuleIdAndAnalyzer(repository.getId(), ruleId, analyzer)
                .orElseGet(() -> RepoLearning.builder()
                        .repository(repository)
                        .ruleId(ruleId)
                        .analyzer(analyzer)
                        .category(issue.getCategory() != null ? issue.getCategory().name() : null)
                        .build());

        // Update counts based on feedback
        if (Boolean.TRUE.equals(request.isFalsePositive())) {
            learning.incrementFalsePositive(autoSuppressThreshold);
            if (learning.getAutoSuppressed()) {
                log.info("Rule {} from {} auto-suppressed for repository {} after {} false positives",
                        ruleId, analyzer, repository.getFullName(), learning.getFalsePositiveCount());
            }
        }

        if (Boolean.FALSE.equals(request.isHelpful())) {
            learning.incrementNotHelpful(severityDowngradeThreshold);

            // Apply severity downgrade if threshold reached
            if (learning.getNotHelpfulCount() >= severityDowngradeThreshold
                    && learning.getSeverityOverride() == null) {
                String downgraded = downgradeSeverity(issue.getSeverity());
                learning.setSeverityOverride(downgraded);
                log.info("Severity for rule {} downgraded to {} for repository {} after {} not-helpful marks",
                        ruleId, downgraded, repository.getFullName(), learning.getNotHelpfulCount());
            }
        }

        learningRepository.save(learning);
    }

    /**
     * Downgrade severity by one level.
     */
    private String downgradeSeverity(ReviewIssue.Severity severity) {
        if (severity == null) return "INFO";
        return switch (severity) {
            case CRITICAL -> "HIGH";
            case HIGH -> "MEDIUM";
            case MEDIUM -> "LOW";
            case LOW, INFO -> "INFO";
        };
    }

    /**
     * Get all suppressed rules for a repository.
     */
    public List<RepoLearning> getSuppressedRules(UUID repositoryId) {
        return learningRepository.findSuppressedRules(repositoryId, autoSuppressThreshold);
    }

    /**
     * Get all active prompt hints for a repository.
     */
    public List<String> getActiveHints(UUID repositoryId) {
        return hintRepository.findByRepositoryIdAndActiveTrue(repositoryId)
                .stream()
                .map(RepoPromptHint::getHint)
                .collect(Collectors.toList());
    }

    /**
     * Get learning stats for a repository.
     */
    public LearningStats getLearningStats(UUID repositoryId) {
        List<RepoLearning> allLearning = learningRepository.findByRepositoryId(repositoryId);
        List<RepoPromptHint> hints = hintRepository.findByRepositoryIdAndActiveTrue(repositoryId);

        int suppressedCount = (int) allLearning.stream()
                .filter(l -> l.getAutoSuppressed() || l.getFalsePositiveCount() >= autoSuppressThreshold)
                .count();

        int severityOverrideCount = (int) allLearning.stream()
                .filter(l -> l.getSeverityOverride() != null)
                .count();

        int totalFeedback = allLearning.stream()
                .mapToInt(l -> l.getFalsePositiveCount() + l.getNotHelpfulCount())
                .sum();

        return new LearningStats(
                suppressedCount,
                severityOverrideCount,
                hints.size(),
                totalFeedback,
                allLearning
        );
    }

    /**
     * Check if a rule should be suppressed for a repository.
     */
    public boolean isRuleSuppressed(UUID repositoryId, String ruleId, String analyzer) {
        Optional<RepoLearning> learning = learningRepository
                .findByRepositoryIdAndRuleIdAndAnalyzer(repositoryId, ruleId, analyzer);

        return learning.map(l -> l.shouldSuppress(autoSuppressThreshold)).orElse(false);
    }

    /**
     * Get severity override for a rule in a repository.
     */
    public Optional<String> getSeverityOverride(UUID repositoryId, String ruleId, String analyzer) {
        return learningRepository
                .findByRepositoryIdAndRuleIdAndAnalyzer(repositoryId, ruleId, analyzer)
                .map(RepoLearning::getSeverityOverride);
    }

    /**
     * Manually unsuppress a rule.
     */
    @Transactional
    public void unsuppressRule(UUID repositoryId, String ruleId, String analyzer) {
        learningRepository
                .findByRepositoryIdAndRuleIdAndAnalyzer(repositoryId, ruleId, analyzer)
                .ifPresent(learning -> {
                    learning.setAutoSuppressed(false);
                    learning.setFalsePositiveCount(0);
                    learningRepository.save(learning);
                    log.info("Rule {} from {} unsuppressed for repository {}",
                            ruleId, analyzer, repositoryId);
                });
    }

    /**
     * Add a prompt hint for a repository.
     */
    @Transactional
    public RepoPromptHint addPromptHint(UUID repositoryId, String hint, Repository repository) {
        RepoPromptHint promptHint = RepoPromptHint.builder()
                .repository(repository)
                .hint(hint)
                .source(RepoPromptHint.Source.USER_ADDED)
                .active(true)
                .build();

        log.info("Added prompt hint for repository {}", repositoryId);
        return hintRepository.save(promptHint);
    }

    /**
     * Deactivate a prompt hint.
     */
    @Transactional
    public void deactivateHint(UUID hintId) {
        hintRepository.findById(hintId).ifPresent(hint -> {
            hint.setActive(false);
            hintRepository.save(hint);
            log.info("Deactivated prompt hint {}", hintId);
        });
    }

    // Request/Response DTOs
    public record FeedbackRequest(
            Boolean isHelpful,
            Boolean isFalsePositive,
            String note
    ) {}

    public record LearningStats(
            int suppressedRulesCount,
            int severityOverridesCount,
            int activeHintsCount,
            int totalFeedbackCount,
            List<RepoLearning> learningEntries
    ) {}
}
