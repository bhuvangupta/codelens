package com.codelens.service;

import com.codelens.repository.ReviewFileDiffRepository;
import com.codelens.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service to clean up old diff data to prevent database bloat.
 * Runs on a schedule to remove raw diffs and file diffs older than the retention period.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiffCleanupService {

    private final ReviewRepository reviewRepository;
    private final ReviewFileDiffRepository fileDiffRepository;

    // Default retention: 30 days
    @Value("${codelens.diff.retention-days:30}")
    private int retentionDays;

    // Batch size to avoid memory issues
    private static final int BATCH_SIZE = 100;

    /**
     * Scheduled task to clean up old diffs.
     * Runs daily at 3 AM.
     */
    @Scheduled(cron = "${codelens.diff.cleanup-cron:0 0 3 * * ?}")
    public void scheduledCleanup() {
        log.info("Starting scheduled diff cleanup (retention: {} days)", retentionDays);
        CleanupResult result = cleanupOldDiffs();
        log.info("Diff cleanup completed: {} reviews processed, {} raw diffs cleared, {} file diffs deleted",
                result.reviewsProcessed, result.rawDiffsCleared, result.fileDiffsDeleted);
    }

    /**
     * Clean up diffs older than the retention period.
     * Can be called manually or by the scheduled task.
     */
    @Transactional
    public CleanupResult cleanupOldDiffs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        return cleanupDiffsOlderThan(cutoff);
    }

    /**
     * Clean up diffs older than a specific date.
     */
    @Transactional
    public CleanupResult cleanupDiffsOlderThan(LocalDateTime cutoff) {
        int totalReviewsProcessed = 0;
        int totalRawDiffsCleared = 0;
        int totalFileDiffsDeleted = 0;

        List<UUID> reviewIds;
        do {
            // Find reviews with diffs older than cutoff (in batches)
            reviewIds = reviewRepository.findReviewIdsWithDiffsOlderThan(cutoff);

            if (reviewIds.isEmpty()) {
                break;
            }

            // Process in batches
            List<UUID> batch = reviewIds.size() > BATCH_SIZE
                    ? reviewIds.subList(0, BATCH_SIZE)
                    : reviewIds;

            // Delete file diffs first (foreign key constraint)
            int fileDiffsDeleted = fileDiffRepository.deleteByReviewIds(batch);
            totalFileDiffsDeleted += fileDiffsDeleted;

            // Clear raw diffs
            int rawDiffsCleared = reviewRepository.clearRawDiffByIds(batch);
            totalRawDiffsCleared += rawDiffsCleared;

            totalReviewsProcessed += batch.size();

            log.debug("Cleaned up {} reviews in this batch", batch.size());

        } while (reviewIds.size() > BATCH_SIZE);

        return new CleanupResult(totalReviewsProcessed, totalRawDiffsCleared, totalFileDiffsDeleted);
    }

    /**
     * Get the current retention period in days.
     */
    public int getRetentionDays() {
        return retentionDays;
    }

    /**
     * Result of a cleanup operation.
     */
    public record CleanupResult(int reviewsProcessed, int rawDiffsCleared, int fileDiffsDeleted) {}
}
