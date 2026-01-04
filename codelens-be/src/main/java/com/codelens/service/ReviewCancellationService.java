package com.codelens.service;

import com.codelens.model.entity.Review;
import com.codelens.model.entity.User;
import com.codelens.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codelens.util.UuidUtils;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * Service for managing review cancellation.
 * Tracks running review tasks and allows cancellation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewCancellationService {

    private final ReviewRepository reviewRepository;

    // Map of reviewId -> Future for running reviews
    private final Map<UUID, Future<?>> runningReviews = new ConcurrentHashMap<>();

    /**
     * Register a running review task.
     * Called when a review starts executing.
     */
    public void registerRunningReview(UUID reviewId, Future<?> future) {
        runningReviews.put(reviewId, future);
        log.debug("Registered running review: {}", reviewId);
    }

    /**
     * Unregister a review task (called when review completes or fails).
     */
    public void unregisterReview(UUID reviewId) {
        runningReviews.remove(reviewId);
        log.debug("Unregistered review: {}", reviewId);
    }

    /**
     * Check if a review is currently running.
     */
    public boolean isReviewRunning(UUID reviewId) {
        Future<?> future = runningReviews.get(reviewId);
        return future != null && !future.isDone() && !future.isCancelled();
    }

    /**
     * Cancel a running review.
     * Uses atomic update to prevent race condition where review completes
     * between status check and cancellation.
     *
     * @param reviewId    The review to cancel
     * @param cancelledBy The user requesting cancellation (can be null for system cancellation)
     * @param reason      Optional reason for cancellation
     * @return The cancelled review
     * @throws IllegalArgumentException if review not found
     * @throws IllegalStateException if review is not cancellable (already completed/failed/cancelled)
     */
    @Transactional
    public Review cancelReview(UUID reviewId, User cancelledBy, String reason) {
        // First, try to cancel the running task (before DB update)
        Future<?> future = runningReviews.get(reviewId);
        if (future != null && !future.isDone()) {
            boolean cancelled = future.cancel(true); // Interrupt if running
            log.info("Attempted to cancel review {} task: {}", reviewId, cancelled ? "success" : "failed");
        }

        // Atomically update status to CANCELLED only if currently cancellable
        // This prevents race condition where review completes between check and update
        LocalDateTime cancelledAt = LocalDateTime.now();
        byte[] reviewIdBytes = UuidUtils.toBytes(reviewId);
        byte[] cancelledByIdBytes = cancelledBy != null ? UuidUtils.toBytes(cancelledBy.getId()) : null;

        int updated = reviewRepository.updateStatusToCancelledIfCancellable(
                reviewIdBytes, cancelledAt, cancelledByIdBytes, reason);

        if (updated == 0) {
            // Atomic update failed - review was not in cancellable state
            // Fetch to determine actual state for appropriate error message
            Review review = reviewRepository.findById(reviewId)
                    .orElseThrow(() -> new IllegalArgumentException("Review not found: " + reviewId));

            switch (review.getStatus()) {
                case COMPLETED -> throw new IllegalStateException("Cannot cancel a completed review");
                case CANCELLED -> throw new IllegalStateException("Review is already cancelled");
                case FAILED -> throw new IllegalStateException("Cannot cancel a failed review");
                default -> throw new IllegalStateException("Review cannot be cancelled, status: " + review.getStatus());
            }
        }

        // Fetch the updated review to return
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalStateException("Review disappeared after cancellation"));

        log.info("Cancelled review {} by user {} with reason: {}",
                reviewId,
                cancelledBy != null ? cancelledBy.getEmail() : "system",
                reason);

        // Clean up the running reviews map
        unregisterReview(reviewId);

        return review;
    }

    /**
     * Get the number of currently running reviews.
     */
    public int getRunningReviewCount() {
        return (int) runningReviews.entrySet().stream()
                .filter(e -> !e.getValue().isDone() && !e.getValue().isCancelled())
                .count();
    }
}
