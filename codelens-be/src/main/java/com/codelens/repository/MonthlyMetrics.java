package com.codelens.repository;

/**
 * Projection interface for monthly review metrics.
 * Used by aggregate queries to return lightweight trend data.
 */
public interface MonthlyMetrics {
    String getMonth();
    Long getReviewCount();
    Double getAvgIssues();
    Long getCriticalIssues();
    Long getHighIssues();
}
