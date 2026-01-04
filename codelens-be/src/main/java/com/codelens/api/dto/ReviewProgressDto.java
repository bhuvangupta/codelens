package com.codelens.api.dto;

import com.codelens.model.entity.Review;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight DTO for review progress polling.
 * Only includes fields needed for progress display, avoiding large fields like raw_diff.
 *
 * Used by GET /api/reviews/{id}/status endpoint which is polled every 2 seconds.
 */
public record ReviewProgressDto(
        UUID id,
        String status,
        Integer filesReviewed,
        Integer totalFiles,
        String currentFile,
        LocalDateTime startedAt,
        // Optimization progress
        Boolean optimizationInProgress,
        Integer optimizationFilesAnalyzed,
        Integer optimizationTotalFiles,
        String optimizationCurrentFile
) {
    /**
     * Calculate progress percentage (0-100).
     */
    public int getProgress() {
        if ("PENDING".equals(status)) {
            return 0;
        }
        if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
            return 100;
        }
        // IN_PROGRESS
        if (totalFiles != null && totalFiles > 0 && filesReviewed != null) {
            // Scale to 95 to reserve 5% for final processing
            return Math.min(95, (filesReviewed * 95) / totalFiles);
        }
        return 10; // Default for IN_PROGRESS without file counts
    }

    /**
     * Calculate elapsed time in milliseconds.
     */
    public Long getElapsedMs() {
        if (startedAt == null) {
            return null;
        }
        return java.time.Duration.between(startedAt, LocalDateTime.now()).toMillis();
    }
}
