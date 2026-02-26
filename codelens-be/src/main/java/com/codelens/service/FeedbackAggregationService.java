package com.codelens.service;

import com.codelens.model.entity.RepoPromptHint;
import com.codelens.model.entity.Repository;
import com.codelens.model.entity.ReviewIssue;
import com.codelens.repository.RepoPromptHintRepository;
import com.codelens.repository.RepositoryRepository;
import com.codelens.repository.ReviewIssueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackAggregationService {

    private static final long MIN_TOTAL_FEEDBACK = 10;
    private static final long MIN_RULE_FEEDBACK = 5;
    private static final double FP_RATE_THRESHOLD = 0.60;

    private final ReviewIssueRepository issueRepository;
    private final RepoPromptHintRepository hintRepository;
    private final RepositoryRepository repositoryRepository;

    /**
     * Aggregate feedback patterns for a repository and generate auto-learned prompt hints
     * for rules with high false positive rates.
     *
     * Called from LearningService after feedback submission.
     */
    @Transactional
    public void aggregateIfReady(UUID repositoryId) {
        long totalFeedback = issueRepository.countFeedbackByRepository(repositoryId);

        if (totalFeedback < MIN_TOTAL_FEEDBACK) {
            log.debug("Repository {} has only {} feedback items, skipping aggregation (min={})",
                    repositoryId, totalFeedback, MIN_TOTAL_FEEDBACK);
            return;
        }

        Repository repository = repositoryRepository.findById(repositoryId).orElse(null);
        if (repository == null) {
            log.warn("Repository {} not found, skipping aggregation", repositoryId);
            return;
        }

        List<Object[]> aggregated = issueRepository.aggregateFeedbackByRule(repositoryId, MIN_RULE_FEEDBACK);

        int created = 0;
        int updated = 0;

        for (Object[] row : aggregated) {
            String rule = (String) row[0];
            String analyzer = (String) row[1];
            ReviewIssue.Category category = (ReviewIssue.Category) row[2];
            long fpCount = ((Number) row[3]).longValue();
            long helpfulCount = ((Number) row[4]).longValue();
            long totalCount = ((Number) row[5]).longValue();

            double fpRate = (double) fpCount / totalCount;

            if (fpRate <= FP_RATE_THRESHOLD) {
                continue;
            }

            String ruleKey = rule + ":" + analyzer;
            double confidence = calculateConfidence(fpRate, totalCount);
            int feedbackCount = (int) totalCount;

            String categoryLabel = category != null ? category.name().toLowerCase() : "general";
            String hintText = String.format(
                    "Rule '%s' from %s (%s) has a high false positive rate in this repo. " +
                    "Apply extra scrutiny before flagging.",
                    rule, analyzer, categoryLabel);

            Optional<RepoPromptHint> existing = hintRepository
                    .findByRepositoryIdAndGeneratedFromRule(repositoryId, ruleKey);

            if (existing.isPresent()) {
                RepoPromptHint hint = existing.get();
                hint.setHint(hintText);
                hint.setConfidence(confidence);
                hint.setFeedbackCount(feedbackCount);
                hint.setActive(true);
                hintRepository.save(hint);
                updated++;
            } else {
                RepoPromptHint hint = RepoPromptHint.builder()
                        .repository(repository)
                        .hint(hintText)
                        .source(RepoPromptHint.Source.AUTO_LEARNED)
                        .active(true)
                        .confidence(confidence)
                        .generatedFromRule(ruleKey)
                        .feedbackCount(feedbackCount)
                        .build();
                hintRepository.save(hint);
                created++;
            }
        }

        if (created > 0 || updated > 0) {
            log.info("Feedback aggregation for repository {}: created {} hints, updated {} hints",
                    repositoryId, created, updated);
        }
    }

    /**
     * Calculate confidence score based on FP rate and data volume.
     * Higher FP rate and more data points yield higher confidence.
     */
    private double calculateConfidence(double fpRate, long dataPoints) {
        // Volume factor: scales from 0.5 at 5 items to ~1.0 at 50+ items
        double volumeFactor = Math.min(1.0, 0.5 + (dataPoints - MIN_RULE_FEEDBACK) * 0.5 / 45.0);
        // Confidence = FP rate weighted by volume factor
        return Math.round(fpRate * volumeFactor * 100.0) / 100.0;
    }
}
