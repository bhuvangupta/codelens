package com.codelens.service;

import com.codelens.model.entity.Repository.GitProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Separate service for async review execution.
 * This avoids self-invocation issues with @Async in ReviewService.
 */
@Slf4j
@Service
public class ReviewAsyncService {

    private final ReviewExecutor reviewExecutor;

    public ReviewAsyncService(ReviewExecutor reviewExecutor) {
        this.reviewExecutor = reviewExecutor;
    }

    @Async("reviewTaskExecutor")
    public CompletableFuture<Void> executeReviewAsync(UUID reviewId, GitProvider provider, String owner, String repo, int prNumber) {
        log.info("Starting async review execution for {} on thread {}", reviewId, Thread.currentThread().getName());
        try {
            reviewExecutor.executeReview(reviewId, provider, owner, repo, prNumber);
        } catch (Exception e) {
            log.error("Error executing review {}", reviewId, e);
            reviewExecutor.handleReviewFailure(reviewId, e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    @Async("reviewTaskExecutor")
    public CompletableFuture<Void> executeCommitReviewAsync(UUID reviewId, GitProvider provider, String owner, String repo, String commitSha) {
        log.info("Starting async commit review execution for {} on thread {}", reviewId, Thread.currentThread().getName());
        try {
            reviewExecutor.executeCommitReview(reviewId, provider, owner, repo, commitSha);
        } catch (Exception e) {
            log.error("Error executing commit review {}", reviewId, e);
            reviewExecutor.handleReviewFailure(reviewId, e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }
}
