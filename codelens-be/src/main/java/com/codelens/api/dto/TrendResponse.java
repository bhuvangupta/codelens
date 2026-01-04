package com.codelens.api.dto;

import lombok.Builder;

import java.util.List;

/**
 * Response DTO for trend analytics.
 */
@Builder
public record TrendResponse(
        List<MonthData> monthlyData,
        String trend,  // "improving", "stable", "degrading", "insufficient_data"
        Long totalReviews,
        Long totalCriticalIssues,
        Double averageIssuesPerReview
) {
    /**
     * Monthly data point for trend charts.
     */
    public record MonthData(
            String month,        // "2024-01"
            Long reviewCount,
            Double avgIssues,
            Long criticalIssues,
            Long highIssues
    ) {}
}
