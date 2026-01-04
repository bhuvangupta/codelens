package com.codelens.service;

import com.codelens.model.entity.Repository.GitProvider;
import java.util.UUID;

/**
 * Interface for review execution logic.
 * Separated to avoid circular dependencies with async service.
 */
public interface ReviewExecutor {
    void executeReview(UUID reviewId, GitProvider provider, String owner, String repo, int prNumber);
    void executeCommitReview(UUID reviewId, GitProvider provider, String owner, String repo, String commitSha);
    void handleReviewFailure(UUID reviewId, String errorMessage);
}
