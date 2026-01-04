package com.codelens.service;

import com.codelens.core.ReviewEngine;
import com.codelens.model.entity.Review;
import com.codelens.repository.ReviewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.codelens.util.UuidUtils;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Separate service for updating review progress.
 * This is needed because Spring's @Transactional doesn't work with self-invocation.
 * Uses atomic SQL updates to prevent optimistic locking failures during parallel file reviews.
 */
@Slf4j
@Service
public class ReviewProgressService {

    private final ReviewRepository reviewRepository;

    /**
     * Tracks the last seen filesCompleted count per review.
     * Used to detect when a file completes (count increases) vs just starting (count unchanged).
     * This enables proper increment of the DB counter only on file completion.
     */
    private final Map<UUID, Integer> lastSeenCount = new ConcurrentHashMap<>();

    public ReviewProgressService(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    /**
     * Clear progress tracking state for a review.
     * Call this when a review starts or completes.
     */
    public void clearProgressState(UUID reviewId) {
        lastSeenCount.remove(reviewId);
    }

    /**
     * Update review progress (called from async thread)
     * Uses atomic SQL updates to prevent optimistic locking failures during parallel file reviews.
     *
     * This method handles three scenarios:
     * 1. Initial call (filesCompleted=0): Sets total files count
     * 2. Before file review: Updates current file being processed (count unchanged)
     * 3. After file completion: Increments counter and updates current file (count increased)
     *
     * The method tracks the last seen count per review to detect file completions.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateProgress(UUID reviewId, ReviewEngine.ProgressUpdate progress) {
        try {
            byte[] reviewIdBytes = UuidUtils.toBytes(reviewId);
            String currentFile = truncateFilename(progress.currentFile());
            int currentCount = progress.filesCompleted();
            int lastCount = lastSeenCount.getOrDefault(reviewId, -1);

            // First call for this review - initialize total files
            if (lastCount == -1) {
                reviewRepository.setReviewTotalFiles(reviewIdBytes, progress.totalFiles());
                lastSeenCount.put(reviewId, currentCount);
                log.debug("Initialized review {} progress: {}/{} files",
                        reviewId, currentCount, progress.totalFiles());
            }
            // File completed - count increased from last seen
            else if (currentCount > lastCount) {
                // Use atomic increment to update count (thread-safe for parallel file reviews)
                reviewRepository.incrementReviewProgress(reviewIdBytes, currentFile);
                lastSeenCount.put(reviewId, currentCount);
                log.debug("File completed for review {}: {}/{} files ({})",
                        reviewId, currentCount, progress.totalFiles(), currentFile);
            }
            // Same count - just starting a new file, update current file display
            else {
                reviewRepository.updateReviewCurrentFile(reviewIdBytes, currentFile);
                log.debug("Reviewing file for review {}: {}", reviewId, currentFile);
            }

        } catch (Exception e) {
            // Log but don't fail the review if progress update fails
            log.debug("Failed to update progress for review {}: {}", reviewId, e.getMessage());
        }
    }

    private String truncateFilename(String filename) {
        if (filename == null) return null;
        // Get just the filename, not full path
        int lastSlash = filename.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < filename.length() - 1) {
            return filename.substring(lastSlash + 1);
        }
        return filename;
    }

    /**
     * Update review status to IN_PROGRESS.
     * Uses REQUIRES_NEW to commit immediately so dashboard can see the status.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatusInProgress(UUID reviewId) {
        reviewRepository.findById(reviewId).ifPresent(review -> {
            review.setStatus(Review.ReviewStatus.IN_PROGRESS);
            review.setStartedAt(LocalDateTime.now());
            review.setFilesReviewedCount(0);
            reviewRepository.save(review);
            log.info("Review {} status updated to IN_PROGRESS", reviewId);
        });
    }

    /**
     * Update review status to COMPLETED with results.
     * Uses REQUIRES_NEW to commit immediately.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatusCompleted(UUID reviewId) {
        reviewRepository.findById(reviewId).ifPresent(review -> {
            review.setStatus(Review.ReviewStatus.COMPLETED);
            review.setCompletedAt(LocalDateTime.now());
            reviewRepository.save(review);
            log.info("Review {} status updated to COMPLETED", reviewId);
        });
    }

    /**
     * Update review status to FAILED.
     * Uses REQUIRES_NEW to commit immediately.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatusFailed(UUID reviewId, String error) {
        reviewRepository.findById(reviewId).ifPresent(review -> {
            review.setStatus(Review.ReviewStatus.FAILED);
            review.setErrorMessage(error);
            review.setCompletedAt(LocalDateTime.now());
            reviewRepository.save(review);
            log.info("Review {} status updated to FAILED: {}", reviewId, error);
        });
    }

    // ============ Optimization Progress Methods ============

    /**
     * Start optimization analysis - set in progress flag and total files
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startOptimizationProgress(UUID reviewId, int totalFiles) {
        reviewRepository.findById(reviewId).ifPresent(review -> {
            review.setOptimizationInProgress(true);
            review.setOptimizationTotalFiles(totalFiles);
            review.setOptimizationFilesAnalyzed(0);
            review.setOptimizationStartedAt(LocalDateTime.now());
            reviewRepository.save(review);
            log.info("Optimization started for review {}: {} files to analyze", reviewId, totalFiles);
        });
    }

    /**
     * Update optimization progress (DEPRECATED - use incrementOptimizationProgress for parallel processing)
     * @deprecated Use {@link #incrementOptimizationProgress(UUID, String)} for thread-safe updates
     */
    @Deprecated
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateOptimizationProgress(UUID reviewId, int filesAnalyzed, String currentFile) {
        try {
            reviewRepository.findById(reviewId).ifPresent(review -> {
                review.setOptimizationFilesAnalyzed(filesAnalyzed);
                review.setOptimizationCurrentFile(truncateFilename(currentFile));
                reviewRepository.save(review);
                log.debug("Optimization progress for review {}: {}/{} files",
                    reviewId, filesAnalyzed, review.getOptimizationTotalFiles());
            });
        } catch (Exception e) {
            log.debug("Failed to update optimization progress for review {}: {}", reviewId, e.getMessage());
        }
    }

    /**
     * Atomically increment optimization progress counter.
     * Thread-safe for parallel file processing - prevents lost updates.
     *
     * @param reviewId The review ID
     * @param currentFile The file that was just completed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementOptimizationProgress(UUID reviewId, String currentFile) {
        try {
            reviewRepository.incrementOptimizationProgress(
                    UuidUtils.toBytes(reviewId),
                    truncateFilename(currentFile));
            log.debug("Incremented optimization progress for review {}, file: {}", reviewId, currentFile);
        } catch (Exception e) {
            log.debug("Failed to increment optimization progress for review {}: {}", reviewId, e.getMessage());
        }
    }

    /**
     * Update the current file being analyzed without changing the counter.
     * Use this to show "analyzing file X" before increment.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateOptimizationCurrentFile(UUID reviewId, String currentFile) {
        try {
            reviewRepository.updateOptimizationCurrentFile(
                    UuidUtils.toBytes(reviewId),
                    truncateFilename(currentFile));
        } catch (Exception e) {
            log.debug("Failed to update current file for review {}: {}", reviewId, e.getMessage());
        }
    }

    /**
     * Atomically increment review progress counter.
     * Thread-safe for parallel file processing - prevents lost updates.
     *
     * @param reviewId The review ID
     * @param currentFile The file that was just completed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementReviewProgress(UUID reviewId, String currentFile) {
        try {
            reviewRepository.incrementReviewProgress(
                    UuidUtils.toBytes(reviewId),
                    truncateFilename(currentFile));
            log.debug("Incremented review progress for review {}, file: {}", reviewId, currentFile);
        } catch (Exception e) {
            log.debug("Failed to increment review progress for review {}: {}", reviewId, e.getMessage());
        }
    }

    /**
     * Complete optimization analysis
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeOptimizationProgress(UUID reviewId) {
        reviewRepository.findById(reviewId).ifPresent(review -> {
            review.setOptimizationInProgress(false);
            review.setOptimizationCurrentFile(null);
            reviewRepository.save(review);
            log.info("Optimization progress completed for review {}", reviewId);
        });
    }

}
